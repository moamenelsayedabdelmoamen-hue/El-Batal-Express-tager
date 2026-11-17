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

async function seedApprovedMerchant(uid, email, subOverrides = {}) {
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
      maxCaptains: 0,
      planId: 0,
      createdAt: new Date(Date.now() - 60000),
      updatedAt: new Date(Date.now() - 60000),
      ...subOverrides,
    });
  });
}

function buildPendingOrderPayload(merchantId) {
  return {
    orderId: "order_test_1",
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
    merchantName: "مطعم البطل",
    status: "pending",
    dispatchStatus: "searching",
    captainId: null,
    customerDetailsSubmitted: false,
    customerDetailsConfirmed: false,
    customerDetailsRejected: false,
    createdAt: new Date(Date.now() - 1000),
    created_at: new Date(Date.now() - 1000),
  };
}

test("Merchant cannot self-activate, extend, suspend, or overwrite subscription", async () => {
  await seedApprovedMerchant(MERCHANT_A, "merchanta@example.com");
  const merchDb = testEnv
    .authenticatedContext(MERCHANT_A, {
      email: "merchanta@example.com",
      email_verified: true,
    })
    .firestore();

  // Attempt direct write to subscriptions/{uid}
  await assertFails(
    merchDb.collection("subscriptions").doc(MERCHANT_A).set({
      merchantId: MERCHANT_A,
      status: "active",
      isSubscribed: true,
      maxCaptains: 8,
      planId: 4,
      paymentStatus: "paid",
    })
  );

  // Attempt tampering subscriptionStatus on merchants/{uid}
  await assertFails(
    merchDb.collection("merchants").doc(MERCHANT_A).update({
      subscriptionStatus: "active",
      isSubscribed: true,
      maxCaptains: 8,
    })
  );

  // Legitimate merchant profile update (name/address only) still succeeds
  await assertSucceeds(
    merchDb.collection("merchants").doc(MERCHANT_A).update({
      name: "مطعم البطل المحدث",
      updatedAt: new Date(Date.now() - 500),
    })
  );
});

test("Merchant can read only their own subscription doc; cross-merchant & list are denied", async () => {
  await seedApprovedMerchant(MERCHANT_A, "merchanta@example.com");
  await seedApprovedMerchant(MERCHANT_B, "merchantb@example.com");
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("subscriptions").doc(MERCHANT_A).set({
      merchantId: MERCHANT_A,
      status: "active",
      isSubscribed: true,
      planId: 2,
      maxCaptains: 4,
      paymentStatus: "paid",
      endDate: new Date(Date.now() + 86400000),
    });
    await db.collection("subscriptions").doc(MERCHANT_B).set({
      merchantId: MERCHANT_B,
      status: "suspended",
      isSubscribed: false,
      planId: 2,
      maxCaptains: 0,
      paymentStatus: "paid",
    });
  });

  const merchADb = testEnv
    .authenticatedContext(MERCHANT_A, {
      email: "merchanta@example.com",
      email_verified: true,
    })
    .firestore();

  await assertSucceeds(merchADb.collection("subscriptions").doc(MERCHANT_A).get());
  await assertFails(merchADb.collection("subscriptions").doc(MERCHANT_B).get());
  await assertFails(merchADb.collection("subscriptions").get());
  await assertFails(
    merchADb.collection("subscriptions").where("merchantId", "==", MERCHANT_A).get()
  );
});

