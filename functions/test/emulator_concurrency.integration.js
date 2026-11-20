const test = require("node:test");
const assert = require("node:assert/strict");
const admin = require("firebase-admin");
const {
  applyAdminSubscriptionUpdate,
  applySubscriptionWriteSync,
  applyMerchantSubscriptionWriteSync,
  reserveAndCreateOrderAtomic,
  releaseOrderCapacitySlot,
  runAutoDispatch,
  verifyMerchantSubscription,
  isTerminalOrderStatusForCapacity,
} = require("../lib/index.js");

if (!process.env.FIRESTORE_EMULATOR_HOST) {
  throw new Error(
    "FIRESTORE_EMULATOR_HOST must be set! Run via: firebase emulators:exec --only firestore --project demo-no-project \"node --test functions/test/emulator_concurrency.integration.js\""
  );
}

const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const app = admin.apps.length
  ? admin.app()
  : admin.initializeApp({ projectId: PROJECT_ID });
const db = app.firestore();

async function clearCollection(colPath) {
  const snap = await db.collection(colPath).get();
  for (const doc of snap.docs) {
    const subs = await doc.ref.listCollections();
    for (const sub of subs) {
      const subSnap = await sub.get();
      for (const sd of subSnap.docs) {
        await sd.ref.delete();
      }
    }
    await doc.ref.delete();
  }
}

async function resetFirestore() {
  await Promise.all([
    clearCollection("merchants"),
    clearCollection("subscriptions"),
    clearCollection("orders"),
    clearCollection("order_codes"),
    clearCollection("idempotency_keys"),
    clearCollection("captains"),
    clearCollection("captain_schedules"),
    clearCollection("operation_logs"),
  ]);
}

async function seedMerchantAndSubscription(uid, opts = {}) {
  const merchantStatus = opts.merchantStatus || "approved";
  const captainLimit = opts.captainLimit ?? 2;
  const planId = opts.planId ?? 1;
  const planName = opts.planName || "الباقة الأساسية";
  const subStatus = opts.subStatus || "active";
  const version = opts.version ?? 2;
  const futureDate = admin.firestore.Timestamp.fromMillis(Date.now() + 30 * 86400000);

  await db.collection("merchants").doc(uid).set({
    uid,
    name: opts.name || "تاجر الاختبار",
    email: `${uid}@example.com`,
    accountType: "merchant",
    role: "merchant",
    status: merchantStatus,
    emailVerified: true,
    location: new admin.firestore.GeoPoint(30.0444, 31.2357),
    latitude: 30.0444,
    longitude: 31.2357,
    subscriptionStatus: subStatus,
    isActive: subStatus === "active",
    isSubscribed: subStatus === "active",
    planId,
    planName,
    captainLimit: subStatus === "active" ? captainLimit : 0,
    maxCaptains: subStatus === "active" ? captainLimit : 0,
    paymentStatus: subStatus === "active" ? "paid" : "unpaid",
    subscriptionVersion: version,
    expiresAt: futureDate,
    subscriptionEndDate: futureDate,
  });

  if (opts.createSubDoc !== false) {
    await db.collection("subscriptions").doc(uid).set({
      merchantId: uid,
      status: subStatus,
      subscriptionStatus: subStatus,
      isActive: subStatus === "active",
      isSubscribed: subStatus === "active",
      planId,
      planName,
      captainLimit: subStatus === "active" ? captainLimit : 0,
      maxCaptains: subStatus === "active" ? captainLimit : 0,
      paymentStatus: subStatus === "active" ? "paid" : "unpaid",
      subscriptionVersion: version,
      expiresAt: futureDate,
      endDate: futureDate,
    });
  }
}

function buildAllDayWeeklySchedule() {
  const days = [
    "saturday",
    "sunday",
    "monday",
    "tuesday",
    "wednesday",
    "thursday",
    "friday",
  ];
  const weeklySchedule = {};
  for (const d of days) {
    weeklySchedule[d] = {
      isDayOff: false,
      shifts: [{ startTime: "00:00", endTime: "23:59", isOvernight: false }],
    };
  }
  return { isPaused: false, weeklySchedule };
}

