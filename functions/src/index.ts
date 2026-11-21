import * as functions from "firebase-functions";
import * as admin from "firebase-admin";

if (!admin.apps.length) {
  admin.initializeApp();
}
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
 * Checks whether an order status is a terminal status that frees captain capacity.
 * Rejection/re-dispatch states (pending, searching, offered, reassigned) are NOT terminal.
 */
export function isTerminalOrderStatusForCapacity(rawStatus: unknown): boolean {
  const raw = String(rawStatus || "").trim().toLowerCase();
  if (!raw) return false;
  return (
    [
      "completed",
      "delivered",
      "cancelled",
      "canceled",
      "ملغي",
      "ملغية",
      "تم الإلغاء",
      "تم الالغاء",
      "تم التسليم",
      "تم التوصيل",
      "مكتمل",
      "مكتملة"
    ].includes(raw) ||
    raw.includes("مكتمل") ||
    raw.includes("تم التسليم") ||
    raw.includes("تم التوصيل") ||
    raw.includes("delivered") ||
    raw.includes("ملغ") ||
    raw.includes("cancel")
  );
}

/**
 * Releases a reserved capacity slot for a terminal order atomically and idempotently (at most once).
 * Never releases capacity if the order is still active / being re-dispatched after captain rejection.
 */
export async function releaseOrderCapacitySlot(
  targetDb: FirebaseFirestore.Firestore,
  orderId: string,
  merchantIdHint?: string,
  releaseReason?: string
): Promise<{ released: boolean; reason: string; remainingReservedCount?: number }> {
  if (!orderId) {
    return { released: false, reason: "invalid_order_id" };
  }

  return targetDb.runTransaction(async (transaction) => {
    const orderRef = targetDb.collection("orders").doc(orderId);
    const orderSnap = await transaction.get(orderRef);
    if (!orderSnap.exists) {
      return { released: false, reason: "order_not_found" };
    }
    const orderData = orderSnap.data() || {};
    if (orderData.capacityReleased === true) {
      return { released: false, reason: "already_released" };
    }
    if (!isTerminalOrderStatusForCapacity(orderData.status)) {
      return { released: false, reason: "order_not_terminal" };
    }

    const merchantId = String(
      merchantIdHint || orderData.merchantId || orderData.userId || orderData.restaurantId || ""
    ).trim();
    if (!merchantId) {
      transaction.set(
        orderRef,
        {
          capacityReleased: true,
          capacityReleasedAt: admin.firestore.FieldValue.serverTimestamp(),
          capacityReleasedReason: String(releaseReason || orderData.status || "terminal")
        },
        { merge: true }
      );
      return { released: true, reason: "released_without_merchant", remainingReservedCount: 0 };
    }

    const capRef = targetDb
      .collection("merchants")
      .doc(merchantId)
      .collection("capacity")
      .doc("current");
    const capSnap = await transaction.get(capRef);
    const capData = capSnap.exists ? (capSnap.data() || {}) : {};
    const prevIds: string[] = Array.isArray(capData.reservedOrderIds)
      ? capData.reservedOrderIds.map((x: unknown) => String(x)).filter(Boolean)
      : [];
    const nextIds = prevIds.filter((id) => id !== orderId);

    transaction.set(
      capRef,
      {
        merchantId,
        reservedOrderIds: nextIds,
        activeReservationsCount: nextIds.length,
        capacityVersion: admin.firestore.FieldValue.increment(1),
        lastReleasedOrderId: orderId,
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      },
      { merge: true }
    );

    transaction.set(
      orderRef,
      {
        capacityReleased: true,
        capacityReleasedAt: admin.firestore.FieldValue.serverTimestamp(),
        capacityReleasedReason: String(releaseReason || orderData.status || "terminal")
      },
      { merge: true }
    );

    return {
      released: true,
      reason: "released",
      remainingReservedCount: nextIds.length
    };
  });
}

const DISPATCH_LEASE_MS = 30_000;

async function releaseDispatchLeaseIfOwned(
  targetDb: FirebaseFirestore.Firestore,
  orderId: string,
  dispatchAttemptId: string,
  extraFields: Record<string, any> = {}
): Promise<void> {
  try {
    await targetDb.runTransaction(async (transaction) => {
      const orderRef = targetDb.collection("orders").doc(orderId);
      const snap = await transaction.get(orderRef);
      if (!snap.exists) return;
      const data = snap.data() || {};
      if (data.dispatchAttemptId !== dispatchAttemptId) return;
      if (data.captainId || data.dispatchStatus === "assigned") return;
      transaction.update(orderRef, {
        dispatchStatus: extraFields.dispatchStatus || "searching",
        dispatchLockedUntilMs: admin.firestore.FieldValue.delete(),
        lastSearchAt: admin.firestore.FieldValue.serverTimestamp(),
        ...extraFields
      });
    });
  } catch (err: any) {
    console.warn(`[AutoDispatch] Lease release warning for order ${orderId}: ${err?.message || err}`);
  }
}

/**
 * Main auto-dispatch engine function.
 * Protected by an atomic per-order dispatch lease (`dispatchStatus: "dispatching"` + `dispatchAttemptId`)
 * so retried triggers or concurrent triggers can NEVER double-dispatch or send duplicate offers to captains.
 */
