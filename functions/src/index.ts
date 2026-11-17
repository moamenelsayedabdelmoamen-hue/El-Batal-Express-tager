import * as functions from "firebase-functions";
import * as admin from "firebase-admin";

admin.initializeApp();
const db = admin.firestore();

/**
 * Calculates the great-circle distance between two points on the Earth's surface
 * using the Haversine formula.
 *
 * @param lat1 Latitude of point 1 in degrees
 * @param lon1 Longitude of point 1 in degrees
 * @param lat2 Latitude of point 2 in degrees
 * @param lon2 Longitude of point 2 in degrees
 * @returns Distance in kilometers
 */
export function calculateHaversineDistance(
  lat1: number,
  lon1: number,
  lat2: number,
  lon2: number
): number {
  const R = 6371; // Earth radius in kilometers
  const toRad = (degree: number) => (degree * Math.PI) / 180;

  const dLat = toRad(lat2 - lat1);
  const dLon = toRad(lon2 - lon1);

  const a =
    Math.sin(dLat / 2) * Math.sin(dLat / 2) +
    Math.cos(toRad(lat1)) *
      Math.cos(toRad(lat2)) *
      Math.sin(dLon / 2) *
      Math.sin(dLon / 2);

  const c = 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
  return R * c; // Distance in KM
}

interface CandidateCaptain {
  id: string;
  name: string;
  phone: string;
  profileImageUrl: string;
  fcmToken?: string;
  distanceKm: number;
}

// Shared eligibility gate used before ranking and again inside the Firestore assignment transaction.
function isCaptainReadyForDispatch(data: FirebaseFirestore.DocumentData | undefined): boolean {
  if (!data || data.isOnline !== true) return false;
  const status = String(data.status || "").toLowerCase();
  const presence = String(data.presenceStatus || "available").toLowerCase();
  const availability = String(data.availabilityStatus || "available").toLowerCase();
  const unavailableStates = ["offline", "busy", "unavailable", "paused"];
  return ["online", "active", "approved"].includes(status)
    && data.canReceiveOrders !== false
    && data.isReceivingOrders !== false
    && data.receiveOrders !== false
    && data.isBlocked !== true
    && data.isSuspended !== true
    && !unavailableStates.includes(presence)
    && !unavailableStates.includes(availability)
    && !(data.currentOrderId && String(data.currentOrderId).trim());
}

function isCaptainCurrentlyInShift(schedule: FirebaseFirestore.DocumentData | undefined, now = new Date()): boolean {
  if (!schedule || schedule.isPaused === true || !schedule.weeklySchedule) return false;
  const parts = new Intl.DateTimeFormat("en-US", {
    timeZone: "Africa/Cairo", weekday: "long", hour: "2-digit", minute: "2-digit", hourCycle: "h23"
  }).formatToParts(now);
  const part = (type: string) => parts.find((p) => p.type === type)?.value || "";
  const today = part("weekday").toLowerCase();
  const currentMinutes = Number(part("hour")) * 60 + Number(part("minute"));
  const days = ["saturday", "sunday", "monday", "tuesday", "wednesday", "thursday", "friday"];
  const todayIndex = days.indexOf(today);
  if (todayIndex < 0) return false;
  const parseTime = (value: unknown): number => {
    if (typeof value !== "string" || !/^\d{2}:\d{2}$/.test(value)) return -1;
    const [h, m] = value.split(":").map(Number);
    return h >= 0 && h <= 23 && m >= 0 && m <= 59 ? h * 60 + m : -1;
  };
  const weekly = schedule.weeklySchedule as Record<string, any>;
  const todaySchedule = weekly[today];
  if (todaySchedule && todaySchedule.isDayOff !== true) {
    for (const shift of (todaySchedule.shifts || [])) {
      const start = parseTime(shift.startTime);
      const end = parseTime(shift.endTime);
      if (start < 0 || end < 0) continue;
      if (shift.isOvernight === true || end <= start) {
        if (currentMinutes >= start) return true;
      } else if (currentMinutes >= start && currentMinutes < end) return true;
    }
  }
  const previousDay = days[(todayIndex + days.length - 1) % days.length];
  const previousSchedule = weekly[previousDay];
  if (previousSchedule && previousSchedule.isDayOff !== true) {
    for (const shift of (previousSchedule.shifts || [])) {
      const start = parseTime(shift.startTime);
      const end = parseTime(shift.endTime);
      if (start >= 0 && end >= 0 && (shift.isOvernight === true || end <= start) && currentMinutes < end) return true;
    }
  }
  return false;
}

/**
 * Main auto-dispatch engine function.
 * Given an order ID and data, verifies merchant subscription, searches for eligible captains,
 * sorts by proximity, and attempts an atomic transaction assignment.
 */
