const test = require('node:test');
const assert = require('node:assert/strict');
const admin = require('firebase-admin');
const {
  OFFICIAL_SUBSCRIPTION_PLANS,
  resolveTrustedServerPlan,
  normalizeEgyptianMobile,
  isValidMerchantReceiptStoragePath,
  assertManualRequestMerchantOwnership,
  evaluateDualDocSubscription,
  applyManualSubscriptionRequest,
  applyAdminSubscriptionUpdate,
} = require('../lib/index.js');

const NOW = 1760000000000;
const DAY_MS = 24 * 60 * 60 * 1000;

/**
 * Lightweight in-memory Firestore fake supporting runTransaction, get, set({merge}), and FieldValue.delete().
 */
function createFakeFirestore(initialCollections = {}) {
  const store = new Map();
  for (const [colName, docs] of Object.entries(initialCollections)) {
    for (const [docId, data] of Object.entries(docs)) {
      store.set(`${colName}/${docId}`, { ...data });
    }
  }

  function applyMerge(existing, incoming, merge) {
    const base = merge && existing ? { ...existing } : {};
    for (const [k, v] of Object.entries(incoming || {})) {
      // Handle admin.firestore.FieldValue.delete()
      if (v && typeof v === 'object' && v.constructor && v.constructor.name === 'DeleteTransform') {
        delete base[k];
      } else if (v && typeof v === 'object' && v.constructor && v.constructor.name === 'ServerTimestampTransform') {
        base[k] = admin.firestore.Timestamp.fromMillis(NOW);
      } else {
        base[k] = v;
      }
    }
    return base;
  }

  function makeRef(colName, docId) {
    const key = `${colName}/${docId}`;
    return {
      id: docId,
      path: key,
      async get() {
        const val = store.get(key);
        return {
          id: docId,
          exists: val !== undefined,
          data: () => (val ? { ...val } : undefined),
        };
      },
    };
  }

  return {
    store,
    collection(colName) {
      return {
        doc(docId) {
          return makeRef(colName, docId);
        },
      };
    },
    async runTransaction(fn) {
      const writes = [];
      const tx = {
        async get(ref) {
          return ref.get();
        },
        set(ref, data, opts) {
          writes.push({ ref, data, merge: Boolean(opts && opts.merge) });
        },
      };
      const result = await fn(tx);
      for (const w of writes) {
        const prev = store.get(w.ref.path);
        store.set(w.ref.path, applyMerge(prev, w.data, w.merge));
      }
      return result;
    },
  };
}

const okStorageVerifier = async () => ({
  exists: true,
  sizeBytes: 145000,
  contentType: 'image/jpeg',
});

const missingStorageVerifier = async () => ({
  exists: false,
});

test('Official 4 plans catalog preserves exact prices and captain limits', () => {
  assert.deepEqual(Object.keys(OFFICIAL_SUBSCRIPTION_PLANS).map(Number), [1, 2, 3, 4]);
  assert.deepEqual(resolveTrustedServerPlan(1), {
    planId: 1,
    planName: 'الباقة الأساسية',
    maxCaptains: 2,
    captainLimit: 2,
    price: 400,
    durationDays: 30,
  });
  assert.deepEqual(resolveTrustedServerPlan(2), {
    planId: 2,
    planName: 'الباقة المتوسطة',
    maxCaptains: 4,
    captainLimit: 4,
    price: 800,
    durationDays: 30,
  });
  assert.deepEqual(resolveTrustedServerPlan(3), {
    planId: 3,
    planName: 'الباقة المميزة',
    maxCaptains: 6,
    captainLimit: 6,
    price: 1200,
    durationDays: 30,
  });
  assert.deepEqual(resolveTrustedServerPlan(4), {
    planId: 4,
    planName: 'الباقة المتقدمة',
    maxCaptains: 8,
    captainLimit: 8,
    price: 1600,
    durationDays: 30,
  });
  assert.equal(resolveTrustedServerPlan(99), null);
});

