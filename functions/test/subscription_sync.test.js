const test = require('node:test');
const assert = require('node:assert/strict');
const {
  ACTIVE_ORDER_STATUSES_FOR_LIMIT,
  isTerminalOrderStatusForCapacity,
  parseMillis,
  normalizeSubscriptionStatus,
  resolvePlanMetadata,
  computeRemainingDuration,
  evaluateDualDocSubscription,
  generateDistinct6DigitCode,
  calculateHaversineDistance,
} = require('../lib/index.js');

const NOW = 1760000000000;
const DAY_MS = 24 * 60 * 60 * 1000;

test('normalizeSubscriptionStatus maps Arabic and English statuses including inactive and pending', () => {
  assert.equal(normalizeSubscriptionStatus('active'), 'active');
  assert.equal(normalizeSubscriptionStatus('نشطة'), 'active');
  assert.equal(normalizeSubscriptionStatus('inactive'), 'inactive');
  assert.equal(normalizeSubscriptionStatus('غير نشط'), 'inactive');
  assert.equal(normalizeSubscriptionStatus('suspended'), 'suspended');
  assert.equal(normalizeSubscriptionStatus('موقوفة'), 'suspended');
  assert.equal(normalizeSubscriptionStatus('canceled'), 'cancelled');
  assert.equal(normalizeSubscriptionStatus('ملغاة'), 'cancelled');
  assert.equal(normalizeSubscriptionStatus('expired'), 'expired');
  assert.equal(normalizeSubscriptionStatus('منتهية'), 'expired');
  assert.equal(normalizeSubscriptionStatus('pending'), 'pending');
  assert.equal(normalizeSubscriptionStatus('قيد المراجعة'), 'pending');
  assert.equal(normalizeSubscriptionStatus('pending_payment'), 'pending_payment');
  assert.equal(normalizeSubscriptionStatus('failed'), 'payment_failed');
});

test('evaluateDualDocSubscription rejects non-merchant and pending merchant accounts', () => {
  const noMerchant = evaluateDualDocSubscription(
    { status: 'active', isActive: true, captainLimit: 4, expiresAt: NOW + DAY_MS },
    { accountType: 'captain', status: 'approved', subscriptionStatus: 'active' },
    0,
    NOW
  );
  assert.equal(noMerchant.isValid, false);
  assert.equal(noMerchant.status, 'not_found');

  const pendingMerchant = evaluateDualDocSubscription(
    { status: 'active', isActive: true, captainLimit: 4, expiresAt: NOW + DAY_MS },
    { accountType: 'merchant', status: 'pending', subscriptionStatus: 'active' },
    0,
    NOW
  );
  assert.equal(pendingMerchant.isValid, false);
  assert.equal(pendingMerchant.status, 'merchant_pending');
});

test('evaluateDualDocSubscription prioritizes higher subscriptionVersion and newer updatedAt on dual-doc sync', () => {
  const suspendedOverride = evaluateDualDocSubscription(
    {
      status: 'active',
      isActive: true,
      planId: 2,
      captainLimit: 4,
      expiresAt: NOW + 10 * DAY_MS,
      updatedAt: NOW - 5000,
      subscriptionVersion: 1,
    },
    {
      accountType: 'merchant',
      status: 'approved',
      subscriptionStatus: 'suspended',
      isActive: false,
      updatedAt: NOW,
      subscriptionVersion: 2,
      suspensionReason: 'مخالفة السياسات',
    },
    0,
    NOW
  );
  assert.equal(suspendedOverride.isValid, false);
  assert.equal(suspendedOverride.status, 'suspended');
  assert.equal(suspendedOverride.captainLimit, 0);
  assert.match(suspendedOverride.errorMessage, /مخالفة السياسات/);
});

