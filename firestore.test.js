const {
  initializeTestEnvironment,
  assertFails,
  assertSucceeds,
} = require("@firebase/rules-unit-testing");
const { test, before, after, beforeEach } = require("node:test");
const fs = require("node:fs");

let testEnv;
const PROJECT_ID = process.env.GCP_PROJECT || "demo-no-project";
const MERCHANT_A = "merchant_a_101";
const MERCHANT_B = "merchant_b_202";
const CAPTAIN_UID = "captain_999";
const OTHER_CAPTAIN_UID = "captain_888";
const ADMIN_UID = "admin_001";

const [emulatorHost, emulatorPortStr] = (
  process.env.FIRESTORE_EMULATOR_HOST || "127.0.0.1:8085"
).split(":");
const emulatorPort = parseInt(emulatorPortStr, 10);

before(async () => {
  const rules = fs.readFileSync("./firestore.rules", "utf8");
  testEnv = await initializeTestEnvironment({
    projectId: PROJECT_ID,
    firestore: {
      rules,
      host: emulatorHost,
      port: emulatorPort,
    },
  });
});

after(async () => {
  if (testEnv) {
    await testEnv.cleanup();
  }
});

beforeEach(async () => {
  if (testEnv) {
    await testEnv.clearFirestore();
  }
});

async function seedMerchant(uid, email, overrides = {}) {
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("merchants").doc(uid).set({
      uid,
      name: "مطعم البطل",
      email,
      phone: "01012345678",
      address: "القاهرة",
      category: "مطعم",
      accountType: "merchant",
      role: "merchant",
      status: "approved",
      emailVerified: true,
      subscriptionStatus: "unsubscribed",
      isSubscribed: false,
      isActive: false,
      maxCaptains: 0,
      captainLimit: 0,
      planId: 0,
      subscriptionVersion: 1,
      createdAt: new Date(Date.now() - 60000),
      updatedAt: new Date(Date.now() - 60000),
      ...overrides,
    });
  });
}

function buildPendingOrderPayload(merchantId, orderId = "order_test_1") {
  return {
    orderId,
    orderCode: "548291",
    code: "548291",
    address: "مدينة نصر",
    destinationAddress: "مدينة نصر",
    deliveryAddress: "مدينة نصر",
    price: "35 جنيه",
    serviceFee: "35 جنيه",
    totalAmount: "35 جنيه",
    merchantId,
    userId: merchantId,
    restaurantId: merchantId,
    merchantName: "مطعم البطل",
    status: "pending",
    dispatchStatus: "searching",
    captainId: null,
    customerDetailsSubmitted: false,
    customerDetailsConfirmed: false,
    customerDetailsRejected: false,
    capacityReserved: true,
    capacityReleased: false,
    createdByServerFunction: "requestCaptains",
    createdAt: new Date(Date.now() - 1000),
    created_at: new Date(Date.now() - 1000),
  };
}

