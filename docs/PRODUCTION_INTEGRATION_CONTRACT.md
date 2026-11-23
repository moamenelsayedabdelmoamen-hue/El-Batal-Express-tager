# Production integration contract

## Authoritative backend
The dashboard repository is the single source of truth for Firebase Cloud Functions, Firestore rules, and Storage rules. The `functions/` folder in this repository is legacy/reference code only. Its deploy, shell, and start scripts intentionally fail; do not re-enable deployment from this repository.

## Subscription and payment rules
The app may display cached subscription information, but the server is authoritative for plan price, duration, active status, and captain capacity. Vodafone Cash receipts must remain pending review until an authorized admin verifies and approves them through the central callable service. Never activate a subscription from a client-side write.

## Order rules
Order creation must call the central `merchantCreateOrder` callable (or the supported backward-compatible alias), with an idempotency key. Do not write orders, assign captains, increment capacity, or settle captain earnings directly from Android.

## Required release checks
- Verify the callable region and names against the dashboard repository.
- Verify App Check initialization before server enforcement is enabled.
- Test subscription refresh on app resume and after admin approval/rejection.
- Test duplicate receipt submission, duplicate approval, expiry, and package capacity under concurrent order requests.
- Run Android unit tests and release build in CI; perform end-to-end device tests before publishing an APK.