test('evaluateDualDocSubscription blocks inactive, pending, cancelled, payment_failed, and expired dates', () => {
  const baseMerchant = { accountType: 'merchant', status: 'approved', subscriptionStatus: 'active' };

  const inactive = evaluateDualDocSubscription(
    { status: 'inactive', isActive: false, planId: 1, captainLimit: 2, expiresAt: NOW + DAY_MS },
    baseMerchant,
    0,
    NOW
  );
  assert.equal(inactive.isValid, false);
  assert.equal(inactive.status, 'inactive');

  const pending = evaluateDualDocSubscription(
    { status: 'pending', isActive: false, planId: 1, captainLimit: 2, expiresAt: NOW + DAY_MS },
    baseMerchant,
    0,
    NOW
  );
  assert.equal(pending.isValid, false);
  assert.equal(pending.status, 'pending');

  const cancelled = evaluateDualDocSubscription(
    { status: 'cancelled', isActive: false, planId: 2, captainLimit: 4, expiresAt: NOW + DAY_MS },
    baseMerchant,
    0,
    NOW
  );
  assert.equal(cancelled.isValid, false);
  assert.equal(cancelled.status, 'cancelled');

  const unpaid = evaluateDualDocSubscription(
    { status: 'payment_failed', paymentStatus: 'failed', isActive: false },
    baseMerchant,
    0,
    NOW
  );
  assert.equal(unpaid.isValid, false);
  assert.equal(unpaid.status, 'payment_failed');

  const expiredByTime = evaluateDualDocSubscription(
    { status: 'active', isActive: true, planId: 2, captainLimit: 4, expiresAt: NOW - 1000 },
    baseMerchant,
    0,
    NOW
  );
  assert.equal(expiredByTime.isValid, false);
  assert.equal(expiredByTime.status, 'expired');
});

test('evaluateDualDocSubscription enforces captainLimit atomically and unlocks slots on upgrade 2 -> 6', () => {
  const merchant = { accountType: 'merchant', status: 'approved', subscriptionStatus: 'active' };

  const atLimit = evaluateDualDocSubscription(
    { status: 'active', isActive: true, planId: 1, captainLimit: 2, expiresAt: NOW + 15 * DAY_MS, paymentStatus: 'paid' },
    merchant,
    2,
    NOW,
    1
  );
  assert.equal(atLimit.isValid, false);
  assert.equal(atLimit.captainLimit, 2);
  assert.equal(atLimit.remainingCaptains, 0);
  assert.match(atLimit.errorMessage, /الحد الأقصى لعدد الكباتن المتزامنين في باقتك \(2\)/);

  const exceedRemaining = evaluateDualDocSubscription(
    { status: 'active', isActive: true, planId: 1, captainLimit: 2, expiresAt: NOW + 15 * DAY_MS, paymentStatus: 'paid' },
    merchant,
    1,
    NOW,
    2
  );
  assert.equal(exceedRemaining.isValid, false);
  assert.equal(exceedRemaining.remainingCaptains, 1);
  assert.match(exceedRemaining.errorMessage, /المتبقي لك 1 كابتن فقط/);

  const upgradedTo6 = evaluateDualDocSubscription(
    { status: 'active', isActive: true, planId: 3, captainLimit: 6, expiresAt: NOW + 15 * DAY_MS, paymentStatus: 'paid', subscriptionVersion: 3 },
    merchant,
    2,
    NOW,
    1
  );
  assert.equal(upgradedTo6.isValid, true);
  assert.equal(upgradedTo6.captainLimit, 6);
  assert.equal(upgradedTo6.remainingCaptains, 4);
  assert.equal(upgradedTo6.subscriptionVersion, 3);
});

test('resolvePlanMetadata, computeRemainingDuration, parseMillis, and generateDistinct6DigitCode work reliably', () => {
  const meta = resolvePlanMetadata(3, undefined, undefined, undefined);
  assert.equal(meta.planId, 3);
  assert.equal(meta.captainLimit, 6);
  assert.equal(meta.price, 1200);

  const rem = computeRemainingDuration(NOW + 5 * DAY_MS + 3 * 60 * 60 * 1000, NOW);
  assert.equal(rem.remainingDays, 5);
  assert.equal(rem.remainingHours, 5 * 24 + 3);

  const remExpired = computeRemainingDuration(NOW - 500, NOW);
  assert.equal(remExpired.remainingMillis, 0);
  assert.equal(remExpired.remainingDays, 0);

  assert.equal(parseMillis({ _seconds: 1760000000 }), 1760000000000);
  assert.ok(ACTIVE_ORDER_STATUSES_FOR_LIMIT.includes('pending'));
  assert.ok(ACTIVE_ORDER_STATUSES_FOR_LIMIT.includes('searching'));

  for (let i = 0; i < 20; i++) {
    const code = generateDistinct6DigitCode();
    assert.match(code, /^[1-9]\d{5}$/);
    assert.equal(new Set(code.split('')).size, 6);
  }

  assert.ok(calculateHaversineDistance(30.0444, 31.2357, 30.0444, 31.2357) < 0.001);
});