test.beforeEach(async () => {
  await resetFirestore();
});

// =========================================================================
// TASK 1: Subscription Race Condition & Monotonic Version Protection
// =========================================================================
test("TASK 1A: Delayed v2 active trigger arriving after v3 suspend is REJECTED (both directions + Auto-ID + idempotent retry)", async () => {
  const uid = "merchant_race_1";
  await seedMerchantAndSubscription(uid, { subStatus: "unsubscribed", version: 1, createSubDoc: false });

  // Step 1: Admin activates at version 2
  const v2 = await applyAdminSubscriptionUpdate(db, "admin_1", {
    merchantId: uid,
    action: "activate",
    planId: 2,
    durationDays: 30,
  });
  assert.equal(v2.status, "active");
  assert.equal(v2.subscriptionVersion, 2);

  const subV2Snapshot = (await db.collection("subscriptions").doc(uid).get()).data();
  const merchV2Snapshot = (await db.collection("merchants").doc(uid).get()).data();

  // Step 2: Admin suspends at version 3
  const v3 = await applyAdminSubscriptionUpdate(db, "admin_1", {
    merchantId: uid,
    action: "suspend",
    reason: "مخالفة إدارية",
  });
  assert.equal(v3.status, "suspended");
  assert.equal(v3.subscriptionVersion, 3);

  // Step 3: Delayed trigger from v2 arrives LATE on BOTH triggers!
  const lateSubTrigger = await applySubscriptionWriteSync(db, uid, null, subV2Snapshot);
  assert.equal(lateSubTrigger.synced, false);
  assert.equal(lateSubTrigger.skippedReason, "stale_version");

  const lateMerchTrigger = await applyMerchantSubscriptionWriteSync(
    db,
    uid,
    { subscriptionStatus: "unsubscribed", subscriptionVersion: 1 },
    merchV2Snapshot
  );
  assert.equal(lateMerchTrigger.synced, false);
  assert.equal(lateMerchTrigger.skippedReason, "stale_version");

  // Also test stale Auto-ID doc with v2 arriving while canonical is v3
  const lateAutoId = await applySubscriptionWriteSync(db, "auto_doc_xyz", null, {
    ...subV2Snapshot,
    merchantId: uid,
  });
  assert.equal(lateAutoId.synced, false);
  assert.equal(lateAutoId.skippedReason, "stale_version");

  // Verify BOTH documents stayed suspended at v3!
  const subAfter = (await db.collection("subscriptions").doc(uid).get()).data();
  const merchAfter = (await db.collection("merchants").doc(uid).get()).data();
  assert.equal(subAfter.status, "suspended");
  assert.equal(subAfter.subscriptionVersion, 3);
  assert.equal(merchAfter.subscriptionStatus, "suspended");
  assert.equal(merchAfter.subscriptionVersion, 3);

  // Step 4: Reverse direction — Reactivate at v4, then delayed v3 suspend arrives LATE!
  const subV3Snapshot = { ...subAfter };
  const merchV3Snapshot = { ...merchAfter };

  const v4 = await applyAdminSubscriptionUpdate(db, "admin_1", {
    merchantId: uid,
    action: "reactivate",
    planId: 3,
    durationDays: 30,
  });
  assert.equal(v4.status, "active");
  assert.equal(v4.subscriptionVersion, 4);
  assert.equal(v4.captainLimit, 6);

  // Delayed v3 suspend trigger arrives late
  const lateSuspendSub = await applySubscriptionWriteSync(db, uid, subV2Snapshot, subV3Snapshot);
  assert.equal(lateSuspendSub.synced, false);
  assert.equal(lateSuspendSub.skippedReason, "stale_version");

  const lateSuspendMerch = await applyMerchantSubscriptionWriteSync(db, uid, merchV2Snapshot, merchV3Snapshot);
  assert.equal(lateSuspendMerch.synced, false);
  assert.equal(lateSuspendMerch.skippedReason, "stale_version");

  // Verify BOTH docs remain active at v4!
  const subV4Live = (await db.collection("subscriptions").doc(uid).get()).data();
  const merchV4Live = (await db.collection("merchants").doc(uid).get()).data();
  assert.equal(subV4Live.status, "active");
  assert.equal(subV4Live.subscriptionVersion, 4);
  assert.equal(merchV4Live.subscriptionStatus, "active");
  assert.equal(merchV4Live.subscriptionVersion, 4);

  // Step 5: Re-executing trigger for v4 is strictly idempotent (claims push ONCE, never bumps version)
  const firstRunV4 = await applySubscriptionWriteSync(db, uid, subV3Snapshot, subV4Live);
  assert.equal(firstRunV4.synced, true);
  assert.equal(firstRunV4.resolvedVersion, 4);
  assert.ok(firstRunV4.pushSpec);

  const retryRunV4 = await applySubscriptionWriteSync(db, uid, subV3Snapshot, subV4Live);
  assert.equal(retryRunV4.synced, false);
  assert.equal(retryRunV4.skippedReason, "already_in_sync");
  assert.equal(retryRunV4.pushSpec, undefined);

  const finalSub = (await db.collection("subscriptions").doc(uid).get()).data();
  assert.equal(finalSub.subscriptionVersion, 4);
});