test("Server rules block order creation when unsubscribed, suspended, cancelled, or expired; allow when active", async () => {
  await seedApprovedMerchant(MERCHANT_A, "merchanta@example.com");
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    await ctx.firestore().collection("admins").doc(ADMIN_UID).set({ role: "admin" });
  });

  const merchDb = testEnv
    .authenticatedContext(MERCHANT_A, {
      email: "merchanta@example.com",
      email_verified: true,
    })
    .firestore();
  const adminDb = testEnv
    .authenticatedContext(ADMIN_UID, {
      email: "admin@elbatalexpress.com",
      email_verified: true,
    })
    .firestore();

  // 1. Unsubscribed -> order create MUST FAIL
  await assertFails(
    merchDb.collection("orders").doc("ord_unsub").set(buildPendingOrderPayload(MERCHANT_A))
  );

  // 2. Admin activates subscription -> order create MUST SUCCEED
  const futureEnd = new Date(Date.now() + 30 * 86400000);
  await assertSucceeds(
    adminDb.collection("subscriptions").doc(MERCHANT_A).set({
      merchantId: MERCHANT_A,
      status: "active",
      subscriptionStatus: "active",
      isSubscribed: true,
      planId: 2,
      planName: "الباقة المتوسطة",
      maxCaptains: 4,
      price: 800,
      paymentStatus: "paid",
      startDate: new Date(Date.now() - 1000),
      endDate: futureEnd,
    })
  );
  await assertSucceeds(
    merchDb.collection("orders").doc("ord_active").set(buildPendingOrderPayload(MERCHANT_A))
  );

  // 3. Admin suspends on subscriptions -> order create MUST FAIL immediately
  await assertSucceeds(
    adminDb.collection("subscriptions").doc(MERCHANT_A).update({
      status: "suspended",
      subscriptionStatus: "suspended",
      isSubscribed: false,
    })
  );
  await assertFails(
    merchDb.collection("orders").doc("ord_suspended").set(buildPendingOrderPayload(MERCHANT_A))
  );

  // 4. Admin reactivates subscriptions, but merchants/{uid} marks suspended -> MUST STILL FAIL
  await assertSucceeds(
    adminDb.collection("subscriptions").doc(MERCHANT_A).update({
      status: "active",
      subscriptionStatus: "active",
      isSubscribed: true,
    })
  );
  await assertSucceeds(
    adminDb.collection("merchants").doc(MERCHANT_A).update({
      subscriptionStatus: "suspended",
    })
  );
  await assertFails(
    merchDb.collection("orders").doc("ord_merch_suspended").set(buildPendingOrderPayload(MERCHANT_A))
  );

  // 5. Admin clears suspension -> order create succeeds again
  await assertSucceeds(
    adminDb.collection("merchants").doc(MERCHANT_A).update({
      subscriptionStatus: "active",
    })
  );
  await assertSucceeds(
    merchDb.collection("orders").doc("ord_reactivated").set(buildPendingOrderPayload(MERCHANT_A))
  );

  // 6. Admin cancels -> MUST FAIL
  await assertSucceeds(
    adminDb.collection("subscriptions").doc(MERCHANT_A).update({
      status: "cancelled",
      subscriptionStatus: "cancelled",
      isSubscribed: false,
      maxCaptains: 0,
    })
  );
  await assertFails(
    merchDb.collection("orders").doc("ord_cancelled").set(buildPendingOrderPayload(MERCHANT_A))
  );

  // 7. Expired endDate -> MUST FAIL even if status accidentally says active
  await assertSucceeds(
    adminDb.collection("subscriptions").doc(MERCHANT_A).update({
      status: "active",
      subscriptionStatus: "active",
      isSubscribed: true,
      maxCaptains: 4,
      endDate: new Date(Date.now() - 5000),
    })
  );
  await assertFails(
    merchDb.collection("orders").doc("ord_expired").set(buildPendingOrderPayload(MERCHANT_A))
  );
});

test("Captain account cannot read merchant subscription or create merchant orders; unverified admin email rejected", async () => {
  await seedApprovedMerchant(MERCHANT_A, "merchanta@example.com");
  await testEnv.withSecurityRulesDisabled(async (ctx) => {
    const db = ctx.firestore();
    await db.collection("captains").doc(CAPTAIN_UID).set({
      uid: CAPTAIN_UID,
      name: "كابتن",
      role: "captain",
      status: "approved",
      walletBalance: 0,
    });
    await db.collection("subscriptions").doc(MERCHANT_A).set({
      merchantId: MERCHANT_A,
      status: "active",
      isSubscribed: true,
      maxCaptains: 4,
      paymentStatus: "paid",
      endDate: new Date(Date.now() + 86400000),
    });
  });

  const captainDb = testEnv
    .authenticatedContext(CAPTAIN_UID, { email: "cap@example.com", email_verified: true })
    .firestore();
  await assertFails(captainDb.collection("merchants").doc(MERCHANT_A).get());
  await assertFails(captainDb.collection("subscriptions").doc(MERCHANT_A).get());
  await assertFails(
    captainDb.collection("orders").doc("ord_cap").set(buildPendingOrderPayload(CAPTAIN_UID))
  );

  // Spoofed unverified admin email must NOT be treated as admin
  const spoofAdminDb = testEnv
    .authenticatedContext("spoof_uid", {
      email: "admin@elbatalexpress.com",
      email_verified: false,
    })
    .firestore();
  await assertFails(
    spoofAdminDb.collection("subscriptions").doc(MERCHANT_A).update({
      status: "suspended",
    })
  );
});