export async function runAutoDispatch(
  orderId: string,
  orderData: FirebaseFirestore.DocumentData,
  options: {
    triggerSource?: string;
    targetDb?: FirebaseFirestore.Firestore;
    sendFcm?: (payload: admin.messaging.Message) => Promise<any>;
    nowMs?: number;
  } = {}
): Promise<boolean> {
  // Distribution is disabled in the merchant repository. The Dashboard repository
  // is the sole dispatcher; this opt-in guard exists only for controlled legacy recovery.
  if (process.env.ENABLE_LEGACY_MERCHANT_DISPATCH !== "true") {
    console.info(`[LegacyAutoDispatchDisabled] Order ${orderId} must be handled by Dashboard Functions.`);
    return false;
  }
  if (orderData.dispatchAuthority === "dashboard_functions") return false;

  const targetDb = options.targetDb || db;
  const triggerSource = options.triggerSource || "onOrderCreated";
  const nowMs = options.nowMs ?? Date.now();
  const sendFcm = options.sendFcm || ((payload: admin.messaging.Message) => admin.messaging().send(payload));

  console.log(`[AutoDispatch] Starting dispatch evaluation for order: ${orderId} (source=${triggerSource})`);

  // 1. Atomic Dispatch Claim / Lease Lock to prevent duplicate concurrent executions
  const dispatchAttemptId = `${nowMs}_${crypto.randomBytes(6).toString("hex")}`;
  let claimedOrderData: FirebaseFirestore.DocumentData | null = null;

  try {
    claimedOrderData = await targetDb.runTransaction(async (transaction) => {
      const orderRef = targetDb.collection("orders").doc(orderId);
      const snap = await transaction.get(orderRef);
      if (!snap.exists) {
        return null;
      }
      const fresh = snap.data() || {};
      const currentStatus = String(fresh.status || "").toLowerCase();
      const dispatchStatus = String(fresh.dispatchStatus || "").toLowerCase();
      const existingCaptainId = fresh.captainId;

      if (existingCaptainId || currentStatus !== "pending") {
        return null;
      }
      if (dispatchStatus === "assigned" || dispatchStatus === "failed") {
        return null;
      }
      if (dispatchStatus === "dispatching") {
        const lockedUntil = Number(fresh.dispatchLockedUntilMs || 0);
        if (lockedUntil > nowMs) {
          console.log(`[AutoDispatch] Order ${orderId} already locked by attempt ${fresh.dispatchAttemptId}. Skipping duplicate dispatch.`);
          return null;
        }
      }

      transaction.update(orderRef, {
        dispatchStatus: "dispatching",
        dispatchAttemptId,
        dispatchLockedUntilMs: nowMs + DISPATCH_LEASE_MS,
        dispatchTriggerSource: triggerSource,
        lastDispatchAttemptAt: admin.firestore.FieldValue.serverTimestamp()
      });
      return fresh;
    });
  } catch (claimErr: any) {
    console.warn(`[AutoDispatch] Failed to claim dispatch lease for ${orderId}: ${claimErr?.message || claimErr}`);
    return false;
  }

  if (!claimedOrderData) {
    console.log(`[AutoDispatch] Order ${orderId} not eligible or already claimed. Skipping.`);
    return false;
  }

  const effectiveOrderData = { ...orderData, ...claimedOrderData };

  // 2. Verify the canonical merchant account and subscription.
  const merchantId = effectiveOrderData.merchantId || effectiveOrderData.userId;
  if (!merchantId) {
    await releaseDispatchLeaseIfOwned(targetDb, orderId, dispatchAttemptId, {
      dispatchStatus: "failed",
      dispatchError: "معرف التاجر مفقود"
    });
    return false;
  }
  const merchantDoc = await targetDb.collection("merchants").doc(merchantId).get();
  if (!merchantDoc.exists || !isMerchantAccountDoc(merchantDoc.data())) {
    await releaseDispatchLeaseIfOwned(targetDb, orderId, dispatchAttemptId, {
      dispatchStatus: "failed",
      dispatchError: "التاجر غير موجود في merchants",
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });
    return false;
  }
  const restData = merchantDoc.data() || {};
  if (restData.status !== "approved" && restData.status !== "active") {
    await releaseDispatchLeaseIfOwned(targetDb, orderId, dispatchAttemptId, {
      dispatchStatus: "failed",
      dispatchError: "حساب التاجر في انتظار موافقة الإدارة",
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });
    return false;
  }
  const subCheck = await verifyMerchantSubscription(merchantId, 1, targetDb, nowMs);
  if (!subCheck.isActive) {
    await releaseDispatchLeaseIfOwned(targetDb, orderId, dispatchAttemptId, {
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

  if (effectiveOrderData.pickupLocation && typeof effectiveOrderData.pickupLocation.latitude === "number") {
    pickupLat = effectiveOrderData.pickupLocation.latitude;
    pickupLng = effectiveOrderData.pickupLocation.longitude;
  } else if (typeof effectiveOrderData.merchantLatitude === "number" && typeof effectiveOrderData.merchantLongitude === "number") {
    pickupLat = effectiveOrderData.merchantLatitude;
    pickupLng = effectiveOrderData.merchantLongitude;
  } else if (restData.location && typeof restData.location.latitude === "number") {
    pickupLat = restData.location.latitude;
    pickupLng = restData.location.longitude;
  } else if (typeof restData.latitude === "number" && typeof restData.longitude === "number") {
    pickupLat = restData.latitude;
    pickupLng = restData.longitude;
  }

  if (pickupLat === null || pickupLng === null) {
    console.warn(`[AutoDispatch] No valid location found for merchant ${merchantId}. Order ${orderId} remains in searching.`);
    await releaseDispatchLeaseIfOwned(targetDb, orderId, dispatchAttemptId, {
      dispatchStatus: "searching",
      dispatchNote: "في انتظار تحديد إحداثيات التاجر"
    });
    return false;
  }

  // 4. Query eligible captains from 'captains' collection
  const captainsSnapshot = await targetDb.collection("captains")
    .where("isOnline", "==", true)
    .get();

  if (captainsSnapshot.empty) {
    console.log(`[AutoDispatch] No online, available, approved captains currently available.`);
    await releaseDispatchLeaseIfOwned(targetDb, orderId, dispatchAttemptId, {
      dispatchStatus: "searching",
      searchAttempts: admin.firestore.FieldValue.increment(1)
    });
    return false;
  }

  const maxLocationStaleMs = 3 * 60 * 1000; // 3 minutes maximum for fresh location
  const candidates: CandidateCaptain[] = [];
  const scheduleSnapshots = await Promise.all(captainsSnapshot.docs.map((captainDoc) =>
    targetDb.collection("captain_schedules").doc(captainDoc.id).get()
  ));

  for (let captainIndex = 0; captainIndex < captainsSnapshot.docs.length; captainIndex++) {
    const doc = captainsSnapshot.docs[captainIndex];
    if (!isCaptainCurrentlyInShift(scheduleSnapshots[captainIndex].data(), new Date(nowMs))) continue;
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
    await releaseDispatchLeaseIfOwned(targetDb, orderId, dispatchAttemptId, {
      dispatchStatus: "searching",
      searchAttempts: admin.firestore.FieldValue.increment(1)
    });
    return false;
  }

  // Sort candidates by proximity (closest captain first)
  candidates.sort((a, b) => a.distanceKm - b.distanceKm);
  console.log(`[AutoDispatch] Found ${candidates.length} candidate captains for order ${orderId}. Closest is ${candidates[0].id} (${candidates[0].distanceKm.toFixed(2)} km).`);

  // 5. Atomic Reservation using Firestore Transactions
  let assignedCaptain: CandidateCaptain | null = null;

  for (const candidate of candidates) {
    try {
      await targetDb.runTransaction(async (transaction) => {
        const orderRef = targetDb.collection("orders").doc(orderId);
        const captainRef = targetDb.collection("captains").doc(candidate.id);
        const scheduleRef = targetDb.collection("captain_schedules").doc(candidate.id);

        const [currentOrderDoc, currentCaptainDoc, scheduleDoc] = await Promise.all([
          transaction.get(orderRef),
          transaction.get(captainRef),
          transaction.get(scheduleRef)
        ]);

        if (!currentOrderDoc.exists) {
          throw new Error("ORDER_NOT_FOUND");
        }

        const freshOrder = currentOrderDoc.data() || {};
        // Re-verify order is still unassigned AND owned by this dispatch attempt lease
        if (
          freshOrder.captainId ||
          freshOrder.status !== "pending" ||
          freshOrder.dispatchStatus !== "dispatching" ||
          freshOrder.dispatchAttemptId !== dispatchAttemptId
        ) {
          throw new Error("ORDER_ALREADY_ASSIGNED");
        }

        if (!currentCaptainDoc.exists) {
          throw new Error("CAPTAIN_NOT_FOUND");
        }

        const freshCaptain = currentCaptainDoc.data() || {};
        // Re-verify readiness and shift membership inside the assignment transaction.
        if (
          !isCaptainReadyForDispatch(freshCaptain) ||
          !isCaptainCurrentlyInShift(scheduleDoc.data(), new Date(nowMs))
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

        // Atomically update Order: assign captain & seal dispatch attempt idempotently
        transaction.update(orderRef, {
          captainId: candidate.id,
          captainName: candidate.name,
          captainPhone: candidate.phone,
          captainPhotoUrl: candidate.profileImageUrl,
          driver_name: candidate.name,
          driverPhone: candidate.phone,
          status: "accepted",
          dispatchStatus: "assigned",
          dispatchLockedUntilMs: admin.firestore.FieldValue.delete(),
          dispatchAssignedByAttemptId: dispatchAttemptId,
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
    }
  }

  // 6. Post-Assignment: Push Notification via FCM (only sent by the winning dispatchAttemptId)
  if (assignedCaptain) {
    if (assignedCaptain.fcmToken) {
      try {
        const messagePayload: admin.messaging.Message = {
          token: assignedCaptain.fcmToken,
          notification: {
            title: "طلب توصيل جديد 🛵",
            body: `طلب جديد من مطعم ${effectiveOrderData.merchantName || "البطل"} على بعد ${assignedCaptain.distanceKm.toFixed(1)} كم!`
          },
          data: {
            orderId: orderId,
            merchantName: String(effectiveOrderData.merchantName || ""),
            distance: `${assignedCaptain.distanceKm.toFixed(1)} km`,
            fee: String(effectiveOrderData.serviceFee || effectiveOrderData.price || "30 جنيه"),
            type: "NEW_ORDER_ASSIGNED",
            dispatchAttemptId
          },
          android: {
            priority: "high",
            notification: {
              channelId: "order_status_channel",
              sound: "default"
            }
          }
        };

        await sendFcm(messagePayload);
        console.log(`[AutoDispatch] FCM notification successfully sent to captain ${assignedCaptain.id}`);
      } catch (fcmError: any) {
        console.error(`[AutoDispatch] Failed to send FCM notification: ${fcmError.message}`);
      }
    }
    return true;
  } else {
    console.log(`[AutoDispatch] Could not reserve any candidate for order ${orderId}. Remains in searching.`);
    await releaseDispatchLeaseIfOwned(targetDb, orderId, dispatchAttemptId, {
      dispatchStatus: "searching",
      searchAttempts: admin.firestore.FieldValue.increment(1)
    });
    return false;
  }
}

/**
 * Trigger: On New Order Created in Firestore.
 * Single authoritative entry point for initial auto-dispatch of new orders.
 */
export const onOrderCreated = functions.firestore
  .document("orders/{orderId}")
  .onCreate(async (snap, context) => {
    const orderId = context.params.orderId;
    const orderData = snap.data();
    if (!orderData) return;

    await runAutoDispatch(orderId, orderData, { triggerSource: "onOrderCreated" });
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
        await runAutoDispatch(orderDoc.id, orderDoc.data(), { triggerSource: "onCaptainUpdated" });
      }
    }
  });

/**
 * Trigger: When an Order Status Changes.
 * 1. Idempotently releases the merchant's reserved captainLimit slot when transitioning to a terminal state
 *    (even if cancelled before captain assignment). Never releases on captain rejection / re-dispatch.
 * 2. Frees the assigned captain cleanly without touching captain earnings or ongoing orders.
 */
export const onOrderStatusChanged = functions.firestore
  .document("orders/{orderId}")
  .onUpdate(async (change, context) => {
    const orderId = context.params.orderId;
    const afterData = change.after.data() || {};
    const beforeData = change.before.data() || {};

    // Dashboard Functions own capacity release and captain cleanup for canonical orders.
    if (afterData.dispatchAuthority === "dashboard_functions") return;

    const newStatus = String(afterData.status || "").toLowerCase().trim();
    const oldStatus = String(beforeData.status || "").toLowerCase().trim();
    const captainId = afterData.captainId;
    const merchantId = String(afterData.merchantId || afterData.userId || afterData.restaurantId || "").trim();

    const isTerminalNow = isTerminalOrderStatusForCapacity(newStatus);
    const wasAlreadyFinished = isTerminalOrderStatusForCapacity(oldStatus);

    // 1. Idempotent capacity slot release (protected by transaction + order.capacityReleased flag)
    if (isTerminalNow && afterData.capacityReleased !== true) {
      try {
        const rel = await releaseOrderCapacitySlot(db, orderId, merchantId, newStatus);
        console.log(`[CapacityRelease] Order ${orderId} (${newStatus}): ${rel.reason}`);
      } catch (err: any) {
        console.error(`[CapacityRelease] Error releasing capacity for order ${orderId}: ${err?.message || err}`);
      }
    }

    // 2. Free assigned captain when order reaches terminal state (never modifies earnings/wallet/transactions)
    if (captainId && isTerminalNow && !wasAlreadyFinished) {
      console.log(`[AutoDispatch] Order ${orderId} concluded (${newStatus}). Freeing captain ${captainId}...`);
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
  isActive: boolean;
  isSubscribed: boolean;
  status: string; // active, inactive, suspended, cancelled, expired, pending, pending_payment, payment_failed, unsubscribed, merchant_pending, not_found
  errorMessage?: string;
  planId: number;
  planName: string;
  maxCaptains: number;
  captainLimit: number;
  price: number;
  startDate?: number | null;
  activatedAt?: number | null;
  endDate?: number | null;
  expiresAt?: number | null;
  remainingMillis?: number;
  remainingDays?: number;
  remainingHours?: number;
  activeCaptainsCount: number;
  remainingCaptains: number;
  subscriptionVersion: number;
  updatedBy?: string;
  reason?: string;
  rejectionReason?: string;
  resubmissionReason?: string;
  paymentMethod?: string;
  pendingRequestId?: string;
  approvedAt?: number | null;
}

const ADMIN_EMAIL_ALLOWLIST = [
  "moamen.elsayed.abdelmoamen@gmail.com",
  "admin@elbatalexpress.com"
];

export const ACTIVE_ORDER_STATUSES_FOR_LIMIT = [
  "pending",
  "searching",
  "accepted",
  "in_progress",
  "out_for_delivery",
  "captain_reached_store",
  "order_picked_up",
  "waiting_merchant_confirmation",
  "customer_confirmed",
  "customer_rejected",
  "جاري البحث",
  "جاري البحث عن كابتن",
  "في انتظار كابتن",
  "في الطريق لك",
  "جاري التوصيل",
  "وصل المتجر",
  "تم الاستلام",
  "بانتظار تأكيد البيانات",
  "بيانات مرفوضة"
];

export function parseMillis(rawDate: any): number | null {
  if (rawDate === null || rawDate === undefined) return null;
  if (typeof rawDate.toMillis === "function") return rawDate.toMillis();
  if (rawDate instanceof Date) return rawDate.getTime();
  if (typeof rawDate === "number" && Number.isFinite(rawDate)) {
    if (rawDate <= 0) return null;
    // Normalize Unix timestamps provided in seconds (< 1e12) into milliseconds
    return rawDate < 1_000_000_000_000 ? Math.round(rawDate * 1000) : Math.round(rawDate);
  }
  if (typeof rawDate === "object") {
    if (typeof rawDate._seconds === "number") return rawDate._seconds * 1000;
    if (typeof rawDate.seconds === "number") return rawDate.seconds * 1000;
  }
  if (typeof rawDate === "string") {
    const trimmed = rawDate.trim();
    if (!trimmed) return null;
    const asNum = Number(trimmed);
    if (!isNaN(asNum) && asNum > 1_000_000_000) {
      return asNum < 1_000_000_000_000 ? Math.round(asNum * 1000) : Math.round(asNum);
    }
    const parsed = Date.parse(trimmed);
    if (!isNaN(parsed)) return parsed;
  }
  return null;
}

export function isMerchantAccountDoc(merchantData: Record<string, any> | null | undefined): boolean {
  if (!merchantData) return false;
  const accountType = String(merchantData.accountType || "").toLowerCase().trim();
  const role = String(merchantData.role || "").toLowerCase().trim();
  if (accountType === "merchant") return true;
  if (!accountType && role === "merchant") return true;
  return false;
}

export function normalizeSubscriptionStatus(raw: unknown): string {
  const s = String(raw || "").toLowerCase().trim();
  if ([
    "active", "activated", "subscribed", "enabled", "valid", "approved", "paid",
    "نشط", "نشطة", "ساري", "سارية", "فعال", "فعّال", "فعالة", "فعّالة",
    "مفعل", "مفعّل", "مفعلة", "مفعّلة", "مشترك", "مشتركة"
  ].includes(s)) return "active";
  if ([
    "inactive", "disabled", "not_active", "deactivated",
    "غير نشط", "غير نشطة", "غير فعال", "غير فعّال", "غير مفعل", "غير مفعّل"
  ].includes(s)) return "inactive";
  if ([
    "suspended", "suspend", "paused", "blocked", "frozen",
    "موقوف", "موقوفة", "متوقف", "متوقفة"
  ].includes(s)) return "suspended";
  if ([
    "cancelled", "canceled", "cancel", "revoked", "terminated",
    "ملغي", "ملغى", "ملغاة"
  ].includes(s)) return "cancelled";
  if (["expired", "منتهي", "منتهية", "منتهي الصلاحية"].includes(s)) return "expired";
  if ([
    "pending_review", "awaiting_review", "manual_pending", "under_review", "in_review",
    "في انتظار مراجعة الإدارة", "في انتظار مراجعة الادارة", "قيد مراجعة الإدارة", "بانتظار مراجعة الإدارة"
  ].includes(s)) return "pending_review";
  if ([
    "rejected", "request_rejected", "manual_rejected",
    "مرفوض", "مرفوضة", "تم رفض الطلب", "تم الرفض"
  ].includes(s)) return "rejected";
  if ([
    "resubmission_required", "resubmit", "resubmit_required", "reupload_receipt", "receipt_resubmission_required",
    "مطلوب إعادة إرسال الإيصال", "مطلوب اعادة ارسال الايصال", "إعادة إرسال الإيصال", "اعادة ارسال الايصال"
  ].includes(s)) return "resubmission_required";
  if (["pending", "قيد المراجعة", "معلق", "معلقة", "بانتظار التفعيل"].includes(s)) return "pending";
  if (["pending_payment", "awaiting_payment", "في انتظار الدفع"].includes(s)) return "pending_payment";
  if (["payment_failed", "failed", "declined"].includes(s)) return "payment_failed";
  if (["unsubscribed", "none", ""].includes(s)) return "unsubscribed";
  return s;
}

export interface OfficialSubscriptionPlan {
  planId: number;
  planName: string;
  maxCaptains: number;
  captainLimit: number;
  price: number;
  durationDays: number;
}

export const OFFICIAL_SUBSCRIPTION_PLANS: Record<number, OfficialSubscriptionPlan> = {
  1: { planId: 1, planName: "الباقة الأساسية", maxCaptains: 2, captainLimit: 2, price: 400, durationDays: 30 },
  2: { planId: 2, planName: "الباقة المتوسطة", maxCaptains: 4, captainLimit: 4, price: 800, durationDays: 30 },
  3: { planId: 3, planName: "الباقة المميزة", maxCaptains: 6, captainLimit: 6, price: 1200, durationDays: 30 },
  4: { planId: 4, planName: "الباقة المتقدمة", maxCaptains: 8, captainLimit: 8, price: 1600, durationDays: 30 }
};

/**
 * Resolves a plan STRICTLY from the server-trusted catalog (ignoring any client-tampered price/maxCaptains).
 */
export function resolveTrustedServerPlan(rawPlanId: unknown): OfficialSubscriptionPlan | null {
  const numId = Number(rawPlanId);
  if (!Number.isInteger(numId)) return null;
  return OFFICIAL_SUBSCRIPTION_PLANS[numId] || null;
}

export function normalizeEgyptianMobile(rawPhone: unknown): string | null {
  const cleaned = String(rawPhone || "")
    .trim()
    .replace(/[\s\-()]/g, "");
  if (!cleaned) return null;
  let normalized = cleaned;
  if (normalized.startsWith("+20")) {
    normalized = "0" + normalized.slice(3);
  } else if (normalized.startsWith("20") && normalized.length === 12) {
    normalized = "0" + normalized.slice(2);
  } else if (normalized.startsWith("1") && normalized.length === 10) {
    normalized = "0" + normalized;
  }
  if (!/^01[0125][0-9]{8}$/.test(normalized)) {
    return null;
  }
  return normalized;
}

export function isValidMerchantReceiptStoragePath(merchantId: string, rawPath: unknown): string | null {
  const cleaned = String(rawPath || "").trim().replace(/^\/+/, "");
  if (!cleaned || cleaned.includes("..")) return null;
  const expectedPrefix = `merchants/${merchantId}/subscription_receipts/`;
  if (!cleaned.startsWith(expectedPrefix)) return null;
  const fileName = cleaned.slice(expectedPrefix.length);
  if (!/^[A-Za-z0-9_\-]{6,120}\.(jpg|jpeg|png|webp)$/i.test(fileName)) {
    return null;
  }
  return cleaned;
}

export function resolvePlanMetadata(rawPlanId: any, rawPlanName: any, rawMaxCaptains: any, rawPrice: any) {
  const planId = Number(rawPlanId || 0) || (
    String(rawPlanName || "").includes("المتقدمة") || String(rawPlanName || "").includes("8") ? 4 :
    String(rawPlanName || "").includes("المميزة") || String(rawPlanName || "").includes("6") ? 3 :
    String(rawPlanName || "").includes("المتوسطة") || String(rawPlanName || "").includes("4") ? 2 : 1
  );
  const def = OFFICIAL_SUBSCRIPTION_PLANS[planId] || OFFICIAL_SUBSCRIPTION_PLANS[1];
  const planName = String(rawPlanName || "").trim() || def.planName;
  const maxCaptains = Number(rawMaxCaptains) > 0 ? Number(rawMaxCaptains) : def.maxCaptains;
  const price = Number(rawPrice) > 0 ? Number(rawPrice) : def.price;
  return { planId, planName, maxCaptains, captainLimit: maxCaptains, price };
}

export function computeRemainingDuration(endDateMs: number | null, nowMs = Date.now()) {
  if (!endDateMs || !Number.isFinite(endDateMs) || endDateMs <= nowMs) {
    return { remainingMillis: 0, remainingDays: 0, remainingHours: 0 };
  }
  const remainingMillis = Math.max(0, endDateMs - nowMs);
  const remainingHours = Math.floor(remainingMillis / (60 * 60 * 1000));
  const remainingDays = Math.floor(remainingMillis / (24 * 60 * 60 * 1000));
  return { remainingMillis, remainingDays, remainingHours };
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

async function writeOperationLog(entry: Record<string, any>): Promise<void> {
  try {
    await db.collection("operation_logs").add({
      ...entry,
      createdAt: admin.firestore.FieldValue.serverTimestamp()
    });
  } catch (err: any) {
    console.warn(`[OperationLog] Failed to write log: ${err?.message || err}`);
  }
}

async function sendMerchantSubscriptionNotification(
  merchantId: string,
  title: string,
  body: string,
  type: string,
  extraData: Record<string, string> = {}
): Promise<boolean> {
  try {
    const merchDoc = await db.collection("merchants").doc(merchantId).get();
    const merchData = merchDoc.data() || {};
    const fcmToken = String(merchData.fcmToken || "").trim();
    if (!fcmToken) {
      return false;
    }

    await admin.messaging().send({
      token: fcmToken,
      notification: {
        title,
        body
      },
      data: {
        type,
        merchantId,
        ...extraData
      },
      android: {
        priority: "high",
        notification: {
          channelId: "subscription_sync_channel",
          sound: "default"
        }
      }
    });
    return true;
  } catch (err: any) {
    console.warn(`[FCM] Subscription push failed for ${merchantId}: ${err?.message || err}`);
    return false;
  }
}

export function evaluateDualDocSubscription(
  subDataInput: Record<string, any> | null | undefined,
  merchantDataInput: Record<string, any> | null | undefined,
  activeCaptainsCount = 0,
  now = Date.now(),
  requestedCaptains = 1
): VerifySubscriptionResult {
  const merchantData = merchantDataInput || null;
  if (!isMerchantAccountDoc(merchantData)) {
    return {
      isValid: false,
      isActive: false,
      isSubscribed: false,
      status: "not_found",
      planId: 0,
      planName: "غير مشترك",
      maxCaptains: 0,
      captainLimit: 0,
      price: 0,
      activeCaptainsCount,
      remainingCaptains: 0,
      subscriptionVersion: 0,
      errorMessage: "تعذر العثور على بيانات حساب التاجر."
    };
  }
  const merchantAccountStatus = String(merchantData!.status || "").toLowerCase().trim();
  const isLegacyExplicitlyApproved = merchantAccountStatus === "" && merchantData!.isApproved === true;
  if (
    merchantAccountStatus !== "approved" &&
    merchantAccountStatus !== "active" &&
    !isLegacyExplicitlyApproved
  ) {
    return {
      isValid: false,
      isActive: false,
      isSubscribed: false,
      status: "merchant_pending",
      planId: 0,
      planName: "بانتظار الاعتماد",
      maxCaptains: 0,
      captainLimit: 0,
      price: 0,
      activeCaptainsCount,
      remainingCaptains: 0,
      subscriptionVersion: Number(merchantData!.subscriptionVersion || 0),
      errorMessage: "حساب التاجر في انتظار موافقة الإدارة."
    };
  }

  const hasSubDoc = Boolean(subDataInput && Object.keys(subDataInput).length > 0);
  const subData = subDataInput || {};
  const subStatus = hasSubDoc
    ? normalizeSubscriptionStatus(subData.status || subData.subscriptionStatus || subData.planStatus)
    : "unsubscribed";
  const merchSubStatus = normalizeSubscriptionStatus(merchantData!.subscriptionStatus || merchantData!.planStatus);

  // Use dedicated subscriptionUpdatedAt whenever present so profile/login/FCM updatedAt writes on
  // merchants/{uid} NEVER override an active subscriptions/{uid} document.
  const subUpdatedMs = parseMillis(subData.subscriptionUpdatedAt || subData.updatedAt || subData.activatedAt) || 0;
  const merchLastLoginMs = parseMillis(merchantData!.lastLoginAt) || 0;
  const merchRawUpdatedMs = parseMillis(merchantData!.updatedAt) || 0;
  const merchSubUpdatedExplicit = parseMillis(merchantData!.subscriptionUpdatedAt);
  const isLoginOnlyBump = merchSubUpdatedExplicit === null && merchLastLoginMs > 0 && Math.abs(merchRawUpdatedMs - merchLastLoginMs) <= 10_000;
  const merchUpdatedMs = merchSubUpdatedExplicit ?? (isLoginOnlyBump ? 0 : merchRawUpdatedMs);

  const subVer = Number(subData.subscriptionVersion || 0);
  const merchVer = Number(merchantData!.subscriptionVersion || 0);
  const subVersion = Math.max(subVer, merchVer, 1);

  let effectiveDoc: Record<string, any> = hasSubDoc ? subData : merchantData!;
  let effectiveStatus = hasSubDoc ? subStatus : merchSubStatus;

  if (hasSubDoc && merchSubStatus !== "unsubscribed" && merchSubStatus !== subStatus) {
    if (merchVer > subVer || (merchVer === subVer && merchUpdatedMs > subUpdatedMs)) {
      effectiveDoc = { ...subData, ...merchantData! };
      effectiveStatus = merchSubStatus;
    } else if (
      merchVer === subVer &&
      subUpdatedMs === 0 &&
      merchUpdatedMs > 0 &&
      ["suspended", "inactive", "cancelled", "expired", "pending", "pending_review", "rejected", "resubmission_required"].includes(merchSubStatus)
    ) {
      effectiveDoc = { ...subData, ...merchantData! };
      effectiveStatus = merchSubStatus;
    }
  } else if (hasSubDoc && subStatus === "unsubscribed" && merchSubStatus !== "unsubscribed") {
    effectiveDoc = { ...subData, ...merchantData! };
    effectiveStatus = merchSubStatus;
  }

  const rawLimitCandidate =
    Number(effectiveDoc.captainLimit || 0) ||
    Number(effectiveDoc.maxCaptains || 0) ||
    Number(subData.captainLimit || 0) ||
    Number(subData.maxCaptains || 0) ||
    Number(merchantData!.captainLimit || 0) ||
    Number(merchantData!.maxCaptains || 0);
  const rawPlanId = effectiveDoc.planId ?? subData.planId ?? merchantData!.planId;
  const rawPlanName = effectiveDoc.planName ?? subData.planName ?? merchantData!.planName;
  const rawPrice = effectiveDoc.price ?? subData.price ?? merchantData!.price;
  const meta = resolvePlanMetadata(rawPlanId, rawPlanName, rawLimitCandidate, rawPrice);

  const startDateMs = parseMillis(
    effectiveDoc.activatedAt ||
      effectiveDoc.startDate ||
      effectiveDoc.subscriptionStartDate ||
      subData.activatedAt ||
      subData.startDate ||
      merchantData!.subscriptionStartDate
  );
  const approvedAtMs = parseMillis(
    effectiveDoc.approvedAt || subData.approvedAt || merchantData!.approvedAt || startDateMs
  );
  const endDateMs = parseMillis(
    effectiveDoc.expiresAt ||
      effectiveDoc.endDate ||
      effectiveDoc.subscriptionEndDate ||
      effectiveDoc.expiryDate ||
      subData.expiresAt ||
      subData.endDate ||
      merchantData!.subscriptionEndDate
  );
  const remaining = computeRemainingDuration(endDateMs, now);
  const rejectionReason = String(
    effectiveDoc.rejectionReason || subData.rejectionReason || merchantData!.rejectionReason || ""
  ).trim();
  const resubmissionReason = String(
    effectiveDoc.resubmissionReason || subData.resubmissionReason || merchantData!.resubmissionReason || ""
  ).trim();
  const reason = String(
    effectiveDoc.suspensionReason || effectiveDoc.cancellationReason || resubmissionReason || rejectionReason || merchantData!.suspensionReason || ""
  ).trim();
  const updatedBy = String(effectiveDoc.updatedBy || effectiveDoc.updatedByAdminUid || merchantData!.updatedBy || "").trim() || undefined;
  const paymentMethod = String(effectiveDoc.paymentMethod || subData.paymentMethod || merchantData!.paymentMethod || "").trim() || undefined;
  const pendingRequestId = String(effectiveDoc.pendingRequestId || subData.pendingRequestId || merchantData!.pendingRequestId || "").trim() || undefined;
  const paymentStatus = String(effectiveDoc.paymentStatus || merchantData!.paymentStatus || "paid").toLowerCase().trim();

  // Only treat boolean flags as disabling an "active" doc when Admin explicitly turned BOTH flags off
  // or explicitly set isActive === false without isSubscribed === true. Stale signup isSubscribed:false
  // alone must NOT invalidate an Admin-activated status:"active" subscription.
  const explicitDeactivatedFlag =
    effectiveDoc.isActive === false && effectiveDoc.isSubscribed !== true;

  if (effectiveStatus === "suspended" || effectiveStatus === "inactive") {
    return {
      isValid: false,
      isActive: false,
      isSubscribed: false,
      status: effectiveStatus,
      planId: meta.planId,
      planName: meta.planName,
      maxCaptains: 0,
      captainLimit: 0,
      price: meta.price,
      startDate: startDateMs,
      activatedAt: startDateMs,
      endDate: endDateMs,
      expiresAt: endDateMs,
      remainingMillis: 0,
      remainingDays: 0,
      remainingHours: 0,
      activeCaptainsCount,
      remainingCaptains: 0,
      subscriptionVersion: subVersion,
      updatedBy,
      reason,
      errorMessage: reason
        ? `تم إيقاف اشتراكك من قبل الإدارة (${reason}). يرجى التواصل مع الإدارة أو تجديد الاشتراك.`
        : "تم إيقاف اشتراكك من قبل الإدارة. يرجى التواصل مع الإدارة أو تجديد الاشتراك."
    };
  }
  if (effectiveStatus === "cancelled") {
    return {
      isValid: false,
      isActive: false,
      isSubscribed: false,
      status: "cancelled",
      planId: meta.planId,
      planName: meta.planName,
      maxCaptains: 0,
      captainLimit: 0,
      price: meta.price,
      startDate: startDateMs,
      activatedAt: startDateMs,
      endDate: endDateMs,
      expiresAt: endDateMs,
      remainingMillis: 0,
      remainingDays: 0,
      remainingHours: 0,
      activeCaptainsCount,
      remainingCaptains: 0,
      subscriptionVersion: subVersion,
      updatedBy,
      reason,
      errorMessage: reason
        ? `تم إلغاء اشتراكك (${reason}). يرجى التواصل مع الإدارة أو الاشتراك في باقة جديدة.`
        : "تم إلغاء اشتراكك. يرجى التواصل مع الإدارة أو الاشتراك في باقة جديدة."
    };
  }
  if (effectiveStatus === "pending_review" || effectiveStatus === "pending") {
    return {
      isValid: false,
      isActive: false,
      isSubscribed: false,
      status: effectiveStatus === "pending_review" ? "pending_review" : "pending",
      planId: meta.planId,
      planName: meta.planName,
      maxCaptains: 0,
      captainLimit: 0,
      price: meta.price,
      startDate: startDateMs,
      activatedAt: startDateMs,
      endDate: endDateMs,
      expiresAt: endDateMs,
      remainingMillis: 0,
      remainingDays: 0,
      remainingHours: 0,
      activeCaptainsCount,
      remainingCaptains: 0,
      subscriptionVersion: subVersion,
      updatedBy,
      paymentMethod,
      pendingRequestId,
      errorMessage: "طلب الاشتراك في انتظار مراجعة الإدارة ولم يتم تفعيله بعد."
    };
  }
  if (effectiveStatus === "rejected") {
    const rejectText = rejectionReason || reason || "تم رفض طلب الاشتراك من الإدارة.";
    return {
      isValid: false,
      isActive: false,
      isSubscribed: false,
      status: "rejected",
      planId: meta.planId,
      planName: meta.planName,
      maxCaptains: 0,
      captainLimit: 0,
      price: meta.price,
      startDate: startDateMs,
      activatedAt: startDateMs,
      endDate: endDateMs,
      expiresAt: endDateMs,
      remainingMillis: 0,
      remainingDays: 0,
      remainingHours: 0,
      activeCaptainsCount,
      remainingCaptains: 0,
      subscriptionVersion: subVersion,
      updatedBy,
      paymentMethod,
      reason: rejectText,
      rejectionReason: rejectText,
      errorMessage: `تم رفض طلب الاشتراك (${rejectText}). يمكنك مراجعة البيانات وإعادة الإرسال.`
    };
  }
  if (effectiveStatus === "resubmission_required") {
    const resubmitText = resubmissionReason || reason || "يرجى إعادة إرسال صورة واضحة لإيصال التحويل.";
    return {
      isValid: false,
      isActive: false,
      isSubscribed: false,
      status: "resubmission_required",
      planId: meta.planId,
      planName: meta.planName,
      maxCaptains: 0,
      captainLimit: 0,
      price: meta.price,
      startDate: startDateMs,
      activatedAt: startDateMs,
      endDate: endDateMs,
      expiresAt: endDateMs,
      remainingMillis: 0,
      remainingDays: 0,
      remainingHours: 0,
      activeCaptainsCount,
      remainingCaptains: 0,
      subscriptionVersion: subVersion,
      updatedBy,
      paymentMethod,
      pendingRequestId,
      reason: resubmitText,
      resubmissionReason: resubmitText,
      errorMessage: `مطلوب إعادة إرسال الإيصال (${resubmitText}). يرجى رفع صورة واضحة للإيصال وإعادة الإرسال.`
    };
  }
  if (
    effectiveStatus === "payment_failed" ||
    effectiveStatus === "pending_payment" ||
    (effectiveStatus !== "active" && ["failed", "unpaid", "pending"].includes(paymentStatus))
  ) {
    return {
      isValid: false,
      isActive: false,
      isSubscribed: false,
      status: effectiveStatus === "pending_payment" ? "pending_payment" : "payment_failed",
      planId: meta.planId,
      planName: meta.planName,
      maxCaptains: 0,
      captainLimit: 0,
      price: meta.price,
      activeCaptainsCount,
      remainingCaptains: 0,
      subscriptionVersion: subVersion,
      updatedBy,
      errorMessage: "لم يتم تأكيد الدفع لهذا الاشتراك."
    };
  }
  if (effectiveStatus === "expired" || (endDateMs !== null && now >= endDateMs)) {
    return {
      isValid: false,
      isActive: false,
      isSubscribed: false,
      status: "expired",
      planId: meta.planId,
      planName: meta.planName,
      maxCaptains: meta.maxCaptains,
      captainLimit: meta.captainLimit,
      price: meta.price,
      startDate: startDateMs,
      activatedAt: startDateMs,
      endDate: endDateMs,
      expiresAt: endDateMs,
      remainingMillis: 0,
      remainingDays: 0,
      remainingHours: 0,
      activeCaptainsCount,
      remainingCaptains: 0,
      subscriptionVersion: subVersion,
      updatedBy,
      errorMessage: "انتهت فترة اشتراكك، يرجى تجديد الاشتراك."
    };
  }
  if (effectiveStatus !== "active" || explicitDeactivatedFlag || meta.captainLimit <= 0) {
    return {
      isValid: false,
      isActive: false,
      isSubscribed: false,
      status: effectiveStatus || "unsubscribed",
      planId: 0,
      planName: "غير مشترك",
      maxCaptains: 0,
      captainLimit: 0,
      price: 0,
      activeCaptainsCount,
      remainingCaptains: 0,
      subscriptionVersion: subVersion,
      updatedBy,
      errorMessage:
        !hasSubDoc && merchSubStatus === "unsubscribed"
          ? "لم يتم العثور على اشتراك التاجر."
          : "لا يوجد اشتراك نشط لهذا التاجر."
    };
  }

  const remainingCaptains = Math.max(0, meta.captainLimit - activeCaptainsCount);
  const safeRequested = Math.max(1, Number(requestedCaptains) || 1);
  if (activeCaptainsCount + safeRequested > meta.captainLimit && meta.captainLimit > 0) {
    return {
      isValid: false,
      isActive: true,
      isSubscribed: true,
      status: "active",
      planId: meta.planId,
      planName: meta.planName,
      maxCaptains: meta.maxCaptains,
      captainLimit: meta.captainLimit,
      price: meta.price,
      startDate: startDateMs,
      activatedAt: startDateMs,
      endDate: endDateMs,
      expiresAt: endDateMs,
      remainingMillis: remaining.remainingMillis,
      remainingDays: remaining.remainingDays,
      remainingHours: remaining.remainingHours,
      activeCaptainsCount,
      remainingCaptains,
      subscriptionVersion: subVersion,
      updatedBy,
      errorMessage: activeCaptainsCount >= meta.captainLimit
        ? `وصلت إلى الحد الأقصى لعدد الكباتن المتزامنين في باقتك (${meta.captainLimit}).`
        : `المتبقي لك ${remainingCaptains} كابتن فقط من أصل ${meta.captainLimit} في باقتك الحالية.`
    };
  }

  return {
    isValid: true,
    isActive: true,
    isSubscribed: true,
    status: "active",
    planId: meta.planId,
    planName: meta.planName,
    maxCaptains: meta.maxCaptains,
    captainLimit: meta.captainLimit,
    price: meta.price,
    startDate: startDateMs,
    activatedAt: startDateMs,
    endDate: endDateMs,
    expiresAt: endDateMs,
    remainingMillis: remaining.remainingMillis,
    remainingDays: remaining.remainingDays,
    remainingHours: remaining.remainingHours,
    activeCaptainsCount,
    remainingCaptains,
    subscriptionVersion: subVersion,
    updatedBy,
    paymentMethod,
    approvedAt: approvedAtMs
  };
}

function isOrderDocActiveForCaptainLimit(orderData: Record<string, any> | undefined): boolean {
  if (!orderData) return false;
  const raw = String(orderData.status || "").trim().toLowerCase();
  if (!raw) return false;
  return !isTerminalOrderStatusForCapacity(raw);
}

/**
 * Resolves the authoritative subscription document for a merchant:
 * 1. Canonical path: `subscriptions/{merchantId}`
 * 2. Fallback: query `subscriptions` where `merchantId` / `restaurantId` / `userId` == merchantId
 *    (in case Admin Dashboard created a doc under an auto-generated ID).
 */
async function findAuthoritativeSubscriptionDoc(
  merchantId: string,
  targetDb: FirebaseFirestore.Firestore = db
): Promise<Record<string, any> | null> {
  const canonicalDoc = await targetDb.collection("subscriptions").doc(merchantId).get();
  if (canonicalDoc.exists) {
    return canonicalDoc.data() || {};
  }

  const [byMerchantId, byRestaurantId, byUserId] = await Promise.all([
    targetDb.collection("subscriptions").where("merchantId", "==", merchantId).get(),
    targetDb.collection("subscriptions").where("restaurantId", "==", merchantId).get(),
    targetDb.collection("subscriptions").where("userId", "==", merchantId).get()
  ]);

  const candidates = new Map<string, Record<string, any>>();
  [byMerchantId, byRestaurantId, byUserId].forEach((snap) => {
    snap.docs.forEach((doc) => {
      candidates.set(doc.id, doc.data() || {});
    });
  });

  if (candidates.size === 0) return null;

  let best: Record<string, any> | null = null;
  let bestScore = -1;
  candidates.forEach((docData) => {
    const ver = Number(docData.subscriptionVersion || 0);
    const ts = parseMillis(docData.subscriptionUpdatedAt || docData.updatedAt || docData.activatedAt || docData.createdAt) || 0;
    const score = ver * 1_000_000_000_000 + ts;
    if (score > bestScore) {
      bestScore = score;
      best = docData;
    }
  });
  return best;
}

/**
 * Server-side subscription validation against BOTH subscriptions/{merchantId} and merchants/{merchantId}.
 * Guarantees suspended / inactive / cancelled / expired / pending / failed states are never bypassed.
 */
export async function verifyMerchantSubscription(
  merchantId: string,
  requestedCaptains = 1,
  targetDb: FirebaseFirestore.Firestore = db,
  now = Date.now()
): Promise<VerifySubscriptionResult> {
  const [subData, merchantDoc, ordersByMerchant, ordersByLegacy, ordersByUser] = await Promise.all([
    findAuthoritativeSubscriptionDoc(merchantId, targetDb),
    targetDb.collection("merchants").doc(merchantId).get(),
    targetDb.collection("orders").where("merchantId", "==", merchantId).get(),
    targetDb.collection("orders").where("restaurantId", "==", merchantId).get(),
    targetDb.collection("orders").where("userId", "==", merchantId).get()
  ]);

  const activeOrders = new Map<string, FirebaseFirestore.DocumentData>();
  [ordersByMerchant, ordersByLegacy, ordersByUser].forEach((snap) => {
    snap.docs.forEach((doc) => {
      const data = doc.data();
      if (isOrderDocActiveForCaptainLimit(data)) {
        activeOrders.set(doc.id, data);
      }
    });
  });

  return evaluateDualDocSubscription(
    subData,
    merchantDoc.exists ? (merchantDoc.data() || {}) : null,
    activeOrders.size,
    now,
    requestedCaptains
  );
}

/**
 * Shared atomic transactional core for `adminUpdateMerchantSubscription`.
 * Keeps merchant account approval (`merchants/{uid}.status`) strictly separate from subscription
 * activation unless the Admin explicitly passes `approveMerchant: true`.
 */
export async function applyAdminSubscriptionUpdate(
  targetDb: FirebaseFirestore.Firestore,
  adminUid: string,
  data: Record<string, any>
) {
  const merchantId = String(data?.merchantId || "").trim();
  const action = String(data?.action || "").toLowerCase().trim();
  if (!merchantId) {
    throw new functions.https.HttpsError("invalid-argument", "معرف التاجر merchantId مطلوب.");
  }
  const supportedActions = ["activate", "reactivate", "suspend", "deactivate", "cancel", "change_plan", "extend", "set_pending", "reject", "resubmission_required", "request_resubmission"];
  if (!supportedActions.includes(action)) {
    throw new functions.https.HttpsError(
      "invalid-argument",
      `الإجراء غير مدعوم. الإجراءات المتاحة: ${supportedActions.join(", ")}`
    );
  }

  const reason = String(data?.reason || data?.resubmissionReason || data?.rejectionReason || "").trim();
  const explicitRequestId = String(data?.requestId || "").trim();
  const durationDays = Math.max(1, Math.min(3650, Number(data?.durationDays || 30)));
  // Merchant approval is strictly independent from subscription activation unless explicitly requested by Admin.
  const explicitApproveMerchant = data?.approveMerchant === true;

  const result = await targetDb.runTransaction(async (transaction) => {
    const merchantRef = targetDb.collection("merchants").doc(merchantId);
    const subRef = targetDb.collection("subscriptions").doc(merchantId);
    const [merchantDoc, subDoc] = await Promise.all([
      transaction.get(merchantRef),
      transaction.get(subRef)
    ]);

    if (!merchantDoc.exists || !isMerchantAccountDoc(merchantDoc.data())) {
      throw new functions.https.HttpsError("not-found", "حساب التاجر غير موجود في مجموعة merchants.");
    }

    const existingSub = subDoc.data() || {};
    const existingMerch = merchantDoc.data() || {};
    const previousStatus = normalizeSubscriptionStatus(existingSub.status || existingMerch.subscriptionStatus);
    const prevVersion = Math.max(
      Number(existingSub.subscriptionVersion || 0),
      Number(existingMerch.subscriptionVersion || 0)
    );
    const nextVersion = prevVersion + 1;
    const existingMerchantStatus = String(existingMerch.status || "pending").toLowerCase().trim() || "pending";

    const meta = resolvePlanMetadata(
      data?.planId ?? existingSub.planId ?? existingMerch.planId,
      data?.planName ?? existingSub.planName ?? existingMerch.planName,
      data?.captainLimit ?? data?.maxCaptains ?? existingSub.captainLimit ?? existingSub.maxCaptains ?? existingMerch.maxCaptains,
      data?.price ?? existingSub.price ?? existingMerch.price
    );

    if (action === "activate" || action === "reactivate" || action === "change_plan" || action === "extend") {
      const nowMs = Date.now();
      const existingStartMs = parseMillis(existingSub.activatedAt || existingSub.startDate || existingMerch.subscriptionStartDate);
      const existingEndMs = parseMillis(existingSub.expiresAt || existingSub.endDate || existingMerch.subscriptionEndDate);

      let startMs = typeof data?.startDateMs === "number" ? (parseMillis(data.startDateMs) || nowMs) : nowMs;
      let endMs = typeof data?.endDateMs === "number"
        ? (parseMillis(data.endDateMs) || (startMs + durationDays * 24 * 60 * 60 * 1000))
        : startMs + durationDays * 24 * 60 * 60 * 1000;

      if (action === "extend") {
        const baseEnd = existingEndMs && existingEndMs > nowMs ? existingEndMs : nowMs;
        startMs = existingStartMs || nowMs;
        endMs = typeof data?.endDateMs === "number" ? (parseMillis(data.endDateMs) || (baseEnd + durationDays * 24 * 60 * 60 * 1000)) : baseEnd + durationDays * 24 * 60 * 60 * 1000;
      } else if (action === "change_plan" && typeof data?.endDateMs !== "number" && existingEndMs && existingEndMs > nowMs) {
        startMs = existingStartMs || nowMs;
        endMs = data?.durationDays ? nowMs + durationDays * 24 * 60 * 60 * 1000 : existingEndMs;
      }

      const startTs = admin.firestore.Timestamp.fromMillis(startMs);
      const endTs = admin.firestore.Timestamp.fromMillis(endMs);
      const willApproveMerchant = explicitApproveMerchant && existingMerchantStatus !== "approved" && existingMerchantStatus !== "active";
      const resultingMerchantStatus = willApproveMerchant ? "approved" : existingMerchantStatus;
      const linkedRequestId = explicitRequestId || String(existingSub.pendingRequestId || existingMerch.pendingRequestId || "").trim();

      transaction.set(subRef, {
        merchantId,
        status: "active",
        subscriptionStatus: "active",
        isActive: true,
        isSubscribed: true,
        paymentStatus: "paid",
        planId: meta.planId,
        planName: meta.planName,
        maxCaptains: meta.maxCaptains,
        captainLimit: meta.captainLimit,
        price: meta.price,
        startDate: startTs,
        activatedAt: startTs,
        approvedAt: startTs,
        approvedBy: adminUid,
        endDate: endTs,
        expiresAt: endTs,
        suspensionReason: admin.firestore.FieldValue.delete(),
        cancellationReason: admin.firestore.FieldValue.delete(),
        rejectionReason: admin.firestore.FieldValue.delete(),
        resubmissionReason: admin.firestore.FieldValue.delete(),
        pendingRequestId: admin.firestore.FieldValue.delete(),
        expiryNotifiedAt: admin.firestore.FieldValue.delete(),
        updatedBy: adminUid,
        updatedByAdminUid: adminUid,
        subscriptionVersion: nextVersion,
        lastAuditLoggedVersion: nextVersion,
        subscriptionUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });

      const merchantUpdate: Record<string, any> = {
        accountType: existingMerch.accountType || "merchant",
        role: existingMerch.role || "merchant",
        subscriptionStatus: "active",
        isActive: true,
        isSubscribed: true,
        paymentStatus: "paid",
        planId: meta.planId,
        planName: meta.planName,
        maxCaptains: meta.maxCaptains,
        captainLimit: meta.captainLimit,
        price: meta.price,
        subscriptionStartDate: startTs,
        activatedAt: startTs,
        subscriptionEndDate: endTs,
        expiresAt: endTs,
        suspensionReason: admin.firestore.FieldValue.delete(),
        rejectionReason: admin.firestore.FieldValue.delete(),
        resubmissionReason: admin.firestore.FieldValue.delete(),
        pendingRequestId: admin.firestore.FieldValue.delete(),
        updatedBy: adminUid,
        subscriptionVersion: nextVersion,
        subscriptionUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      };
      if (willApproveMerchant) {
        merchantUpdate.status = "approved";
        merchantUpdate.isApproved = true;
        merchantUpdate.approvedBy = adminUid;
        merchantUpdate.approvedAt = admin.firestore.FieldValue.serverTimestamp();
      }
      transaction.set(merchantRef, merchantUpdate, { merge: true });

      if (linkedRequestId) {
        const reqRef = targetDb.collection("subscription_requests").doc(linkedRequestId);
        transaction.set(reqRef, {
          status: "approved",
          reviewedBy: adminUid,
          reviewedAt: admin.firestore.FieldValue.serverTimestamp(),
          approvedAt: startTs,
          startDate: startTs,
          endDate: endTs,
          rejectionReason: admin.firestore.FieldValue.delete(),
          updatedAt: admin.firestore.FieldValue.serverTimestamp()
        }, { merge: true });
      }

      return {
        previousStatus,
        status: "active",
        subscriptionVersion: nextVersion,
        startDateMs: startMs,
        endDateMs: endMs,
        requestId: linkedRequestId || null,
        merchantStatus: resultingMerchantStatus,
        merchantApproved: resultingMerchantStatus === "approved" || resultingMerchantStatus === "active",
        merchantApprovedExplicitly: willApproveMerchant,
        ...meta
      };
    }

    if (action === "reject") {
      const rejectReason = reason || "تم رفض طلب الاشتراك من الإدارة. يرجى مراجعة إيصال التحويل وإعادة المحاولة.";
      const linkedRequestId = explicitRequestId || String(existingSub.pendingRequestId || existingMerch.pendingRequestId || "").trim();

      transaction.set(subRef, {
        merchantId,
        status: "rejected",
        subscriptionStatus: "rejected",
        isActive: false,
        isSubscribed: false,
        paymentStatus: "rejected",
        planId: meta.planId,
        planName: meta.planName,
        maxCaptains: 0,
        captainLimit: 0,
        price: meta.price,
        rejectionReason: rejectReason,
        suspensionReason: admin.firestore.FieldValue.delete(),
        pendingRequestId: admin.firestore.FieldValue.delete(),
        rejectedAt: admin.firestore.FieldValue.serverTimestamp(),
        rejectedBy: adminUid,
        updatedBy: adminUid,
        updatedByAdminUid: adminUid,
        subscriptionVersion: nextVersion,
        lastAuditLoggedVersion: nextVersion,
        subscriptionUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });

      transaction.set(merchantRef, {
        subscriptionStatus: "rejected",
        isActive: false,
        isSubscribed: false,
        paymentStatus: "rejected",
        planId: meta.planId,
        planName: meta.planName,
        maxCaptains: 0,
        captainLimit: 0,
        price: meta.price,
        rejectionReason: rejectReason,
        pendingRequestId: admin.firestore.FieldValue.delete(),
        updatedBy: adminUid,
        subscriptionVersion: nextVersion,
        subscriptionUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });

      if (linkedRequestId) {
        const reqRef = targetDb.collection("subscription_requests").doc(linkedRequestId);
        transaction.set(reqRef, {
          status: "rejected",
          rejectionReason: rejectReason,
          reviewedBy: adminUid,
          reviewedAt: admin.firestore.FieldValue.serverTimestamp(),
          updatedAt: admin.firestore.FieldValue.serverTimestamp()
        }, { merge: true });
      }

      return {
        previousStatus,
        status: "rejected",
        subscriptionVersion: nextVersion,
        reason: rejectReason,
        rejectionReason: rejectReason,
        requestId: linkedRequestId || null,
        merchantStatus: existingMerchantStatus,
        merchantApproved: existingMerchantStatus === "approved" || existingMerchantStatus === "active",
        merchantApprovedExplicitly: false,
        ...meta
      };
    }

    if (action === "resubmission_required" || action === "request_resubmission") {
      const resubmitReason = reason || "يرجى إعادة إرسال صورة واضحة لإيصال تحويل فودافون كاش.";
      const linkedRequestId = explicitRequestId || String(existingSub.pendingRequestId || existingMerch.pendingRequestId || "").trim();

      transaction.set(subRef, {
        merchantId,
        status: "resubmission_required",
        subscriptionStatus: "resubmission_required",
        isActive: false,
        isSubscribed: false,
        paymentStatus: "resubmission_required",
        planId: meta.planId,
        planName: meta.planName,
        maxCaptains: 0,
        captainLimit: 0,
        price: meta.price,
        resubmissionReason: resubmitReason,
        rejectionReason: admin.firestore.FieldValue.delete(),
        suspensionReason: admin.firestore.FieldValue.delete(),
        pendingRequestId: admin.firestore.FieldValue.delete(),
        resubmissionRequestedAt: admin.firestore.FieldValue.serverTimestamp(),
        resubmissionRequestedBy: adminUid,
        updatedBy: adminUid,
        updatedByAdminUid: adminUid,
        subscriptionVersion: nextVersion,
        lastAuditLoggedVersion: nextVersion,
        subscriptionUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });

      transaction.set(merchantRef, {
        subscriptionStatus: "resubmission_required",
        isActive: false,
        isSubscribed: false,
        paymentStatus: "resubmission_required",
        planId: meta.planId,
        planName: meta.planName,
        maxCaptains: 0,
        captainLimit: 0,
        price: meta.price,
        resubmissionReason: resubmitReason,
        rejectionReason: admin.firestore.FieldValue.delete(),
        pendingRequestId: admin.firestore.FieldValue.delete(),
        updatedBy: adminUid,
        subscriptionVersion: nextVersion,
        subscriptionUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });

      if (linkedRequestId) {
        const reqRef = targetDb.collection("subscription_requests").doc(linkedRequestId);
        transaction.set(reqRef, {
          status: "resubmission_required",
          resubmissionReason: resubmitReason,
          reviewedBy: adminUid,
          reviewedAt: admin.firestore.FieldValue.serverTimestamp(),
          updatedAt: admin.firestore.FieldValue.serverTimestamp()
        }, { merge: true });
      }

      return {
        previousStatus,
        status: "resubmission_required",
        subscriptionVersion: nextVersion,
        reason: resubmitReason,
        resubmissionReason: resubmitReason,
        requestId: linkedRequestId || null,
        merchantStatus: existingMerchantStatus,
        merchantApproved: existingMerchantStatus === "approved" || existingMerchantStatus === "active",
        merchantApprovedExplicitly: false,
        ...meta
      };
    }

    if (action === "suspend" || action === "deactivate") {
      const targetStatus = action === "deactivate" ? "inactive" : "suspended";
      const finalReason = reason || "تم إيقاف الاشتراك من قبل الإدارة";
      transaction.set(subRef, {
        merchantId,
        status: targetStatus,
        subscriptionStatus: targetStatus,
        isActive: false,
        isSubscribed: false,
        suspensionReason: finalReason,
        suspendedAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedBy: adminUid,
        updatedByAdminUid: adminUid,
        subscriptionVersion: nextVersion,
        lastAuditLoggedVersion: nextVersion,
        subscriptionUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });

      transaction.set(merchantRef, {
        subscriptionStatus: targetStatus,
        isActive: false,
        isSubscribed: false,
        suspensionReason: finalReason,
        updatedBy: adminUid,
        subscriptionVersion: nextVersion,
        subscriptionUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });

      return {
        previousStatus,
        status: targetStatus,
        subscriptionVersion: nextVersion,
        reason: finalReason,
        merchantStatus: existingMerchantStatus,
        merchantApproved: existingMerchantStatus === "approved" || existingMerchantStatus === "active",
        merchantApprovedExplicitly: false,
        ...meta
      };
    }

    if (action === "set_pending") {
      transaction.set(subRef, {
        merchantId,
        status: "pending",
        subscriptionStatus: "pending",
        isActive: false,
        isSubscribed: false,
        planId: meta.planId,
        planName: meta.planName,
        maxCaptains: 0,
        captainLimit: 0,
        price: meta.price,
        updatedBy: adminUid,
        updatedByAdminUid: adminUid,
        subscriptionVersion: nextVersion,
        lastAuditLoggedVersion: nextVersion,
        subscriptionUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });

      transaction.set(merchantRef, {
        subscriptionStatus: "pending",
        isActive: false,
        isSubscribed: false,
        planId: meta.planId,
        planName: meta.planName,
        maxCaptains: 0,
        captainLimit: 0,
        updatedBy: adminUid,
        subscriptionVersion: nextVersion,
        subscriptionUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      }, { merge: true });

      return {
        previousStatus,
        status: "pending",
        subscriptionVersion: nextVersion,
        merchantStatus: existingMerchantStatus,
        merchantApproved: existingMerchantStatus === "approved" || existingMerchantStatus === "active",
        merchantApprovedExplicitly: false,
        ...meta
      };
    }

    // action === "cancel"
    const cancelReason = reason || "تم إلغاء الاشتراك من قبل الإدارة";
    transaction.set(subRef, {
      merchantId,
      status: "cancelled",
      subscriptionStatus: "cancelled",
      isActive: false,
      isSubscribed: false,
      maxCaptains: 0,
      captainLimit: 0,
      cancellationReason: cancelReason,
      suspensionReason: cancelReason,
      cancelledAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedBy: adminUid,
      updatedByAdminUid: adminUid,
      subscriptionVersion: nextVersion,
      lastAuditLoggedVersion: nextVersion,
      subscriptionUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });

    transaction.set(merchantRef, {
      subscriptionStatus: "cancelled",
      isActive: false,
      isSubscribed: false,
      maxCaptains: 0,
      captainLimit: 0,
      suspensionReason: cancelReason,
      updatedBy: adminUid,
      subscriptionVersion: nextVersion,
      subscriptionUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    }, { merge: true });

    return {
      previousStatus,
      status: "cancelled",
      subscriptionVersion: nextVersion,
      reason: cancelReason,
      merchantStatus: existingMerchantStatus,
      merchantApproved: existingMerchantStatus === "approved" || existingMerchantStatus === "active",
      merchantApprovedExplicitly: false,
      ...meta
    };
  });

  return { success: true, merchantId, action, reason: reason || null, ...result };
}

/**
 * Trusted Admin Callable: Atomically activates, reactivates, suspends, deactivates, cancels, changes plan, or extends a merchant's subscription.
 * Writes canonical `subscriptions/{merchantId}` + mirrors summary fields onto `merchants/{merchantId}` + logs to `operation_logs`.
 * Merchant account approval (`merchants/{uid}.status`) remains independent unless `approveMerchant: true` is explicitly supplied.
 */
export const adminUpdateMerchantSubscription = functions.https.onCall(async (data, context) => {
  const authorizedAdmin = await isAdminCaller(context);
  if (!authorizedAdmin || !context.auth) {
    throw new functions.https.HttpsError("permission-denied", "هذه العملية مسموحة لمسؤولي الإدارة فقط.");
  }

  const adminUid = context.auth.uid;
  const result = await applyAdminSubscriptionUpdate(db, adminUid, data || {});

  await writeOperationLog({
    type: "admin_subscription_update",
    action: result.action,
    merchantId: result.merchantId,
    actorUid: adminUid,
    actorRole: "admin",
    previousStatus: result.previousStatus,
    newStatus: result.status,
    planId: result.planId,
    planName: result.planName,
    captainLimit: result.captainLimit,
    reason: result.reason || null,
    subscriptionVersion: result.subscriptionVersion,
    merchantStatus: result.merchantStatus,
    merchantApprovedExplicitly: result.merchantApprovedExplicitly,
    success: true
  });

  return result;
});

async function buildMerchantSubscriptionResponse(merchantId: string, requestedCaptains = 1) {
  const verification = await verifyMerchantSubscription(merchantId, requestedCaptains);
  return {
    ...verification,
    merchantId,
    serverTimeMs: Date.now()
  };
}

/**
 * Primary Callable Endpoint: Returns the authoritative subscription state for the authenticated merchant.
 */
export const getMerchantSubscription = functions.https.onCall(async (data, context) => {
  if (!context.auth || !context.auth.uid) {
    throw new functions.https.HttpsError("unauthenticated", "يجب تسجيل الدخول أولاً.");
  }
  if (context.auth.token.email_verified !== true) {
    throw new functions.https.HttpsError("failed-precondition", "يجب تأكيد البريد الإلكتروني أولاً قبل استخدام خدمات التاجر.");
  }

  let targetMerchantId = context.auth.uid;
  const requestedId = String(data?.merchantId || "").trim();
  if (requestedId && requestedId !== context.auth.uid) {
    const adminAllowed = await isAdminCaller(context);
    if (!adminAllowed) {
      throw new functions.https.HttpsError("permission-denied", "غير مسموح بالوصول لاشتراك تاجر آخر.");
    }
    targetMerchantId = requestedId;
  }

  return buildMerchantSubscriptionResponse(targetMerchantId);
});

/**
 * Backward-compatible alias for getMerchantSubscription.
 */
export const getMySubscriptionStatus = functions.https.onCall(async (data, context) => {
  if (!context.auth || !context.auth.uid) {
    throw new functions.https.HttpsError("unauthenticated", "يجب تسجيل الدخول أولاً.");
  }
  if (context.auth.token.email_verified !== true) {
    throw new functions.https.HttpsError("failed-precondition", "يجب تأكيد البريد الإلكتروني أولاً.");
  }
  return buildMerchantSubscriptionResponse(context.auth.uid);
});

/**
 * Server-Side Subscription & Capacity Validation Endpoint.
 * Checks Auth, verified email, merchant status, subscription status, server expiration time,
 * plan permissions, and concurrent active captains limit.
 */
export const validateMerchantSubscription = functions.https.onCall(async (data, context) => {
  if (!context.auth || !context.auth.uid) {
    throw new functions.https.HttpsError("unauthenticated", "يجب تسجيل الدخول أولاً.");
  }
  if (context.auth.token.email_verified !== true) {
    throw new functions.https.HttpsError("failed-precondition", "يجب تأكيد البريد الإلكتروني أولاً.");
  }
  const requestedCaptains = Math.max(1, Math.min(8, Number(data?.requestedCaptains || 1)));
  const response = await buildMerchantSubscriptionResponse(context.auth.uid, requestedCaptains);
  return {
    allowed: response.isValid,
    requestedCaptains,
    ...response
  };
});

/**
 * Scheduled Cloud Function: Automatically expires subscriptions whose endDate/expiresAt has passed,
 * mirrors the expired state onto `merchants/{merchantId}` inside a version-checked transaction,
 * writes an audit log, and sends FCM alert once.
 */
export const onMerchantSubscriptionExpired = functions.pubsub
  .schedule("every 15 minutes")
  .timeZone("Africa/Cairo")
  .onRun(async () => {
    const now = Date.now();
    const activeSubsSnapshot = await db.collection("subscriptions").where("status", "==", "active").get();
    let expiredCount = 0;
    let warnedCount = 0;

    for (const docSnap of activeSubsSnapshot.docs) {
      const data = docSnap.data() || {};
      const merchantId = String(data.merchantId || docSnap.id).trim();
      if (!merchantId) continue;

      const endMs = parseMillis(data.expiresAt || data.endDate || data.subscriptionEndDate || data.expiryDate);
      if (endMs === null) continue;

      if (endMs <= now) {
        const txRes = await db.runTransaction(async (transaction) => {
          const subRef = db.collection("subscriptions").doc(docSnap.id);
          const merchRef = db.collection("merchants").doc(merchantId);
          const [freshSub, freshMerch] = await Promise.all([
            transaction.get(subRef),
            transaction.get(merchRef)
          ]);
          if (!freshSub.exists) return { expired: false, nextVersion: 0 };
          const freshData = freshSub.data() || {};
          const freshMerchData = freshMerch.exists ? (freshMerch.data() || {}) : {};
          if (normalizeSubscriptionStatus(freshData.status || freshData.subscriptionStatus) !== "active") {
            return { expired: false, nextVersion: 0 };
          }
          const freshEndMs = parseMillis(
            freshData.expiresAt || freshData.endDate || freshData.subscriptionEndDate || freshData.expiryDate
          );
          if (freshEndMs === null || freshEndMs > now) {
            return { expired: false, nextVersion: 0 };
          }

          const nextVersion =
            Math.max(
              Number(freshData.subscriptionVersion || 0),
              Number(freshMerchData.subscriptionVersion || 0)
            ) + 1;

          transaction.set(subRef, {
            status: "expired",
            subscriptionStatus: "expired",
            isActive: false,
            isSubscribed: false,
            expiredAt: admin.firestore.FieldValue.serverTimestamp(),
            updatedBy: "system_expiry_scheduler",
            subscriptionVersion: nextVersion,
            lastAuditLoggedVersion: nextVersion,
            lastNotifiedVersion: nextVersion,
            subscriptionUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
          }, { merge: true });

          if (freshMerch.exists && isMerchantAccountDoc(freshMerchData)) {
            transaction.set(merchRef, {
              subscriptionStatus: "expired",
              isActive: false,
              isSubscribed: false,
              updatedBy: "system_expiry_scheduler",
              subscriptionVersion: nextVersion,
              subscriptionUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
              updatedAt: admin.firestore.FieldValue.serverTimestamp()
            }, { merge: true });
          }
          return { expired: true, nextVersion };
        });

        if (!txRes.expired) continue;

        await writeOperationLog({
          type: "subscription_auto_expired",
          action: "auto_expire",
          merchantId,
          actorUid: "system_expiry_scheduler",
          actorRole: "system",
          previousStatus: "active",
          newStatus: "expired",
          planId: Number(data.planId || 0),
          planName: String(data.planName || ""),
          captainLimit: Number(data.captainLimit || data.maxCaptains || 0),
          subscriptionVersion: txRes.nextVersion,
          success: true
        });

        await sendMerchantSubscriptionNotification(
          merchantId,
          "انتهت صلاحية اشتراكك ⌛",
          "انتهت مدة باقتك الحالية وتم إيقاف طلب الكباتن تلقائياً. يرجى تجديد الاشتراك للمتابعة.",
          "SUBSCRIPTION_EXPIRED",
          { subscriptionVersion: String(txRes.nextVersion) }
        );
        expiredCount++;
      } else if (endMs - now <= 3 * 24 * 60 * 60 * 1000 && data.expiringSoonNotifiedEndMs !== endMs) {
        const remaining = computeRemainingDuration(endMs, now);
        await db.collection("subscriptions").doc(docSnap.id).set({
          expiringSoonNotifiedEndMs: endMs
        }, { merge: true });
        await sendMerchantSubscriptionNotification(
          merchantId,
          "تنبيه قرب انتهاء الاشتراك ⏳",
          `متبقي ${Math.max(1, remaining.remainingDays)} يوم على انتهاء باقة ${data.planName || "البطل"}. يرجى التجديد قبل الانتهاء لتفادي توقف الخدمة.`,
          "SUBSCRIPTION_EXPIRING_SOON",
          { remainingDays: String(remaining.remainingDays) }
        );
        warnedCount++;
      }
    }

    console.log(`[SubscriptionExpiry] Processed ${activeSubsSnapshot.size} active subscriptions: expired=${expiredCount}, warned=${warnedCount}`);
    return null;
  });

export interface SubscriptionSyncResult {
  synced: boolean;
  skippedReason?: string;
  resolvedVersion?: number;
  auditEntry?: Record<string, any>;
  pushSpec?: {
    merchantId: string;
    title: string;
    body: string;
    type: string;
    data: Record<string, string>;
  };
}

/**
 * Transactional, monotonic-version-guarded sync from `subscriptions/{subId}` -> `subscriptions/{uid}` & `merchants/{uid}`.
 * Guarantees:
 * - Delayed / out-of-order triggers with lower `subscriptionVersion` can NEVER overwrite a newer subscription state.
 * - Auto-ID subscription writes can NEVER overwrite a newer canonical `subscriptions/{uid}` version.
 * - Re-executing triggers is strictly idempotent (zero duplicate writes, zero version bumps, zero duplicate notifications).
 * - Merchant account approval (`merchants/{uid}.status`) is NEVER silently flipped by subscription triggers.
 */
export async function applySubscriptionWriteSync(
  targetDb: FirebaseFirestore.Firestore,
  subId: string,
  beforeData: Record<string, any> | null,
  afterData: Record<string, any> | null
): Promise<SubscriptionSyncResult> {
  // Handle document deletion safely inside a transaction
  if (!afterData) {
    const before = beforeData || {};
    const targetId = String(before.merchantId || before.restaurantId || before.userId || subId).trim();
    if (!targetId) return { synced: false, skippedReason: "missing_merchant_id" };

    return targetDb.runTransaction(async (transaction) => {
      const merchRef = targetDb.collection("merchants").doc(targetId);
      const canonicalSubRef = targetDb.collection("subscriptions").doc(targetId);
      const [merchSnap, canonicalSubSnap] = await Promise.all([
        transaction.get(merchRef),
        transaction.get(canonicalSubRef)
      ]);
      if (!merchSnap.exists || !isMerchantAccountDoc(merchSnap.data())) {
        return { synced: false, skippedReason: "not_merchant" };
      }
      // Deleting an auto-ID duplicate must never wipe a live canonical subscription
      if (subId !== targetId && canonicalSubSnap.exists) {
        return { synced: false, skippedReason: "canonical_still_exists" };
      }
      const mData = merchSnap.data() || {};
      const incomingVer = Number(before.subscriptionVersion || 0);
      const currentMerchVer = Number(mData.subscriptionVersion || 0);
      if (incomingVer > 0 && incomingVer < currentMerchVer) {
        return { synced: false, skippedReason: "stale_version" };
      }
      const nextVer = Math.max(incomingVer, currentMerchVer) + 1;
      transaction.set(
        merchRef,
        {
          subscriptionStatus: "unsubscribed",
          isActive: false,
          isSubscribed: false,
          maxCaptains: 0,
          captainLimit: 0,
          subscriptionVersion: nextVer,
          subscriptionUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
          updatedAt: admin.firestore.FieldValue.serverTimestamp()
        },
        { merge: true }
      );
      return { synced: true, resolvedVersion: nextVer };
    });
  }

  const before = beforeData || {};
  const data = afterData;
  const targetMerchantId = String(data.merchantId || data.restaurantId || data.userId || subId).trim();
  if (!targetMerchantId) return { synced: false, skippedReason: "missing_merchant_id" };

  const hadBefore = beforeData !== null;
  const prevStatus = hadBefore
    ? normalizeSubscriptionStatus(before.status || before.subscriptionStatus || before.planStatus)
    : "unsubscribed";
  const normalizedStatus = normalizeSubscriptionStatus(data.status || data.subscriptionStatus || data.planStatus);
  const transitionedToActive = (!hadBefore || prevStatus !== "active") && normalizedStatus === "active";
  const isActive =
    normalizedStatus === "active" &&
    (transitionedToActive || !(data.isActive === false && data.isSubscribed !== true));

  const incomingVer = Number(data.subscriptionVersion || 0);
  const beforeVer = hadBefore ? Number(before.subscriptionVersion || 0) : 0;
  const incomingStartVal = data.activatedAt || data.startDate || data.subscriptionStartDate;
  const incomingEndVal = data.expiresAt || data.endDate || data.subscriptionEndDate || data.expiryDate;
  const incomingEndMs = parseMillis(incomingEndVal);
  const incomingReason = String(data.suspensionReason || data.cancellationReason || "").trim();

  return targetDb.runTransaction(async (transaction) => {
    const merchRef = targetDb.collection("merchants").doc(targetMerchantId);
    const canonicalSubRef = targetDb.collection("subscriptions").doc(targetMerchantId);
    const sourceSubRef = targetDb.collection("subscriptions").doc(subId);

    const reads: Promise<FirebaseFirestore.DocumentSnapshot>[] = [
      transaction.get(merchRef),
      transaction.get(canonicalSubRef)
    ];
    if (subId !== targetMerchantId) {
      reads.push(transaction.get(sourceSubRef));
    }
    const [merchSnap, canonicalSubSnap, sourceSubSnap] = await Promise.all(reads);

    if (!merchSnap.exists || !isMerchantAccountDoc(merchSnap.data())) {
      return { synced: false, skippedReason: "not_merchant" };
    }

    const mData = merchSnap.data() || {};
    const cData = canonicalSubSnap.exists ? (canonicalSubSnap.data() || {}) : {};
    const liveMerchVer = Number(mData.subscriptionVersion || 0);
    const liveCanonicalVer = canonicalSubSnap.exists ? Number(cData.subscriptionVersion || 0) : 0;
    const liveMaxVer = Math.max(liveMerchVer, liveCanonicalVer);

    // 1. STRICT MONOTONIC VERSION GUARD: reject delayed / stale triggers immediately
    if (incomingVer > 0 && incomingVer < liveMaxVer) {
      return { synced: false, skippedReason: "stale_version" };
    }
    if (incomingVer === 0 && liveMaxVer > 0) {
      // Unversioned write cannot overwrite an already-versioned canonical subscription
      if (subId !== targetMerchantId || liveCanonicalVer > beforeVer || liveMerchVer > beforeVer) {
        return { synced: false, skippedReason: "stale_unversioned_write" };
      }
    }
    // Auto-ID mirroring must NEVER overwrite an equal-or-newer canonical subscriptions/{uid}
    if (subId !== targetMerchantId && canonicalSubSnap.exists && liveCanonicalVer > 0) {
      if (incomingVer <= liveCanonicalVer) {
        return { synced: false, skippedReason: "stale_auto_id_write" };
      }
    }

    const resolvedMeta = resolvePlanMetadata(
      data.planId ?? mData.planId,
      data.planName ?? mData.planName,
      Number(data.captainLimit || data.maxCaptains || 0) || Number(mData.captainLimit || mData.maxCaptains || 0),
      data.price ?? mData.price
    );
    const rawLimit = isActive
      ? resolvedMeta.captainLimit
      : Number(data.captainLimit || data.maxCaptains || 0);
    const maxCaptains =
      normalizedStatus === "cancelled" ||
      normalizedStatus === "pending" ||
      normalizedStatus === "pending_review" ||
      normalizedStatus === "rejected" ||
      normalizedStatus === "resubmission_required"
        ? 0
        : rawLimit;
    const resolvedPlanId = isActive ? resolvedMeta.planId : Number(data.planId || mData.planId || 0);
    const resolvedPlanName = String(data.planName || (isActive ? resolvedMeta.planName : mData.planName || "")).trim();
    const resolvedPrice = Number(data.price || (isActive ? resolvedMeta.price : mData.price || 0));
    const resolvedPaymentStatus = isActive
      ? "paid"
      : String(data.paymentStatus || mData.paymentStatus || "unpaid").toLowerCase().trim();
    const incomingRejectionReason = String(data.rejectionReason || "").trim();
    const incomingResubmissionReason = String(data.resubmissionReason || "").trim();

    const prevLimit = Number(before.captainLimit || before.maxCaptains || 0);
    const prevEndMs = parseMillis(before.expiresAt || before.endDate || before.subscriptionEndDate);
    const statusChanged = !hadBefore || prevStatus !== normalizedStatus;
    const planChanged = hadBefore && isActive && (Number(before.planId || 0) !== resolvedPlanId || prevLimit !== rawLimit);
    const expiryExtended = hadBefore && isActive && incomingEndMs !== null && prevEndMs !== incomingEndMs;
    const meaningfulTransition = statusChanged || planChanged || expiryExtended;

    // Only increment subscriptionVersion if a direct Admin write changed state WITHOUT bumping version,
    // and ONLY when live DB is NOT ahead of incomingVer. Never bump randomly on retries or Callable writes!
    const isDirectUnbumpedWrite =
      meaningfulTransition &&
      (incomingVer === 0 || (incomingVer === beforeVer && Number(data.lastAuditLoggedVersion || 0) !== incomingVer));
    const resolvedVersion = isDirectUnbumpedWrite
      ? liveMaxVer + 1
      : Math.max(incomingVer, 1);

    const currentMerchStatus = normalizeSubscriptionStatus(mData.subscriptionStatus);
    const currentMerchSubFlag = Boolean(mData.isSubscribed && mData.isActive !== false);
    const currentMerchPlan = Number(mData.planId || 0);
    const currentMerchMax = Number(mData.captainLimit || mData.maxCaptains || 0);
    const currentMerchEndMs = parseMillis(mData.expiresAt || mData.subscriptionEndDate);
    const currentReason = String(mData.suspensionReason || "").trim();

    const needsMerchMirror = !(
      currentMerchStatus === normalizedStatus &&
      currentMerchSubFlag === isActive &&
      currentMerchPlan === resolvedPlanId &&
      currentMerchMax === maxCaptains &&
      liveMerchVer === resolvedVersion &&
      currentMerchEndMs === incomingEndMs &&
      currentReason === incomingReason &&
      String(mData.paymentStatus || "").toLowerCase().trim() === resolvedPaymentStatus
    );

    const needsCanonicalMirror =
      subId !== targetMerchantId && (!canonicalSubSnap.exists || liveCanonicalVer < resolvedVersion);

    const liveSourceData = subId === targetMerchantId ? cData : (sourceSubSnap?.data() || data);
    const needsSubRepair =
      liveSourceData.status !== normalizedStatus ||
      liveSourceData.subscriptionStatus !== normalizedStatus ||
      liveSourceData.isActive !== isActive ||
      liveSourceData.isSubscribed !== isActive ||
      Number(liveSourceData.captainLimit || 0) !== maxCaptains ||
      Number(liveSourceData.maxCaptains || 0) !== maxCaptains ||
      Number(liveSourceData.subscriptionVersion || 0) < resolvedVersion ||
      (isActive && String(liveSourceData.paymentStatus || "").toLowerCase().trim() !== "paid");

    // Determine notification spec (claimed idempotently inside the SAME transaction)
    const transitionFingerprint = `${normalizedStatus}:${resolvedPlanId}:${rawLimit}:${incomingEndMs || 0}:${resolvedVersion}`;
    let pushTitle = "";
    let pushBody = "";
    let pushType = "";
    if (statusChanged && normalizedStatus === "active") {
      pushType = "SUBSCRIPTION_ACTIVATED";
      pushTitle = "تم تفعيل اشتراكك بنجاح ✓";
      pushBody = `تم تفعيل ${resolvedPlanName || "الباقة"} (${rawLimit} كابتن) ويمكنك الآن طلب الكباتن فوراً.`;
    } else if (planChanged) {
      pushType = "SUBSCRIPTION_PLAN_CHANGED";
      pushTitle = "تم تحديث باقة اشتراكك 🔄";
      pushBody = `تم تعديل باقتك إلى ${resolvedPlanName || "الباقة"} بحد أقصى ${rawLimit} كابتن.`;
    } else if (statusChanged && (normalizedStatus === "suspended" || normalizedStatus === "inactive")) {
      pushType = "SUBSCRIPTION_SUSPENDED";
      pushTitle = "تم إيقاف الاشتراك مؤقتاً ⚠️";
      pushBody = incomingReason
        ? `تم إيقاف اشتراكك (${incomingReason}). يرجى التواصل مع الإدارة أو تجديد الاشتراك.`
        : "تم إيقاف اشتراكك من الإدارة. يرجى التواصل مع الإدارة أو تجديد الاشتراك.";
    } else if (statusChanged && normalizedStatus === "cancelled") {
      pushType = "SUBSCRIPTION_SUSPENDED";
      pushTitle = "تم إلغاء الاشتراك ❌";
      pushBody = incomingReason
        ? `تم إلغاء اشتراكك (${incomingReason}). يرجى التواصل مع الإدارة.`
        : "تم إلغاء اشتراكك من الإدارة. يرجى التواصل مع الإدارة أو الاشتراك في باقة جديدة.";
    } else if (statusChanged && normalizedStatus === "rejected") {
      pushType = "SUBSCRIPTION_REJECTED";
      pushTitle = "تم رفض طلب الاشتراك ❌";
      pushBody = incomingRejectionReason
        ? `تم رفض طلب الاشتراك: ${incomingRejectionReason}`
        : "تم رفض طلب الاشتراك من الإدارة. يرجى مراجعة البيانات وإعادة المحاولة.";
    } else if (statusChanged && normalizedStatus === "resubmission_required") {
      pushType = "SUBSCRIPTION_RESUBMISSION_REQUIRED";
      pushTitle = "مطلوب إعادة إرسال إيصال التحويل 📄";
      pushBody = incomingResubmissionReason
        ? `يرجى إعادة رفع الإيصال: ${incomingResubmissionReason}`
        : "طلبت الإدارة إعادة إرسال صورة واضحة لإيصال تحويل فودافون كاش.";
    } else if (statusChanged && normalizedStatus === "expired" && data.updatedBy !== "system_expiry_scheduler") {
      pushType = "SUBSCRIPTION_EXPIRED";
      pushTitle = "انتهى اشتراكك ⌛";
      pushBody = "انتهت مدة باقتك الحالية، يرجى تجديد الاشتراك للاستمرار في طلب الكباتن.";
    } else if (expiryExtended) {
      pushType = "SUBSCRIPTION_ACTIVATED";
      pushTitle = "تم تمديد صلاحية اشتراكك ✓";
      pushBody = `تم تحديث مدة اشتراكك في ${resolvedPlanName || "الباقة"} بنجاح.`;
    }

    const alreadyNotified =
      cData.lastNotifiedFingerprint === transitionFingerprint ||
      Number(cData.lastNotifiedVersion || 0) >= resolvedVersion;
    const shouldClaimNotification = Boolean(pushType) && !alreadyNotified;

    const alreadyAudited =
      Number(cData.lastAuditLoggedVersion || 0) >= resolvedVersion ||
      Number(data.lastAuditLoggedVersion || 0) >= resolvedVersion;
    const shouldAuditLog = meaningfulTransition && !alreadyAudited;

    if (!needsMerchMirror && !needsCanonicalMirror && !needsSubRepair && !shouldClaimNotification && !shouldAuditLog) {
      return { synced: false, skippedReason: "already_in_sync", resolvedVersion };
    }

    const buildNormalizedSubPatch = (): Record<string, any> => {
      const patch: Record<string, any> = {
        merchantId: targetMerchantId,
        status: normalizedStatus,
        subscriptionStatus: normalizedStatus,
        isActive,
        isSubscribed: isActive,
        planId: resolvedPlanId,
        planName: resolvedPlanName,
        maxCaptains,
        captainLimit: maxCaptains,
        price: resolvedPrice,
        paymentStatus: resolvedPaymentStatus,
        subscriptionVersion: resolvedVersion,
        updatedBy: data.updatedBy || data.updatedByAdminUid || "admin_dashboard",
        subscriptionUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      };
      if (incomingStartVal) {
        patch.startDate = incomingStartVal;
        patch.activatedAt = incomingStartVal;
      }
      if (incomingEndVal) {
        patch.endDate = incomingEndVal;
        patch.expiresAt = incomingEndVal;
      }
      if (incomingReason) {
        patch.suspensionReason = incomingReason;
      } else if (isActive) {
        patch.suspensionReason = admin.firestore.FieldValue.delete();
        patch.cancellationReason = admin.firestore.FieldValue.delete();
      }
      if (shouldAuditLog) {
        patch.lastAuditLoggedVersion = resolvedVersion;
      }
      if (shouldClaimNotification) {
        patch.lastNotifiedFingerprint = transitionFingerprint;
        patch.lastNotifiedVersion = resolvedVersion;
      }
      return patch;
    };

    if (needsCanonicalMirror || (subId === targetMerchantId && (needsSubRepair || shouldClaimNotification || shouldAuditLog))) {
      transaction.set(canonicalSubRef, buildNormalizedSubPatch(), { merge: true });
    }
    if (subId !== targetMerchantId && (needsSubRepair || shouldAuditLog)) {
      transaction.set(sourceSubRef, buildNormalizedSubPatch(), { merge: true });
    }

    // Mirror onto merchants/{targetMerchantId} WITHOUT ever touching merchant account approval (`status` / `isApproved`)
    if (needsMerchMirror) {
      const mirrorPayload: Record<string, any> = {
        accountType: mData.accountType || "merchant",
        role: mData.role || "merchant",
        subscriptionStatus: normalizedStatus,
        isActive,
        isSubscribed: isActive,
        planId: resolvedPlanId,
        planName: resolvedPlanName,
        maxCaptains,
        captainLimit: maxCaptains,
        price: resolvedPrice,
        paymentStatus: resolvedPaymentStatus,
        subscriptionVersion: resolvedVersion,
        subscriptionUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      };
      if (data.updatedBy || data.updatedByAdminUid) {
        mirrorPayload.updatedBy = data.updatedBy || data.updatedByAdminUid;
      }
      if (incomingStartVal) {
        mirrorPayload.subscriptionStartDate = incomingStartVal;
        mirrorPayload.activatedAt = incomingStartVal;
      }
      if (incomingEndVal) {
        mirrorPayload.subscriptionEndDate = incomingEndVal;
        mirrorPayload.expiresAt = incomingEndVal;
      }
      if (incomingReason) {
        mirrorPayload.suspensionReason = incomingReason;
      } else if (isActive) {
        mirrorPayload.suspensionReason = admin.firestore.FieldValue.delete();
      }
      if (incomingRejectionReason) {
        mirrorPayload.rejectionReason = incomingRejectionReason;
      } else if (isActive || normalizedStatus === "pending_review" || normalizedStatus === "resubmission_required") {
        mirrorPayload.rejectionReason = admin.firestore.FieldValue.delete();
      }
      if (incomingResubmissionReason) {
        mirrorPayload.resubmissionReason = incomingResubmissionReason;
      } else if (isActive || normalizedStatus === "pending_review" || normalizedStatus === "rejected") {
        mirrorPayload.resubmissionReason = admin.firestore.FieldValue.delete();
      }
      if (data.pendingRequestId) {
        mirrorPayload.pendingRequestId = data.pendingRequestId;
      } else if (isActive || normalizedStatus === "rejected" || normalizedStatus === "resubmission_required") {
        mirrorPayload.pendingRequestId = admin.firestore.FieldValue.delete();
      }
      if (data.paymentMethod) {
        mirrorPayload.paymentMethod = data.paymentMethod;
      }
      transaction.set(merchRef, mirrorPayload, { merge: true });
    }

    const resultObj: SubscriptionSyncResult = {
      synced: true,
      resolvedVersion
    };
    if (shouldAuditLog) {
      resultObj.auditEntry = {
        type: "subscription_direct_write_sync",
        action: statusChanged ? `status_${normalizedStatus}` : planChanged ? "plan_changed" : "expiry_updated",
        merchantId: targetMerchantId,
        actorUid: String(data.updatedBy || data.updatedByAdminUid || "admin_dashboard"),
        actorRole: "admin",
        previousStatus: prevStatus,
        newStatus: normalizedStatus,
        planId: resolvedPlanId,
        planName: resolvedPlanName,
        captainLimit: maxCaptains,
        reason: incomingReason || null,
        subscriptionVersion: resolvedVersion,
        success: true
      };
    }
    if (shouldClaimNotification) {
      resultObj.pushSpec = {
        merchantId: targetMerchantId,
        title: pushTitle,
        body: pushBody,
        type: pushType,
        data: {
          status: normalizedStatus,
          planId: String(resolvedPlanId),
          captainLimit: String(rawLimit),
          subscriptionVersion: String(resolvedVersion)
        }
      };
    }
    return resultObj;
  });
}

/**
 * Bidirectional Loop-Safe, Race-Free Sync Trigger 1:
 * When Admin Dashboard or Callable writes to `subscriptions/{subscriptionId}`, atomically sync summary fields
 * onto `merchants/{merchantId}` using monotonic `subscriptionVersion` inside a Firestore Transaction.
 */
export const onSubscriptionWriteSyncMerchant = functions.firestore
  .document("subscriptions/{subscriptionId}")
  .onWrite(async (change, context) => {
    const subId = context.params.subscriptionId;
    const beforeData = change.before.exists ? (change.before.data() || {}) : null;
    const afterData = change.after.exists ? (change.after.data() || {}) : null;

    const syncResult = await applySubscriptionWriteSync(db, subId, beforeData, afterData);
    if (syncResult.auditEntry) {
      await writeOperationLog(syncResult.auditEntry);
    }
    if (syncResult.pushSpec) {
      await sendMerchantSubscriptionNotification(
        syncResult.pushSpec.merchantId,
        syncResult.pushSpec.title,
        syncResult.pushSpec.body,
        syncResult.pushSpec.type,
        syncResult.pushSpec.data
      );
    }
  });

/**
 * Transactional, monotonic-version-guarded sync from `merchants/{merchantId}` -> `subscriptions/{merchantId}`.
 * Guarantees that a delayed trigger carrying an older `subscriptionVersion` NEVER bumps version above
 * or overwrites a newer `subscriptions/{merchantId}` document.
 */
export async function applyMerchantSubscriptionWriteSync(
  targetDb: FirebaseFirestore.Firestore,
  merchantId: string,
  before: Record<string, any>,
  after: Record<string, any>
): Promise<SubscriptionSyncResult> {
  if (!isMerchantAccountDoc(after)) {
    return { synced: false, skippedReason: "not_merchant" };
  }

  const trackedFields = [
    "subscriptionStatus",
    "isSubscribed",
    "isActive",
    "planId",
    "planName",
    "maxCaptains",
    "captainLimit",
    "price",
    "subscriptionStartDate",
    "activatedAt",
    "subscriptionEndDate",
    "expiresAt",
    "suspensionReason",
    "rejectionReason",
    "resubmissionReason",
    "pendingRequestId",
    "paymentMethod",
    "paymentStatus",
    "subscriptionVersion"
  ];
  const hasSubDiff = trackedFields.some((f) => JSON.stringify(before[f]) !== JSON.stringify(after[f]));
  if (!hasSubDiff) {
    return { synced: false, skippedReason: "no_subscription_diff" };
  }

  const prevMerchStatus = normalizeSubscriptionStatus(before.subscriptionStatus || before.planStatus);
  const merchStatus = normalizeSubscriptionStatus(after.subscriptionStatus || after.planStatus);
  const transitionedToActive = prevMerchStatus !== "active" && merchStatus === "active";
  const isActive =
    merchStatus === "active" &&
    (transitionedToActive || !(after.isActive === false && after.isSubscribed !== true));

  const incomingVer = Number(after.subscriptionVersion || 0);
  const beforeVer = Number(before.subscriptionVersion || 0);
  const afterEndVal = after.expiresAt || after.subscriptionEndDate;
  const afterStartVal = after.activatedAt || after.subscriptionStartDate;
  const afterEndMs = parseMillis(afterEndVal);
  const afterReason = String(after.suspensionReason || "").trim();

  return targetDb.runTransaction(async (transaction) => {
    const merchRef = targetDb.collection("merchants").doc(merchantId);
    const subRef = targetDb.collection("subscriptions").doc(merchantId);
    const [liveMerchSnap, subSnap] = await Promise.all([
      transaction.get(merchRef),
      transaction.get(subRef)
    ]);

    if (!liveMerchSnap.exists || !isMerchantAccountDoc(liveMerchSnap.data())) {
      return { synced: false, skippedReason: "not_merchant" };
    }

    const liveMerch = liveMerchSnap.data() || {};
    const sData = subSnap.exists ? (subSnap.data() || {}) : {};
    const liveMerchVer = Number(liveMerch.subscriptionVersion || 0);
    const liveSubVer = subSnap.exists ? Number(sData.subscriptionVersion || 0) : 0;
    const liveMaxVer = Math.max(liveMerchVer, liveSubVer);

    // STRICT MONOTONIC GUARD: reject stale/delayed triggers immediately
    if (incomingVer > 0 && incomingVer < liveMaxVer) {
      return { synced: false, skippedReason: "stale_version" };
    }
    if (incomingVer === 0 && liveMaxVer > 0 && beforeVer < liveMaxVer) {
      return { synced: false, skippedReason: "stale_unversioned_write" };
    }

    const resolvedMeta = resolvePlanMetadata(
      after.planId ?? sData.planId,
      after.planName ?? sData.planName,
      Number(after.captainLimit || after.maxCaptains || 0) || Number(sData.captainLimit || sData.maxCaptains || 0),
      after.price ?? sData.price
    );
    const afterPlanId = isActive ? resolvedMeta.planId : Number(after.planId || sData.planId || 0);
    const afterPlanName = String(after.planName || (isActive ? resolvedMeta.planName : sData.planName || "")).trim();
    const afterMax = isActive
      ? resolvedMeta.captainLimit
      : merchStatus === "cancelled" || merchStatus === "pending" || merchStatus === "pending_review" || merchStatus === "rejected" || merchStatus === "resubmission_required"
        ? 0
        : Number(after.captainLimit || after.maxCaptains || 0);
    const afterPrice = Number(after.price || (isActive ? resolvedMeta.price : sData.price || 0));
    const afterPaymentStatus = isActive
      ? "paid"
      : String(after.paymentStatus || sData.paymentStatus || "unpaid").toLowerCase().trim();

    const currentSubStatus = subSnap.exists ? normalizeSubscriptionStatus(sData.status || sData.subscriptionStatus) : "unsubscribed";
    const currentSubFlag = subSnap.exists ? Boolean(sData.isSubscribed && sData.isActive !== false) : false;
    const currentSubPlan = Number(sData.planId || 0);
    const currentSubMax = Number(sData.captainLimit || sData.maxCaptains || 0);
    const currentSubEndMs = parseMillis(sData.expiresAt || sData.endDate);
    const currentReason = String(sData.suspensionReason || sData.cancellationReason || "").trim();

    if (
      subSnap.exists &&
      liveSubVer >= incomingVer &&
      currentSubStatus === merchStatus &&
      currentSubFlag === isActive &&
      currentSubPlan === afterPlanId &&
      currentSubMax === afterMax &&
      currentSubEndMs === afterEndMs &&
      currentReason === afterReason &&
      String(sData.paymentStatus || "").toLowerCase().trim() === afterPaymentStatus
    ) {
      return { synced: false, skippedReason: "already_in_sync", resolvedVersion: liveSubVer };
    }

    // Only advance version when a direct admin write on merchants/{uid} changed fields without bumping version
    const targetVersion = incomingVer > liveSubVer ? incomingVer : liveMaxVer + 1;

    const subPayload: Record<string, any> = {
      merchantId,
      status: merchStatus,
      subscriptionStatus: merchStatus,
      isActive,
      isSubscribed: isActive,
      planId: afterPlanId,
      planName: afterPlanName,
      maxCaptains: afterMax,
      captainLimit: afterMax,
      price: afterPrice,
      paymentStatus: afterPaymentStatus,
      subscriptionVersion: targetVersion,
      updatedBy: after.updatedBy || "admin_dashboard",
      subscriptionUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    };
    if (afterStartVal) {
      subPayload.startDate = afterStartVal;
      subPayload.activatedAt = afterStartVal;
    }
    if (afterEndVal) {
      subPayload.endDate = afterEndVal;
      subPayload.expiresAt = afterEndVal;
    }
    if (afterReason) {
      subPayload.suspensionReason = afterReason;
    } else if (isActive) {
      subPayload.suspensionReason = admin.firestore.FieldValue.delete();
      subPayload.cancellationReason = admin.firestore.FieldValue.delete();
    }

    transaction.set(subRef, subPayload, { merge: true });
    if (liveMerchVer < targetVersion) {
      transaction.set(
        merchRef,
        {
          subscriptionVersion: targetVersion,
          subscriptionUpdatedAt: admin.firestore.FieldValue.serverTimestamp()
        },
        { merge: true }
      );
    }

    return { synced: true, resolvedVersion: targetVersion };
  });
}

/**
 * Bidirectional Loop-Safe, Race-Free Sync Trigger 2:
 * When Admin Dashboard writes subscription fields directly onto `merchants/{merchantId}`, mirror them onto
 * canonical `subscriptions/{merchantId}` inside a version-guarded transaction.
 */
export const onMerchantSubscriptionWriteSync = functions.firestore
  .document("merchants/{merchantId}")
  .onUpdate(async (change, context) => {
    const merchantId = context.params.merchantId;
    const before = change.before.data() || {};
    const after = change.after.data() || {};
    await applyMerchantSubscriptionWriteSync(db, merchantId, before, after);
  });

export interface ReserveOrderParams {
  uid: string;
  actionName: string;
  destinationAddress: string;
  serviceFee: string;
  customerName?: string;
  customerPhone?: string;
  orderDetails?: string;
  deliveryNotes?: string;
  requestedCaptains?: number;
  idempotencyKey?: string;
  requestId?: string;
  merchantPhone?: string;
  customerLatitude?: number | null;
  customerLongitude?: number | null;
  merchantLatitude?: number | null;
  merchantLongitude?: number | null;
  nowMs?: number;
  simulatePostReserveError?: boolean;
}

function logSafeFunctionError(
  functionName: string,
  errorCode: string,
  merchantId: string,
  requestId: string,
  detailMessage: string
) {
  console.error(
    JSON.stringify({
      event: "cloud_function_error",
      functionName,
      errorCode,
      merchantId,
      requestId,
      detailMessage,
      timestamp: new Date().toISOString()
    })
  );
}

/**
 * Core atomic transaction for captainLimit capacity reservation + order creation + code reservation + idempotency key.
 * Uses `merchants/{uid}/capacity/current` as the single shared contention document per merchant so
 * concurrent requests serialize cleanly via Firestore Transactions and can NEVER exceed `captainLimit`.
 */
export async function reserveAndCreateOrderAtomic(
  targetDb: FirebaseFirestore.Firestore,
  params: ReserveOrderParams
) {
  const uid = params.uid;
  const actionName = params.actionName;
  const idempotencyKey = String(params.idempotencyKey || "").trim();
  const requestId = String(params.requestId || idempotencyKey || crypto.randomBytes(6).toString("hex")).trim();
  const destinationAddress = String(params.destinationAddress || "").trim();
  const serviceFee = String(params.serviceFee || "30 جنيه").trim();
  const customerName = String(params.customerName || "").trim();
  const customerPhone = String(params.customerPhone || "").trim();
  const orderDetails = String(params.orderDetails || "").trim();
  const deliveryNotes = String(params.deliveryNotes || "").trim();
  const requestedCaptains = Math.max(1, Math.min(8, Number(params.requestedCaptains || 1)));
  const customerLatitude = typeof params.customerLatitude === "number" ? params.customerLatitude : null;
  const customerLongitude = typeof params.customerLongitude === "number" ? params.customerLongitude : null;

  if (!destinationAddress) {
    logSafeFunctionError(actionName, "MISSING_CUSTOMER_ADDRESS", uid, requestId, "destinationAddress is blank");
    throw new functions.https.HttpsError(
      "invalid-argument",
      "بيانات عنوان العميل غير مكتملة. يرجى إدخال عنوان التوصيل.",
      { reasonCode: "MISSING_CUSTOMER_ADDRESS", requestId }
    );
  }

  // Fast-path idempotency replay check (also enforced inside transaction)
  if (idempotencyKey) {
    const existingKeyDoc = await targetDb.collection("idempotency_keys").doc(idempotencyKey).get();
    if (existingKeyDoc.exists) {
      const keyData = existingKeyDoc.data() || {};
      if (keyData.merchantId && keyData.merchantId !== uid) {
        logSafeFunctionError(actionName, "IDEMPOTENCY_OWNERSHIP_MISMATCH", uid, requestId, "key owned by another merchant");
        throw new functions.https.HttpsError(
          "permission-denied",
          "مفتاح التكرار لا يتبع هذا التاجر.",
          { reasonCode: "IDEMPOTENCY_OWNERSHIP_MISMATCH", requestId }
        );
      }
      return {
        success: true,
        orderId: keyData.orderId,
        orderCode: keyData.orderCode,
        isReplay: true,
        requestId,
        message: "تم استرجاع بيانات الطلب المنشأ مسبقاً بنجاح."
      };
    }
  }

  const merchantPreDoc = await targetDb.collection("merchants").doc(uid).get();
  if (!merchantPreDoc.exists || !isMerchantAccountDoc(merchantPreDoc.data())) {
    logSafeFunctionError(actionName, "MERCHANT_NOT_FOUND", uid, requestId, "merchants/{uid} missing or non-merchant");
    throw new functions.https.HttpsError(
      "not-found",
      "تعذر العثور على بيانات حساب التاجر.",
      { reasonCode: "MERCHANT_NOT_FOUND", requestId }
    );
  }
  const merchantPreData = merchantPreDoc.data() || {};
  const merchantName = merchantPreData.name || "تاجر";
  const merchantPhone = String(merchantPreData.phone || params.merchantPhone || "").trim();
  const clientLat = typeof params.merchantLatitude === "number" ? params.merchantLatitude : null;
  const clientLng = typeof params.merchantLongitude === "number" ? params.merchantLongitude : null;
  const merchantLoc =
    merchantPreData.location ||
    (typeof merchantPreData.latitude === "number" && typeof merchantPreData.longitude === "number"
      ? new admin.firestore.GeoPoint(merchantPreData.latitude, merchantPreData.longitude)
      : clientLat !== null && clientLng !== null
        ? new admin.firestore.GeoPoint(clientLat, clientLng)
        : new admin.firestore.GeoPoint(30.0444, 31.2357));

  const fallbackSubDocData = await findAuthoritativeSubscriptionDoc(uid, targetDb);

  // High-concurrency retry budget for Firestore contention on shared `merchants/{uid}/capacity/current`
  const MAX_ATTEMPTS = 25;
  let allocatedCode = "";
  let newOrderId = "";
  const txBox: { verification: VerifySubscriptionResult | null; reservedCountAfter: number } = {
    verification: null,
    reservedCountAfter: 0
  };

  for (let attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
    const candidateCode = generateDistinct6DigitCode();
    const candidateOrderId = `${Date.now()}_${candidateCode}_${crypto.randomBytes(2).toString("hex")}`;

    try {
      await targetDb.runTransaction(async (transaction) => {
        const merchRef = targetDb.collection("merchants").doc(uid);
        const subRef = targetDb.collection("subscriptions").doc(uid);
        const capRef = targetDb.collection("merchants").doc(uid).collection("capacity").doc("current");
        const ordersByMerchQuery = targetDb.collection("orders").where("merchantId", "==", uid);
        const ordersByUserQuery = targetDb.collection("orders").where("userId", "==", uid);
        const ordersByRestQuery = targetDb.collection("orders").where("restaurantId", "==", uid);
        const codeRef = targetDb.collection("order_codes").doc(candidateCode);
        const keyRef = idempotencyKey ? targetDb.collection("idempotency_keys").doc(idempotencyKey) : null;

        const baseReads: Promise<any>[] = [
          transaction.get(merchRef),
          transaction.get(subRef),
          transaction.get(capRef),
          transaction.get(ordersByMerchQuery),
          transaction.get(ordersByUserQuery),
          transaction.get(ordersByRestQuery),
          transaction.get(codeRef)
        ];
        if (keyRef) {
          baseReads.push(transaction.get(keyRef));
        }

        const [
          txMerchDoc,
          txSubDoc,
          txCapDoc,
          txOrdersMerch,
          txOrdersUser,
          txOrdersRest,
          codeDoc,
          keyDoc
        ] = await Promise.all(baseReads);

        // Check idempotency key FIRST before touching capacity
        if (keyDoc && keyDoc.exists) {
          throw new Error("IDEMPOTENT_ALREADY_EXISTS");
        }

        // Reconcile active capacity across ALL order ownership fields + shared capacity ledger
        const liveActiveOrderIds = new Set<string>();
        const finishedOrderIds = new Set<string>();
        const allQueriedOrderIds = new Set<string>();
        [txOrdersMerch, txOrdersUser, txOrdersRest].forEach((snap: FirebaseFirestore.QuerySnapshot) => {
          snap.docs.forEach((d) => {
            const dData = d.data();
            allQueriedOrderIds.add(d.id);
            if (isOrderDocActiveForCaptainLimit(dData)) {
              liveActiveOrderIds.add(d.id);
            } else {
              finishedOrderIds.add(d.id);
            }
          });
        });

        const capData = txCapDoc.exists ? (txCapDoc.data() || {}) : {};
        const storedReservedIds: string[] = Array.isArray(capData.reservedOrderIds)
          ? capData.reservedOrderIds.map((x: unknown) => String(x)).filter(Boolean)
          : [];

        const effectiveReservedIds = new Set<string>(liveActiveOrderIds);
        for (const id of storedReservedIds) {
          if (!finishedOrderIds.has(id) && allQueriedOrderIds.has(id)) {
            effectiveReservedIds.add(id);
          }
        }

        const activeCount = effectiveReservedIds.size;
        const evalResult = evaluateDualDocSubscription(
          txSubDoc.exists ? (txSubDoc.data() || {}) : fallbackSubDocData,
          txMerchDoc.exists ? (txMerchDoc.data() || {}) : null,
          activeCount,
          params.nowMs ?? Date.now(),
          requestedCaptains
        );
        txBox.verification = evalResult;

        if (!evalResult.isValid) {
          throw new Error(`SUBSCRIPTION_REJECTED:${evalResult.errorMessage || "الاشتراك غير متاح لإنشاء الطلب."}`);
        }
        if (activeCount + requestedCaptains > evalResult.captainLimit) {
          throw new Error(
            `SUBSCRIPTION_REJECTED:وصلت إلى الحد الأقصى لعدد الكباتن المتزامنين في باقتك (${evalResult.captainLimit}).`
          );
        }

        if (codeDoc.exists) {
          throw new Error("CODE_ALREADY_USED");
        }

        // 1. Atomically reserve 1 slot on shared per-merchant capacity document (forces Transaction Contention!)
        effectiveReservedIds.add(candidateOrderId);
        const nextReservedIds = Array.from(effectiveReservedIds);
        txBox.reservedCountAfter = nextReservedIds.length;

        transaction.set(
          capRef,
          {
            merchantId: uid,
            reservedOrderIds: nextReservedIds,
            activeReservationsCount: nextReservedIds.length,
            captainLimitSnapshot: evalResult.captainLimit,
            capacityVersion: admin.firestore.FieldValue.increment(1),
            lastReservedOrderId: candidateOrderId,
            updatedAt: admin.firestore.FieldValue.serverTimestamp()
          },
          { merge: true }
        );

        // Optional failure injection for atomic rollback tests (proves zero orphan reservation on failure)
        if (params.simulatePostReserveError) {
          throw new Error("SIMULATED_ORDER_CREATION_FAILURE");
        }

        // 2. Reserve unique 6-digit order code
        transaction.set(codeRef, {
          code: candidateCode,
          orderCode: candidateCode,
          orderId: candidateOrderId,
          merchantId: uid,
          createdAt: admin.firestore.FieldValue.serverTimestamp()
        });

        // 3. Create Order Document
        const orderRef = targetDb.collection("orders").doc(candidateOrderId);
        const orderPayload: Record<string, any> = {
          orderId: candidateOrderId,
          orderCode: candidateCode,
          code: candidateCode,
          address: destinationAddress,
          destinationAddress,
          deliveryAddress: destinationAddress,
          price: serviceFee,
          totalAmount: serviceFee,
          serviceFee,
          customerName,
          customerPhone,
          orderDetails,
          items: orderDetails,
          deliveryNotes,
          notes: deliveryNotes,
          status: "pending",
          dispatchStatus: "searching",
          merchantId: uid,
          userId: uid,
          restaurantId: uid,
          merchantName,
          merchantPhone,
          merchantLocation: merchantLoc,
          pickupLocation: merchantLoc,
          merchantLatitude: merchantLoc.latitude,
          merchantLongitude: merchantLoc.longitude,
          captainId: null,
          driver_name: null,
          captainName: null,
          captainPhone: null,
          captainPhotoUrl: null,
          customerDetailsSubmitted: false,
          customerDetailsConfirmed: false,
          customerDetailsRejected: false,
          capacityReserved: true,
          capacityReleased: false,
          capacityReservedAt: admin.firestore.FieldValue.serverTimestamp(),
          createdByServerFunction: actionName,
          subscriptionVersionAtCreation: evalResult.subscriptionVersion,
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

        // 4. Register Idempotency Key atomically
        if (keyRef) {
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
      break;
    } catch (err: any) {
      const msg = String(err?.message || "");
      if (msg.startsWith("SUBSCRIPTION_REJECTED:")) {
        const rejectionReason = msg.replace("SUBSCRIPTION_REJECTED:", "");
        return {
          rejected: true as const,
          rejectionReason,
          verification: txBox.verification
        };
      }
      if (msg === "SIMULATED_ORDER_CREATION_FAILURE") {
        throw new functions.https.HttpsError("internal", "فشل إنشاء الطلب وتم إلغاء الحجز بالكامل دون ترك حجز يتيم.");
      }
      if (msg === "IDEMPOTENT_ALREADY_EXISTS") {
        const replayDoc = await targetDb.collection("idempotency_keys").doc(idempotencyKey).get();
        const rData = replayDoc.data() || {};
        return {
          success: true,
          orderId: rData.orderId,
          orderCode: rData.orderCode,
          isReplay: true,
          message: "تم استرجاع بيانات الطلب المنشأ مسبقاً بنجاح."
        };
      }
      // Retry on code collision or Firestore transaction contention (ABORTED / code 10)
      const isRetryable =
        msg === "CODE_ALREADY_USED" ||
        err?.code === 10 ||
        /ABORTED|contention|too much contention|cross-transaction/i.test(msg);
      if (!isRetryable && attempt >= 3) {
        throw err;
      }
      const jitterMs = 15 + Math.floor(Math.random() * 45);
      await new Promise((resolve) => setTimeout(resolve, jitterMs));
    }
  }

  if (!allocatedCode || !newOrderId) {
    throw new functions.https.HttpsError("resource-exhausted", "تعذر تخصيص كود طلب غير مكرر حالياً، يرجى المحاولة بعد لحظات.");
  }

  const verifiedSnapshot = txBox.verification;
  const activeCountAfter = txBox.reservedCountAfter || ((verifiedSnapshot?.activeCaptainsCount ?? 0) + 1);
  return {
    success: true,
    orderId: newOrderId,
    orderCode: allocatedCode,
    status: "pending",
    planId: verifiedSnapshot?.planId ?? 0,
    planName: verifiedSnapshot?.planName ?? "",
    captainLimit: verifiedSnapshot?.captainLimit ?? 0,
    activeCaptainsCount: activeCountAfter,
    remainingCaptains: Math.max(0, (verifiedSnapshot?.captainLimit ?? 0) - activeCountAfter),
    subscriptionVersion: verifiedSnapshot?.subscriptionVersion ?? 1,
    message: "تم إنشاء طلب التوصيل بنجاح"
  };
}

/**
 * Shared atomic server-side order creation & captain request logic.
 * Used by BOTH `requestCaptains` and `createOrderWithCode` so client requests can NEVER bypass
 * subscription validation, expiry, email verification, or concurrent captain limits.
 * Note: Initial auto-dispatch is triggered exclusively via the single authoritative `onOrderCreated` trigger.
 */
async function executeServerOrderCreation(
  context: functions.https.CallableContext,
  data: Record<string, any>,
  actionName: string
) {
  const requestId = String(
    data?.requestId || data?.idempotencyKey || crypto.randomBytes(6).toString("hex")
  ).trim();

  // 1. Verify Authentication & verified email
  if (!context.auth || !context.auth.uid) {
    logSafeFunctionError(actionName, "UNAUTHENTICATED", "anonymous", requestId, "missing auth context");
    throw new functions.https.HttpsError(
      "unauthenticated",
      "يجب تسجيل الدخول أولاً لإنشاء طلب جديد.",
      { reasonCode: "UNAUTHENTICATED", requestId }
    );
  }
  const uid = context.auth.uid;
  if (context.auth.token.email_verified !== true) {
    logSafeFunctionError(actionName, "EMAIL_NOT_VERIFIED", uid, requestId, "email_verified is false");
    await writeOperationLog({
      type: "captain_request_rejected",
      action: actionName,
      merchantId: uid,
      actorUid: uid,
      actorRole: "merchant",
      requestId,
      errorCode: "EMAIL_NOT_VERIFIED",
      success: false,
      errorMessage: "البريد الإلكتروني غير مؤكد."
    });
    throw new functions.https.HttpsError(
      "permission-denied",
      "يجب تأكيد البريد الإلكتروني أولاً.",
      { reasonCode: "EMAIL_NOT_VERIFIED", requestId }
    );
  }

  const requestedCaptains = Math.max(1, Math.min(8, Number(data?.requestedCaptains || 1)));
  try {
    const outcome = await reserveAndCreateOrderAtomic(db, {
      uid,
      actionName,
      requestId,
      destinationAddress: String(data?.destinationAddress || data?.address || "").trim(),
      serviceFee: String(data?.serviceFee || data?.price || "30 جنيه").trim(),
      customerName: String(data?.customerName || "").trim(),
      customerPhone: String(data?.customerPhone || "").trim(),
      orderDetails: String(data?.orderDetails || data?.items || "").trim(),
      deliveryNotes: String(data?.deliveryNotes || data?.notes || "").trim(),
      merchantPhone: String(data?.merchantPhone || "").trim(),
      requestedCaptains,
      idempotencyKey: String(data?.idempotencyKey || "").trim(),
      customerLatitude: typeof data?.customerLatitude === "number" ? data.customerLatitude : null,
      customerLongitude: typeof data?.customerLongitude === "number" ? data.customerLongitude : null,
      merchantLatitude: typeof data?.merchantLatitude === "number" ? data.merchantLatitude : null,
      merchantLongitude: typeof data?.merchantLongitude === "number" ? data.merchantLongitude : null
    });

    if ("rejected" in outcome && outcome.rejected) {
      const v = outcome.verification;
      const reasonCode =
        v?.status === "not_found"
          ? "MERCHANT_NOT_FOUND"
          : v?.status === "merchant_pending"
            ? "MERCHANT_PENDING_APPROVAL"
            : v?.status === "unsubscribed"
              ? "SUBSCRIPTION_NOT_FOUND"
              : v?.status === "active" && (v?.remainingCaptains ?? 0) < requestedCaptains
                ? "CAPTAIN_LIMIT_REACHED"
                : `SUBSCRIPTION_${String(v?.status || "REJECTED").toUpperCase()}`;

      logSafeFunctionError(actionName, reasonCode, uid, requestId, outcome.rejectionReason);
      await writeOperationLog({
        type: "captain_request_rejected",
        action: actionName,
        merchantId: uid,
        actorUid: uid,
        actorRole: "merchant",
        requestId,
        errorCode: reasonCode,
        requestedCaptains,
        status: v?.status || "rejected",
        activeCaptainsCount: v?.activeCaptainsCount ?? 0,
        captainLimit: v?.captainLimit ?? 0,
        success: false,
        errorMessage: outcome.rejectionReason
      });
      const httpsCode: functions.https.FunctionsErrorCode =
        reasonCode === "MERCHANT_NOT_FOUND"
          ? "not-found"
          : reasonCode === "CAPTAIN_LIMIT_REACHED"
            ? "resource-exhausted"
            : "failed-precondition";
      throw new functions.https.HttpsError(httpsCode, outcome.rejectionReason, {
        reasonCode,
        requestId,
        subscriptionStatus: v?.status || "rejected"
      });
    }

    if (!outcome.isReplay) {
      await writeOperationLog({
        type: "captain_request_accepted",
        action: actionName,
        merchantId: uid,
        actorUid: uid,
        actorRole: "merchant",
        requestId,
        orderId: outcome.orderId,
        orderCode: outcome.orderCode,
        requestedCaptains,
        activeCaptainsCount: outcome.activeCaptainsCount ?? 0,
        captainLimit: outcome.captainLimit ?? 0,
        planId: outcome.planId ?? 0,
        planName: outcome.planName ?? "",
        subscriptionVersion: outcome.subscriptionVersion ?? 1,
        success: true
      });
    }

    return outcome;
  } catch (err: any) {
    if (err instanceof functions.https.HttpsError) {
      throw err;
    }
    const safeCode = String(err?.code || "INTERNAL_ORDER_ERROR");
    logSafeFunctionError(actionName, safeCode, uid, requestId, String(err?.message || "unexpected error"));
    await writeOperationLog({
      type: "captain_request_error",
      action: actionName,
      merchantId: uid,
      actorUid: uid,
      actorRole: "merchant",
      requestId,
      errorCode: safeCode,
      success: false,
      errorMessage: "تعذر إنشاء الطلب بسبب خطأ داخلي في السيرفر."
    });
    throw new functions.https.HttpsError(
      "internal",
      "تعذر إنشاء الطلب على السيرفر حالياً، يرجى المحاولة مرة أخرى.",
      { reasonCode: "INTERNAL_ORDER_ERROR", requestId }
    );
  }
}

/**
 * Primary Server-Enforced Endpoint for requesting a captain & creating an order.
 * Explicitly pinned to `us-central1` to match the Android Merchant App configuration.
 */
export const requestCaptains = functions
  .region("us-central1")
  .https.onCall(async (data, context) => {
    return executeServerOrderCreation(context, data || {}, "requestCaptains");
  });

/**
 * Unified Order Creation Endpoint sharing server subscription & capacity transaction enforcement.
 * Explicitly pinned to `us-central1` to match the Android Merchant App configuration.
 */
export const createOrderWithCode = functions
  .region("us-central1")
  .https.onCall(async (data, context) => {
    return executeServerOrderCreation(context, data || {}, "createOrderWithCode");
  });

export interface ManualSubscriptionRequestInput {
  merchantId: string;
  planId?: unknown;
  packageId?: unknown;
  planName?: unknown;
  maxCaptains?: unknown;
  expectedAmount?: unknown;
  amount?: unknown;
  senderPhone: unknown;
  transactionReference?: unknown;
  notes?: unknown;
  receiptStoragePath: unknown;
  requestId?: unknown;
  idempotencyKey?: unknown;
  nowMs?: number;
}

/**
 * Enforces that the requested merchantId (if supplied by the client) strictly matches
 * the authenticated Firebase Auth UID. Prevents cross-merchant impersonation.
 */
export function assertManualRequestMerchantOwnership(
  authUid: string,
  requestedMerchantId: unknown
): string {
  const cleanAuthUid = String(authUid || "").trim();
  if (!cleanAuthUid) {
    throw new functions.https.HttpsError("unauthenticated", "يجب تسجيل الدخول أولاً لإرسال طلب الاشتراك.");
  }
  const requested = String(requestedMerchantId ?? "").trim();
  if (requested && requested !== cleanAuthUid) {
    throw new functions.https.HttpsError(
      "permission-denied",
      "لا يمكنك إرسال طلب اشتراك لحساب تاجر آخر."
    );
  }
  return cleanAuthUid;
}

export interface ReceiptVerificationResult {
  exists: boolean;
  sizeBytes?: number;
  contentType?: string;
}

export async function defaultVerifyReceiptInStorage(
  storagePath: string
): Promise<ReceiptVerificationResult> {
  const candidateBuckets: string[] = [];
  try {
    const defaultBucket = admin.storage().bucket();
    if (defaultBucket?.name) candidateBuckets.push(defaultBucket.name);
  } catch {
    // Ignore default bucket lookup error and try explicit buckets below
  }
  const projectId =
    process.env.GCLOUD_PROJECT ||
    process.env.GCP_PROJECT ||
    admin.app().options.projectId ||
    "el-batal-express-956ae";
  for (const name of [
    `${projectId}.firebasestorage.app`,
    `${projectId}.appspot.com`
  ]) {
    if (name && !candidateBuckets.includes(name)) {
      candidateBuckets.push(name);
    }
  }

  for (const bucketName of candidateBuckets) {
    try {
      const bucket = admin.storage().bucket(bucketName);
      const file = bucket.file(storagePath);
      const [exists] = await file.exists();
      if (exists) {
        const [metadata] = await file.getMetadata();
        return {
          exists: true,
          sizeBytes: Number(metadata.size || 0),
          contentType: String(metadata.contentType || "")
        };
      }
    } catch (err: any) {
      console.warn(
        `[ManualSubscription] Storage check warning on bucket ${bucketName} for ${storagePath}: ${err?.message || err}`
      );
    }
  }
  return { exists: false };
}

/**
 * Core atomic transactional handler for manual Vodafone Cash subscription requests.
 * Security invariants enforced:
 * 1. Plan price, maxCaptains, and durationDays come EXCLUSIVELY from `OFFICIAL_SUBSCRIPTION_PLANS` (client values ignored).
 * 2. Receipt path MUST belong to `merchants/{merchantId}/subscription_receipts/{file}` and MUST exist in Firebase Storage.
 * 3. Subscription NEVER activates here — status is strictly set to `pending_review` with `isActive: false`, `isSubscribed: false`, `maxCaptains: 0`.
 * 4. Duplicate concurrent or repeat submissions are blocked via Firestore Transaction + idempotency key + pending/active status check.
 */
export async function applyManualSubscriptionRequest(
  targetDb: FirebaseFirestore.Firestore,
  input: ManualSubscriptionRequestInput,
  verifyReceipt: (storagePath: string) => Promise<ReceiptVerificationResult> = defaultVerifyReceiptInStorage
) {
  const merchantId = String(input.merchantId || "").trim();
  if (!merchantId) {
    throw new functions.https.HttpsError("unauthenticated", "معرف التاجر مطلوب.");
  }

  // 1. Validate plan STRICTLY against server-trusted catalog (ignore client-supplied price/maxCaptains)
  const trustedPlan = resolveTrustedServerPlan(input.planId ?? input.packageId);
  if (!trustedPlan) {
    throw new functions.https.HttpsError(
      "invalid-argument",
      "الباقة المختارة غير صالحة. يرجى اختيار إحدى الباقات الرسمية المتاحة."
    );
  }

  // 2. Validate Egyptian sender phone number
  const normalizedSenderPhone = normalizeEgyptianMobile(input.senderPhone);
  if (!normalizedSenderPhone) {
    throw new functions.https.HttpsError(
      "invalid-argument",
      "يرجى إدخال رقم الهاتف المحوَّل منه بشكل صحيح (رقم مصري يبدأ بـ 01)."
    );
  }

  // 3. Validate transaction reference & optional notes (sanitized)
  const transactionReference = String(input.transactionReference || "").trim().slice(0, 80);
  const notes = String(input.notes || "").trim().slice(0, 500);

  // 4. Validate receipt Storage path ownership & format
  const verifiedStoragePath = isValidMerchantReceiptStoragePath(merchantId, input.receiptStoragePath);
  if (!verifiedStoragePath) {
    throw new functions.https.HttpsError(
      "invalid-argument",
      "مسار صورة إيصال التحويل غير صالح أو لا يتبع حسابك. يرجى رفع صورة الإيصال من جديد."
    );
  }

  // 5. Verify uploaded receipt actually exists in Firebase Storage and is a valid image
  const storageCheck = await verifyReceipt(verifiedStoragePath);
  if (!storageCheck.exists) {
    throw new functions.https.HttpsError(
      "failed-precondition",
      "لم يتم العثور على صورة إيصال التحويل في Firebase Storage. يرجى رفع الإيصال أولاً قبل إرسال الطلب."
    );
  }
  if (
    typeof storageCheck.sizeBytes === "number" &&
    storageCheck.sizeBytes <= 0
  ) {
    throw new functions.https.HttpsError(
      "invalid-argument",
      "ملف إيصال التحويل فارغ أو تالف. يرجى رفع صورة واضحة للإيصال."
    );
  }
  if (
    storageCheck.contentType &&
    !/^image\/(jpeg|jpg|png|webp)$/i.test(storageCheck.contentType)
  ) {
    throw new functions.https.HttpsError(
      "invalid-argument",
      "نوع ملف الإيصال غير مدعوم. يرجى رفع صورة بصيغة JPG أو PNG أو WEBP."
    );
  }

  const rawRequestId = String(input.requestId || input.idempotencyKey || "").trim();
  const safeRequestId = /^[A-Za-z0-9_\-]{6,100}$/.test(rawRequestId)
    ? rawRequestId
    : `subreq_${Date.now()}_${crypto.randomBytes(4).toString("hex")}`;
  const nowMs = input.nowMs ?? Date.now();

  return targetDb.runTransaction(async (transaction) => {
    const merchantRef = targetDb.collection("merchants").doc(merchantId);
    const subRef = targetDb.collection("subscriptions").doc(merchantId);
    const reqRef = targetDb.collection("subscription_requests").doc(safeRequestId);

    const [merchantSnap, subSnap, existingReqSnap] = await Promise.all([
      transaction.get(merchantRef),
      transaction.get(subRef),
      transaction.get(reqRef)
    ]);

    if (!merchantSnap.exists || !isMerchantAccountDoc(merchantSnap.data())) {
      throw new functions.https.HttpsError("not-found", "تعذر العثور على حساب التاجر في قاعدة البيانات.");
    }

    // Idempotent replay check: if the exact same requestId was already submitted by this merchant, return it safely
    if (existingReqSnap.exists) {
      const existingReq = existingReqSnap.data() || {};
      if (existingReq.merchantId !== merchantId) {
        throw new functions.https.HttpsError("permission-denied", "معرف الطلب لا يتبع هذا التاجر.");
      }
      return {
        success: true,
        isReplay: true,
        requestId: safeRequestId,
        merchantId,
        status: String(existingReq.status || "pending_review"),
        planId: Number(existingReq.planId || trustedPlan.planId),
        packageId: Number(existingReq.packageId || existingReq.planId || trustedPlan.planId),
        planName: String(existingReq.planName || trustedPlan.planName),
        maxCaptains: Number(existingReq.maxCaptains || trustedPlan.maxCaptains),
        expectedAmount: Number(existingReq.expectedAmount || trustedPlan.price),
        amount: Number(existingReq.amount || existingReq.expectedAmount || trustedPlan.price),
        durationDays: Number(existingReq.durationDays || trustedPlan.durationDays),
        paymentMethod: "vodafone_cash_manual",
        notes: String(existingReq.notes || ""),
        receiptStoragePath: String(existingReq.receiptStoragePath || verifiedStoragePath),
        subscriptionVersion: Number(existingReq.subscriptionVersion || 1),
        message: "تم استلام طلب الاشتراك مسبقاً وهو حالياً في انتظار مراجعة الإدارة."
      };
    }

    const merchData = merchantSnap.data() || {};
    const subData = subSnap.exists ? (subSnap.data() || {}) : {};
    const currentStatus = normalizeSubscriptionStatus(
      subSnap.exists
        ? (subData.status || subData.subscriptionStatus)
        : merchData.subscriptionStatus
    );
    const currentEndMs = parseMillis(
      subData.expiresAt || subData.endDate || merchData.expiresAt || merchData.subscriptionEndDate
    );

    // Block duplicate submission if a pending_review request already exists
    if (currentStatus === "pending_review" || currentStatus === "pending") {
      throw new functions.https.HttpsError(
        "already-exists",
        "لديك بالفعل طلب اشتراك في انتظار مراجعة الإدارة حالياً. يرجى انتظار قرار الإدارة قبل إرسال طلب جديد."
      );
    }

    // Block duplicate submission if merchant already has an active unexpired subscription
    if (
      currentStatus === "active" &&
      subData.isActive !== false &&
      (currentEndMs === null || currentEndMs > nowMs)
    ) {
      throw new functions.https.HttpsError(
        "failed-precondition",
        "لديك اشتراك فعال وساري حالياً. لا يمكن إرسال طلب اشتراك جديد قبل انتهاء الباقة الحالية."
      );
    }

    const nextVersion =
      Math.max(Number(subData.subscriptionVersion || 0), Number(merchData.subscriptionVersion || 0)) + 1;
    const merchantName = String(merchData.name || merchData.restaurantName || "").trim();
    const merchantEmail = String(merchData.email || "").trim();
    const merchantPhone = String(merchData.phone || "").trim();

    // 1. Write immutable manual subscription request document for Admin Dashboard review queue
    transaction.set(reqRef, {
      requestId: safeRequestId,
      merchantId,
      merchantName,
      merchantEmail,
      merchantPhone,
      planId: trustedPlan.planId,
      packageId: trustedPlan.planId,
      planName: trustedPlan.planName,
      maxCaptains: trustedPlan.maxCaptains,
      captainLimit: trustedPlan.captainLimit,
      expectedAmount: trustedPlan.price,
      amount: trustedPlan.price,
      price: trustedPlan.price,
      durationDays: trustedPlan.durationDays,
      paymentMethod: "vodafone_cash_manual",
      senderPhone: normalizedSenderPhone,
      transactionReference,
      notes,
      receiptStoragePath: verifiedStoragePath,
      receiptContentType: storageCheck.contentType || "image/jpeg",
      receiptSizeBytes: storageCheck.sizeBytes || 0,
      status: "pending_review",
      subscriptionVersion: nextVersion,
      submittedAt: admin.firestore.FieldValue.serverTimestamp(),
      createdAt: admin.firestore.FieldValue.serverTimestamp(),
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });

    // 2. Update canonical subscriptions/{merchantId} to pending_review (NEVER active!)
    transaction.set(
      subRef,
      {
        merchantId,
        status: "pending_review",
        subscriptionStatus: "pending_review",
        isActive: false,
        isSubscribed: false,
        paymentStatus: "pending_review",
        paymentMethod: "vodafone_cash_manual",
        planId: trustedPlan.planId,
        packageId: trustedPlan.planId,
        planName: trustedPlan.planName,
        maxCaptains: 0,
        captainLimit: 0,
        requestedMaxCaptains: trustedPlan.maxCaptains,
        price: trustedPlan.price,
        amount: trustedPlan.price,
        durationDays: trustedPlan.durationDays,
        pendingRequestId: safeRequestId,
        senderPhone: normalizedSenderPhone,
        transactionReference,
        notes,
        receiptStoragePath: verifiedStoragePath,
        rejectionReason: admin.firestore.FieldValue.delete(),
        resubmissionReason: admin.firestore.FieldValue.delete(),
        suspensionReason: admin.firestore.FieldValue.delete(),
        updatedBy: merchantId,
        subscriptionVersion: nextVersion,
        lastAuditLoggedVersion: nextVersion,
        subscriptionUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      },
      { merge: true }
    );

    // 3. Mirror pending_review onto merchants/{merchantId} (keeping merchant account approval independent)
    transaction.set(
      merchantRef,
      {
        subscriptionStatus: "pending_review",
        isActive: false,
        isSubscribed: false,
        paymentStatus: "pending_review",
        paymentMethod: "vodafone_cash_manual",
        planId: trustedPlan.planId,
        planName: trustedPlan.planName,
        maxCaptains: 0,
        captainLimit: 0,
        price: trustedPlan.price,
        pendingRequestId: safeRequestId,
        rejectionReason: admin.firestore.FieldValue.delete(),
        resubmissionReason: admin.firestore.FieldValue.delete(),
        updatedBy: merchantId,
        subscriptionVersion: nextVersion,
        subscriptionUpdatedAt: admin.firestore.FieldValue.serverTimestamp(),
        updatedAt: admin.firestore.FieldValue.serverTimestamp()
      },
      { merge: true }
    );

    return {
      success: true,
      isReplay: false,
      requestId: safeRequestId,
      merchantId,
      status: "pending_review",
      planId: trustedPlan.planId,
      packageId: trustedPlan.planId,
      planName: trustedPlan.planName,
      maxCaptains: trustedPlan.maxCaptains,
      expectedAmount: trustedPlan.price,
      amount: trustedPlan.price,
      durationDays: trustedPlan.durationDays,
      paymentMethod: "vodafone_cash_manual",
      senderPhone: normalizedSenderPhone,
      transactionReference,
      notes,
      receiptStoragePath: verifiedStoragePath,
      subscriptionVersion: nextVersion,
      message: "تم إرسال طلب الاشتراك بنجاح وهو الآن في انتظار مراجعة الإدارة."
    };
  });
}

/**
 * Merchant Callable Cloud Function: Submits a manual Vodafone Cash subscription request for Admin review.
 * Never activates the subscription; only transitions state to `pending_review` after validating auth,
 * server-trusted plan pricing, Firebase Storage receipt ownership/existence, and duplicate guards.
 */
export const submitManualSubscriptionRequest = functions
  .region("us-central1")
  .https.onCall(async (data, context) => {
    if (!context.auth || !context.auth.uid) {
      throw new functions.https.HttpsError("unauthenticated", "يجب تسجيل الدخول أولاً لإرسال طلب الاشتراك.");
    }
    if (context.auth.token.email_verified !== true) {
      throw new functions.https.HttpsError(
        "failed-precondition",
        "يجب تأكيد البريد الإلكتروني أولاً قبل إرسال طلب الاشتراك."
      );
    }

    const uid = assertManualRequestMerchantOwnership(context.auth.uid, data?.merchantId);

    const result = await applyManualSubscriptionRequest(db, {
      merchantId: uid,
      planId: data?.planId ?? data?.packageId,
      packageId: data?.packageId ?? data?.planId,
      planName: data?.planName,
      maxCaptains: data?.maxCaptains,
      expectedAmount: data?.expectedAmount ?? data?.amount,
      amount: data?.amount ?? data?.expectedAmount,
      senderPhone: data?.senderPhone,
      transactionReference: data?.transactionReference,
      notes: data?.notes,
      receiptStoragePath: data?.receiptStoragePath,
      requestId: data?.requestId,
      idempotencyKey: data?.idempotencyKey
    });

    if (!result.isReplay) {
      await writeOperationLog({
        type: "manual_subscription_request_submitted",
        action: "submit_manual_vodafone_cash",
        merchantId: uid,
        actorUid: uid,
        actorRole: "merchant",
        requestId: result.requestId,
        planId: result.planId,
        packageId: result.packageId,
        planName: result.planName,
        expectedAmount: result.expectedAmount,
        amount: result.amount,
        maxCaptains: result.maxCaptains,
        paymentMethod: "vodafone_cash_manual",
        receiptStoragePath: result.receiptStoragePath,
        newStatus: "pending_review",
        subscriptionVersion: result.subscriptionVersion,
        success: true
      });
    }

    return result;
  });

/**
 * Trusted Admin Callable for reviewing a manual Vodafone Cash subscription request
 * (`approve`, `reject`, or `resubmission_required`).
 * Delegates directly to `applyAdminSubscriptionUpdate` so canonical `subscriptions/{merchantId}`,
 * `merchants/{merchantId}`, `subscription_requests/{requestId}`, and `operation_logs` stay 100% consistent.
 */
export const adminReviewManualSubscriptionRequest = functions
  .region("us-central1")
  .https.onCall(async (data, context) => {
    const authorizedAdmin = await isAdminCaller(context);
    if (!authorizedAdmin || !context.auth) {
      throw new functions.https.HttpsError("permission-denied", "هذه العملية مسموحة لمسؤولي الإدارة فقط.");
    }

    const requestId = String(data?.requestId || "").trim();
    const decision = String(data?.decision || data?.action || "").toLowerCase().trim();
    if (!requestId) {
      throw new functions.https.HttpsError("invalid-argument", "معرف الطلب requestId مطلوب.");
    }
    if (!["approve", "activate", "reject", "resubmission_required", "request_resubmission"].includes(decision)) {
      throw new functions.https.HttpsError("invalid-argument", "القرار يجب أن يكون approve أو reject أو resubmission_required.");
    }

    const reqSnap = await db.collection("subscription_requests").doc(requestId).get();
    if (!reqSnap.exists) {
      throw new functions.https.HttpsError("not-found", "طلب الاشتراك غير موجود.");
    }
    const reqData = reqSnap.data() || {};
    const merchantId = String(reqData.merchantId || data?.merchantId || "").trim();
    if (!merchantId) {
      throw new functions.https.HttpsError("failed-precondition", "بيانات التاجر غير مكتملة في الطلب.");
    }

    const mappedAction =
      decision === "reject"
        ? "reject"
        : decision === "resubmission_required" || decision === "request_resubmission"
          ? "resubmission_required"
          : "activate";
    const adminUid = context.auth.uid;
    const result = await applyAdminSubscriptionUpdate(db, adminUid, {
      merchantId,
      action: mappedAction,
      requestId,
      planId: data?.planId ?? reqData.planId,
      planName: data?.planName ?? reqData.planName,
      captainLimit: data?.captainLimit ?? reqData.maxCaptains,
      price: data?.price ?? reqData.expectedAmount,
      durationDays: data?.durationDays ?? reqData.durationDays ?? 30,
      reason: data?.resubmissionReason ?? data?.rejectionReason ?? data?.reason ?? "",
      approveMerchant: data?.approveMerchant === true
    });

    await writeOperationLog({
      type: "admin_manual_subscription_review",
      action: mappedAction,
      requestId,
      merchantId,
      actorUid: adminUid,
      actorRole: "admin",
      previousStatus: result.previousStatus,
      newStatus: result.status,
      planId: result.planId,
      planName: result.planName,
      captainLimit: result.captainLimit,
      reason: result.reason || null,
      subscriptionVersion: result.subscriptionVersion,
      success: true
    });

    return result;
  });