test('Phone normalization and receipt Storage path ownership validation', () => {
  assert.equal(normalizeEgyptianMobile('+20 101 234 5678'), '01012345678');
  assert.equal(normalizeEgyptianMobile('01555667788'), '01555667788');
  assert.equal(normalizeEgyptianMobile('12345'), null);

  assert.equal(
    isValidMerchantReceiptStoragePath('m_uid_1', 'merchants/m_uid_1/subscription_receipts/subreq_1.jpg'),
    'merchants/m_uid_1/subscription_receipts/subreq_1.jpg'
  );
  // Reject cross-merchant path or path traversal
  assert.equal(
    isValidMerchantReceiptStoragePath('m_uid_1', 'merchants/m_uid_2/subscription_receipts/subreq_1.jpg'),
    null
  );
  assert.equal(
    isValidMerchantReceiptStoragePath('m_uid_1', 'merchants/m_uid_1/subscription_receipts/../hack.jpg'),
    null
  );
});

test('applyManualSubscriptionRequest rejects missing receipt, unowned path, and invalid phone', async () => {
  const db = createFakeFirestore({
    merchants: {
      m1: { accountType: 'merchant', status: 'approved', name: 'مطعم البطل', email: 'm1@test.com' },
    },
  });

  await assert.rejects(
    () =>
      applyManualSubscriptionRequest(
        db,
        {
          merchantId: 'm1',
          planId: 2,
          senderPhone: '01012345678',
          receiptStoragePath: 'merchants/m1/subscription_receipts/subreq_missing.jpg',
          requestId: 'subreq_missing',
          nowMs: NOW,
        },
        missingStorageVerifier
      ),
    (err) => err.code === 'failed-precondition'
  );

  await assert.rejects(
    () =>
      applyManualSubscriptionRequest(
        db,
        {
          merchantId: 'm1',
          planId: 2,
          senderPhone: '01012345678',
          receiptStoragePath: 'merchants/other_merchant/subscription_receipts/subreq_stolen.jpg',
          requestId: 'subreq_stolen',
          nowMs: NOW,
        },
        okStorageVerifier
      ),
    (err) => err.code === 'invalid-argument'
  );

  await assert.rejects(
    () =>
      applyManualSubscriptionRequest(
        db,
        {
          merchantId: 'm1',
          planId: 2,
          senderPhone: '01012345678',
          receiptStoragePath: 'merchants/m1/subscription_receipts/subreq_pdf.jpg',
          requestId: 'subreq_pdf',
          nowMs: NOW,
        },
        async () => ({ exists: true, sizeBytes: 5000, contentType: 'application/pdf' })
      ),
    (err) => err.code === 'invalid-argument'
  );
});