export async function runAutoDispatch(orderId: string, orderData: FirebaseFirestore.DocumentData): Promise<boolean> {
  console.log(`[AutoDispatch] Starting dispatch evaluation for order: ${orderId}`);

  // 1. Ensure order is eligible for dispatch
  const currentStatus = (orderData.status || "").toLowerCase();
  const dispatchStatus = (orderData.dispatchStatus || "").toLowerCase();
  const existingCaptainId = orderData.captainId;

  if (existingCaptainId || (currentStatus !== "pending" && dispatchStatus !== "searching")) {
    console.log(`[AutoDispatch] Order ${orderId} is not in pending/searching state. Skipping.`);
    return false;
  }

  // 2. Verify the canonical merchant account and subscription.
  const merchantId = orderData.merchantId || orderData.userId;
  if (!merchantId) return false;
  const merchantDoc = await db.collection("merchants").doc(merchantId).get();
  if (!merchantDoc.exists || merchantDoc.data()?.accountType !== "merchant") {
    await db.collection("orders").doc(orderId).update({
      dispatchStatus: "failed",
      dispatchError: "التاجر غير موجود في merchants",
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });
    return false;
  }
  const restData = merchantDoc.data() || {};
  if (restData.status !== "approved" && restData.status !== "active") {
    await db.collection("orders").doc(orderId).update({
      dispatchStatus: "failed",
      dispatchError: "حساب التاجر في انتظار موافقة الإدارة",
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });
    return false;
  }
  const subCheck = await verifyMerchantSubscription(merchantId);
  if (!subCheck.isValid) {
    await db.collection("orders").doc(orderId).update({
      status: "cancelled",
      dispatchStatus: "failed",
      cancelReason: subCheck.errorMessage || "اشتراك التاجر غير فعال",
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });
    return false;
  }

  // 3. Determine Pickup / Restaurant Location
  let pickupLat: number | null = null;
  let pickupLng: number | null = null;

  if (orderData.pickupLocation && typeof orderData.pickupLocation.latitude === "number") {
    pickupLat = orderData.pickupLocation.latitude;
    pickupLng = orderData.pickupLocation.longitude;
  } else if (typeof orderData.merchantLatitude === "number" && typeof orderData.merchantLongitude === "number") {
    pickupLat = orderData.merchantLatitude;
    pickupLng = orderData.merchantLongitude;
  } else if (restData.location && typeof restData.location.latitude === "number") {
    pickupLat = restData.location.latitude;
    pickupLng = restData.location.longitude;
  } else if (typeof restData.latitude === "number" && typeof restData.longitude === "number") {
    pickupLat = restData.latitude;
    pickupLng = restData.longitude;
  }

  if (pickupLat === null || pickupLng === null) {
    console.warn(`[AutoDispatch] No valid location found for merchant ${merchantId}. Order ${orderId} remains in searching.`);
    await db.collection("orders").doc(orderId).update({
      dispatchStatus: "searching",
      dispatchNote: "في انتظار تحديد إحداثيات التاجر",
      lastSearchAt: admin.firestore.FieldValue.serverTimestamp()
    });
    return false;
  }

  // 4. Query eligible captains from 'captains' collection
  // isOnline is the stable shared field across the captain app and admin dashboard.
  // A shared readiness predicate handles status, presence, reception toggles, and current order.
  // Older/current clients may use presenceStatus/canReceiveOrders instead of availabilityStatus.
  const captainsSnapshot = await db.collection("captains")
    .where("isOnline", "==", true)
    .get();

  if (captainsSnapshot.empty) {
    console.log(`[AutoDispatch] No online, available, approved captains currently available.`);
    await db.collection("orders").doc(orderId).update({
      dispatchStatus: "searching",
      lastSearchAt: admin.firestore.FieldValue.serverTimestamp(),
      searchAttempts: admin.firestore.FieldValue.increment(1)
    });
    return false;
  }

  const nowMs = Date.now();
  const maxLocationStaleMs = 3 * 60 * 1000; // 3 minutes maximum for fresh location
  const candidates: CandidateCaptain[] = [];
  const scheduleSnapshots = await Promise.all(captainsSnapshot.docs.map((captainDoc) =>
    db.collection("captain_schedules").doc(captainDoc.id).get()
  ));

  for (let captainIndex = 0; captainIndex < captainsSnapshot.docs.length; captainIndex++) {
    const doc = captainsSnapshot.docs[captainIndex];
    if (!isCaptainCurrentlyInShift(scheduleSnapshots[captainIndex].data())) continue;
    const data = doc.data();
    if (!isCaptainReadyForDispatch(data)) continue;

    // Validate location existence
    const loc = data.location;
    if (!loc || typeof loc.latitude !== "number" || typeof loc.longitude !== "number") {
      continue;
    }

    // Validate freshness of location
    const lastLocUpdate = data.lastLocationUpdate;
    if (lastLocUpdate) {
      const updateDate = lastLocUpdate.toDate ? lastLocUpdate.toDate() : new Date(lastLocUpdate);
      const diffMs = nowMs - updateDate.getTime();
      if (!Number.isFinite(updateDate.getTime()) || diffMs < -30000 || diffMs > maxLocationStaleMs) {
        console.log(`[AutoDispatch] Captain ${doc.id} skipped due to stale location (${Math.round(diffMs / 1000)}s old).`);
        continue;
      }
    } else {
      // If never updated location timestamp, skip to maintain accuracy
      console.log(`[AutoDispatch] Captain ${doc.id} skipped: lastLocationUpdate missing.`);
      continue;
    }

    // Calculate distance with Haversine formula
    const distanceKm = calculateHaversineDistance(
      pickupLat,
      pickupLng,
      loc.latitude,
      loc.longitude
    );

    candidates.push({
      id: doc.id,
      name: data.name || data.captainName || "الكابتن",
      phone: data.phone || data.phoneNumber || "",
      profileImageUrl: data.profileImageUrl || data.photoUrl || "",
      fcmToken: data.fcmToken,
      distanceKm: distanceKm
    });
  }

  if (candidates.length === 0) {
    console.log(`[AutoDispatch] No captains passed the fresh-location and active status filters.`);
    await db.collection("orders").doc(orderId).update({
      dispatchStatus: "searching",
      lastSearchAt: admin.firestore.FieldValue.serverTimestamp(),
      searchAttempts: admin.firestore.FieldValue.increment(1)
    });
    return false;
  }

  // Sort candidates by proximity (closest captain first)
  candidates.sort((a, b) => a.distanceKm - b.distanceKm);
  console.log(`[AutoDispatch] Found ${candidates.length} candidate captains for order ${orderId}. Closest is ${candidates[0].id} (${candidates[0].distanceKm.toFixed(2)} km).`);

  // 5. Atomic Reservation using Firestore Transactions
  // Try candidates in order. If race condition occurs (candidate taken by another order),
  // the transaction fails and we try the next closest captain.
  let assignedCaptain: CandidateCaptain | null = null;

  for (const candidate of candidates) {
    try {
      await db.runTransaction(async (transaction) => {
        const orderRef = db.collection("orders").doc(orderId);
        const captainRef = db.collection("captains").doc(candidate.id);
        const scheduleRef = db.collection("captain_schedules").doc(candidate.id);

        const [currentOrderDoc, currentCaptainDoc, scheduleDoc] = await Promise.all([
          transaction.get(orderRef),
          transaction.get(captainRef),
          transaction.get(scheduleRef)
        ]);

        if (!currentOrderDoc.exists) {
          throw new Error("ORDER_NOT_FOUND");
        }

        const freshOrder = currentOrderDoc.data() || {};
        // Re-verify order is still unassigned
        if (freshOrder.captainId || (freshOrder.status !== "pending" && freshOrder.dispatchStatus !== "searching")) {
          throw new Error("ORDER_ALREADY_ASSIGNED");
        }

        if (!currentCaptainDoc.exists) {
          throw new Error("CAPTAIN_NOT_FOUND");
        }

        const freshCaptain = currentCaptainDoc.data() || {};
        // Re-verify readiness and shift membership inside the assignment transaction.
        if (
          !isCaptainReadyForDispatch(freshCaptain) ||
          !isCaptainCurrentlyInShift(scheduleDoc.data())
        ) {
          throw new Error("CAPTAIN_BUSY_OR_OFFLINE");
        }

        const estimatedMins = Math.max(5, Math.round(candidate.distanceKm * 2.5 + 5));

        // Atomically update Captain: mark as busy
        transaction.update(captainRef, {
          availabilityStatus: "busy",
          currentOrderId: orderId,
          activeMerchantId: merchantId,
          currentMerchantId: merchantId,
          updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });

        // Atomically update Order: assign captain
        transaction.update(orderRef, {
          captainId: candidate.id,
          captainName: candidate.name,
          captainPhone: candidate.phone,
          captainPhotoUrl: candidate.profileImageUrl,
          driver_name: candidate.name,
          driverPhone: candidate.phone,
          status: "accepted",
          dispatchStatus: "assigned",
          assignedAt: admin.firestore.FieldValue.serverTimestamp(),
          distanceToMerchantKm: Math.round(candidate.distanceKm * 100) / 100,
          estimatedArrival: `${estimatedMins} دقيقة`,
          updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });
      });

      // If transaction committed successfully, we have won the reservation!
      assignedCaptain = candidate;
      console.log(`[AutoDispatch] Order ${orderId} successfully assigned to captain ${candidate.id} (${candidate.name})!`);
      break;
    } catch (err: any) {
      console.warn(`[AutoDispatch] Transaction attempt for captain ${candidate.id} failed: ${err.message}. Retrying next candidate...`);
      if (err.message === "ORDER_ALREADY_ASSIGNED" || err.message === "ORDER_NOT_FOUND") {
        return false;
      }
      // Otherwise candidate was busy or taken -> continue to next candidate
    }
  }

  // 6. Post-Assignment: Push Notification via FCM
  if (assignedCaptain) {
    if (assignedCaptain.fcmToken) {
      try {
        const messagePayload: admin.messaging.Message = {
          token: assignedCaptain.fcmToken,
          notification: {
            title: "طلب توصيل جديد 🛵",
            body: `طلب جديد من مطعم ${orderData.merchantName || "البطل"} على بعد ${assignedCaptain.distanceKm.toFixed(1)} كم!`
          },
          data: {
            orderId: orderId,
            merchantName: String(orderData.merchantName || ""),
            distance: `${assignedCaptain.distanceKm.toFixed(1)} km`,
            fee: String(orderData.serviceFee || orderData.price || "30 جنيه"),
            type: "NEW_ORDER_ASSIGNED"
          },
          android: {
            priority: "high",
            notification: {
              channelId: "order_status_channel",
              sound: "default"
            }
          }
        };

        await admin.messaging().send(messagePayload);
        console.log(`[AutoDispatch] FCM notification successfully sent to captain ${assignedCaptain.id}`);
      } catch (fcmError: any) {
        console.error(`[AutoDispatch] Failed to send FCM notification: ${fcmError.message}`);
      }
    }
    return true;
  } else {
    console.log(`[AutoDispatch] Could not reserve any candidate for order ${orderId}. Remains in searching.`);
    await db.collection("orders").doc(orderId).update({
      dispatchStatus: "searching",
      lastSearchAt: admin.firestore.FieldValue.serverTimestamp(),
      searchAttempts: admin.firestore.FieldValue.increment(1)
    });
    return false;
  }
}