// =========================================================================
// TASK 2: Atomic captainLimit Enforcement Under High Concurrency + Release
// =========================================================================
test("TASK 2A: Limit=2 with 2 already reserved + 10 concurrent requests => 0 accepted, 10 rejected", async () => {
  const uid = "merchant_cap_full";
  await seedMerchantAndSubscription(uid, { captainLimit: 2, planId: 1 });

  // Reserve 2 slots first
  const r1 = await reserveAndCreateOrderAtomic(db, {
    uid,
    actionName: "requestCaptains",
    destinationAddress: "العنوان 1",
    serviceFee: "30 جنيه",
  });
  const r2 = await reserveAndCreateOrderAtomic(db, {
    uid,
    actionName: "requestCaptains",
    destinationAddress: "العنوان 2",
    serviceFee: "30 جنيه",
  });
  assert.equal(r1.success, true);
  assert.equal(r2.success, true);

  // Send 10 concurrent requests
  const burst = await Promise.all(
    Array.from({ length: 10 }, (_, idx) =>
      reserveAndCreateOrderAtomic(db, {
        uid,
        actionName: "requestCaptains",
        destinationAddress: `طلب متزامن ${idx}`,
        serviceFee: "30 جنيه",
      })
    )
  );

  const accepted = burst.filter((x) => x && x.success === true);
  const rejected = burst.filter((x) => x && x.rejected === true);
  assert.equal(accepted.length, 0);
  assert.equal(rejected.length, 10);

  const ordersSnap = await db.collection("orders").where("merchantId", "==", uid).get();
  assert.equal(ordersSnap.size, 2);
});

test("TASK 2B: Limit=2 with 1 slot available + 10 concurrent requests => EXACTLY 1 accepted, 9 rejected", async () => {
  const uid = "merchant_cap_one_slot";
  await seedMerchantAndSubscription(uid, { captainLimit: 2, planId: 1 });

  const initial = await reserveAndCreateOrderAtomic(db, {
    uid,
    actionName: "requestCaptains",
    destinationAddress: "الطلب الأول",
    serviceFee: "30 جنيه",
  });
  assert.equal(initial.success, true);

  const burst = await Promise.all(
    Array.from({ length: 10 }, (_, idx) =>
      reserveAndCreateOrderAtomic(db, {
        uid,
        actionName: "requestCaptains",
        destinationAddress: `طلب متنافس ${idx}`,
        serviceFee: "30 جنيه",
      })
    )
  );

  const accepted = burst.filter((x) => x && x.success === true);
  const rejected = burst.filter((x) => x && x.rejected === true);
  assert.equal(accepted.length, 1);
  assert.equal(rejected.length, 9);

  const ordersSnap = await db.collection("orders").where("merchantId", "==", uid).get();
  assert.equal(ordersSnap.size, 2);
  const capSnap = await db.collection("merchants").doc(uid).collection("capacity").doc("current").get();
  assert.equal(capSnap.data().activeReservationsCount, 2);
});