test('applyManualSubscriptionRequest ignores client-tampered price/captains, writes pending_review, blocks duplicates, and supports idempotent replay', async () => {
  const db = createFakeFirestore({
    merchants: {
      m1: {
        accountType: 'merchant',
        status: 'approved',
        name: 'مطعم النيل',
        email: 'nile@test.com',
        phone: '01099998888',
        subscriptionStatus: 'unsubscribed',
      },
    },
  });

  const submitted = await applyManualSubscriptionRequest(
    db,
    {
      merchantId: 'm1',
      packageId: 3,
      // Attempt to tamper price & captain limit from client:
      amount: 1,
      expectedAmount: 1,
      maxCaptains: 99,
      senderPhone: '+20 100 111 2233',
      transactionReference: 'VF-554433',
      notes: 'تم التحويل الساعة 5 مساءً',
      receiptStoragePath: 'merchants/m1/subscription_receipts/subreq_001.jpg',
      requestId: 'subreq_001',
      nowMs: NOW,
    },
    okStorageVerifier
  );

  assert.equal(submitted.success, true);
  assert.equal(submitted.isReplay, false);
  assert.equal(submitted.status, 'pending_review');
  assert.equal(submitted.packageId, 3);
  assert.equal(submitted.amount, 1200);
  assert.equal(submitted.expectedAmount, 1200);
  assert.equal(submitted.maxCaptains, 6);
  assert.equal(submitted.durationDays, 30);
  assert.equal(submitted.notes, 'تم التحويل الساعة 5 مساءً');

  const reqDoc = db.store.get('subscription_requests/subreq_001');
  const subDoc = db.store.get('subscriptions/m1');
  const merchDoc = db.store.get('merchants/m1');

  assert.equal(reqDoc.status, 'pending_review');
  assert.equal(reqDoc.packageId, 3);
  assert.equal(reqDoc.amount, 1200);
  assert.equal(reqDoc.expectedAmount, 1200);
  assert.equal(reqDoc.notes, 'تم التحويل الساعة 5 مساءً');
  assert.equal(reqDoc.senderPhone, '01001112233');
  assert.equal(subDoc.status, 'pending_review');
  assert.equal(subDoc.isActive, false);
  assert.equal(subDoc.isSubscribed, false);
  assert.equal(subDoc.captainLimit, 0);
  assert.equal(merchDoc.subscriptionStatus, 'pending_review');
  assert.equal(merchDoc.captainLimit, 0);

  // Never active before Admin approval
  const evalWhilePending = evaluateDualDocSubscription(subDoc, merchDoc, 0, NOW, 1);
  assert.equal(evalWhilePending.isValid, false);
  assert.equal(evalWhilePending.status, 'pending_review');
  assert.equal(evalWhilePending.captainLimit, 0);

  // Same requestId is an idempotent replay
  const replay = await applyManualSubscriptionRequest(
    db,
    {
      merchantId: 'm1',
      planId: 3,
      senderPhone: '01001112233',
      receiptStoragePath: 'merchants/m1/subscription_receipts/subreq_001.jpg',
      requestId: 'subreq_001',
      nowMs: NOW + 1000,
    },
    okStorageVerifier
  );
  assert.equal(replay.isReplay, true);
  assert.equal(replay.requestId, 'subreq_001');

  // Different requestId while pending_review MUST be blocked as duplicate
  await assert.rejects(
    () =>
      applyManualSubscriptionRequest(
        db,
        {
          merchantId: 'm1',
          planId: 4,
          senderPhone: '01001112233',
          receiptStoragePath: 'merchants/m1/subscription_receipts/subreq_002.jpg',
          requestId: 'subreq_002',
          nowMs: NOW + 2000,
        },
        okStorageVerifier
      ),
    (err) => err.code === 'already-exists'
  );
});