test("Merchant cannot self-approve, self-activate, tamper with subscriptions/capacity, or write operation_logs", async () => {
  await seedMerchant(MERCHANT_A, "merchanta@example.com", { status: "pending" });
  const merchDb = testEnv
    .authenticatedContext(MERCHANT_A, {
      email: "merchanta@example.com",
      email_verified: true,
    })
    .firestore();

  // Merchant CANNOT self-approve pending account
  await assertFails(
    merchDb.collection("merchants").doc(MERCHANT_A).update({
      status: "approved",
    })
  );
  await assertFails(
    merchDb.collection("merchants").doc(MERCHANT_A).update({
      isApproved: true,
    })
  );

  // Direct write to subscriptions/{uid} denied for non-admin
  await assertFails(
    merchDb.collection("subscriptions").doc(MERCHANT_A).set({
      merchantId: MERCHANT_A,
      status: "active",
      isActive: true,
      isSubscribed: true,
      captainLimit: 8,
      maxCaptains: 8,
      planId: 4,
      paymentStatus: "paid",
    })
  );

  // Tampering protected subscription fields on merchants/{uid} denied
  await assertFails(
    merchDb.collection("merchants").doc(MERCHANT_A).update({
      subscriptionStatus: "active",
      isSubscribed: true,
    })
  );
  await assertFails(
    merchDb.collection("merchants").doc(MERCHANT_A).update({
      isActive: true,
      captainLimit: 8,
    })
  );
  await assertFails(
    merchDb.collection("merchants").doc(MERCHANT_A).update({
      expiresAt: new Date(Date.now() + 99999999),
      subscriptionVersion: 99,
    })
  );

  // Merchant CANNOT tamper with capacity subcollection
  await assertFails(
    merchDb
      .collection("merchants")
      .doc(MERCHANT_A)
      .collection("capacity")
      .doc("current")
      .set({ activeReservationsCount: 0, reservedOrderIds: [] })
  );

  // Merchant cannot write or read server-only operation_logs
  await assertFails(
    merchDb.collection("operation_logs").doc("log_1").set({
      type: "forged",
      merchantId: MERCHANT_A,
    })
  );
  await assertFails(merchDb.collection("operation_logs").doc("log_1").get());

  // Legitimate merchant profile update (name/address only) still succeeds
  await assertSucceeds(
    merchDb.collection("merchants").doc(MERCHANT_A).update({
      name: "مطعم البطل المحدث",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Direct client creation of orders, order_codes, and idempotency_keys is STRICTLY BLOCKED even for active merchants", async () => {
  await seedMerchant(MERCHANT_A, "merchanta@example.com", {
    status: "approved",
    subscriptionStatus: "active",
    isActive: true,
    isSubscribed: true,
    captainLimit: 4,
    maxCaptains: 4,
    planId: 2,
  });
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("admins").doc(ADMIN_UID).set({ role: "admin" });
    await db.collection("subscriptions").doc(MERCHANT_A).set({
      merchantId: MERCHANT_A,
      status: "active",
      subscriptionStatus: "active",
      isActive: true,
      isSubscribed: true,
      planId: 2,
      captainLimit: 4,
      maxCaptains: 4,
      paymentStatus: "paid",
      expiresAt: new Date(Date.now() + 30 * 86400000),
      endDate: new Date(Date.now() + 30 * 86400000),
    });
  });

  const merchDb = testEnv
    .authenticatedContext(MERCHANT_A, {
      email: "merchanta@example.com",
      email_verified: true,
    })
    .firestore();

  // Direct create on orders MUST FAIL even with active subscription (must go via Cloud Functions)
  await assertFails(
    merchDb
      .collection("orders")
      .doc("ord_direct_bypass")
      .set(buildPendingOrderPayload(MERCHANT_A, "ord_direct_bypass"))
  );

  // Direct create on order_codes MUST FAIL
  await assertFails(
    merchDb.collection("order_codes").doc("548291").set({
      code: "548291",
      merchantId: MERCHANT_A,
      orderId: "ord_direct_bypass",
    })
  );

  // Direct create on idempotency_keys MUST FAIL
  await assertFails(
    merchDb.collection("idempotency_keys").doc("key_forged_1").set({
      idempotencyKey: "key_forged_1",
      merchantId: MERCHANT_A,
      orderId: "ord_direct_bypass",
    })
  );
});

test("Merchant isolation: Merchant A cannot access Merchant B profile, subscription, capacity, or orders; non-admins cannot alter subscriptions", async () => {
  await seedMerchant(MERCHANT_A, "merchanta@example.com");
  await seedMerchant(MERCHANT_B, "merchantb@example.com");
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("admins").doc(ADMIN_UID).set({ role: "admin" });
    await db.collection("captains").doc(CAPTAIN_UID).set({
      uid: CAPTAIN_UID,
      name: "كابتن أحمد",
      role: "captain",
      status: "approved",
      walletBalance: 150,
    });
    await db.collection("subscriptions").doc(MERCHANT_A).set({
      merchantId: MERCHANT_A,
      status: "active",
      isActive: true,
      isSubscribed: true,
      planId: 2,
      captainLimit: 4,
      maxCaptains: 4,
      paymentStatus: "paid",
      expiresAt: new Date(Date.now() + 86400000),
    });
    await db.collection("subscriptions").doc(MERCHANT_B).set({
      merchantId: MERCHANT_B,
      status: "suspended",
      isActive: false,
      isSubscribed: false,
      planId: 2,
      captainLimit: 0,
      maxCaptains: 0,
    });
    await db.collection("orders").doc("ord_b_1").set(buildPendingOrderPayload(MERCHANT_B, "ord_b_1"));
    await db.collection("operation_logs").doc("log_admin_read").set({
      type: "subscription_admin_update",
      merchantId: MERCHANT_A,
    });
  });

  const merchADb = testEnv
    .authenticatedContext(MERCHANT_A, {
      email: "merchanta@example.com",
      email_verified: true,
    })
    .firestore();
  const captainDb = testEnv
    .authenticatedContext(CAPTAIN_UID, {
      email: "cap@example.com",
      email_verified: true,
    })
    .firestore();
  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, {
      email: "admin@elbatalexpress.com",
      email_verified: true,
    })
    .firestore();

  // Merchant A reads own subscription, blocked from B & listing
  await assertSucceeds(merchADb.collection("subscriptions").doc(MERCHANT_A).get());
  await assertFails(merchADb.collection("subscriptions").doc(MERCHANT_B).get());
  await assertFails(merchADb.collection("subscriptions").get());
  await assertFails(merchADb.collection("merchants").doc(MERCHANT_B).get());
  await assertFails(merchADb.collection("orders").doc("ord_b_1").get());

  // Captain cannot read/modify any merchant subscription or profile
  await assertFails(captainDb.collection("subscriptions").doc(MERCHANT_A).get());
  await assertFails(
    captainDb.collection("subscriptions").doc(MERCHANT_A).update({ status: "suspended" })
  );
  await assertFails(captainDb.collection("merchants").doc(MERCHANT_A).get());

  // Admin can read operation_logs, cannot forge them directly via client SDK
  await assertSucceeds(adminDb.collection("operation_logs").doc("log_admin_read").get());
  await assertFails(adminDb.collection("operation_logs").doc("log_2").set({ type: "manual" }));
});