test('normalizeSubscriptionStatus maps Arabic active synonyms (فعال, مفعل, مشترك) and English (subscribed, enabled)', () => {
  assert.equal(normalizeSubscriptionStatus('فعال'), 'active');
  assert.equal(normalizeSubscriptionStatus('فعّال'), 'active');
  assert.equal(normalizeSubscriptionStatus('مفعل'), 'active');
  assert.equal(normalizeSubscriptionStatus('مفعّل'), 'active');
  assert.equal(normalizeSubscriptionStatus('ساري'), 'active');
  assert.equal(normalizeSubscriptionStatus('مشترك'), 'active');
  assert.equal(normalizeSubscriptionStatus('subscribed'), 'active');
  assert.equal(normalizeSubscriptionStatus('enabled'), 'active');
  assert.equal(normalizeSubscriptionStatus('غير فعال'), 'inactive');
  assert.equal(normalizeSubscriptionStatus('غير مفعل'), 'inactive');
});

test('parseMillis normalizes Unix seconds, milliseconds, {seconds}, and ISO-8601 strings', () => {
  assert.equal(parseMillis(1760000000), 1760000000000);
  assert.equal(parseMillis('1760000000'), 1760000000000);
  assert.equal(parseMillis(1760000000000), 1760000000000);
  assert.equal(parseMillis({ seconds: 1760000000 }), 1760000000000);
  assert.equal(parseMillis({ _seconds: 1760000000 }), 1760000000000);
  assert.equal(parseMillis('2026-12-18T17:00:00.000Z'), Date.parse('2026-12-18T17:00:00.000Z'));
});

test('evaluateDualDocSubscription heals stale isSubscribed:false, paymentStatus:unpaid, and maxCaptains:0 when Admin activates subscription', () => {
  const merchantAli = {
    accountType: 'merchant',
    role: 'merchant',
    status: 'approved',
    subscriptionStatus: 'unsubscribed',
    isSubscribed: false,
    maxCaptains: 0,
    paymentStatus: 'unpaid',
    planId: 0,
  };
  const adminActivatedSub = {
    merchantId: 'ali_uid',
    status: 'فعال',
    isActive: true,
    isSubscribed: false, // stale legacy field
    paymentStatus: 'unpaid', // stale field not updated by dashboard
    planId: 2,
    maxCaptains: 0, // stale field while planId=2 implies 4 captains
    expiresAt: Math.floor((NOW + 30 * DAY_MS) / 1000), // Unix seconds
    subscriptionVersion: 2,
  };

  const result = evaluateDualDocSubscription(adminActivatedSub, merchantAli, 0, NOW, 1);
  assert.equal(result.isValid, true);
  assert.equal(result.isActive, true);
  assert.equal(result.status, 'active');
  assert.equal(result.planId, 2);
  assert.equal(result.captainLimit, 4);
  assert.equal(result.remainingCaptains, 4);
});

test('merchant login/FCM updatedAt bump on merchants/{uid} does NOT override active subscriptions/{uid}', () => {
  const activeSub = {
    merchantId: 'ali_uid',
    status: 'active',
    isActive: true,
    isSubscribed: true,
    planId: 2,
    captainLimit: 4,
    expiresAt: NOW + 30 * DAY_MS,
    subscriptionVersion: 2,
    subscriptionUpdatedAt: NOW - 60_000,
    updatedAt: NOW - 60_000,
  };
  const merchantAfterLogin = {
    accountType: 'merchant',
    status: 'approved',
    subscriptionStatus: 'suspended', // stale status before mirror arrived
    isSubscribed: false,
    subscriptionVersion: 2,
    lastLoginAt: NOW, // merchant logged in AFTER admin activated
    updatedAt: NOW, // bumped by login/FCM write
  };

  const result = evaluateDualDocSubscription(activeSub, merchantAfterLogin, 0, NOW, 1);
  assert.equal(result.isValid, true);
  assert.equal(result.status, 'active');
  assert.equal(result.captainLimit, 4);
});