test('Full lifecycle: submit -> Admin reject with reason -> resubmit -> Admin approve -> captain limit enforced', async () => {
  const db = createFakeFirestore({
    merchants: {
      m2: {
        accountType: 'merchant',
        status: 'approved',
        name: 'برجر إكسبريس',
        email: 'burger@test.com',
        subscriptionStatus: 'unsubscribed',
      },
    },
  });

  // 1. Submit initial request
  await applyManualSubscriptionRequest(
    db,
    {
      merchantId: 'm2',
      planId: 1,
      senderPhone: '01022223333',
      receiptStoragePath: 'merchants/m2/subscription_receipts/subreq_r1.jpg',
      requestId: 'subreq_r1',
      nowMs: NOW,
    },
    okStorageVerifier
  );

  // 2. Admin rejects with explicit reason
  const rejRes = await applyAdminSubscriptionUpdate(db, 'admin_uid_1', {
    merchantId: 'm2',
    action: 'reject',
    requestId: 'subreq_r1',
    reason: 'الإيصال غير واضح والمبلغ غير ظاهر',
  });
  assert.equal(rejRes.status, 'rejected');
  assert.equal(rejRes.rejectionReason, 'الإيصال غير واضح والمبلغ غير ظاهر');

  const reqAfterReject = db.store.get('subscription_requests/subreq_r1');
  const subAfterReject = db.store.get('subscriptions/m2');
  const merchAfterReject = db.store.get('merchants/m2');
  assert.equal(reqAfterReject.status, 'rejected');
  assert.equal(subAfterReject.status, 'rejected');
  assert.equal(subAfterReject.rejectionReason, 'الإيصال غير واضح والمبلغ غير ظاهر');
  assert.equal(subAfterReject.pendingRequestId, undefined);

  // Rejected subscription blocks captain requests
  const evalRejected = evaluateDualDocSubscription(subAfterReject, merchAfterReject, 0, NOW, 1);
  assert.equal(evalRejected.isValid, false);
  assert.equal(evalRejected.status, 'rejected');
  assert.equal(evalRejected.rejectionReason, 'الإيصال غير واضح والمبلغ غير ظاهر');

  // 3. Merchant resubmits a fresh request with clear receipt
  const resub = await applyManualSubscriptionRequest(
    db,
    {
      merchantId: 'm2',
      planId: 1,
      senderPhone: '01022223333',
      receiptStoragePath: 'merchants/m2/subscription_receipts/subreq_r2.jpg',
      requestId: 'subreq_r2',
      nowMs: NOW + 5000,
    },
    okStorageVerifier
  );
  assert.equal(resub.status, 'pending_review');
  assert.equal(resub.requestId, 'subreq_r2');

  // 4. Admin approves the resubmitted request
  const approveRes = await applyAdminSubscriptionUpdate(db, 'admin_uid_1', {
    merchantId: 'm2',
    action: 'activate',
    requestId: 'subreq_r2',
    planId: 1,
    durationDays: 30,
    startDateMs: NOW + 6000,
    endDateMs: NOW + 6000 + 30 * DAY_MS,
  });
  assert.equal(approveRes.status, 'active');
  assert.equal(approveRes.maxCaptains, 2);

  const reqAfterApprove = db.store.get('subscription_requests/subreq_r2');
  const subAfterApprove = db.store.get('subscriptions/m2');
  const merchAfterApprove = db.store.get('merchants/m2');
  assert.equal(reqAfterApprove.status, 'approved');
  assert.equal(subAfterApprove.status, 'active');
  assert.equal(subAfterApprove.rejectionReason, undefined);
  assert.equal(subAfterApprove.pendingRequestId, undefined);
  assert.equal(merchAfterApprove.subscriptionStatus, 'active');

  // 5. Active subscription allows up to 2 captains and blocks the 3rd
  const allowFirst = evaluateDualDocSubscription(subAfterApprove, merchAfterApprove, 1, NOW + 7000, 1);
  assert.equal(allowFirst.isValid, true);
  assert.equal(allowFirst.remainingCaptains, 1);

  const blockThird = evaluateDualDocSubscription(subAfterApprove, merchAfterApprove, 2, NOW + 7000, 1);
  assert.equal(blockThird.isValid, false);
  assert.equal(blockThird.remainingCaptains, 0);

  // 6. Active merchant cannot submit duplicate manual request while subscription is unexpired
  await assert.rejects(
    () =>
      applyManualSubscriptionRequest(
        db,
        {
          merchantId: 'm2',
          planId: 2,
          senderPhone: '01022223333',
          receiptStoragePath: 'merchants/m2/subscription_receipts/subreq_r3.jpg',
          requestId: 'subreq_r3',
          nowMs: NOW + 8000,
        },
        okStorageVerifier
      ),
    (err) => err.code === 'failed-precondition'
  );
});

test('Scenario 10: assertManualRequestMerchantOwnership blocks submitting request for another merchant', () => {
  assert.equal(assertManualRequestMerchantOwnership('merchant_A', undefined), 'merchant_A');
  assert.equal(assertManualRequestMerchantOwnership('merchant_A', 'merchant_A'), 'merchant_A');
  assert.throws(
    () => assertManualRequestMerchantOwnership('merchant_A', 'merchant_B'),
    (err) => err.code === 'permission-denied'
  );
  assert.throws(
    () => assertManualRequestMerchantOwnership('', 'merchant_A'),
    (err) => err.code === 'unauthenticated'
  );
});