test("TASK 2C: Stress test — Limit=2 with 0 reserved + 100 concurrent requests => NEVER exceeds 2", async () => {
  const uid = "merchant_cap_stress_100";
  await seedMerchantAndSubscription(uid, { captainLimit: 2, planId: 1 });

  const outcomes = await Promise.allSettled(
    Array.from({ length: 100 }, (_, idx) =>
      reserveAndCreateOrderAtomic(db, {
        uid,
        actionName: "requestCaptains",
        destinationAddress: `ضغط ${idx}`,
        serviceFee: "35 جنيه",
      })
    )
  );

  const accepted = outcomes.filter(
    (o) => o.status === "fulfilled" && o.value && o.value.success === true
  );
  assert.equal(accepted.length, 2, `Expected exactly 2 accepted orders, got ${accepted.length}`);

  const ordersSnap = await db.collection("orders").where("merchantId", "==", uid).get();
  assert.equal(ordersSnap.size, 2);
  const capSnap = await db.collection("merchants").doc(uid).collection("capacity").doc("current").get();
  assert.equal(capSnap.data().activeReservationsCount, 2);
});

test("TASK 2D: Rollback on post-reserve failure, idempotency replay, captain rejection (no release), cancellation/completion idempotent release, and plan upgrade", async () => {
  const uid = "merchant_lifecycle_cap";
  await seedMerchantAndSubscription(uid, { captainLimit: 2, planId: 1 });

  // 1. Failure after capacity reserve MUST roll back atomically leaving ZERO orphan reservation
  await assert.rejects(
    reserveAndCreateOrderAtomic(db, {
      uid,
      actionName: "requestCaptains",
      destinationAddress: "طلب سيفشل",
      serviceFee: "30 جنيه",
      simulatePostReserveError: true,
    })
  );
  const capAfterFail = await db.collection("merchants").doc(uid).collection("capacity").doc("current").get();
  assert.equal(capAfterFail.exists, false);
  assert.equal((await db.collection("orders").where("merchantId", "==", uid).get()).size, 0);

  // 2. Same idempotencyKey retried concurrently => only ONE order created & 1 slot consumed
  const sameKey = "idem_retry_001";
  const [try1, try2, try3] = await Promise.all([
    reserveAndCreateOrderAtomic(db, {
      uid,
      actionName: "requestCaptains",
      destinationAddress: "طلب محمي ضد التكرار",
      serviceFee: "30 جنيه",
      idempotencyKey: sameKey,
    }),
    reserveAndCreateOrderAtomic(db, {
      uid,
      actionName: "requestCaptains",
      destinationAddress: "طلب محمي ضد التكرار",
      serviceFee: "30 جنيه",
      idempotencyKey: sameKey,
    }),
    reserveAndCreateOrderAtomic(db, {
      uid,
      actionName: "requestCaptains",
      destinationAddress: "طلب محمي ضد التكرار",
      serviceFee: "30 جنيه",
      idempotencyKey: sameKey,
    }),
  ]);
  assert.equal(try1.orderId, try2.orderId);
  assert.equal(try2.orderId, try3.orderId);
  assert.equal((await db.collection("orders").where("merchantId", "==", uid).get()).size, 1);

  const orderId1 = try1.orderId;

  // 3. Captain rejects order & it stays pending/searching for re-dispatch => slot MUST NOT be released!
  assert.equal(isTerminalOrderStatusForCapacity("pending"), false);
  assert.equal(isTerminalOrderStatusForCapacity("searching"), false);
  const rejectAttempt = await releaseOrderCapacitySlot(db, orderId1, uid, "captain_rejected_redispatch");
  assert.equal(rejectAttempt.released, false);
  assert.equal(rejectAttempt.reason, "order_not_terminal");
  const capStillHeld = await db.collection("merchants").doc(uid).collection("capacity").doc("current").get();
  assert.equal(capStillHeld.data().activeReservationsCount, 1);

  // 4. Create 2nd order (now 2/2 full)
  const ord2 = await reserveAndCreateOrderAtomic(db, {
    uid,
    actionName: "requestCaptains",
    destinationAddress: "الطلب الثاني",
    serviceFee: "40 جنيه",
  });
  assert.equal(ord2.success, true);

  // 5. Cancel order 1 & trigger release 5 times concurrently => released EXACTLY ONCE, count drops 2 -> 1
  await db.collection("orders").doc(orderId1).update({ status: "cancelled" });
  const releases = await Promise.all([
    releaseOrderCapacitySlot(db, orderId1, uid, "cancelled"),
    releaseOrderCapacitySlot(db, orderId1, uid, "cancelled"),
    releaseOrderCapacitySlot(db, orderId1, uid, "cancelled"),
    releaseOrderCapacitySlot(db, orderId1, uid, "cancelled"),
    releaseOrderCapacitySlot(db, orderId1, uid, "cancelled"),
  ]);
  const releasedCount = releases.filter((r) => r.released === true).length;
  assert.equal(releasedCount, 1);
  const capAfterCancel = await db.collection("merchants").doc(uid).collection("capacity").doc("current").get();
  assert.equal(capAfterCancel.data().activeReservationsCount, 1);

  // 6. Complete order 2 => released once, count drops 1 -> 0, captain wallet untouched
  await db.collection("captains").doc("cap_earn_1").set({
    uid: "cap_earn_1",
    role: "captain",
    walletBalance: 750,
    currentOrderId: ord2.orderId,
    availabilityStatus: "busy",
  });
  await db.collection("orders").doc(ord2.orderId).update({
    captainId: "cap_earn_1",
    status: "delivered",
  });
  const delRel1 = await releaseOrderCapacitySlot(db, ord2.orderId, uid, "delivered");
  const delRel2 = await releaseOrderCapacitySlot(db, ord2.orderId, uid, "delivered");
  assert.equal(delRel1.released, true);
  assert.equal(delRel2.released, false);
  assert.equal(delRel2.reason, "already_released");

  const capAfterDelivered = await db.collection("merchants").doc(uid).collection("capacity").doc("current").get();
  assert.equal(capAfterDelivered.data().activeReservationsCount, 0);
  const capWallet = (await db.collection("captains").doc("cap_earn_1").get()).data().walletBalance;
  assert.equal(capWallet, 750);

  // 7. Plan upgrade 2 -> 4 immediately unlocks 4 slots
  const upgrade = await applyAdminSubscriptionUpdate(db, "admin_1", {
    merchantId: uid,
    action: "change_plan",
    planId: 2,
    captainLimit: 4,
  });
  assert.equal(upgrade.captainLimit, 4);
  const vCheck = await verifyMerchantSubscription(uid, 4, db);
  assert.equal(vCheck.isValid, true);
  assert.equal(vCheck.remainingCaptains, 4);
});