/**
 * Trigger: On New Order Created in Firestore.
 * Automatically runs the auto-dispatch matching algorithm.
 */
export const onOrderCreated = functions.firestore
  .document("orders/{orderId}")
  .onCreate(async (snap, context) => {
    const orderId = context.params.orderId;
    const orderData = snap.data();
    if (!orderData) return;

    await runAutoDispatch(orderId, orderData);
  });

/**
 * Trigger: When a Captain updates status or location.
 * If the captain turns Online & Available, checks if there are pending orders
 * waiting in "searching" state and dispatches the oldest pending order!
 */
export const onCaptainUpdated = functions.firestore
  .document("captains/{captainId}")
  .onUpdate(async (change, context) => {
    const beforeData = change.before.data();
    const afterData = change.after.data();

    const becameAvailable =
      isCaptainReadyForDispatch(afterData) &&
      !isCaptainReadyForDispatch(beforeData);

    if (becameAvailable) {
      console.log(`[AutoDispatch] Captain ${context.params.captainId} is now online & available. Looking for queued orders...`);
      const pendingOrdersSnapshot = await db.collection("orders")
        .where("status", "==", "pending")
        .where("dispatchStatus", "==", "searching")
        .orderBy("createdAt", "asc")
        .limit(1)
        .get();

      if (!pendingOrdersSnapshot.empty) {
        const orderDoc = pendingOrdersSnapshot.docs[0];
        console.log(`[AutoDispatch] Queued order ${orderDoc.id} found. Triggering auto-dispatch.`);
        await runAutoDispatch(orderDoc.id, orderDoc.data());
      }
    }
  });

/**
 * Trigger: When an Order Status Changes.
 * If order is delivered, completed, or cancelled, automatically frees the captain
 * so they are immediately available for subsequent orders!
 */
export const onOrderStatusChanged = functions.firestore
  .document("orders/{orderId}")
  .onUpdate(async (change, context) => {
    const afterData = change.after.data();
    const beforeData = change.before.data();

    const newStatus = (afterData.status || "").toLowerCase();
    const oldStatus = (beforeData.status || "").toLowerCase();
    const captainId = afterData.captainId;

    if (!captainId) return;

    const isCompletedOrCancelled =
      ["delivered", "completed", "cancelled", "ملغي", "تم التسليم"].includes(newStatus);
    const wasAlreadyFinished =
      ["delivered", "completed", "cancelled", "ملغي", "تم التسليم"].includes(oldStatus);

    if (isCompletedOrCancelled && !wasAlreadyFinished) {
      console.log(`[AutoDispatch] Order ${context.params.orderId} concluded (${newStatus}). Freeing captain ${captainId}...`);
      try {
        await db.collection("captains").doc(captainId).update({
          availabilityStatus: "available",
          currentOrderId: null,
          activeMerchantId: admin.firestore.FieldValue.delete(),
          currentMerchantId: admin.firestore.FieldValue.delete(),
          updatedAt: admin.firestore.FieldValue.serverTimestamp()
        });
        console.log(`[AutoDispatch] Captain ${captainId} freed and set to available.`);
      } catch (err: any) {
        console.error(`[AutoDispatch] Error freeing captain ${captainId}: ${err.message}`);
      }
    }
  });

/**
 * Generates a cryptographically secure 6-digit order code matching all required constraints:
 * 1. Exactly 6 digits.
 * 2. Does NOT start with 0 (first digit is 1-9).
 * 3. All 6 digits are distinct/unique (no repetition of digits inside the code).
 */
import * as crypto from "crypto";