test('Scenario 8: resubmission_required workflow blocks captains, exposes reason, allows resubmission, and activates after approval', async () => {
  const db = createFakeFirestore({
    merchants: {
      m3: {
        accountType: 'merchant',
        status: 'approved',
        name: 'مطعم الشام',
        email: 'sham@test.com',
        subscriptionStatus: 'unsubscribed',
      },
    },
  });

  // 1. Submit initial request
  await applyManualSubscriptionRequest(
    db,
    {
      merchantId: 'm3',
      packageId: 2,
      senderPhone: '01033334444',
      notes: 'تحويل أول',
      receiptStoragePath: 'merchants/m3/subscription_receipts/subreq_rs1.jpg',
      requestId: 'subreq_rs1',
      nowMs: NOW,
    },
    okStorageVerifier
  );

  // 2. Admin requests receipt resubmission
  const resubReq = await applyAdminSubscriptionUpdate(db, 'admin_uid_2', {
    merchantId: 'm3',
    action: 'resubmission_required',
    requestId: 'subreq_rs1',
    reason: 'الصورة مقطوعة، يرجى تصوير رقم العملية والتاريخ بالكامل',
  });
  assert.equal(resubReq.status, 'resubmission_required');
  assert.equal(resubReq.resubmissionReason, 'الصورة مقطوعة، يرجى تصوير رقم العملية والتاريخ بالكامل');

  const reqDoc = db.store.get('subscription_requests/subreq_rs1');
  const subDoc = db.store.get('subscriptions/m3');
  const merchDoc = db.store.get('merchants/m3');
  assert.equal(reqDoc.status, 'resubmission_required');
  assert.equal(subDoc.status, 'resubmission_required');
  assert.equal(subDoc.captainLimit, 0);
  assert.equal(subDoc.pendingRequestId, undefined);
  assert.equal(merchDoc.subscriptionStatus, 'resubmission_required');
  assert.equal(merchDoc.resubmissionReason, 'الصورة مقطوعة، يرجى تصوير رقم العملية والتاريخ بالكامل');

  // Captains MUST remain blocked while resubmission_required
  const evalResub = evaluateDualDocSubscription(subDoc, merchDoc, 0, NOW + 1000, 1);
  assert.equal(evalResub.isValid, false);
  assert.equal(evalResub.isActive, false);
  assert.equal(evalResub.status, 'resubmission_required');
  assert.equal(evalResub.resubmissionReason, 'الصورة مقطوعة، يرجى تصوير رقم العملية والتاريخ بالكامل');

  // 3. Merchant resubmits clearer receipt -> transitions cleanly back to pending_review
  const resubmitted = await applyManualSubscriptionRequest(
    db,
    {
      merchantId: 'm3',
      packageId: 2,
      senderPhone: '01033334444',
      notes: 'تم رفع صورة أوضح للإيصال',
      receiptStoragePath: 'merchants/m3/subscription_receipts/subreq_rs2.jpg',
      requestId: 'subreq_rs2',
      nowMs: NOW + 2000,
    },
    okStorageVerifier
  );
  assert.equal(resubmitted.status, 'pending_review');
  assert.equal(db.store.get('subscriptions/m3').resubmissionReason, undefined);

  // 4. Admin approves -> active with 4 captains
  const approved = await applyAdminSubscriptionUpdate(db, 'admin_uid_2', {
    merchantId: 'm3',
    action: 'activate',
    requestId: 'subreq_rs2',
    planId: 2,
    durationDays: 30,
    startDateMs: NOW + 3000,
    endDateMs: NOW + 3000 + 30 * DAY_MS,
  });
  assert.equal(approved.status, 'active');
  assert.equal(approved.maxCaptains, 4);
  const evalActive = evaluateDualDocSubscription(
    db.store.get('subscriptions/m3'),
    db.store.get('merchants/m3'),
    0,
    NOW + 4000,
    1
  );
  assert.equal(evalActive.isValid, true);
  assert.equal(evalActive.captainLimit, 4);
});
