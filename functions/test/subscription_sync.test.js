const test = require('node:test');
const assert = require('node:assert/strict');

function normalizeSubscriptionStatus(raw) {
  const s = String(raw || '').toLowerCase().trim();
  if (['active', 'نشط', 'نشطة', 'سارية'].includes(s)) return 'active';
  if (['suspended', 'suspend', 'paused', 'موقوف', 'موقوفة', 'متوقف', 'متوقفة', 'inactive'].includes(s)) return 'suspended';
  if (['cancelled', 'canceled', 'cancel', 'ملغي', 'ملغى', 'ملغاة'].includes(s)) return 'cancelled';
  if (['expired', 'منتهي', 'منتهية'].includes(s)) return 'expired';
  if (['payment_failed', 'failed', 'declined'].includes(s)) return 'payment_failed';
  if (['pending_payment', 'pending', 'في انتظار الدفع'].includes(s)) return 'pending_payment';
  if (['unsubscribed', 'none', ''].includes(s)) return 'unsubscribed';
  return s;
}

function resolvePlanMetadata(rawPlanId, rawPlanName, rawMaxCaptains, rawPrice) {
  const planId = Number(rawPlanId || 0) || (
    String(rawPlanName || '').includes('المتقدمة') || String(rawPlanName || '').includes('8') ? 4 :
    String(rawPlanName || '').includes('المميزة') || String(rawPlanName || '').includes('6') ? 3 :
    String(rawPlanName || '').includes('المتوسطة') || String(rawPlanName || '').includes('4') ? 2 : 1
  );
  const defaults = {
    1: { planName: 'الباقة الأساسية', maxCaptains: 2, price: 400 },
    2: { planName: 'الباقة المتوسطة', maxCaptains: 4, price: 800 },
    3: { planName: 'الباقة المميزة', maxCaptains: 6, price: 1200 },
    4: { planName: 'الباقة المتقدمة', maxCaptains: 8, price: 1600 }
  };
  const def = defaults[planId] || defaults[1];
  return {
    planId,
    planName: String(rawPlanName || '').trim() || def.planName,
    maxCaptains: Number(rawMaxCaptains) > 0 ? Number(rawMaxCaptains) : def.maxCaptains,
    price: Number(rawPrice) > 0 ? Number(rawPrice) : def.price
  };
}

function evaluateDualDocSubscription(subDoc, merchantDoc, nowMs) {
  if (!merchantDoc || merchantDoc.accountType !== 'merchant') {
    return { isValid: false, status: 'not_found' };
  }
  const subStatus = subDoc ? normalizeSubscriptionStatus(subDoc.status || subDoc.subscriptionStatus) : 'unsubscribed';
  const merchStatus = normalizeSubscriptionStatus(merchantDoc.subscriptionStatus);

  const subUpdatedMs = Number(subDoc?.updatedAt || 0);
  const merchUpdatedMs = Number(merchantDoc.updatedAt || 0);

  let effectiveDoc = subDoc ? { ...subDoc } : { ...merchantDoc };
  let effectiveStatus = subDoc ? subStatus : merchStatus;

  if (subDoc && merchStatus !== 'unsubscribed' && merchStatus !== subStatus) {
    if (merchUpdatedMs > subUpdatedMs || (subUpdatedMs === 0 && ['suspended', 'cancelled', 'expired'].includes(merchStatus))) {
      effectiveDoc = { ...subDoc, ...merchantDoc };
      effectiveStatus = merchStatus;
    }
  }

  if (effectiveStatus === 'suspended' || effectiveStatus === 'cancelled') {
    return { isValid: false, status: effectiveStatus, maxCaptains: 0 };
  }
  const endMs = Number(effectiveDoc.endDate || effectiveDoc.subscriptionEndDate || 0);
  if (effectiveStatus === 'expired' || (endMs > 0 && nowMs >= endMs)) {
    return { isValid: false, status: 'expired' };
  }
  const meta = resolvePlanMetadata(effectiveDoc.planId, effectiveDoc.planName, effectiveDoc.maxCaptains, effectiveDoc.price);
  if (effectiveStatus !== 'active' || effectiveDoc.isSubscribed === false || meta.maxCaptains <= 0) {
    return { isValid: false, status: effectiveStatus || 'unsubscribed' };
  }
  return { isValid: true, status: 'active', ...meta };
}

test('normalizeSubscriptionStatus maps Arabic and English states consistently', () => {
  assert.equal(normalizeSubscriptionStatus('active'), 'active');
  assert.equal(normalizeSubscriptionStatus('نشطة'), 'active');
  assert.equal(normalizeSubscriptionStatus('موقوفة'), 'suspended');
  assert.equal(normalizeSubscriptionStatus('canceled'), 'cancelled');
  assert.equal(normalizeSubscriptionStatus('منتهية'), 'expired');
});

test('evaluateDualDocSubscription lets newer/restrictive suspended merchant doc override stale active sub doc', () => {
  const now = 1760000000000;
  const result = evaluateDualDocSubscription(
    { status: 'active', isSubscribed: true, planId: 2, maxCaptains: 4, endDate: now + 86400000, updatedAt: now - 5000 },
    { accountType: 'merchant', subscriptionStatus: 'suspended', isSubscribed: false, updatedAt: now },
    now
  );
  assert.equal(result.isValid, false);
  assert.equal(result.status, 'suspended');
});

test('evaluateDualDocSubscription rejects captain account even with active status', () => {
  const now = 1760000000000;
  const result = evaluateDualDocSubscription(
    { status: 'active', isSubscribed: true, planId: 2, maxCaptains: 4, endDate: now + 86400000 },
    { accountType: 'captain', subscriptionStatus: 'active' },
    now
  );
  assert.equal(result.isValid, false);
  assert.equal(result.status, 'not_found');
});

test('resolvePlanMetadata fills canonical defaults cleanly', () => {
  const meta = resolvePlanMetadata(3, undefined, undefined, undefined);
  assert.equal(meta.planId, 3);
  assert.equal(meta.maxCaptains, 6);
  assert.equal(meta.price, 1200);
});
