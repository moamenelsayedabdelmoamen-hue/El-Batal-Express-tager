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

/**
 * Main auto-dispatch engine function.
 * Given an order ID and data, verifies restaurant subscription, searches for eligible captains,
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

  // 2. Verify Restaurant Subscription
  const restaurantId = orderData.restaurantId || orderData.userId;
  if (!restaurantId) {
    console.warn(`[AutoDispatch] Order ${orderId} has no restaurantId or userId. Cannot verify subscription.`);
    return false;
  }

  const restaurantDoc = await db.collection("restaurants").doc(restaurantId).get();
  if (!restaurantDoc.exists) {
    console.error(`[AutoDispatch] Restaurant doc ${restaurantId} not found.`);
    await db.collection("orders").doc(orderId).update({
      dispatchStatus: "failed",
      dispatchError: "المطعم غير موجود بالنظام",
      updatedAt: admin.firestore.FieldValue.serverTimestamp()
    });
    return false;
  }

  const restData = restaurantDoc.data() || {};
  const subStatus = (restData.subscriptionStatus || "").toLowerCase();

  // Check if subscription is expired or inactive
  let isSubActive = subStatus === "active";
  if (restData.subscriptionEndDate) {
    const endDate = restData.subscriptionEndDate.toDate ? restData.subscriptionEndDate.toDate() : new Date(restData.subscriptionEndDate);
    if (endDate.getTime() < Date.now()) {
      isSubActive = false;
    }
  }

  if (!isSubActive) {
    console.warn(`[AutoDispatch] Restaurant ${restaurantId} has inactive or expired subscription: ${subStatus}`);
    await db.collection("orders").doc(orderId).update({
      status: "cancelled",
      dispatchStatus: "failed",
      cancelReason: "اشتراك المطعم غير فعال أو منتهي",
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
  } else if (typeof orderData.restaurantLatitude === "number" && typeof orderData.restaurantLongitude === "number") {
    pickupLat = orderData.restaurantLatitude;
    pickupLng = orderData.restaurantLongitude;
  } else if (restData.location && typeof restData.location.latitude === "number") {
    pickupLat = restData.location.latitude;
    pickupLng = restData.location.longitude;
  } else if (typeof restData.latitude === "number" && typeof restData.longitude === "number") {
    pickupLat = restData.latitude;
    pickupLng = restData.longitude;
  }

  if (pickupLat === null || pickupLng === null) {
    console.warn(`[AutoDispatch] No valid location found for restaurant ${restaurantId}. Order ${orderId} remains in searching.`);
    await db.collection("orders").doc(orderId).update({
      dispatchStatus: "searching",
      dispatchNote: "في انتظار تحديد إحداثيات المطعم",
      lastSearchAt: admin.firestore.FieldValue.serverTimestamp()
    });
    return false;
  }

  // 4. Query eligible captains from 'captains' collection
  // Criteria:
  // - isOnline == true
  // - availabilityStatus == 'available'
  // - status == 'approved'
  // - currentOrderId == null
  const captainsSnapshot = await db.collection("captains")
    .where("isOnline", "==", true)
    .where("availabilityStatus", "==", "available")
    .where("status", "==", "approved")
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

  for (const doc of captainsSnapshot.docs) {
    const data = doc.data();

    // Ensure no active order
    if (data.currentOrderId && data.currentOrderId !== null && data.currentOrderId !== "") {
      continue;
    }

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
      if (diffMs > maxLocationStaleMs) {
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

        const currentOrderDoc = await transaction.get(orderRef);
        const currentCaptainDoc = await transaction.get(captainRef);

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
        // Re-verify captain is strictly available and not busy
        if (
          !freshCaptain.isOnline ||
          freshCaptain.availabilityStatus !== "available" ||
          (freshCaptain.currentOrderId && freshCaptain.currentOrderId !== null && freshCaptain.currentOrderId !== "")
        ) {
          throw new Error("CAPTAIN_BUSY_OR_OFFLINE");
        }

        const estimatedMins = Math.max(5, Math.round(candidate.distanceKm * 2.5 + 5));

        // Atomically update Captain: mark as busy
        transaction.update(captainRef, {
          availabilityStatus: "busy",
          currentOrderId: orderId,
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
          distanceToRestaurantKm: Math.round(candidate.distanceKm * 100) / 100,
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
            body: `طلب جديد من مطعم ${orderData.restaurantName || "البطل"} على بعد ${assignedCaptain.distanceKm.toFixed(1)} كم!`
          },
          data: {
            orderId: orderId,
            restaurantName: String(orderData.restaurantName || ""),
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
      afterData.isOnline === true &&
      afterData.availabilityStatus === "available" &&
      (!afterData.currentOrderId || afterData.currentOrderId === null) &&
      (beforeData.isOnline !== true || beforeData.availabilityStatus !== "available" || beforeData.currentOrderId !== null);

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
  status: string; // active, expired, suspended, cancelled, pending_payment, not_found
  errorMessage?: string;
  planId?: number;
  planName?: string;
  maxCaptains?: number;
  startDate?: any;
  endDate?: any;
  activeCaptainsCount?: number;
}

/**
 * Server-side subscription validation against 'subscriptions' collection (with fallback to 'restaurants').
 * Checks:
 * - Subscription document exists in 'subscriptions'
 * - Status is 'active'
 * - Current server time has not exceeded endDate
 * - Concurrent active captains limit is not exceeded
 */
