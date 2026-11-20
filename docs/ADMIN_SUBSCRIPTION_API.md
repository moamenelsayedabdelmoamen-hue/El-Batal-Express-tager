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

---

## 4. شكل المدخلات (Request Payload)

```json
{
  "merchantId": "UID_الخاص_بالتاجر",
  "action": "activate",
  "planId": 2,
  "planName": "الباقة المتوسطة",
  "captainLimit": 4,
  "price": 800,
  "durationDays": 30,
  "startDateMs": 1760000000000,
  "endDateMs": 1762592000000,
  "reason": "سبب الإيقاف أو الإلغاء (اختياري)",
  "approveMerchant": false
}
```

### تفاصيل الحقول

- `merchantId` (`string`, **إلزامي**): معرّف التاجر (`UID`) الموجود في مجموعة `merchants`.
- `action` (`string`, **إلزامي**): إحدى القيم الثماني في الجدول أعلاه.
- `planId` (`number`, اختياري): `1` (الأساسية - 2 كابتن - 400 جنيه)، `2` (المتوسطة - 4 كابتن - 800 جنيه)، `3` (المميزة - 6 كابتن - 1200 جنيه)، `4` (المتقدمة - 8 كابتن - 1600 جنيه).
- `planName` (`string`, اختياري): اسم الباقة (يُستنتج تلقائيًا من `planId` عند عدم تمريره).
- `captainLimit` أو `maxCaptains` (`number`, اختياري): الحد الأقصى للكباتن المتزامنين (يُستنتج تلقائيًا من `planId` إن لم يُمرر).
- `price` (`number`, اختياري): سعر الباقة (يُستنتج تلقائيًا من `planId` إن لم يُمرر).
- `durationDays` (`number`, اختياري، الافتراضي `30`، النطاق `1..3650`): مدة التفعيل أو التمديد بالأيام.
- `startDateMs` / `endDateMs` (`number`, اختياري): طوابع زمنية بالمللي ثانية (أو بالثواني وتُحوّل تلقائيًا) لتحديد فترة الاشتراك بدقة.
- `reason` (`string`, اختياري): سبب الإيقاف/الإلغاء الذي يظهر للتاجر وفي الإشعار.
- `approveMerchant` (`boolean`, اختياري، **الافتراضي `false`**):
  - **سياسة فصل الاعتماد:** تفعيل الاشتراك **لا يعتمد** حساب التاجر (`merchants/{uid}.status = "pending"`) تلقائيًا.
  - لتنفيذ اعتماد صريح لحساب التاجر أثناء التفعيل، يجب أن يمرّر المدير `approveMerchant: true` صراحةً (أو يعتمده عبر مسار اعتماد التجار المنفصل).

---

## 5. شكل الاستجابة (Success Response)

```json
{
  "success": true,
  "merchantId": "merchant_uid_123",
  "action": "activate",
  "previousStatus": "unsubscribed",
  "status": "active",
  "subscriptionVersion": 2,
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
  "reason": null
}
```

> إذا كان التاجر ما زال في حالة `pending` ولم يُمرّر المدير `approveMerchant: true`، تعود الاستجابة بـ `"merchantStatus": "pending"` و`"merchantApproved": false` لتنبيه لوحة التحكم أن الباقة فُعّلت لكن الحساب ينتظر اعتماد الإدارة.

---

## 6. رموز الأخطاء (`HttpsError`)

| رمز الخطأ (`code`) | السبب | رسالة الخطأ |
|---|---|---|
| `permission-denied` | المتصل غير مسجل أو لا يملك صلاحية Admin | `هذه العملية مسموحة لمسؤولي الإدارة فقط.` |
| `invalid-argument` | غياب `merchantId` أو تمرير `action` غير مدعوم | `معرف التاجر merchantId مطلوب.` أو `الإجراء غير مدعوم...` |
| `not-found` | عدم وجود مستند تاجر صالح في `merchants/{merchantId}` | `حساب التاجر غير موجود في مجموعة merchants.` |
| `aborted` / `internal` | فشل عابر في معاملة Firestore | يمكن إعادة المحاولة بأمان لأن العملية محمية بمعاملة ذرية. |

---

## 7. مثال استدعاء معتمد من لوحة التحكم (Firebase Web SDK)

```ts
import { getFunctions, httpsCallable } from "firebase/functions";

const functions = getFunctions();
const updateSub = httpsCallable(functions, "adminUpdateMerchantSubscription");

// 1. تفعيل باقة (بدون اعتماد تلقائي للتاجر)
await updateSub({
  merchantId: merchantUid,
  action: "activate",
  planId: 2,
  durationDays: 30,
  approveMerchant: false,
});

// 2. تغيير الباقة إلى 6 كباتن
await updateSub({
  merchantId: merchantUid,
  action: "change_plan",
  planId: 3,
  captainLimit: 6,
  price: 1200,
});

// 3. تمديد الاشتراك 30 يومًا إضافية
await updateSub({
  merchantId: merchantUid,
  action: "extend",
  durationDays: 30,
});

// 4. إيقاف الاشتراك مؤقتًا مع سبب
await updateSub({
  merchantId: merchantUid,
  action: "suspend",
  reason: "مراجعة مالية مؤقتة",
});
```

---

## 8. قواعد إلزامية للوحة التحكم

1. **ممنوع** إنشاء مستندات اشتراك بـ Auto ID في `subscriptions/{autoId}`؛ المسار القياسي الوحيد هو `subscriptions/{merchantId}` وتديره الدالة تلقائيًا.
2. **ممنوع** الكتابة المباشرة غير المحمية من لوحة التحكم على `subscriptions` أو حقول الاشتراك في `merchants` لتجنب تخطي `subscriptionVersion` أو سجل التدقيق `operation_logs`.
3. يتم حفظ سجل كل عملية إدارية تلقائيًا في `operation_logs` (للقراءة فقط من الإدارة، والكتابة حصرًا عبر السيرفر).