export function generateDistinct6DigitCode(): string {
  // First digit: choose from [1..9]
  const nonZeroDigits = [1, 2, 3, 4, 5, 6, 7, 8, 9];
  const firstIndex = crypto.randomInt(0, nonZeroDigits.length);
  const firstDigit = nonZeroDigits[firstIndex];

  // Remaining candidate pool: [0..9] excluding firstDigit
  const pool: number[] = [];
  for (let d = 0; d <= 9; d++) {
    if (d !== firstDigit) pool.push(d);
  }

  // Pick remaining 5 digits without replacement using Fisher-Yates shuffle sample
  const pickedDigits = [firstDigit];
  for (let i = 0; i < 5; i++) {
    const idx = crypto.randomInt(0, pool.length);
    pickedDigits.push(pool[idx]);
    pool.splice(idx, 1);
  }

  return pickedDigits.join("");
}

export interface VerifySubscriptionResult {
  isValid: boolean;
  status: string; // active, expired, suspended, cancelled, payment_failed, unsubscribed, merchant_pending, not_found
  errorMessage?: string;
  planId?: number;
  planName?: string;
  maxCaptains?: number;
  price?: number;
  startDate?: any;
  endDate?: any;
  activeCaptainsCount?: number;
  reason?: string;
}

const ADMIN_EMAIL_ALLOWLIST = [
  "moamen.elsayed.abdelmoamen@gmail.com",
  "admin@elbatalexpress.com"
];

function parseMillis(rawDate: any): number | null {
  if (!rawDate) return null;
  if (typeof rawDate.toMillis === "function") return rawDate.toMillis();
  if (rawDate instanceof Date) return rawDate.getTime();
  if (typeof rawDate === "number" && Number.isFinite(rawDate)) return rawDate;
  if (typeof rawDate === "string") {
    const asNum = Number(rawDate);
    if (!isNaN(asNum) && asNum > 1000000000) return asNum;
    const parsed = Date.parse(rawDate);
    if (!isNaN(parsed)) return parsed;
  }
  return null;
}

function normalizeSubscriptionStatus(raw: unknown): string {
  const s = String(raw || "").toLowerCase().trim();
  if (["active", "نشط", "نشطة", "سارية"].includes(s)) return "active";
  if (["suspended", "suspend", "paused", "موقوف", "موقوفة", "متوقف", "متوقفة", "inactive"].includes(s)) return "suspended";
  if (["cancelled", "canceled", "cancel", "ملغي", "ملغى", "ملغاة"].includes(s)) return "cancelled";
  if (["expired", "منتهي", "منتهية"].includes(s)) return "expired";
  if (["payment_failed", "failed", "declined"].includes(s)) return "payment_failed";
  if (["pending_payment", "pending", "في انتظار الدفع"].includes(s)) return "pending_payment";
  if (["unsubscribed", "none", ""].includes(s)) return "unsubscribed";
  return s;
}

function resolvePlanMetadata(rawPlanId: any, rawPlanName: any, rawMaxCaptains: any, rawPrice: any) {
  const planId = Number(rawPlanId || 0) || (
    String(rawPlanName || "").includes("المتقدمة") || String(rawPlanName || "").includes("8") ? 4 :
    String(rawPlanName || "").includes("المميزة") || String(rawPlanName || "").includes("6") ? 3 :
    String(rawPlanName || "").includes("المتوسطة") || String(rawPlanName || "").includes("4") ? 2 : 1
  );
  const defaults: Record<number, { planName: string; maxCaptains: number; price: number }> = {
    1: { planName: "الباقة الأساسية", maxCaptains: 2, price: 400 },
    2: { planName: "الباقة المتوسطة", maxCaptains: 4, price: 800 },
    3: { planName: "الباقة المميزة", maxCaptains: 6, price: 1200 },
    4: { planName: "الباقة المتقدمة", maxCaptains: 8, price: 1600 }
  };
  const def = defaults[planId] || defaults[1];
  const planName = String(rawPlanName || "").trim() || def.planName;
  const maxCaptains = Number(rawMaxCaptains) > 0 ? Number(rawMaxCaptains) : def.maxCaptains;
  const price = Number(rawPrice) > 0 ? Number(rawPrice) : def.price;
  return { planId, planName, maxCaptains, price };
}

async function isAdminCaller(context: functions.https.CallableContext): Promise<boolean> {
  if (!context.auth || !context.auth.uid) return false;
  const token = context.auth.token || {};
  if (token.role === "admin" || token.admin === true) return true;
  if (
    typeof token.email === "string" &&
    token.email_verified === true &&
    ADMIN_EMAIL_ALLOWLIST.includes(token.email.toLowerCase())
  ) {
    return true;
  }
  const adminDoc = await db.collection("admins").doc(context.auth.uid).get();
  return adminDoc.exists;
}

/**
 * Server-side subscription validation against BOTH subscriptions/{merchantId} and merchants/{merchantId}.
 * Guarantees suspended / cancelled / expired / failed states are never bypassed by stale mirror docs.
 */