// =========================================================================
// TASK 3: Single-Path Idempotent Auto-Dispatch (Zero Double Dispatch / Offers)
// =========================================================================
test("TASK 3: 5 concurrent runAutoDispatch invocations on the same order assign closest captain ONCE and send 1 FCM", async () => {
  const uid = "merchant_dispatch_1";
  await seedMerchantAndSubscription(uid, { captainLimit: 2, planId: 1 });

  const nowMs = Date.now();
  const schedule = buildAllDayWeeklySchedule();

  // Seed 2 eligible captains: cap_near (closer) and cap_far (farther)
  await db.collection("captains").doc("cap_near").set({
    uid: "cap_near",
    name: "كابتن قريب",
    phone: "01011111111",
    role: "captain",
    status: "approved",
    isOnline: true,
    availabilityStatus: "available",
    presenceStatus: "available",
    canReceiveOrders: true,
    location: new admin.firestore.GeoPoint(30.045, 31.236),
    lastLocationUpdate: admin.firestore.Timestamp.fromMillis(nowMs - 5000),
    fcmToken: "token_near",
  });
  await db.collection("captain_schedules").doc("cap_near").set(schedule);

  await db.collection("captains").doc("cap_far").set({
    uid: "cap_far",
    name: "كابتن بعيد",
    phone: "01022222222",
    role: "captain",
    status: "approved",
    isOnline: true,
    availabilityStatus: "available",
    presenceStatus: "available",
    canReceiveOrders: true,
    location: new admin.firestore.GeoPoint(30.1, 31.3),
    lastLocationUpdate: admin.firestore.Timestamp.fromMillis(nowMs - 5000),
    fcmToken: "token_far",
  });
  await db.collection("captain_schedules").doc("cap_far").set(schedule);

  const created = await reserveAndCreateOrderAtomic(db, {
    uid,
    actionName: "requestCaptains",
    destinationAddress: "المعادي",
    serviceFee: "30 جنيه",
  });
  assert.equal(created.success, true);

  const orderData = (await db.collection("orders").doc(created.orderId).get()).data();
  const sentNotifications = [];
  const fakeSendFcm = async (payload) => {
    sentNotifications.push(payload);
    return "msg_ok";
  };

  // Fire 5 concurrent dispatch attempts (simulating double call + Cloud Functions retry + captain update)
  const results = await Promise.all([
    runAutoDispatch(created.orderId, orderData, { triggerSource: "onOrderCreated", targetDb: db, sendFcm: fakeSendFcm, nowMs }),
    runAutoDispatch(created.orderId, orderData, { triggerSource: "onOrderCreated_retry", targetDb: db, sendFcm: fakeSendFcm, nowMs }),
    runAutoDispatch(created.orderId, orderData, { triggerSource: "onCaptainUpdated", targetDb: db, sendFcm: fakeSendFcm, nowMs }),
    runAutoDispatch(created.orderId, orderData, { triggerSource: "duplicate_call", targetDb: db, sendFcm: fakeSendFcm, nowMs }),
    runAutoDispatch(created.orderId, orderData, { triggerSource: "duplicate_call_2", targetDb: db, sendFcm: fakeSendFcm, nowMs }),
  ]);

  const winners = results.filter((r) => r === true);
  assert.equal(winners.length, 1, "Only ONE dispatch execution may win the lease and assign a captain");
  assert.equal(sentNotifications.length, 1, "Captain must receive EXACTLY ONE FCM offer");
  assert.equal(sentNotifications[0].token, "token_near", "Closest captain must be selected");

  const finalOrder = (await db.collection("orders").doc(created.orderId).get()).data();
  assert.equal(finalOrder.captainId, "cap_near");
  assert.equal(finalOrder.dispatchStatus, "assigned");
  assert.equal(finalOrder.status, "accepted");

  const capFar = (await db.collection("captains").doc("cap_far").get()).data();
  assert.equal(capFar.availabilityStatus, "available");
  assert.equal(capFar.currentOrderId, undefined);
});