test('full Ali lifecycle: unsubscribed -> admin activate -> request allowed -> suspend -> blocked -> reactivate -> restored', () => {
  const baseAli = { accountType: 'merchant', role: 'merchant', status: 'approved' };

  const step1Unsub = evaluateDualDocSubscription(null, { ...baseAli, subscriptionStatus: 'unsubscribed' }, 0, NOW, 1);
  assert.equal(step1Unsub.isValid, false);

  const step2Active = evaluateDualDocSubscription(
    { status: 'active', isActive: true, isSubscribed: true, planId: 2, planName: 'الباقة المتوسطة', captainLimit: 4, expiresAt: NOW + 30 * DAY_MS, subscriptionVersion: 2 },
    { ...baseAli, subscriptionStatus: 'active', subscriptionVersion: 2 },
    0,
    NOW,
    1
  );
  assert.equal(step2Active.isValid, true);
  assert.equal(step2Active.planName, 'الباقة المتوسطة');
  assert.equal(step2Active.captainLimit, 4);

  const step3Suspended = evaluateDualDocSubscription(
    { status: 'suspended', isActive: false, isSubscribed: false, planId: 2, captainLimit: 0, suspensionReason: 'إيقاف إداري', subscriptionVersion: 3 },
    { ...baseAli, subscriptionStatus: 'suspended', subscriptionVersion: 3 },
    0,
    NOW + 1000,
    1
  );
  assert.equal(step3Suspended.isValid, false);
  assert.equal(step3Suspended.status, 'suspended');

  const step4Reactivated = evaluateDualDocSubscription(
    { status: 'active', isActive: true, isSubscribed: true, planId: 2, planName: 'الباقة المتوسطة', captainLimit: 4, expiresAt: NOW + 30 * DAY_MS, subscriptionVersion: 4 },
    { ...baseAli, subscriptionStatus: 'active', subscriptionVersion: 4 },
    0,
    NOW + 2000,
    1
  );
  assert.equal(step4Reactivated.isValid, true);
  assert.equal(step4Reactivated.status, 'active');
  assert.equal(step4Reactivated.captainLimit, 4);
});

test('isTerminalOrderStatusForCapacity releases ONLY on terminal states, never on active or re-dispatch states', () => {
  // Terminal states -> release capacity
  assert.equal(isTerminalOrderStatusForCapacity('delivered'), true);
  assert.equal(isTerminalOrderStatusForCapacity('completed'), true);
  assert.equal(isTerminalOrderStatusForCapacity('cancelled'), true);
  assert.equal(isTerminalOrderStatusForCapacity('canceled'), true);
  assert.equal(isTerminalOrderStatusForCapacity('تم التسليم'), true);
  assert.equal(isTerminalOrderStatusForCapacity('تم التوصيل'), true);
  assert.equal(isTerminalOrderStatusForCapacity('مكتمل'), true);
  assert.equal(isTerminalOrderStatusForCapacity('ملغي'), true);

  // Active / captain rejection / re-dispatch states -> MUST NOT release capacity
  assert.equal(isTerminalOrderStatusForCapacity('pending'), false);
  assert.equal(isTerminalOrderStatusForCapacity('searching'), false);
  assert.equal(isTerminalOrderStatusForCapacity('offered'), false);
  assert.equal(isTerminalOrderStatusForCapacity('reassigned'), false);
  assert.equal(isTerminalOrderStatusForCapacity('accepted'), false);
  assert.equal(isTerminalOrderStatusForCapacity('in_progress'), false);
  assert.equal(isTerminalOrderStatusForCapacity('out_for_delivery'), false);
  assert.equal(isTerminalOrderStatusForCapacity('جاري البحث'), false);
  assert.equal(isTerminalOrderStatusForCapacity(''), false);
});