export async function verifyMerchantSubscription(merchantId: string): Promise<VerifySubscriptionResult> {
  const now = Date.now();
  const [subById, merchantDoc] = await Promise.all([
    db.collection("subscriptions").doc(merchantId).get(),
    db.collection("merchants").doc(merchantId).get()
  ]);
  if (!merchantDoc.exists || merchantDoc.data()?.accountType !== "merchant") {
    return { isValid: false, status: "not_found", errorMessage: "حساب التاجر غير موجود في merchants." };
  }
  const merchantData = merchantDoc.data() || {};
  if (merchantData.status !== "approved" && merchantData.status !== "active") {
    return { isValid: false, status: "merchant_pending", errorMessage: "حساب التاجر في انتظار موافقة الإدارة." };
  }

  const subData = subById.exists ? (subById.data() || {}) : {};
  const subStatus = subById.exists
    ? normalizeSubscriptionStatus(subData.status || subData.subscriptionStatus || subData.planStatus)
    : "unsubscribed";
  const merchSubStatus = normalizeSubscriptionStatus(merchantData.subscriptionStatus);

  const subUpdatedMs = parseMillis(subData.updatedAt) || 0;
  const merchUpdatedMs = parseMillis(merchantData.updatedAt) || 0;

  let effectiveDoc: FirebaseFirestore.DocumentData = subById.exists ? subData : merchantData;
  let effectiveStatus = subById.exists ? subStatus : merchSubStatus;

  if (subById.exists && merchSubStatus !== "unsubscribed" && merchSubStatus !== subStatus) {
    if (merchUpdatedMs > subUpdatedMs) {
      effectiveDoc = { ...subData, ...merchantData };
      effectiveStatus = merchSubStatus;
    } else if (subUpdatedMs === 0 && ["suspended", "cancelled", "expired"].includes(merchSubStatus)) {
      effectiveDoc = { ...subData, ...merchantData };
      effectiveStatus = merchSubStatus;
    }
  }

  const meta = resolvePlanMetadata(
    effectiveDoc.planId ?? merchantData.planId,
    effectiveDoc.planName ?? merchantData.planName,
    effectiveDoc.maxCaptains ?? merchantData.maxCaptains,
    effectiveDoc.price ?? merchantData.price
  );
  const startDateMs = parseMillis(effectiveDoc.startDate || effectiveDoc.subscriptionStartDate || subData.startDate || merchantData.subscriptionStartDate);
  const endDateMs = parseMillis(effectiveDoc.endDate || effectiveDoc.subscriptionEndDate || effectiveDoc.expiryDate || subData.endDate || merchantData.subscriptionEndDate);
  const reason = String(effectiveDoc.suspensionReason || effectiveDoc.cancellationReason || merchantData.suspensionReason || "").trim();
  const paymentStatus = String(effectiveDoc.paymentStatus || merchantData.paymentStatus || "paid").toLowerCase().trim();

  if (effectiveStatus === "suspended") {
    return {
      isValid: false,
      status: "suspended",
      planId: meta.planId,
      planName: meta.planName,
      maxCaptains: 0,
      price: meta.price,
      startDate: startDateMs,
      endDate: endDateMs,
      reason,
      errorMessage: reason
        ? `تم إيقاف اشتراكك من قبل الإدارة (${reason}). يرجى التواصل مع الإدارة أو تجديد الاشتراك.`
        : "تم إيقاف اشتراكك من قبل الإدارة. يرجى التواصل مع الإدارة أو تجديد الاشتراك."
    };
  }
  if (effectiveStatus === "cancelled") {
    return {
      isValid: false,
      status: "cancelled",
      planId: meta.planId,
      planName: meta.planName,
      maxCaptains: 0,
      price: meta.price,
      startDate: startDateMs,
      endDate: endDateMs,
      reason,
      errorMessage: reason
        ? `تم إلغاء اشتراكك (${reason}). يرجى التواصل مع الإدارة أو الاشتراك في باقة جديدة.`
        : "تم إلغاء اشتراكك. يرجى التواصل مع الإدارة أو الاشتراك في باقة جديدة."
    };
  }
  if (effectiveStatus === "payment_failed" || effectiveStatus === "pending_payment" || ["failed", "unpaid", "pending"].includes(paymentStatus)) {
    return {
      isValid: false,
      status: effectiveStatus === "pending_payment" ? "pending_payment" : "payment_failed",
      planId: meta.planId,
      planName: meta.planName,
      maxCaptains: 0,
      price: meta.price,
      errorMessage: "لم يتم تأكيد الدفع لهذا الاشتراك."
    };
  }
  if (effectiveStatus === "expired" || (endDateMs !== null && now >= endDateMs)) {
    return {
      isValid: false,
      status: "expired",
      planId: meta.planId,
      planName: meta.planName,
      maxCaptains: meta.maxCaptains,
      price: meta.price,
      startDate: startDateMs,
      endDate: endDateMs,
      errorMessage: "انتهت فترة اشتراكك، يرجى تجديد الاشتراك."
    };
  }
  if (effectiveStatus !== "active" || effectiveDoc.isSubscribed === false || Number(effectiveDoc.maxCaptains) <= 0) {
    return {
      isValid: false,
      status: effectiveStatus || "unsubscribed",
      planId: 0,
      planName: "غير مشترك",
      maxCaptains: 0,
      price: 0,
      errorMessage: "لا يوجد اشتراك نشط لهذا التاجر."
    };
  }

  const activeStatuses = ["accepted", "in_progress", "out_for_delivery", "captain_reached_store", "order_picked_up", "waiting_merchant_confirmation", "customer_confirmed", "customer_rejected", "في الطريق لك", "جاري التوصيل", "وصل المتجر", "تم الاستلام", "بانتظار تأكيد البيانات"];
  const [activeByMerchant, activeLegacy] = await Promise.all([
    db.collection("orders").where("merchantId", "==", merchantId).where("status", "in", activeStatuses).get(),
    db.collection("orders").where("restaurantId", "==", merchantId).where("status", "in", activeStatuses).get()
  ]);
  const activeOrders = new Map<string, FirebaseFirestore.QueryDocumentSnapshot>();
  activeByMerchant.docs.forEach((doc) => activeOrders.set(doc.id, doc));
  activeLegacy.docs.forEach((doc) => activeOrders.set(doc.id, doc));
  const activeCaptainIds = new Set<string>();
  activeOrders.forEach((doc) => {
    const captainId = doc.data().captainId;
    if (typeof captainId === "string" && captainId.trim()) activeCaptainIds.add(captainId.trim());
  });
  const activeCaptainsCount = activeCaptainIds.size;
  if (activeCaptainsCount >= meta.maxCaptains && meta.maxCaptains > 0) {
    return {
      isValid: false,
      status: "active",
      planId: meta.planId,
      planName: meta.planName,
      maxCaptains: meta.maxCaptains,
      price: meta.price,
      startDate: startDateMs,
      endDate: endDateMs,
      activeCaptainsCount,
      errorMessage: `وصلت إلى الحد الأقصى لعدد الكباتن المتزامنين في باقتك (${meta.maxCaptains}).`
    };
  }
  return {
    isValid: true,
    status: "active",
    planId: meta.planId,
    planName: meta.planName,
    maxCaptains: meta.maxCaptains,
    price: meta.price,
    startDate: startDateMs,
    endDate: endDateMs,
    activeCaptainsCount
  };
}

/**
 * Trusted Admin Callable: Atomically activates, reactivates, suspends, or cancels a merchant's subscription.
 * Writes ONLY to canonical `subscriptions/{merchantId}` + mirrors summary fields onto `merchants/{merchantId}`.
 */