// =========================================================================
// TASK 4: Separate Merchant Account Approval from Subscription Activation
// =========================================================================
test("TASK 4: Activating subscription does NOT auto-approve a pending merchant unless explicit approveMerchant=true", async () => {
  const uid = "merchant_pending_sep";
  await seedMerchantAndSubscription(uid, {
    merchantStatus: "pending",
    subStatus: "unsubscribed",
    version: 1,
    createSubDoc: false,
  });

  // 1. Admin activates subscription WITHOUT approveMerchant flag
  const resNoApprove = await applyAdminSubscriptionUpdate(db, "admin_1", {
    merchantId: uid,
    action: "activate",
    planId: 2,
    durationDays: 30,
  });
  assert.equal(resNoApprove.status, "active");
  assert.equal(resNoApprove.merchantStatus, "pending");
  assert.equal(resNoApprove.merchantApproved, false);
  assert.equal(resNoApprove.merchantApprovedExplicitly, false);

  const merchDoc1 = (await db.collection("merchants").doc(uid).get()).data();
  assert.equal(merchDoc1.status, "pending");
  assert.notEqual(merchDoc1.isApproved, true);

  // Trigger sync ALSO must NEVER auto-approve pending merchant
  const subDoc1 = (await db.collection("subscriptions").doc(uid).get()).data();
  await applySubscriptionWriteSync(db, uid, null, subDoc1);
  const merchDocAfterTrigger = (await db.collection("merchants").doc(uid).get()).data();
  assert.equal(merchDocAfterTrigger.status, "pending");

  // Pending merchant still cannot request captains until explicitly approved
  const pendingAttempt = await reserveAndCreateOrderAtomic(db, {
    uid,
    actionName: "requestCaptains",
    destinationAddress: "مدينة نصر",
    serviceFee: "30 جنيه",
  });
  assert.equal(pendingAttempt.rejected, true);
  assert.equal(pendingAttempt.verification.status, "merchant_pending");

  // 2. Explicit Admin approval unlocks merchant cleanly
  const resExplicitApprove = await applyAdminSubscriptionUpdate(db, "admin_1", {
    merchantId: uid,
    action: "activate",
    planId: 2,
    durationDays: 30,
    approveMerchant: true,
  });
  assert.equal(resExplicitApprove.merchantStatus, "approved");
  assert.equal(resExplicitApprove.merchantApproved, true);
  assert.equal(resExplicitApprove.merchantApprovedExplicitly, true);

  const merchDoc2 = (await db.collection("merchants").doc(uid).get()).data();
  assert.equal(merchDoc2.status, "approved");
  assert.equal(merchDoc2.isApproved, true);

  const approvedAttempt = await reserveAndCreateOrderAtomic(db, {
    uid,
    actionName: "requestCaptains",
    destinationAddress: "مدينة نصر",
    serviceFee: "30 جنيه",
  });
  assert.equal(approvedAttempt.success, true);
});

