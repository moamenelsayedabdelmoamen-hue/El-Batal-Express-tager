# توثيق واجهة إدارة اشتراكات التجار (`adminUpdateMerchantSubscription`)

> **تنبيه معماري مهم:** كود لوحة تحكم الإدارة (Admin Dashboard) غير موجود داخل هذا المستودع؛ لذلك لم يتم تعديل أو فحص كود لوحة التحكم مباشرة هنا. يجب على فريق لوحة التحكم اعتماد واجهة Cloud Function الموثوقة `adminUpdateMerchantSubscription` لجميع عمليات إدارة الاشتراكات، وعدم الاعتماد على الكتابة المباشرة غير المحمية في Cloud Firestore.

---

## 1. اسم الدالة والمنطقة (Callable Cloud Function)

- **الاسم:** `adminUpdateMerchantSubscription`
- **النوع:** `https.onCall` (Firebase Callable Function)
- **مشروع Firebase:** `el-batal-express-956ae`
- **الهدف:** تنفيذ جميع تعديلات اشتراك التاجر داخل **Firestore Transaction ذرية واحدة** تقوم بـ:
  1. تحديث المستند القياسي `subscriptions/{merchantId}`.
  2. عكس الحقول الملخصة المتوافقة على `merchants/{merchantId}` بنفس رقم الإصدار الأحادي `subscriptionVersion`.
  3. تسجيل سجل تدقيق غير قابل للتزوير في `operation_logs`.
  4. الحفاظ على استقلالية اعتماد حساب التاجر (`merchants/{merchantId}.status`) عن حالة الاشتراك.

---

## 2. صلاحيات المدير المطلوبة (Admin Authorization)

يتم التحقق من هوية المتصل داخل السيرفر عبر `isAdminCaller(context)` ولا يُسمح بالتنفيذ إلا إذا تحقق **أحد** الشروط التالية مع وجود جلسة Firebase Auth صالحة:

1. Custom Claim على التوكن: `request.auth.token.role === "admin"`.
2. Custom Claim على التوكن: `request.auth.token.admin === true`.
3. بريد إلكتروني مؤكد (`email_verified === true`) ضمن القائمة الإدارية المعتمدة (`moamen.elsayed.abdelmoamen@gmail.com` أو `admin@elbatalexpress.com`).
4. وجود مستند للمستخدم داخل المجموعة `admins/{callerUid}`.

أي مستخدم غير إداري (تاجر، كابتن، أو غير مسجل) يتم رفضه فورًا برمز `permission-denied`.

---

## 3. العمليات المدعومة (`action`)

| القيمة (`action`) | الوصف | الحالة الناتجة (`status`) | ملاحظات |
|---|---|---|---|
| `activate` | تفعيل اشتراك جديد للتاجر | `active` | يضبط `isActive: true`, `isSubscribed: true`, `paymentStatus: "paid"` وتواريخ البداية والنهاية وحد الكباتن. |
| `reactivate` | إعادة تفعيل اشتراك موقوف أو منتهي | `active` | يزيل أسباب الإيقاف/الإلغاء ويزيد `subscriptionVersion`. |
| `suspend` | إيقاف مؤقت للاشتراك | `suspended` | يضبط `isActive: false`, `isSubscribed: false` ويحفظ `suspensionReason`. لا يمس الطلبات الجارية. |
| `deactivate` | تعطيل الاشتراك | `inactive` | يضبط `isActive: false`, `isSubscribed: false` ويحفظ سبب الإيقاف. |
| `cancel` | إلغاء الاشتراك نهائيًا | `cancelled` | يصفّر `captainLimit` و`maxCaptains` ويحفظ `cancellationReason`. |
| `change_plan` | ترقية أو تخفيض الباقة | `active` | يحدّث `planId`, `planName`, `captainLimit`, `maxCaptains`, `price` مع الإبقاء على تاريخ الانتهاء الساري إن لم يُمرّر `durationDays` أو `endDateMs`. |
| `extend` | تمديد مدة الاشتراك | `active` | يضيف `durationDays` فوق `endDate` الحالي إذا كان ساريًا (أو من الوقت الحالي إن كان منتهيًا). |
| `set_pending` | وضع الاشتراك قيد المراجعة | `pending` | يضبط `isActive: false`, `isSubscribed: false`, `captainLimit: 0`. |
| `reject` | رفض طلب اشتراك فودافون كاش اليدوي | `rejected` | يضبط `status: "rejected"`, `isActive: false`, `isSubscribed: false`, `captainLimit: 0`, يحفظ `rejectionReason` ويحدّث `subscription_requests/{requestId}` تلقائياً. |
| `resubmission_required` | طلب إعادة إرسال إيصال تحويل واضح | `resubmission_required` | يضبط `status: "resubmission_required"`, `isActive: false`, `isSubscribed: false`, `captainLimit: 0`, يحفظ `resubmissionReason` ويسمح للتاجر برفع إيصال جديد. |