export async function verifyMerchantSubscription(
  merchantId: string
): Promise<VerifySubscriptionResult> {
  const now = Date.now();

  // 1. Try querying 'subscriptions' collection by document ID or merchantId / restaurantId
  let subDoc: FirebaseFirestore.DocumentSnapshot | null = null;

  const directDoc = await db.collection("subscriptions").doc(merchantId).get();
  if (directDoc.exists) {
    subDoc = directDoc;
  } else {
    // Query by merchantId
    const q1 = await db.collection("subscriptions").where("merchantId", "==", merchantId).limit(1).get();
    if (!q1.empty) {
      subDoc = q1.docs[0];
    } else {
      // Query by restaurantId or userId
      const q2 = await db.collection("subscriptions").where("restaurantId", "==", merchantId).limit(1).get();
      if (!q2.empty) {
        subDoc = q2.docs[0];
      } else {
        const q3 = await db.collection("subscriptions").where("userId", "==", merchantId).limit(1).get();
        if (!q3.empty) {
          subDoc = q3.docs[0];
        }
      }
    }
  }

  // If not found in 'subscriptions', check 'restaurants' document as legitimate legacy bridge
  let subData: FirebaseFirestore.DocumentData | undefined;
  if (subDoc && subDoc.exists) {
    subData = subDoc.data() || {};
  } else {
    const restDoc = await db.collection("restaurants").doc(merchantId).get();
    if (restDoc.exists && (restDoc.data()?.subscriptionStatus || restDoc.data()?.subscriptionEndDate)) {
      subData = restDoc.data();
    }
  }

  if (!subData) {
    return {
      isValid: false,
      status: "not_found",
      errorMessage: "لا يوجد اشتراك مسجل لهذا الحساب، يرجى التواصل مع الإدارة للاشتراك في إحدى الباقات."
    };
  }

  const rawStatus = (subData.status || subData.subscriptionStatus || "").toString().toLowerCase().trim();
  const planId = Number(subData.planId || 1);
  const planName = subData.planName || (planId === 2 ? "الباقة المتوسطة" : planId === 3 ? "الباقة المميزة" : planId === 4 ? "الباقة المتقدمة" : "الباقة الأساسية");
  const maxCaptains = Number(subData.maxCaptains || (planId === 2 ? 4 : planId === 3 ? 6 : planId === 4 ? 8 : 2));

  // Determine end date
  let endDateMs: number | null = null;
  const rawEndDate = subData.endDate || subData.subscriptionEndDate || subData.expiryDate;
  if (rawEndDate) {
    if (typeof rawEndDate.toMillis === "function") {
      endDateMs = rawEndDate.toMillis();
    } else if (rawEndDate instanceof Date) {
      endDateMs = rawEndDate.getTime();
    } else if (typeof rawEndDate === "number") {
      endDateMs = rawEndDate;
    } else if (typeof rawEndDate === "string") {
      const parsed = Date.parse(rawEndDate);
      if (!isNaN(parsed)) endDateMs = parsed;
    }
  }

  // Handle various states
  if (rawStatus === "expired" || (endDateMs !== null && now >= endDateMs)) {
    return {
      isValid: false,
      status: "expired",
      planId,
      planName,
      maxCaptains,
      endDate: endDateMs,
      errorMessage: "اشتراكك منتهي، يرجى تجديد الاشتراك لمتابعة إنشاء وتوصيل الطلبات."
    };
  }

  if (rawStatus === "suspended" || rawStatus === "موقوف") {
    return {
      isValid: false,
      status: "suspended",
      planId,
      planName,
      maxCaptains,
      errorMessage: "اشتراكك موقوف مؤقتاً من قبل الإدارة، يرجى التواصل مع الدعم الفني."
    };
  }

  if (rawStatus === "cancelled" || rawStatus === "canceled" || rawStatus === "ملغي") {
    return {
      isValid: false,
      status: "cancelled",
      planId,
      planName,
      maxCaptains,
      errorMessage: "تم إلغاء الاشتراك، يرجى التواصل مع الإدارة لإعادة التفعيل."
    };
  }

  if (rawStatus === "pending_payment" || rawStatus === "في انتظار الدفع") {
    return {
      isValid: false,
      status: "pending_payment",
      planId,
      planName,
      maxCaptains,
      errorMessage: "اشتراكك في انتظار تأكيد سداد الفاتورة، يرجى إتمام عملية الدفع."
    };
  }

  if (rawStatus !== "active" && rawStatus !== "نشط") {
    return {
      isValid: false,
      status: rawStatus || "inactive",
      planId,
      planName,
      maxCaptains,
      errorMessage: "حالة الاشتراك الحالية غير مفعلة، يرجى مراجعة إدارة البطل إكسبريس."
    };
  }

  // Count active concurrent captains for this restaurant
  const activeOrdersSnap = await db.collection("orders")
    .where("restaurantId", "==", merchantId)
    .where("status", "in", ["accepted", "in_progress", "out_for_delivery", "captain_reached_store", "order_picked_up", "waiting_merchant_confirmation", "customer_confirmed", "customer_rejected", "في الطريق لك", "جاري التوصيل", "وصل المتجر", "تم الاستلام", "بانتظار تأكيد البيانات"])
    .get();

  const activeCaptainIds = new Set<string>();
  activeOrdersSnap.forEach((doc) => {
    const cid = doc.data().captainId;
    if (cid && typeof cid === "string" && cid.trim() !== "") {
      activeCaptainIds.add(cid.trim());
    }
  });

  const activeCaptainsCount = activeCaptainIds.size;
  if (activeCaptainsCount >= maxCaptains && maxCaptains > 0) {
    return {
      isValid: false,
      status: "active",
      planId,
      planName,
      maxCaptains,
      activeCaptainsCount,
      errorMessage: `لقد وصلت للحد الأقصى لعدد الكباتن المتزامنين المسموح بهم في باقتك (${maxCaptains} كباتن). يرجى انتظار تسليم أحد الطلبات الجارية أو ترقية الباقة.`
    };
  }

  return {
    isValid: true,
    status: "active",
    planId,
    planName,
    maxCaptains,
    endDate: endDateMs,
    activeCaptainsCount
  };
}

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

  // Get restaurant profile data
  const restDoc = await db.collection("restaurants").doc(uid).get();
  const restData = restDoc.exists ? restDoc.data() || {} : {};
  const restaurantName = restData.name || restData.restaurantName || "مطعم";
  const restaurantLoc = restData.location || (restData.latitude && restData.longitude ? new admin.firestore.GeoPoint(restData.latitude, restData.longitude) : new admin.firestore.GeoPoint(30.0444, 31.2357));

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
          restaurantId: uid,
          merchantId: uid,
          userId: uid,
          restaurantName: restaurantName,
          "restaurant name": restaurantName,
          restaurantLocation: restaurantLoc,
          pickupLocation: restaurantLoc,
          restaurantLatitude: (restaurantLoc as any).latitude,
          restaurantLongitude: (restaurantLoc as any).longitude,
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