export const adminUpdateMerchantSubscription = functions.https.onCall(async (data, context) => {
  const authorizedAdmin = await isAdminCaller(context);
  if (!authorizedAdmin || !context.auth) {
    throw new functions.https.HttpsError("permission-denied", "هذه العملية مسموحة لمسؤولي الإدارة فقط.");
  }

  const merchantId = String(data?.merchantId || "").trim();
  const action = String(data?.action || "").toLowerCase().trim();
  if (!merchantId) {
    throw new functions.https.HttpsError("invalid-argument", "معرف التاجر merchantId مطلوب.");
  }
  if (!["activate", "reactivate", "suspend", "cancel"].includes(action)) {
    throw new functions.https.HttpsError("invalid-argument", "الإجراء غير مدعوم. الإجراءات المتاحة: activate, reactivate, suspend, cancel.");
  }

  const adminUid = context.auth.uid;
  const reason = String(data?.reason || "").trim();
  const durationDays = Math.max(1, Math.min(365, Number(data?.durationDays || 30)));

  const result = await db.runTransaction(async (transaction) => {
    const merchantRef = db.collection("merchants").doc(merchantId);
    const subRef = db.collection("subscriptions").doc(merchantId);
    const [merchantDoc, subDoc] = await Promise.all([
      transaction.get(merchantRef),
      transaction.get(subRef)
    ]);

    if (!merchantDoc.exists || merchantDoc.data()?.accountType !== "merchant") {
      throw new functions.https.HttpsError("not-found", "حساب التاجر غير موجود في مجموعة merchants.");
    }

    const existingSub = subDoc.data() || {};
    const existingMerch = merchantDoc.data() || {};
    const meta = resolvePlanMetadata(
      data?.planId ?? existingSub.planId ?? existingMerch.planId,
      data?.planName ?? existingSub.planName ?? existingMerch.planName,
      data?.maxCaptains ?? existingSub.maxCaptains ?? existingMerch.maxCaptains,
      data?.price ?? existingSub.price ?? existingMerch.price
    );

    if (action === "activate" || action === "reactivate") {
      const startMs = typeof data?.startDateMs === "number" ? data.startDateMs : Date.now();
      const endMs = typeof data?.endDateMs === "number"
        ? data.endDateMs
        : startMs + durationDays * 24 * 60 * 60 * 1000;
      const startTs = admin.firestore.Timestamp.fromMillis(startMs);
      const endTs = admin.firestore.Timestamp.fromMillis(endMs);

      transaction.set(subRef, {
        merchantId,
        status: "active",
        subscriptionStatus: "active",
        isSubscribed: true,
        paymentStatus: "paid",
        planId: meta.planId,
        planName: meta.planName,
        maxCaptains: meta.maxCaptains,
        price: meta.price,
        startDate: startTs,
        endDate: endTs,
        suspensionReason: admin.firestore.FieldValue.delete(),
        cancellationReason: admin.firestore.FieldValue.delete(),
        updatedByAdminUid: adminUid,
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });

      transaction.set(merchantRef, {
        subscriptionStatus: "active",
        isSubscribed: true,
        paymentStatus: "paid",
        planId: meta.planId,
        planName: meta.planName,
        maxCaptains: meta.maxCaptains,
        price: meta.price,
        subscriptionStartDate: startTs,
        subscriptionEndDate: endTs,
        suspensionReason: admin.firestore.FieldValue.delete(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });

      return { status: "active", startDateMs: startMs, endDateMs: endMs, ...meta };
    }

    if (action === "suspend") {
      const finalReason = reason || "تم إيقاف الاشتراك مؤقتاً من قبل الإدارة";
      transaction.set(subRef, {
        merchantId,
        status: "suspended",
        subscriptionStatus: "suspended",
        isSubscribed: false,
        suspensionReason: finalReason,
        suspendedAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedByAdminUid: adminUid,
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });

      transaction.set(merchantRef, {
        subscriptionStatus: "suspended",
        isSubscribed: false,
        suspensionReason: finalReason,
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });

      return { status: "suspended", reason: finalReason, ...meta };
    }

    // action === "cancel"
    const cancelReason = reason || "تم إلغاء الاشتراك من قبل الإدارة";
    transaction.set(subRef, {
      merchantId,
      status: "cancelled",
      subscriptionStatus: "cancelled",
      isSubscribed: false,
      maxCaptains: 0,
      cancellationReason: cancelReason,
      suspensionReason: cancelReason,
      cancelledAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedByAdminUid: adminUid,
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });

    transaction.set(merchantRef, {
      subscriptionStatus: "cancelled",
      isSubscribed: false,
      maxCaptains: 0,
      suspensionReason: cancelReason,
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });

    return { status: "cancelled", reason: cancelReason, ...meta };
  });

  return { success: true, merchantId, ...result };
});

/**
 * Callable endpoint allowing an authenticated merchant to query server-authoritative subscription status.
 */
export const getMySubscriptionStatus = functions.https.onCall(async (_data, context) => {
  if (!context.auth || !context.auth.uid) {
    throw new functions.https.HttpsError("unauthenticated", "يجب تسجيل الدخول أولاً.");
  }
  const verification = await verifyMerchantSubscription(context.auth.uid);
  return {
    ...verification,
    merchantId: context.auth.uid,
    serverTimeMs: Date.now()
  };
});

/**
 * Bidirectional Loop-Safe Sync Trigger 1:
 * When Admin Dashboard writes directly to `subscriptions/{subscriptionId}`, sync summary fields onto `merchants/{merchantId}`.
 */
export const onSubscriptionWriteSyncMerchant = functions.firestore
  .document("subscriptions/{subscriptionId}")
  .onWrite(async (change, context) => {
    const subId = context.params.subscriptionId;
    if (!change.after.exists) {
      const targetId = String(change.before.data()?.merchantId || subId).trim();
      if (!targetId) return;
      const merchRef = db.collection("merchants").doc(targetId);
      const merchSnap = await merchRef.get();
      if (!merchSnap.exists || merchSnap.data()?.accountType !== "merchant") return;
      await merchRef.set({
        subscriptionStatus: "unsubscribed",
        isSubscribed: false,
        maxCaptains: 0,
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });
      return;
    }

    const data = change.after.data() || {};
    const targetMerchantId = String(data.merchantId || subId).trim();
    if (!targetMerchantId) return;

    const normalizedStatus = normalizeSubscriptionStatus(data.status || data.subscriptionStatus);
    const isActive = normalizedStatus === "active" && data.isSubscribed !== false;
    const maxCaptains = isActive ? Number(data.maxCaptains || 0) : (normalizedStatus === "cancelled" ? 0 : Number(data.maxCaptains || 0));

    const merchRef = db.collection("merchants").doc(targetMerchantId);
    const merchSnap = await merchRef.get();
    if (!merchSnap.exists || merchSnap.data()?.accountType !== "merchant") return;

    const mData = merchSnap.data() || {};
    const currentMerchStatus = normalizeSubscriptionStatus(mData.subscriptionStatus);
    const currentMerchSubFlag = Boolean(mData.isSubscribed);
    const currentMerchPlan = Number(mData.planId || 0);
    const currentMerchMax = Number(mData.maxCaptains || 0);
    const currentMerchEndMs = parseMillis(mData.subscriptionEndDate);
    const incomingEndMs = parseMillis(data.endDate || data.subscriptionEndDate);
    const incomingReason = String(data.suspensionReason || data.cancellationReason || "").trim();
    const currentReason = String(mData.suspensionReason || "").trim();

    if (
      currentMerchStatus === normalizedStatus &&
      currentMerchSubFlag === isActive &&
      currentMerchPlan === Number(data.planId || 0) &&
      currentMerchMax === maxCaptains &&
      currentMerchEndMs === incomingEndMs &&
      currentReason === incomingReason
    ) {
      return; // Loop guard: already in sync
    }

    const mirrorPayload: Record<string, any> = {
      subscriptionStatus: normalizedStatus,
      isSubscribed: isActive,
      planId: Number(data.planId || 0),
      planName: data.planName || mData.planName || "",
      maxCaptains,
      price: Number(data.price || mData.price || 0),
      paymentStatus: data.paymentStatus || mData.paymentStatus || (isActive ? "paid" : "unpaid"),
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    };
    if (data.startDate) mirrorPayload.subscriptionStartDate = data.startDate;
    if (data.endDate) mirrorPayload.subscriptionEndDate = data.endDate;
    if (incomingReason) {
      mirrorPayload.suspensionReason = incomingReason;
    } else if (isActive) {
      mirrorPayload.suspensionReason = admin.firestore.FieldValue.delete();
    }

    await merchRef.set(mirrorPayload, { merge: true });
  });

/**
 * Bidirectional Loop-Safe Sync Trigger 2:
 * When Admin Dashboard writes subscription fields directly onto `merchants/{merchantId}`, mirror them onto canonical `subscriptions/{merchantId}`.
 */
export const onMerchantSubscriptionWriteSync = functions.firestore
  .document("merchants/{merchantId}")
  .onUpdate(async (change, context) => {
    const merchantId = context.params.merchantId;
    const before = change.before.data() || {};
    const after = change.after.data() || {};
    if (after.accountType !== "merchant") return;

    const trackedFields = [
      "subscriptionStatus",
      "isSubscribed",
      "planId",
      "planName",
      "maxCaptains",
      "price",
      "subscriptionStartDate",
      "subscriptionEndDate",
      "suspensionReason",
      "paymentStatus"
    ];
    const hasSubDiff = trackedFields.some((f) => JSON.stringify(before[f]) !== JSON.stringify(after[f]));
    if (!hasSubDiff) return;

    const merchStatus = normalizeSubscriptionStatus(after.subscriptionStatus);
    const isActive = merchStatus === "active" && after.isSubscribed !== false;
    const subRef = db.collection("subscriptions").doc(merchantId);
    const subSnap = await subRef.get();
    const sData = subSnap.data() || {};

    const currentSubStatus = subSnap.exists ? normalizeSubscriptionStatus(sData.status || sData.subscriptionStatus) : "unsubscribed";
    const currentSubFlag = subSnap.exists ? Boolean(sData.isSubscribed) : false;
    const currentSubPlan = Number(sData.planId || 0);
    const currentSubMax = Number(sData.maxCaptains || 0);
    const currentSubEndMs = parseMillis(sData.endDate);
    const afterEndMs = parseMillis(after.subscriptionEndDate);
    const afterReason = String(after.suspensionReason || "").trim();
    const currentReason = String(sData.suspensionReason || sData.cancellationReason || "").trim();

    if (
      subSnap.exists &&
      currentSubStatus === merchStatus &&
      currentSubFlag === isActive &&
      currentSubPlan === Number(after.planId || 0) &&
      currentSubMax === Number(after.maxCaptains || 0) &&
      currentSubEndMs === afterEndMs &&
      currentReason === afterReason
    ) {
      return; // Loop guard: already in sync
    }

    const subPayload: Record<string, any> = {
      merchantId,
      status: merchStatus,
      subscriptionStatus: merchStatus,
      isSubscribed: isActive,
      planId: Number(after.planId || 0),
      planName: after.planName || sData.planName || "",
      maxCaptains: Number(after.maxCaptains || 0),
      price: Number(after.price || sData.price || 0),
      paymentStatus: after.paymentStatus || sData.paymentStatus || (isActive ? "paid" : "unpaid"),
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    };
    if (after.subscriptionStartDate) subPayload.startDate = after.subscriptionStartDate;
    if (after.subscriptionEndDate) subPayload.endDate = after.subscriptionEndDate;
    if (afterReason) {
      subPayload.suspensionReason = afterReason;
    } else if (isActive) {
      subPayload.suspensionReason = admin.firestore.FieldValue.delete();
      subPayload.cancellationReason = admin.firestore.FieldValue.delete();
    }

    await subRef.set(subPayload, { merge: true });
  });

/**
 * Cloud Function: createOrderWithCode
 * 1. Checks Merchant Authentication.
 * 2. Idempotency Key check: prevents duplicate order creation on network retries or multi-click.
 * 3. Server-side Subscription Verification (reads 'subscriptions' and active captains capacity).
 * 4. Generates unique 6-digit order code (non-zero leading, distinct digits).
 * 5. Atomic reservation in Firestore Transaction:
 *    - reserves code in 'order_codes/{code}'
 *    - writes order in 'orders/{orderId}' with 'orderCode'
 *    - saves 'idempotency_keys/{key}'
 * 6. Triggers Auto-Dispatch and returns { orderId, orderCode, ... } immediately.
 */
export const createOrderWithCode = functions.https.onCall(async (data, context) => {
  // 1. Verify Authentication
  if (!context.auth || !context.auth.uid) {
    throw new functions.https.HttpsError("unauthenticated", "يجب تسجيل الدخول أولاً لإنشاء طلب جديد.");
  }
  const uid = context.auth.uid;
  if (context.auth.token.email_verified !== true) {
    throw new functions.https.HttpsError("permission-denied", "يجب تأكيد البريد الإلكتروني أولاً.");
  }

  const idempotencyKey = (data.idempotencyKey || "").toString().trim();
  const destinationAddress = (data.destinationAddress || data.address || "").toString().trim();
  const serviceFee = (data.serviceFee || data.price || "30 جنيه").toString().trim();
  const customerName = (data.customerName || "").toString().trim();
  const customerPhone = (data.customerPhone || "").toString().trim();
  const orderDetails = (data.orderDetails || data.items || "").toString().trim();
  const deliveryNotes = (data.deliveryNotes || data.notes || "").toString().trim();
  const customerLatitude = typeof data.customerLatitude === "number" ? data.customerLatitude : null;
  const customerLongitude = typeof data.customerLongitude === "number" ? data.customerLongitude : null;

  if (!destinationAddress) {
    throw new functions.https.HttpsError("invalid-argument", "عنوان التوصيل مطلوب لإنشاء الطلب.");
  }

  // 2. Check Idempotency Key
  if (idempotencyKey) {
    const existingKeyDoc = await db.collection("idempotency_keys").doc(idempotencyKey).get();
    if (existingKeyDoc.exists) {
      const keyData = existingKeyDoc.data() || {};
      console.log(`[CreateOrder] Idempotent replay for key ${idempotencyKey}, returning order ${keyData.orderId}`);
      return {
        success: true,
        orderId: keyData.orderId,
        orderCode: keyData.orderCode,
        isReplay: true,
        message: "تم استرجاع بيانات الطلب المنشأ مسبقاً بنجاح."
      };
    }
  }

  // 3. Server-Side Subscription Verification
  const subCheck = await verifyMerchantSubscription(uid);
  if (!subCheck.isValid) {
    throw new functions.https.HttpsError("failed-precondition", subCheck.errorMessage || "الاشتراك غير متاح لإنشاء الطلب.");
  }

  // Read the merchant profile exclusively from merchants/{uid}.
  const merchantDoc = await db.collection("merchants").doc(uid).get();
  if (!merchantDoc.exists || merchantDoc.data()?.accountType !== "merchant") {
    throw new functions.https.HttpsError("permission-denied", "حساب التاجر غير موجود في merchants.");
  }
  const merchantData = merchantDoc.data() || {};
  if (merchantData.status !== "approved" && merchantData.status !== "active") {
    throw new functions.https.HttpsError("failed-precondition", "حساب التاجر في انتظار موافقة الإدارة.");
  }
  const merchantName = merchantData.name || "تاجر";
  const merchantLoc = merchantData.location ||
    (typeof merchantData.latitude === "number" && typeof merchantData.longitude === "number"
      ? new admin.firestore.GeoPoint(merchantData.latitude, merchantData.longitude)
      : null);
  if (!merchantLoc) {
    throw new functions.https.HttpsError("failed-precondition", "يرجى تحديد موقع المتجر قبل إنشاء الطلب.");
  }

  // 4. Atomic Transaction: Find unique 6-digit code and create order
  const MAX_ATTEMPTS = 15;
  let allocatedCode = "";
  let newOrderId = "";

  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    const candidateCode = generateDistinct6DigitCode();
    const candidateOrderId = `${Date.now()}_${candidateCode}`;

    try {
      await db.runTransaction(async (transaction) => {
        // Check uniqueness in 'order_codes/{code}'
        const codeRef = db.collection("order_codes").doc(candidateCode);
        const codeDoc = await transaction.get(codeRef);
        if (codeDoc.exists) {
          throw new Error("CODE_ALREADY_USED");
        }

        // Double check idempotency key inside transaction
        if (idempotencyKey) {
          const keyRef = db.collection("idempotency_keys").doc(idempotencyKey);
          const keyDoc = await transaction.get(keyRef);
          if (keyDoc.exists) {
            throw new Error("IDEMPOTENT_ALREADY_EXISTS");
          }
        }

        // Reserve code
        transaction.set(codeRef, {
          code: candidateCode,
          orderId: candidateOrderId,
          merchantId: uid,
          createdAt: admin.firestore.FieldValue.serverTimestamp()
        });

        // Create Order Document
        const orderRef = db.collection("orders").doc(candidateOrderId);
        const orderPayload: Record<string, any> = {
          orderId: candidateOrderId,
          orderCode: candidateCode,
          code: candidateCode,
          address: destinationAddress,
          destinationAddress: destinationAddress,
          deliveryAddress: destinationAddress,
          price: serviceFee,
          totalAmount: serviceFee,
          serviceFee: serviceFee,
          customerName: customerName,
          customerPhone: customerPhone,
          orderDetails: orderDetails,
          items: orderDetails,
          deliveryNotes: deliveryNotes,
          notes: deliveryNotes,
          status: "pending",
          dispatchStatus: "searching",
          merchantId: uid,
          userId: uid,
          merchantName: merchantName,
          
          merchantLocation: merchantLoc,
          pickupLocation: merchantLoc,
          merchantLatitude: (merchantLoc as any).latitude,
          merchantLongitude: (merchantLoc as any).longitude,
          captainId: null,
          driver_name: null,
          captainName: null,
          captainPhone: null,
          captainPhotoUrl: null,
          customerDetailsSubmitted: false,
          customerDetailsConfirmed: false,
          customerDetailsRejected: false,
          createdAt: admin.firestore.FieldValue.serverTimestamp(),
          created_at: admin.firestore.FieldValue.serverTimestamp(),
          updatedAt: admin.firestore.FieldValue.serverTimestamp()
        };

        if (customerLatitude !== null && customerLongitude !== null) {
          orderPayload.customerLocation = new admin.firestore.GeoPoint(customerLatitude, customerLongitude);
          orderPayload.customerLatitude = customerLatitude;
          orderPayload.customerLongitude = customerLongitude;
        }

        transaction.set(orderRef, orderPayload);

        // Record Idempotency Key
        if (idempotencyKey) {
          const keyRef = db.collection("idempotency_keys").doc(idempotencyKey);
          transaction.set(keyRef, {
            idempotencyKey,
            orderId: candidateOrderId,
            orderCode: candidateCode,
            merchantId: uid,
            createdAt: admin.firestore.FieldValue.serverTimestamp()
          });
        }
      });

      allocatedCode = candidateCode;
      newOrderId = candidateOrderId;
      console.log(`[CreateOrder] Order ${newOrderId} created with unique code ${allocatedCode} on attempt ${attempt}`);
      break;
    } catch (err: any) {
      if (err.message === "IDEMPOTENT_ALREADY_EXISTS") {
        const replayDoc = await db.collection("idempotency_keys").doc(idempotencyKey).get();
        const rData = replayDoc.data() || {};
        return {
          success: true,
          orderId: rData.orderId,
          orderCode: rData.orderCode,
          isReplay: true,
          message: "تم استرجاع بيانات الطلب المنشأ مسبقاً بنجاح."
        };
      }
      console.warn(`[CreateOrder] Candidate code ${candidateCode} collision/conflict: ${err.message}. Retrying...`);
    }
  }

  if (!allocatedCode || !newOrderId) {
    throw new functions.https.HttpsError("resource-exhausted", "تعذر تخصيص كود طلب غير مكرر حالياً، يرجى المحاولة بعد لحظات أو التواصل مع الإدارة.");
  }

  // Trigger auto-dispatch
  try {
    const createdDoc = await db.collection("orders").doc(newOrderId).get();
    if (createdDoc.exists) {
      runAutoDispatch(newOrderId, createdDoc.data() || {}).catch((e) => {
        console.warn(`[CreateOrder] AutoDispatch invocation warning: ${e.message}`);
      });
    }
  } catch (_: any) {}

  return {
    success: true,
    orderId: newOrderId,
    orderCode: allocatedCode,
    status: "pending",
    message: "تم إنشاء طلب التوصيل بنجاح"
  };
});