---

## 4. نظام اشتراكات فودافون كاش اليدوي (Manual Vodafone Cash Flow)

تم إلغاء Paymob بالكامل واستبداله بنظام فودافون كاش اليدوي المرتبط بالسيرفر:

### أ. إعداد رقم محفظة فودافون كاش الرسمي والباقات (`settings/subscription_config`)
تقرأ شاشة التاجر رقم المحفظة الرسمي، اسم صاحب المحفظة (اختياري — يُعرض فقط عند وجوده)، التعليمات، وقائمة الباقات ديناميكياً من Cloud Firestore من المسار:
`settings/subscription_config`

```json
{
  "vodafoneCashNumber": "01095783737",
  "vodafoneCashAccountName": "البطل إكسبريس",
  "vodafoneCashInstructions": "1. قم بتحويل قيمة الباقة عبر فودافون كاش إلى الرقم الرسمي...",
  "plans": [
    { "id": 1, "name": "الباقة الأساسية", "maxCaptains": 2, "price": 400, "durationDays": 30 },
    { "id": 2, "name": "الباقة المتوسطة", "maxCaptains": 4, "price": 800, "durationDays": 30 },
    { "id": 3, "name": "الباقة المميزة", "maxCaptains": 6, "price": 1200, "durationDays": 30, "isPopular": true },
    { "id": 4, "name": "الباقة المتقدمة", "maxCaptains": 8, "price": 1600, "durationDays": 30 }
  ]
}
```

### ب. مسار رفع إيصال التحويل (Firebase Storage)
يرفع تطبيق التاجر صورة الإيصال **حصريًا** إلى:
`merchants/{merchantUid}/subscription_receipts/{requestId}.jpg`

قواعد `storage.rules` تسمح للتاجر المالك فقط بإنشاء صورة صالحة (`image/*` بحد أقصى `10MB`)، وتسمح للقراءة للمالك أو الإدارة، وتمنع التعديل/الحذف من العميل.

### ج. دالة إرسال طلب الاشتراك من تطبيق التاجر (`submitManualSubscriptionRequest`)
- **الاسم:** `submitManualSubscriptionRequest` — **المنطقة:** `us-central1`
- **المدخلات:** `merchantId` (من جلسة Firebase Auth فقط)، `packageId` / `planId` (`1..4`)، `planName`، `maxCaptains`، `amount` / `expectedAmount`، `senderPhone`، `transactionReference` (اختياري)، `notes` (اختياري)، `receiptStoragePath`، `requestId` / `idempotencyKey`.
- **ضمانات الأمان في السيرفر:**
  1. يرفض السيرفر أي محاولة لإرسال طلب باسم تاجر آخر برمز `permission-denied` عبر `assertManualRequestMerchantOwnership`.
  2. يتجاهل السيرفر السعر أو عدد الكباتن المرسل من العميل ويعتمد جدول الباقات الرسمي `OFFICIAL_SUBSCRIPTION_PLANS` (`400 / 800 / 1200 / 1600` ج.م لعدد `2 / 4 / 6 / 8` كابتن لمدة `30` يوماً).
  3. يتحقق من وجود ملف الإيصال فعلياً داخل Firebase Storage وملكيته لـ `merchants/{uid}/subscription_receipts/`.
  4. يمنع الطلبات المكررة عبر Transaction ذرية إذا كان التاجر لديه طلب `pending_review` أو باقة `active` غير منتهية.
  5. ينشئ المستند `subscription_requests/{requestId}` ويضبط `subscriptions/{uid}` و`merchants/{uid}` على `status: "pending_review"`, `isActive: false`, `isSubscribed: false`, `captainLimit: 0`.

### د. مراجعة الطلبات من لوحة الإدارة (`adminReviewManualSubscriptionRequest` أو `adminUpdateMerchantSubscription`)
يمكن للوحة الإدارة مراجعة أي طلب داخل `subscription_requests/{requestId}` عبر:
- `adminReviewManualSubscriptionRequest({ requestId, decision: "approve" | "reject" | "resubmission_required", reason?, durationDays?, approveMerchant? })`
- أو عبر `adminUpdateMerchantSubscription({ merchantId, action: "activate" | "reject" | "resubmission_required", requestId, planId, durationDays, reason })`