// =========================================================================
// TASK 5: End-to-End Request Captain Scenario (Basic Plan: 2 Captains, 0/2)
//         + Idempotency Retry + Structured Errors (Zero Raw NOT_FOUND)
// =========================================================================
test("TASK 5: Active Basic Plan (2 captains, 0/2) creates order, dispatches captain, replays idempotently, and returns structured Arabic errors", async () => {
  const uid = "merchant_basic_e2e";
  await seedMerchantAndSubscription(uid, {
    name: "مطعم البطل",
    merchantStatus: "approved",
    subStatus: "active",
    planId: 1,
    planName: "الباقة الأساسية",
    captainLimit: 2,
    version: 2,
  });

  // Seed 1 available captain ready to receive the dispatched order
  const nowMs = Date.now();
  await db.collection("captains").doc("cap_e2e_1").set({
    uid: "cap_e2e_1",
    name: "كابتن أحمد",
    phone: "01099998888",
    role: "captain",
    status: "approved",
    isOnline: true,
    availabilityStatus: "available",
    presenceStatus: "available",
    canReceiveOrders: true,
    location: new admin.firestore.GeoPoint(30.0451, 31.2361),
    lastLocationUpdate: admin.firestore.Timestamp.fromMillis(nowMs - 3000),
    fcmToken: "token_cap_e2e_1",
  });
  await db.collection("captain_schedules").doc("cap_e2e_1").set(buildAllDayWeeklySchedule());

  // 1. Merchant enters customer address & delivery fee and presses "طلب كابتن"
  const idemKey = "e2e_req_basic_001";
  const created = await reserveAndCreateOrderAtomic(db, {
    uid,
    actionName: "requestCaptains",
    requestId: idemKey,
    idempotencyKey: idemKey,
    destinationAddress: "شارع التحرير - الدقي - عمارة 14",
    serviceFee: "35 جنيه",
    merchantPhone: "01012345678",
    requestedCaptains: 1,
  });

  assert.equal(created.success, true);
  assert.match(created.orderCode, /^[1-9]\d{5}$/);
  assert.equal(created.activeCaptainsCount, 1);
  assert.equal(created.remainingCaptains, 1);
  assert.equal(created.captainLimit, 2);

  // 2. Verify order exists in Firestore with proper UID ownership and no missing fields
  const orderSnap = await db.collection("orders").doc(created.orderId).get();
  assert.equal(orderSnap.exists, true);
  const orderData = orderSnap.data();
  assert.equal(orderData.merchantId, uid);
  assert.equal(orderData.restaurantId, uid);
  assert.equal(orderData.userId, uid);
  assert.equal(orderData.destinationAddress, "شارع التحرير - الدقي - عمارة 14");
  assert.equal(orderData.serviceFee, "35 جنيه");
  assert.equal(orderData.merchantPhone, "01012345678");

  // 3. Auto-dispatch runs and assigns the captain once
  const fcmSent = [];
  const dispatched = await runAutoDispatch(created.orderId, orderData, {
    triggerSource: "onOrderCreated",
    targetDb: db,
    sendFcm: async (p) => {
      fcmSent.push(p);
      return "msg_e2e_1";
    },
    nowMs,
  });
  assert.equal(dispatched, true);
  assert.equal(fcmSent.length, 1);
  assert.equal(fcmSent[0].token, "token_cap_e2e_1");

  const assignedOrder = (await db.collection("orders").doc(created.orderId).get()).data();
  assert.equal(assignedOrder.captainId, "cap_e2e_1");
  assert.equal(assignedOrder.status, "accepted");

  // 4. Retry with same idempotencyKey returns existing order without duplication or extra slot usage
  const replay = await reserveAndCreateOrderAtomic(db, {
    uid,
    actionName: "requestCaptains",
    requestId: idemKey,
    idempotencyKey: idemKey,
    destinationAddress: "شارع التحرير - الدقي - عمارة 14",
    serviceFee: "35 جنيه",
    requestedCaptains: 1,
  });
  assert.equal(replay.success, true);
  assert.equal(replay.isReplay, true);
  assert.equal(replay.orderId, created.orderId);
  assert.equal(replay.orderCode, created.orderCode);
  assert.equal((await db.collection("orders").where("merchantId", "==", uid).get()).size, 1);

  // 5. Missing customer address returns structured invalid-argument with Arabic message
  await assert.rejects(
    reserveAndCreateOrderAtomic(db, {
      uid,
      actionName: "requestCaptains",
      destinationAddress: "   ",
      serviceFee: "30 جنيه",
    }),
    (err) => {
      assert.equal(err.code, "invalid-argument");
      assert.match(err.message, /عنوان العميل/);
      assert.equal(err.details?.reasonCode, "MISSING_CUSTOMER_ADDRESS");
      return true;
    }
  );

  // 6. Missing merchant doc returns structured not-found with Arabic message (never bare NOT_FOUND)
  await assert.rejects(
    reserveAndCreateOrderAtomic(db, {
      uid: "nonexistent_merchant_uid",
      actionName: "requestCaptains",
      destinationAddress: "المعادي",
      serviceFee: "30 جنيه",
    }),
    (err) => {
      assert.equal(err.code, "not-found");
      assert.equal(err.message, "تعذر العثور على بيانات حساب التاجر.");
      assert.equal(err.details?.reasonCode, "MERCHANT_NOT_FOUND");
      return true;
    }
  );

  // 7. Missing subscription doc returns structured rejection with clear Arabic message
  await seedMerchantAndSubscription("merchant_no_sub", {
    merchantStatus: "approved",
    subStatus: "unsubscribed",
    createSubDoc: false,
  });
  const noSubRes = await reserveAndCreateOrderAtomic(db, {
    uid: "merchant_no_sub",
    actionName: "requestCaptains",
    destinationAddress: "المعادي",
    serviceFee: "30 جنيه",
  });
  assert.equal(noSubRes.rejected, true);
  assert.equal(noSubRes.rejectionReason, "لم يتم العثور على اشتراك التاجر.");
});