test("Ongoing orders, Captain delivery workflow, Captain earnings protection, and financial/assignment immutability", async () => {
  await seedMerchant(MERCHANT_A, "merchanta@example.com", {
    status: "approved",
    subscriptionStatus: "suspended",
    isActive: false,
    isSubscribed: false,
  });
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("captains").doc(CAPTAIN_UID).set({
      uid: CAPTAIN_UID,
      name: "كابتن أحمد",
      role: "captain",
      status: "approved",
      walletBalance: 500,
    });
    await db.collection("captains").doc(OTHER_CAPTAIN_UID).set({
      uid: OTHER_CAPTAIN_UID,
      name: "كابتن آخر",
      role: "captain",
      status: "approved",
      walletBalance: 100,
    });
    await db
      .collection("captains")
      .doc(CAPTAIN_UID)
      .collection("transactions")
      .doc("tx_earning_1")
      .set({ amount: 35, orderId: "ord_active", type: "delivery_earning" });

    // Server-created pending order (unassigned)
    await db.collection("orders").doc("ord_pending").set(
      buildPendingOrderPayload(MERCHANT_A, "ord_pending")
    );

    // Server-created ongoing order assigned to CAPTAIN_UID
    await db.collection("orders").doc("ord_active").set({
      ...buildPendingOrderPayload(MERCHANT_A, "ord_active"),
      captainId: CAPTAIN_UID,
      captainName: "كابتن أحمد",
      status: "accepted",
      dispatchStatus: "assigned",
      captainEarnings: 30,
    });

    // Legacy restaurantId order
    await db.collection("orders").doc("ord_legacy_rest").set({
      orderId: "ord_legacy_rest",
      restaurantId: MERCHANT_A,
      status: "delivered",
    });
  });

  const merchDb = testEnv
    .authenticatedContext(MERCHANT_A, { email: "merchanta@example.com", email_verified: true })
    .firestore();
  const captainDb = testEnv
    .authenticatedContext(CAPTAIN_UID, { email: "cap@example.com", email_verified: true })
    .firestore();
  const otherCaptainDb = testEnv
    .authenticatedContext(OTHER_CAPTAIN_UID, { email: "cap2@example.com", email_verified: true })
    .firestore();

  // Ongoing order remains readable & confirmable by Merchant even after subscription suspension
  await assertSucceeds(merchDb.collection("orders").doc("ord_active").get());
  await assertSucceeds(merchDb.collection("orders").doc("ord_legacy_rest").get());
  await assertSucceeds(
    merchDb.collection("orders").doc("ord_active").update({ customerDetailsConfirmed: true })
  );

  // Merchant CANNOT tamper with assigned order's financial/assignment/capacity fields
  await assertFails(
    merchDb.collection("orders").doc("ord_active").update({ price: "1 جنيه" })
  );
  await assertFails(
    merchDb.collection("orders").doc("ord_active").update({ captainEarnings: 0 })
  );
  await assertFails(
    merchDb.collection("orders").doc("ord_active").update({ captainId: OTHER_CAPTAIN_UID })
  );
  await assertFails(
    merchDb.collection("orders").doc("ord_active").update({ dispatchStatus: "searching" })
  );
  await assertFails(
    merchDb.collection("orders").doc("ord_active").update({ capacityReleased: true })
  );

  // Merchant CAN still cancel an unassigned pending order
  await assertSucceeds(
    merchDb.collection("orders").doc("ord_pending").update({
      status: "cancelled",
      cancelReason: "إلغاء التاجر",
    })
  );

  // Assigned captain CAN read & progress delivery status
  await assertSucceeds(captainDb.collection("orders").doc("ord_active").get());
  await assertSucceeds(
    captainDb.collection("orders").doc("ord_active").update({ status: "in_progress" })
  );
  await assertSucceeds(
    captainDb.collection("orders").doc("ord_active").update({ status: "delivered" })
  );

  // Captain CANNOT tamper with order financials, earnings, capacity, or own wallet transactions
  await assertFails(
    captainDb.collection("orders").doc("ord_active").update({ price: "999 جنيه" })
  );
  await assertFails(
    captainDb.collection("orders").doc("ord_active").update({ captainEarnings: 9999 })
  );
  await assertFails(
    captainDb.collection("orders").doc("ord_active").update({ capacityReleased: true })
  );
  await assertSucceeds(
    captainDb.collection("captains").doc(CAPTAIN_UID).collection("transactions").doc("tx_earning_1").get()
  );
  await assertFails(
    captainDb
      .collection("captains")
      .doc(CAPTAIN_UID)
      .collection("transactions")
      .doc("tx_earning_1")
      .update({ amount: 9999 })
  );

  // Unassigned captain CANNOT read or modify another captain's order
  await assertFails(otherCaptainDb.collection("orders").doc("ord_active").get());
  await assertFails(
    otherCaptainDb.collection("orders").doc("ord_active").update({ status: "delivered" })
  );

  // Unverified merchant email cannot read subscriptions
  const unverifiedMerchDb = testEnv
    .authenticatedContext(MERCHANT_A, { email: "merchanta@example.com", email_verified: false })
    .firestore();
  await assertFails(unverifiedMerchDb.collection("subscriptions").doc(MERCHANT_A).get());
});