الحالات الأساسية الأربعة لطلب الاشتراك:
1. **`pending_review`** (`قيد مراجعة الإدارة`): طلب الكباتن محظور، ويظهر رقم الطلب للتاجر.
2. **`approved` / `active`** (`تمت الموافقة`): يُحدَّث `subscriptions/{merchantId}` و`merchants/{merchantId}` إلى `active` مع `approvedAt`, `startDate`, `endDate`, `maxCaptains`, `captainLimit` ويُحذف `rejectionReason` و`resubmissionReason` و`pendingRequestId`.
3. **`rejected`** (`مرفوض`): يُحفظ `rejectionReason` ويظهر سبب الرفض فوراً داخل تطبيق التاجر، ويُسمح له بإرسال طلب جديد مع استمرار حظر طلب الكباتن.
4. **`resubmission_required`** (`مطلوب إعادة إرسال الإيصال`): يُحفظ `resubmissionReason`، يستمر حظر طلب الكباتن، ويظهر السبب داخل شاشة التاجر مع إتاحة رفع إيصال جديد وإعادة الإرسال.

---

## 5. شكل المدخلات (`adminUpdateMerchantSubscription`)

```json
{
  "merchantId": "UID_الخاص_بالتاجر",
  "action": "activate",
  "requestId": "subreq_1760000000000_abcd1234",
  "planId": 2,
  "planName": "الباقة المتوسطة",
  "captainLimit": 4,
  "price": 800,
  "durationDays": 30,
  "startDateMs": 1760000000000,
  "endDateMs": 1762592000000,
  "reason": "سبب الإيقاف أو الرفض أو الإلغاء (اختياري)",
  "approveMerchant": false
}
```

### تفاصيل الحقول

- `merchantId` (`string`, **إلزامي**): معرّف التاجر (`UID`) الموجود في مجموعة `merchants`.
- `action` (`string`, **إلزامي**): إحدى القيم المدعومة (`activate`, `reactivate`, `suspend`, `deactivate`, `cancel`, `change_plan`, `extend`, `set_pending`, `reject`).
- `requestId` (`string`, اختياري): معرّف طلب فودافون كاش اليدوي داخل `subscription_requests` لتحديث حالته ذرياً عند الموافقة أو الرفض.
- `planId` (`number`, اختياري): `1` (الأساسية - 2 كابتن - 400 جنيه)، `2` (المتوسطة - 4 كابتن - 800 جنيه)، `3` (المميزة - 6 كابتن - 1200 جنيه)، `4` (المتقدمة - 8 كابتن - 1600 جنيه).
- `planName` (`string`, اختياري): اسم الباقة (يُستنتج تلقائيًا من `planId` عند عدم تمريره).
- `captainLimit` أو `maxCaptains` (`number`, اختياري): الحد الأقصى للكباتن المتزامنين (يُستنتج تلقائيًا من `planId` إن لم يُمرر).
- `price` (`number`, اختياري): سعر الباقة (يُستنتج تلقائيًا من `planId` إن لم يُمرر).
- `durationDays` (`number`, اختياري، الافتراضي `30`، النطاق `1..3650`): مدة التفعيل أو التمديد بالأيام.
- `startDateMs` / `endDateMs` (`number`, اختياري): طوابع زمنية بالمللي ثانية (أو بالثواني وتُحوّل تلقائيًا) لتحديد فترة الاشتراك بدقة.
- `reason` (`string`, اختياري): سبب الإيقاف/الرفض/الإلغاء الذي يظهر للتاجر وفي الإشعار.
- `approveMerchant` (`boolean`, اختياري، **الافتراضي `false`**):
  - **سياسة فصل الاعتماد:** تفعيل الاشتراك **لا يعتمد** حساب التاجر (`merchants/{uid}.status = "pending"`) تلقائيًا.
  - لتنفيذ اعتماد صريح لحساب التاجر أثناء التفعيل، يجب أن يمرّر المدير `approveMerchant: true` صراحةً (أو يعتمده عبر مسار اعتماد التجار المنفصل).

---

## 6. شكل الاستجابة (Success Response)

```json
{
  "success": true,
  "merchantId": "merchant_uid_123",
  "action": "activate",
  "previousStatus": "pending_review",
  "status": "active",
  "subscriptionVersion": 3,
  "planId": 2,
  "planName": "الباقة المتوسطة",
  "maxCaptains": 4,
  "captainLimit": 4,
  "price": 800,
  "startDateMs": 1760000000000,
  "endDateMs": 1762592000000,
  "merchantStatus": "approved",
  "merchantApproved": true,
  "merchantApprovedExplicitly": false,
  "reason": null,
  "requestId": "subreq_1760000000000_abcd1234"
}
```

> إذا كان التاجر ما زال في حالة `pending` ولم يُمرّر المدير `approveMerchant: true`، تعود الاستجابة بـ `"merchantStatus": "pending"` و`"merchantApproved": false` لتنبيه لوحة التحكم أن الباقة فُعّلت لكن الحساب ينتظر اعتماد الإدارة.

---

## 7. رموز الأخطاء (`HttpsError`)

| رمز الخطأ (`code`) | السبب | رسالة الخطأ |
|---|---|---|
| `permission-denied` | المتصل غير مسجل أو لا يملك صلاحية Admin | `هذه العملية مسموحة لمسؤولي الإدارة فقط.` |
| `invalid-argument` | غياب `merchantId` أو تمرير `action` غير مدعوم | `معرف التاجر merchantId مطلوب.` أو `الإجراء غير مدعوم...` |
| `not-found` | عدم وجود مستند تاجر صالح في `merchants/{merchantId}` | `حساب التاجر غير موجود في مجموعة merchants.` |
| `already-exists` | وجود طلب اشتراك `pending_review` مسبقاً | `لديك بالفعل طلب اشتراك في انتظار مراجعة الإدارة حالياً.` |
| `failed-precondition` | وجود باقة نشطة حالياً أو عدم وجود ملف الإيصال في Storage | `لديك اشتراك فعال حالياً...` أو `تعذر العثور على صورة إيصال التحويل...` |
| `aborted` / `internal` | فشل عابر في معاملة Firestore | يمكن إعادة المحاولة بأمان لأن العملية محمية بمعاملة ذرية. |

---

## 8. مثال استدعاء معتمد من لوحة التحكم (Firebase Web SDK)

```ts
import { getFunctions, httpsCallable } from "firebase/functions";

const functions = getFunctions(undefined, "us-central1");
const reviewManualReq = httpsCallable(functions, "adminReviewManualSubscriptionRequest");
const updateSub = httpsCallable(functions, "adminUpdateMerchantSubscription");

// 1. الموافقة على طلب فودافون كاش اليدوي وتفعيل الباقة
await reviewManualReq({
  requestId: "subreq_1760000000000_abcd1234",
  decision: "approve",
  durationDays: 30,
});

// 2. رفض طلب فودافون كاش اليدوي مع سبب واضح للتاجر
await reviewManualReq({
  requestId: "subreq_1760000000000_abcd1234",
  decision: "reject",
  reason: "صورة إيصال التحويل غير واضحة والمبلغ غير مطابق لقيمة الباقة",
});

// 3. تغيير الباقة إلى 6 كباتن
await updateSub({
  merchantId: merchantUid,
  action: "change_plan",
  planId: 3,
  captainLimit: 6,
  price: 1200,
});

// 4. تمديد الاشتراك 30 يومًا إضافية
await updateSub({
  merchantId: merchantUid,
  action: "extend",
  durationDays: 30,
});

// 5. إيقاف الاشتراك مؤقتًا مع سبب
await updateSub({
  merchantId: merchantUid,
  action: "suspend",
  reason: "مراجعة مالية مؤقتة",
});
```

---

## 9. قواعد إلزامية للوحة التحكم

1. **ممنوع** إنشاء مستندات اشتراك بـ Auto ID في `subscriptions/{autoId}`؛ المسار القياسي الوحيد هو `subscriptions/{merchantId}` وتديره الدالة تلقائيًا.
2. **ممنوع** الكتابة المباشرة غير المحمية من لوحة التحكم على `subscriptions` أو حقول الاشتراك في `merchants` لتجنب تخطي `subscriptionVersion` أو سجل التدقيق `operation_logs`.
3. يتم حفظ سجل كل عملية إدارية تلقائيًا في `operation_logs` (للقراءة فقط من الإدارة، والكتابة حصرًا عبر السيرفر).
4. يتم حفظ طلبات الدفع اليدوي عبر فودافون كاش في `subscription_requests/{requestId}` مع `receiptStoragePath` لتمكين الإدارة من معاينة الإيصال عبر Firebase Storage قبل الموافقة أو الرفض.

