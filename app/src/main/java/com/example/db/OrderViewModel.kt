package com.example.db

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.network.NetworkHelper
import com.example.notification.MerchantMessagingService
import com.example.notification.NotificationHelper
import com.google.firebase.Firebase
import com.google.firebase.app
import com.google.firebase.Timestamp
import com.google.firebase.auth.auth
import android.net.Uri
import com.google.firebase.firestore.GeoPoint
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.DocumentSnapshot
import com.google.firebase.firestore.ListenerRegistration
import com.google.firebase.firestore.MetadataChanges
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.Source
import com.google.firebase.firestore.firestore
import com.google.firebase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.TimeoutCancellationException
import com.example.storage.FirebaseStorageManager
import com.example.storage.ImageMigrationRecord
import com.example.storage.MigrationStepStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.tasks.await

object OrderCountdownTracker {
    const val TOTAL_COUNTDOWN_SECONDS = 20 * 60 // 20 minutes (1200 seconds)
    private val orderStartTimestamps = java.util.concurrent.ConcurrentHashMap<String, Long>()

    fun registerServerAcceptanceTimestamp(orderKey: String, orderNumericKey: String, captainKey: String, timestampMs: Long) {
        if (timestampMs <= 0L) return
        if (orderKey.isNotBlank()) {
            orderStartTimestamps[orderKey] = timestampMs
        }
        if (orderNumericKey.isNotBlank()) {
            orderStartTimestamps[orderNumericKey] = timestampMs
        }
        if (captainKey.isNotBlank()) {
            orderStartTimestamps[captainKey] = timestampMs
        }
    }

    fun getOrInitStartTimestamp(orderKey: String, captainKey: String): Long {
        if (orderKey.isNotBlank()) {
            val byOrder = orderStartTimestamps[orderKey]
            if (byOrder != null && byOrder > 0L) {
                return byOrder
            }
        }
        if (captainKey.isNotBlank()) {
            val byCaptain = orderStartTimestamps[captainKey]
            if (byCaptain != null && byCaptain > 0L) {
                if (orderKey.isNotBlank()) {
                    orderStartTimestamps.putIfAbsent(orderKey, byCaptain)
                }
                return byCaptain
            }
        }
        val now = System.currentTimeMillis()
        if (orderKey.isNotBlank()) {
            orderStartTimestamps.putIfAbsent(orderKey, now)
        }
        if (captainKey.isNotBlank()) {
            orderStartTimestamps.putIfAbsent(captainKey, now)
        }
        return (if (orderKey.isNotBlank()) orderStartTimestamps[orderKey] else orderStartTimestamps[captainKey]) ?: now
    }
}

// Safe string extraction helpers for Firestore documents to prevent ClassCastException / RuntimeException
fun DocumentSnapshot.safeString(field: String): String? {
    try {
        if (!contains(field)) return null
        val value = get(field) ?: return null
        return when (value) {
            is String -> value
            is List<*> -> {
                value.mapNotNull { item ->
                    when (item) {
                        null -> null
                        is Map<*, *> -> {
                            val name = item["name"] ?: item["title"] ?: item["itemName"] ?: item["item"]
                            val qty = item["quantity"] ?: item["count"] ?: item["qty"]
                            if (name != null && qty != null) "$name (x$qty)"
                            else name?.toString() ?: item.toString()
                        }
                        else -> item.toString()
                    }
                }.joinToString("، ")
            }
            is Map<*, *> -> {
                value.entries.joinToString("، ") { "${it.key}: ${it.value}" }
            }
            is Number -> value.toString()
            is Boolean -> value.toString()
            else -> value.toString()
        }
    } catch (e: Exception) {
        return null
    }
}

fun DocumentSnapshot.getAnyString(vararg fields: String): String? {
    for (f in fields) {
        val s = safeString(f)
        if (!s.isNullOrBlank()) return s
    }
    return null
}

data class PushNotificationBanner(
    val id: String = System.currentTimeMillis().toString(),
    val title: String,
    val body: String,
    val timestamp: String
)

data class CaptainLocationInfo(
    val captainId: String = "",
    val name: String = "",
    val phone: String = "",
    val photoUrl: String = "",
    val latitude: Double? = null,
    val longitude: Double? = null,
    val status: String = "",
    val estimatedArrival: String = ""
)

data class RestaurantProfile(
    val name: String = "",
    val phone: String = "",
    val address: String = "",
    val imageUri: String? = null,
    val logoUrl: String? = imageUri,
    val photoUrl: String? = logoUrl ?: imageUri,
    val storagePath: String = "",
    val email: String = "",
    val category: String = "مطعم",
    val status: String = "active",
    val rating: Double = 5.0,
    val ratingCount: Long = 0L,
    val subscriptionStatus: String = "unsubscribed",
    val subscriptionId: String? = null,
    val ownerId: String = "",
    val uid: String = "",
    val location: GeoPoint? = null,
    val latitude: Double? = location?.latitude,
    val longitude: Double? = location?.longitude,
    val locationUrl: String = ""
)

data class DriverProfile(
    val name: String = "",
    val phone: String = "",
    val vehicleType: String = "دراجة نارية",
    val rating: String = "5.0",
    val totalTrips: Int = 0,
    val isOnline: Boolean = true,
    val photoUrl: String = ""
)

data class CaptainProfile(
    val uid: String = "",
    val name: String = "",
    val phone: String = "",
    val profileImageUrl: String = "",
    val role: String = "captain",
    val status: String = "approved", // approved, pending, suspended
    val isOnline: Boolean = true,
    val availabilityStatus: String = "available", // available, busy, offline
    val currentOrderId: String? = null,
    val latitude: Double? = 30.0444,
    val longitude: Double? = 31.2357,
    val lastLocationUpdate: Timestamp? = null,
    val rating: String = "5.0",
    val totalTrips: Int = 0,
    val vehicleType: String = "دراجة نارية"
)

// SubscriptionStatusState is declared in SubscriptionSyncResolver.kt in com.example.db

class OrderViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: OrderRepository
    private val subscriptionRepository = MerchantSubscriptionRepository()

    @Volatile
    private var lastServerActiveCaptainsCount: Int? = null

    val ordersState: StateFlow<List<DeliveryOrderEntity>>

    private val _firestoreOrders = MutableStateFlow<List<DeliveryOrderEntity>>(emptyList())
    val firestoreOrders: StateFlow<List<DeliveryOrderEntity>> = _firestoreOrders.asStateFlow()

    private val _pendingOrders = MutableStateFlow<List<DeliveryOrderEntity>>(emptyList())
    val pendingOrders: StateFlow<List<DeliveryOrderEntity>> = _pendingOrders.asStateFlow()

    private val _captainActiveOrders = MutableStateFlow<List<DeliveryOrderEntity>>(emptyList())
    val captainActiveOrders: StateFlow<List<DeliveryOrderEntity>> = _captainActiveOrders.asStateFlow()

    private val _captainCompletedOrders = MutableStateFlow<List<DeliveryOrderEntity>>(emptyList())
    val captainCompletedOrders: StateFlow<List<DeliveryOrderEntity>> = _captainCompletedOrders.asStateFlow()

    private val _driverProfile = MutableStateFlow(DriverProfile())
    val driverProfile: StateFlow<DriverProfile> = _driverProfile.asStateFlow()

    private val _captainProfile = MutableStateFlow(CaptainProfile())
    val captainProfile: StateFlow<CaptainProfile> = _captainProfile.asStateFlow()

    private val _isRefreshing = MutableStateFlow(false)
    val isRefreshing: StateFlow<Boolean> = _isRefreshing.asStateFlow()

    fun refreshRestaurantFeed() {
        viewModelScope.launch {
            _isRefreshing.value = true
            try {
                listenToFirestore()
                // Fetch merchant profile and orders exclusively from merchants/{uid} and orders
                val user = Firebase.auth.currentUser
                val uid = user?.uid
                if (uid != null && user.isEmailVerified) {
                    try {
                        val doc = Firebase.firestore.collection("merchants").document(uid).get().await()
                        if (doc != null && doc.exists() && doc.safeString("accountType")?.trim()?.lowercase() == "merchant") {
                            _restaurantProfile.value = mapDocToRestaurantProfile(doc, uid)
                        }
                    } catch (e: Exception) {
                        Log.w("OrderViewModel", "Failed to refresh merchant profile directly: ${e.message}")
                    }

                    try {
                        val db = Firebase.firestore
                        val byUser = try { db.collection("orders").whereEqualTo("userId", uid).get().await().documents } catch (_: Exception) { emptyList() }
                        val byRest = try { db.collection("orders").whereEqualTo("restaurantId", uid).get().await().documents } catch (_: Exception) { emptyList() }
                        val byMerch = try { db.collection("orders").whereEqualTo("merchantId", uid).get().await().documents } catch (_: Exception) { emptyList() }

                        userOrdersDocs.clear()
                        byUser.forEach { userOrdersDocs[it.id] = it }
                        restaurantOrdersDocs.clear()
                        byRest.forEach { restaurantOrdersDocs[it.id] = it }
                        merchantOrdersDocs.clear()
                        byMerch.forEach { merchantOrdersDocs[it.id] = it }

                        rebuildMergedFirestoreOrders()
                    } catch (e: Exception) {
                        Log.w("OrderViewModel", "Failed to refresh orders directly: ${e.message}")
                    }
                }
                delay(400)
            } finally {
                _isRefreshing.value = false
            }
        }
    }

    private val _walletEarnings = MutableStateFlow(0.0)
    val walletEarnings: StateFlow<Double> = _walletEarnings.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _errorMessage = MutableStateFlow<String?>(null)
    val errorMessage: StateFlow<String?> = _errorMessage.asStateFlow()

    private val _isSaveSuccess = MutableStateFlow(false)
    val isSaveSuccess: StateFlow<Boolean> = _isSaveSuccess.asStateFlow()

    fun resetSaveRestaurantState() {
        _isSaving.value = false
        _errorMessage.value = null
        _isSaveSuccess.value = false
    }

    private val _snackbarMessage = MutableStateFlow<String?>(null)
    val snackbarMessage: StateFlow<String?> = _snackbarMessage.asStateFlow()

    private val _restaurantProfile = MutableStateFlow(RestaurantProfile())
    val restaurantProfile: StateFlow<RestaurantProfile> = _restaurantProfile.asStateFlow()

    private val _subscriptionStatus = MutableStateFlow(SubscriptionStatusState())
    val subscriptionStatus: StateFlow<SubscriptionStatusState> = _subscriptionStatus.asStateFlow()

    private val _subscriptionPackages = MutableStateFlow<List<SubscriptionPlanOption>>(emptyList())
    val subscriptionPackages: StateFlow<List<SubscriptionPlanOption>> = _subscriptionPackages.asStateFlow()

    private val _isLoadingPackages = MutableStateFlow(false)
    val isLoadingPackages: StateFlow<Boolean> = _isLoadingPackages.asStateFlow()

    private val _packagesError = MutableStateFlow<String?>(null)
    val packagesError: StateFlow<String?> = _packagesError.asStateFlow()

    private val _subscriptionHistory = MutableStateFlow<List<SubscriptionHistoryItem>>(emptyList())
    val subscriptionHistory: StateFlow<List<SubscriptionHistoryItem>> = _subscriptionHistory.asStateFlow()

    private val _manualSubscriptionRequests = MutableStateFlow<List<ManualSubscriptionRequestRecord>>(emptyList())
    val manualSubscriptionRequests: StateFlow<List<ManualSubscriptionRequestRecord>> = _manualSubscriptionRequests.asStateFlow()

    private val _merchantPayments = MutableStateFlow<List<MerchantPaymentRecord>>(emptyList())
    val merchantPayments: StateFlow<List<MerchantPaymentRecord>> = _merchantPayments.asStateFlow()

    private val _isLoadingPayments = MutableStateFlow(false)
    val isLoadingPayments: StateFlow<Boolean> = _isLoadingPayments.asStateFlow()

    private val _paymentsError = MutableStateFlow<String?>(null)
    val paymentsError: StateFlow<String?> = _paymentsError.asStateFlow()

    private val _vodafoneCashSettings = MutableStateFlow(VodafoneCashSettings(isLoading = true))
    val vodafoneCashSettings: StateFlow<VodafoneCashSettings> = _vodafoneCashSettings.asStateFlow()

    private val _manualSubscriptionUiState = MutableStateFlow<ManualSubscriptionUiState>(ManualSubscriptionUiState.Idle)
    val manualSubscriptionUiState: StateFlow<ManualSubscriptionUiState> = _manualSubscriptionUiState.asStateFlow()

    private val _isSubmittingOrder = MutableStateFlow(false)
    val isSubmittingOrder: StateFlow<Boolean> = _isSubmittingOrder.asStateFlow()

    private val orderSubmissionInFlight = AtomicBoolean(false)

    private val _pushNotificationState = MutableStateFlow<PushNotificationBanner?>(null)
    val pushNotificationState: StateFlow<PushNotificationBanner?> = _pushNotificationState.asStateFlow()

    private val _latestCreatedOrderCode = MutableStateFlow<String?>(null)
    val latestCreatedOrderCode: StateFlow<String?> = _latestCreatedOrderCode.asStateFlow()

    fun clearLatestCreatedOrderCode() {
        _latestCreatedOrderCode.value = null
    }

    private val _isMigratingImage = MutableStateFlow(false)
    val isMigratingImage: StateFlow<Boolean> = _isMigratingImage.asStateFlow()

    private val _migrationRecordState = MutableStateFlow<ImageMigrationRecord?>(null)
    val migrationRecordState: StateFlow<ImageMigrationRecord?> = _migrationRecordState.asStateFlow()

    fun migrateCurrentProfileImageToFirebaseStorage() {
        val currentUser = Firebase.auth.currentUser ?: return
        val uid = currentUser.uid
        val currentUrl = _restaurantProfile.value.photoUrl
            ?: _restaurantProfile.value.logoUrl
            ?: _restaurantProfile.value.imageUri
            ?: return

        if (!FirebaseStorageManager.isLegacyExternalUrl(currentUrl)) {
            _snackbarMessage.value = "الصورة الحالية محفوظة بالفعل على Firebase Storage ولا تحتاج إلى ترحيل."
            return
        }

        _isMigratingImage.value = true
        viewModelScope.launch {
            try {
                val record = FirebaseStorageManager.migrateLegacyImageSafely(
                    merchantUid = uid,
                    legacyImageUrl = currentUrl
                )
                _migrationRecordState.value = record
                if (record.status == MigrationStepStatus.SUCCESS && !record.newFirebaseUrl.isNullOrBlank()) {
                    _restaurantProfile.value = _restaurantProfile.value.copy(
                        imageUri = record.newFirebaseUrl,
                        logoUrl = record.newFirebaseUrl,
                        photoUrl = record.newFirebaseUrl,
                        storagePath = record.storagePath.orEmpty()
                    )
                    _snackbarMessage.value = "تم ترحيل الصورة بنجاح إلى Firebase Storage"
                } else {
                    _snackbarMessage.value = record.errorMessage ?: "فشل ترحيل الصورة؛ تم الاحتفاظ بالرابط القديم ويمكنك إعادة المحاولة"
                }
            } finally {
                _isMigratingImage.value = false
            }
        }
    }

    fun retryFailedImageMigration() {
        migrateCurrentProfileImageToFirebaseStorage()
    }

    private var profileListener: ListenerRegistration? = null
    private var merchantProfileListener: ListenerRegistration? = null
    private var ordersListener: ListenerRegistration? = null
    private var restaurantOrdersListener: ListenerRegistration? = null
    private var merchantOrdersListener: ListenerRegistration? = null
    private var pendingListener: ListenerRegistration? = null
    private var captainOrdersListener: ListenerRegistration? = null
    private var driverListener: ListenerRegistration? = null
    private var restaurantSubListener: ListenerRegistration? = null
    private var merchantSubscriptionsQueryListener: ListenerRegistration? = null
    private var subscriptionPackagesListener: ListenerRegistration? = null
    private var subscriptionHistoryListener: ListenerRegistration? = null
    private var manualRequestsListener: ListenerRegistration? = null
    private var merchantPaymentsListener: ListenerRegistration? = null
    private var vodafoneSettingsListener: ListenerRegistration? = null
    private var orderTrackingListener: ListenerRegistration? = null
    private var captainTrackingListener: ListenerRegistration? = null
    private var currentTrackedCaptainId: String? = null
    @Volatile
    private var activeListenersUid: String? = null

    @Volatile
    private var latestSubscriptionSnapshot: RawSubscriptionSnapshot = RawSubscriptionSnapshot()
    @Volatile
    private var latestQuerySubscriptionSnapshots: List<RawSubscriptionSnapshot> = emptyList()
    @Volatile
    private var latestArchivedHistoryItems: List<SubscriptionHistoryItem> = emptyList()
    @Volatile
    private var latestRawVodafoneSettings: VodafoneCashSettings = VodafoneCashSettings(isLoading = true)
    @Volatile
    private var latestMerchantSubSnapshot: RawSubscriptionSnapshot = RawSubscriptionSnapshot()
    @Volatile
    private var hasVerifiedServerSubscriptionOnce: Boolean = false
    @Volatile
    private var isInitialSubscriptionCheckInProgress: Boolean = false
    @Volatile
    private var latestSubscriptionServerError: String? = null
    @Volatile
    private var hasEmittedSubscriptionOnce: Boolean = false
    @Volatile
    private var hasLoadedPaymentsOnce: Boolean = false
    @Volatile
    private var hasLoadedManualRequestsOnce: Boolean = false
    private val knownPaymentIds = ConcurrentHashMap.newKeySet<String>()
    private val knownRequestStatuses = ConcurrentHashMap<String, String>()
    private val notifiedExpiringSoonKeys = ConcurrentHashMap.newKeySet<String>()
    @Volatile
    private var lastObservedOnlineState: Boolean = NetworkHelper.isOnline.value

    private val userOrdersDocs = mutableMapOf<String, DocumentSnapshot>()
    private val restaurantOrdersDocs = mutableMapOf<String, DocumentSnapshot>()
    private val merchantOrdersDocs = mutableMapOf<String, DocumentSnapshot>()
    private val captainInfoCache = ConcurrentHashMap<String, CaptainLocationInfo>()

    private val _trackedCaptainInfo = MutableStateFlow<CaptainLocationInfo?>(null)
    val trackedCaptainInfo: StateFlow<CaptainLocationInfo?> = _trackedCaptainInfo.asStateFlow()

    fun updateRestaurantLocation(lat: Double, lng: Double, onComplete: ((Boolean) -> Unit)? = null) {
        if (!NetworkHelper.isNetworkAvailable(getApplication())) {
            val noNetMsg = "لا يمكن تعديل الموقع: أنت غير متصل بالإنترنت!"
            _snackbarMessage.value = noNetMsg
            NetworkHelper.notifyNoInternet(getApplication(), "تعديل وتحديد موقع المطعم")
            onComplete?.invoke(false)
            return
        }
        val geoPoint = GeoPoint(lat, lng)
        _restaurantProfile.value = _restaurantProfile.value.copy(
            location = geoPoint,
            latitude = lat,
            longitude = lng
        )
        val currentUser = Firebase.auth.currentUser
        if (currentUser == null || !currentUser.isEmailVerified) {
            onComplete?.invoke(false)
            return
        }
        val uid = currentUser.uid
        viewModelScope.launch {
            try {
                val merchantDocRef = Firebase.firestore.collection("merchants").document(uid)
                val existing = merchantDocRef.get().await()
                if (!existing.exists() || existing.safeString("accountType")?.trim()?.lowercase() != "merchant") {
                    _snackbarMessage.value = "لا يمكن حفظ الموقع: حساب التاجر غير موجود في merchants"
                    onComplete?.invoke(false)
                    return@launch
                }
                val updates = hashMapOf<String, Any>(
                    "location" to geoPoint,
                    "latitude" to lat,
                    "longitude" to lng,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                merchantDocRef.set(updates, SetOptions.merge()).await()
                _snackbarMessage.value = "تم حفظ موقع المتجر بنجاح"
                onComplete?.invoke(true)
            } catch (e: Exception) {
                Log.e("OrderViewModel", "Failed to update merchant location: ${e.message}", e)
                _snackbarMessage.value = "تعذر حفظ الموقع: ${e.localizedMessage}"
                onComplete?.invoke(false)
            }
        }
    }

    fun startTrackingOrderCaptain(orderId: String) {
        stopTrackingOrderCaptain()
        val uid = Firebase.auth.currentUser?.uid ?: return
        val cleanOrderId = orderId.trim()
        if (cleanOrderId.isEmpty()) return

        orderTrackingListener = Firebase.firestore.collection("orders")
            .document(cleanOrderId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w("OrderViewModel", "Error tracking order $cleanOrderId: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val ownerIds = listOfNotNull(
                        snapshot.safeString("userId"),
                        snapshot.safeString("restaurantId"),
                        snapshot.safeString("merchantId")
                    )
                    if (uid !in ownerIds) {
                        _trackedCaptainInfo.value = null
                        return@addSnapshotListener
                    }

                    val captId = snapshot.getAnyString(
                        "captainId", "driverId", "captain_id", "driver_id",
                        "assignedCaptainId", "acceptedBy", "captainUid", "driverUid"
                    ) ?: ""
                    val orderCaptName = snapshot.getAnyString(
                        "captainName", "driver_name", "driverName", "captain_name", "captainFullName", "driverFullName"
                    ) ?: ""
                    val orderCaptPhone = snapshot.getAnyString(
                        "captainPhone", "captain_phone", "driverPhone", "driver_phone",
                        "captainMobile", "driverMobile", "captainPhoneNumber"
                    ) ?: ""
                    val rawCaptPhoto = snapshot.getAnyString(
                        "captainPhotoUrl", "captain_photo_url", "captain_photo", "captainPhoto",
                        "driverPhotoUrl", "driver_photo_url", "driverPhoto", "driver_photo",
                        "captainImage", "captainImageUrl", "driverImage", "driverImageUrl",
                        "captainAvatar", "driverAvatar", "captainProfileImage", "driverProfileImage",
                        "captainLogo", "driverLogo", "captainStoragePath", "captainPhotoPath", "driverStoragePath"
                    ) ?: ""
                    val cachedCaptain = if (captId.isNotBlank()) captainInfoCache[captId] else null
                    val resolvedOrderPath = if (rawCaptPhoto.isNotBlank() &&
                        !FirebaseStorageManager.isRemoteHttpUrl(rawCaptPhoto) &&
                        !FirebaseStorageManager.isPlaceholderAvatarUrl(rawCaptPhoto) &&
                        !FirebaseStorageManager.isLocalDeviceUri(rawCaptPhoto)
                    ) {
                        resolvedOrderStoragePhotoCache[rawCaptPhoto].orEmpty()
                    } else ""

                    val mergedName = FirebaseStorageManager.selectAuthoritativeCaptainName(
                        captainDocName = cachedCaptain?.name,
                        orderDocName = orderCaptName,
                        captainId = captId
                    )
                    val mergedPhone = FirebaseStorageManager.selectAuthoritativeCaptainPhone(
                        captainDocPhone = cachedCaptain?.phone,
                        orderDocPhone = orderCaptPhone
                    )
                    val mergedPhoto = FirebaseStorageManager.selectBestCaptainPhotoUrl(
                        captainDocPhotoUrl = cachedCaptain?.photoUrl,
                        resolvedOrderStorageUrl = resolvedOrderPath,
                        orderDocPhotoUrl = rawCaptPhoto
                    )

                    val status = snapshot.safeString("status") ?: ""
                    val eta = snapshot.safeString("estimatedArrival") ?: ""
                    val captainGeo = snapshot.getGeoPoint("captainLocation")
                    val captLat = captainGeo?.latitude
                        ?: (snapshot.get("captainLatitude") as? Number)?.toDouble()
                        ?: cachedCaptain?.latitude
                    val captLng = captainGeo?.longitude
                        ?: (snapshot.get("captainLongitude") as? Number)?.toDouble()
                        ?: cachedCaptain?.longitude

                    if (captId.isNotBlank() && captId != currentTrackedCaptainId) {
                        attachCaptainProfileListener(captId)
                    }

                    if (captId.isNotBlank() || FirebaseStorageManager.isValidCaptainName(mergedName)) {
                        _trackedCaptainInfo.value = CaptainLocationInfo(
                            captainId = captId,
                            name = mergedName,
                            phone = mergedPhone,
                            photoUrl = mergedPhoto,
                            latitude = captLat,
                            longitude = captLng,
                            status = status,
                            estimatedArrival = eta
                        )
                        if (captId.isNotBlank() && (
                                cachedCaptain == null ||
                                    !FirebaseStorageManager.isValidCaptainName(cachedCaptain.name) ||
                                    cachedCaptain.phone.isBlank() ||
                                    !FirebaseStorageManager.isFirebaseStorageUrl(cachedCaptain.photoUrl)
                                )
                        ) {
                            fetchAndCacheCaptains(
                                captainIds = setOf(captId),
                                storagePathsToResolve = if (resolvedOrderPath.isBlank() &&
                                    rawCaptPhoto.isNotBlank() &&
                                    !FirebaseStorageManager.isRemoteHttpUrl(rawCaptPhoto) &&
                                    !FirebaseStorageManager.isPlaceholderAvatarUrl(rawCaptPhoto) &&
                                    !FirebaseStorageManager.isLocalDeviceUri(rawCaptPhoto)
                                ) setOf(rawCaptPhoto) else emptySet()
                            )
                        }
                    } else {
                        _trackedCaptainInfo.value = null
                    }
                } else {
                    _trackedCaptainInfo.value = null
                }
            }
    }

    private fun attachCaptainProfileListener(captId: String) {
        captainTrackingListener?.remove()
        captainTrackingListener = null
        currentTrackedCaptainId = captId

        captainTrackingListener = Firebase.firestore.collection("captains")
            .document(captId)
            .addSnapshotListener { captSnap, error ->
                if (error != null) {
                    Log.w("OrderViewModel", "Error listening to captain $captId: ${error.message}")
                    return@addSnapshotListener
                }
                if (captSnap != null && captSnap.exists()) {
                    viewModelScope.launch(Dispatchers.IO) {
                        val realName = captSnap.getAnyString(
                            "name", "fullName", "full_name", "driver_name", "driverName", "displayName", "captainName", "userName"
                        ) ?: ""
                        val realPhone = captSnap.getAnyString(
                            "phone", "phoneNumber", "phone_number", "mobile", "mobileNumber", "captainPhone", "driverPhone"
                        ) ?: ""
                        val realPhoto = extractCaptainPhotoFromDoc(captSnap, captSnap.id, captId)
                        val geo = captSnap.getGeoPoint("location") ?: captSnap.getGeoPoint("currentLocation")
                        val lat = geo?.latitude ?: (captSnap.get("latitude") as? Number)?.toDouble()
                        val lng = geo?.longitude ?: (captSnap.get("longitude") as? Number)?.toDouble()

                        val info = CaptainLocationInfo(
                            captainId = captId,
                            name = realName,
                            phone = realPhone,
                            photoUrl = realPhoto,
                            latitude = lat,
                            longitude = lng
                        )
                        captainInfoCache[captId] = info
                        if (realPhone.isNotBlank()) {
                            captainInfoByPhoneCache[realPhone.trim()] = info
                        }

                        withContext(Dispatchers.Main) {
                            val currentTracked = _trackedCaptainInfo.value
                            if (currentTracked != null && (currentTracked.captainId == captId || currentTracked.captainId.isBlank())) {
                                _trackedCaptainInfo.value = currentTracked.copy(
                                    captainId = captId,
                                    name = FirebaseStorageManager.selectAuthoritativeCaptainName(realName, currentTracked.name, captId),
                                    phone = FirebaseStorageManager.selectAuthoritativeCaptainPhone(realPhone, currentTracked.phone),
                                    photoUrl = FirebaseStorageManager.selectBestCaptainPhotoUrl(realPhoto, null, currentTracked.photoUrl),
                                    latitude = lat ?: currentTracked.latitude,
                                    longitude = lng ?: currentTracked.longitude
                                )
                            }
                            rebuildMergedFirestoreOrders()
                        }
                    }
                }
            }
    }

    fun stopTrackingOrderCaptain() {
        orderTrackingListener?.remove()
        orderTrackingListener = null
        captainTrackingListener?.remove()
        captainTrackingListener = null
        currentTrackedCaptainId = null
        _trackedCaptainInfo.value = null
    }

    private val authStateListener = com.google.firebase.auth.FirebaseAuth.AuthStateListener { auth ->
        val user = auth.currentUser
        if (user != null && user.isEmailVerified) {
            listenToFirestore()
        } else {
            clearListeners()
        }
    }

    init {
        val database = AppDatabase.getInstance(application)
        repository = OrderRepository(database.orderDao())

        viewModelScope.launch {
            repository.ensureInitialData()
        }

        ordersState = repository.allOrders.stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

        Firebase.auth.addAuthStateListener(authStateListener)

        viewModelScope.launch {
            NetworkHelper.isOnline.drop(1).collect { online ->
                val wasOnline = lastObservedOnlineState
                lastObservedOnlineState = online
                if (!online) {
                    hasVerifiedServerSubscriptionOnce = false
                    recomputeSubscriptionState()
                } else if (!wasOnline) {
                    val uid = Firebase.auth.currentUser?.takeIf { it.isEmailVerified }?.uid
                    if (uid != null) {
                        hasVerifiedServerSubscriptionOnce = false
                        recomputeSubscriptionState()
                        refreshSubscriptionFromServer(uid)
                        refreshPaymentsAndRequestsFromServer(uid)
                        loadVodafoneCashSettings()
                    }
                }
            }
        }

        viewModelScope.launch {
            MerchantMessagingService.subscriptionRefreshSignals.collect {
                val uid = Firebase.auth.currentUser?.takeIf { it.isEmailVerified }?.uid
                if (uid != null && NetworkHelper.isOnline.value) {
                    refreshSubscriptionFromServer(uid)
                    refreshPaymentsAndRequestsFromServer(uid)
                }
            }
        }

        val currentUser = Firebase.auth.currentUser
        if (currentUser != null && currentUser.isEmailVerified) {
            listenToFirestore()
        }
    }

    fun applyAuthenticatedMerchant(merchant: com.example.auth.MerchantAccount) {
        val resolvedImg = merchant.logo.ifBlank { merchant.photoUrl }.ifBlank { null }
        _restaurantProfile.value = RestaurantProfile(
            name = merchant.name,
            phone = merchant.phone,
            address = merchant.address,
            imageUri = resolvedImg,
            logoUrl = resolvedImg,
            photoUrl = resolvedImg,
            storagePath = merchant.storagePath,
            email = merchant.email,
            category = merchant.category.ifBlank { "مطعم" },
            status = merchant.status.ifBlank { "approved" },
            subscriptionStatus = merchant.subscriptionStatus.ifBlank { "unsubscribed" },
            ownerId = merchant.uid,
            uid = merchant.uid
        )
        if ((resolvedImg.isNullOrBlank() || !FirebaseStorageManager.isRemoteHttpUrl(resolvedImg)) &&
            merchant.storagePath.isNotBlank()
        ) {
            resolveAndApplyStorageLogo(merchant.uid, merchant.storagePath)
        }
    }

    private fun resolveAndApplyStorageLogo(uid: String, rawStoragePath: String) {
        val candidatePath = rawStoragePath.trim().ifBlank { "merchants/$uid/profile.jpg" }
        viewModelScope.launch {
            val resolvedUrl = FirebaseStorageManager.resolveFirebaseStorageDownloadUrl(candidatePath)
            if (resolvedUrl.isNotBlank() && _restaurantProfile.value.uid == uid) {
                _restaurantProfile.value = _restaurantProfile.value.copy(
                    imageUri = resolvedUrl,
                    logoUrl = resolvedUrl,
                    photoUrl = resolvedUrl,
                    storagePath = candidatePath
                )
            }
        }
    }

    fun mapDocToRestaurantProfile(doc: DocumentSnapshot, defaultUid: String): RestaurantProfile {
        val name = doc.safeString("name")
            ?: doc.safeString("restaurantName")
            ?: Firebase.auth.currentUser?.displayName
            ?: "متجر"
        val phone = doc.safeString("phone") ?: ""
        val address = doc.safeString("address") ?: "العنوان غير محدد"
        val logoUrl = doc.safeString("logo")
            ?: doc.safeString("photoUrl")
            ?: doc.safeString("logoUrl")
            ?: doc.getAnyString("imageUrl", "imageUri", "image")
            ?: ""
        val storagePath = doc.safeString("storagePath") ?: ""
        val email = doc.safeString("email") ?: (Firebase.auth.currentUser?.email ?: "")
        val category = doc.safeString("category") ?: "مطعم"
        val status = doc.safeString("status") ?: "approved"
        val rating = (doc.get("rating") as? Number)?.toDouble() ?: 5.0
        val ratingCount = (doc.get("ratingCount") as? Number)?.toLong() ?: 0L
        val subStatus = doc.safeString("subscriptionStatus") ?: "unsubscribed"
        val subId = doc.safeString("subscriptionId")
        val ownerId = doc.safeString("ownerId") ?: defaultUid
        val uid = doc.safeString("uid") ?: defaultUid

        val locationUrl = doc.safeString("locationUrl")
            ?: doc.getAnyString("mapLink", "mapUrl", "locationLink")
            ?: ""

        val locationGeoPoint = doc.getGeoPoint("location")
            ?: run {
                val lat = (doc.get("latitude") as? Number)?.toDouble()
                val lng = (doc.get("longitude") as? Number)?.toDouble()
                if (lat != null && lng != null) GeoPoint(lat, lng) else null
            }

        val resolvedLogoForUi = logoUrl.takeIf { it.isNotBlank() }
        if (resolvedLogoForUi == null && storagePath.isNotBlank()) {
            resolveAndApplyStorageLogo(uid, storagePath)
        } else if (resolvedLogoForUi != null && !FirebaseStorageManager.isRemoteHttpUrl(resolvedLogoForUi)) {
            resolveAndApplyStorageLogo(uid, resolvedLogoForUi)
        }

        return RestaurantProfile(
            name = name,
            phone = phone,
            address = address,
            imageUri = resolvedLogoForUi,
            logoUrl = resolvedLogoForUi,
            photoUrl = resolvedLogoForUi,
            storagePath = storagePath,
            email = email,
            category = category,
            status = status,
            rating = rating,
            ratingCount = ratingCount,
            subscriptionStatus = subStatus,
            subscriptionId = subId,
            ownerId = ownerId,
            uid = uid,
            location = locationGeoPoint,
            locationUrl = locationUrl
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun cleanAndNormalizeRestaurantDoc(doc: DocumentSnapshot) {
        // Automatic migration/cleanup of restaurants collection is disabled for strict account isolation.
    }

    @Suppress("UNUSED_PARAMETER")
    fun checkAndMigrateRestaurantDoc(uid: String) {
        // Automatic migration is disabled to avoid touching unrelated or conflicting accounts.
    }

    fun listenToFirestore() {
        val currentUser = Firebase.auth.currentUser
        if (currentUser == null || !currentUser.isEmailVerified) {
            clearListeners()
            return
        }
        val uid = currentUser.uid

        // Prevent cross-merchant data leakage when switching accounts
        if (activeListenersUid != null && activeListenersUid != uid) {
            clearListeners()
        }

        // Prevent duplicate listeners when listenToFirestore() is triggered repeatedly for the same active merchant
        if (activeListenersUid == uid &&
            merchantProfileListener != null &&
            restaurantSubListener != null &&
            subscriptionPackagesListener != null &&
            subscriptionHistoryListener != null
        ) {
            return
        }
        activeListenersUid = uid

        checkAndListenToRestaurantSubscription()
        viewModelScope.launch(Dispatchers.IO) {
            subscriptionRepository.syncFcmToken(uid)
        }

        // 1. Read / Listen EXCLUSIVELY to `merchants/{uid}` (never query restaurants, captains, or users)
        profileListener?.remove()
        profileListener = null
        merchantProfileListener?.remove()

        merchantProfileListener = Firebase.firestore.collection("merchants").document(uid)
            .addSnapshotListener(MetadataChanges.INCLUDE) { mSnap, mErr ->
                if (mErr != null) {
                    if (mErr.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        merchantProfileListener?.remove()
                        merchantProfileListener = null
                    } else {
                        Log.w("OrderViewModel", "Error reading merchants/$uid: ${mErr.message}")
                    }
                    return@addSnapshotListener
                }
                if (mSnap != null && mSnap.exists()) {
                    val accountType = mSnap.safeString("accountType")?.trim()?.lowercase()
                    val role = mSnap.safeString("role")?.trim()?.lowercase()
                    if (accountType == "merchant" || (accountType.isNullOrBlank() && role == "merchant")) {
                        _restaurantProfile.value = mapDocToRestaurantProfile(mSnap, uid)
                        latestMerchantSubSnapshot = SubscriptionSyncResolver.fromFirestoreSnapshot(mSnap)
                        if (!mSnap.metadata.isFromCache && NetworkHelper.isOnline.value) {
                            hasVerifiedServerSubscriptionOnce = true
                            isInitialSubscriptionCheckInProgress = false
                            latestSubscriptionServerError = null
                        }
                        recomputeSubscriptionState()
                    } else {
                        Log.w("OrderViewModel", "Rejected non-merchant document in merchants/$uid: accountType=$accountType, role=$role")
                        _restaurantProfile.value = RestaurantProfile()
                        latestMerchantSubSnapshot = RawSubscriptionSnapshot(exists = false)
                        recomputeSubscriptionState()
                    }
                } else {
                    Log.w("OrderViewModel", "No document exists at merchants/$uid; never auto-creating on read.")
                    _restaurantProfile.value = RestaurantProfile()
                    latestMerchantSubSnapshot = RawSubscriptionSnapshot(exists = false)
                    recomputeSubscriptionState()
                }
            }

        // 2. Read / Listen to real-time orders for current merchant from Firestore across userId, restaurantId, and merchantId
        ordersListener?.remove()
        restaurantOrdersListener?.remove()
        merchantOrdersListener?.remove()

        ordersListener = Firebase.firestore.collection("orders")
            .whereEqualTo("userId", uid)
            .addSnapshotListener { querySnapshot, error ->
                if (error != null) {
                    if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        ordersListener?.remove()
                        ordersListener = null
                    }
                    return@addSnapshotListener
                }
                if (querySnapshot != null) {
                    userOrdersDocs.clear()
                    querySnapshot.documents.forEach { userOrdersDocs[it.id] = it }
                    rebuildMergedFirestoreOrders()
                }
            }

        restaurantOrdersListener = Firebase.firestore.collection("orders")
            .whereEqualTo("restaurantId", uid)
            .addSnapshotListener { querySnapshot, error ->
                if (error != null) {
                    if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        restaurantOrdersListener?.remove()
                        restaurantOrdersListener = null
                    }
                    return@addSnapshotListener
                }
                if (querySnapshot != null) {
                    restaurantOrdersDocs.clear()
                    querySnapshot.documents.forEach { restaurantOrdersDocs[it.id] = it }
                    rebuildMergedFirestoreOrders()
                }
            }

        merchantOrdersListener = Firebase.firestore.collection("orders")
            .whereEqualTo("merchantId", uid)
            .addSnapshotListener { querySnapshot, error ->
                if (error != null) {
                    if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        merchantOrdersListener?.remove()
                        merchantOrdersListener = null
                    }
                    return@addSnapshotListener
                }
                if (querySnapshot != null) {
                    merchantOrdersDocs.clear()
                    querySnapshot.documents.forEach { merchantOrdersDocs[it.id] = it }
                    rebuildMergedFirestoreOrders()
                }
            }
    }

    private fun rebuildMergedFirestoreOrders() {
        val mergedDocs = LinkedHashMap<String, DocumentSnapshot>()
        userOrdersDocs.forEach { (k, v) -> mergedDocs[k] = v }
        restaurantOrdersDocs.forEach { (k, v) -> mergedDocs[k] = v }
        merchantOrdersDocs.forEach { (k, v) -> mergedDocs[k] = v }

        val sortedDocs = mergedDocs.values.sortedByDescending { doc ->
            val ts = doc.getTimestamp("created_at") ?: doc.getTimestamp("createdAt")
            ts?.toDate()?.time ?: 0L
        }

        val missingCaptainIds = mutableSetOf<String>()
        val unresolvedStoragePaths = mutableSetOf<String>()
        val captainsWithoutIdByPhone = mutableSetOf<String>()
        val nowMs = System.currentTimeMillis()

        val mappedOrders = sortedDocs.mapNotNull { doc ->
            try {
                val baseOrder = mapDocToOrder(doc)
                val captId = baseOrder.captainId?.trim().orEmpty()
                val rawPhoto = baseOrder.captainPhotoUrl.trim()
                val hasValidRemotePhoto = FirebaseStorageManager.isValidDisplayableCaptainPhoto(rawPhoto)
                val isStoragePathCandidate = rawPhoto.isNotBlank() &&
                    !hasValidRemotePhoto &&
                    !FirebaseStorageManager.isPlaceholderAvatarUrl(rawPhoto) &&
                    !FirebaseStorageManager.isLocalDeviceUri(rawPhoto)
                val cachedResolvedPath = if (isStoragePathCandidate) {
                    resolvedOrderStoragePhotoCache[rawPhoto].orEmpty()
                } else ""

                if (isStoragePathCandidate && cachedResolvedPath.isBlank()) {
                    unresolvedStoragePaths.add(rawPhoto)
                }

                if (captId.isNotEmpty()) {
                    val cachedCaptain = captainInfoCache[captId]
                    val lastAttempt = captainLookupTimestampMs[captId] ?: 0L
                    val canRefreshCaptain = (nowMs - lastAttempt) >= 12_000L

                    if (cachedCaptain != null) {
                        val enrichedName = FirebaseStorageManager.selectAuthoritativeCaptainName(
                            captainDocName = cachedCaptain.name,
                            orderDocName = baseOrder.captainName,
                            captainId = captId
                        )
                        val enrichedPhone = FirebaseStorageManager.selectAuthoritativeCaptainPhone(
                            captainDocPhone = cachedCaptain.phone,
                            orderDocPhone = baseOrder.captainPhone
                        )
                        val enrichedPhoto = FirebaseStorageManager.selectBestCaptainPhotoUrl(
                            captainDocPhotoUrl = cachedCaptain.photoUrl,
                            resolvedOrderStorageUrl = cachedResolvedPath,
                            orderDocPhotoUrl = rawPhoto
                        )

                        val needsBetterCaptainData = !FirebaseStorageManager.isValidCaptainName(cachedCaptain.name) ||
                            cachedCaptain.phone.isBlank() ||
                            !FirebaseStorageManager.isFirebaseStorageUrl(cachedCaptain.photoUrl)
                        if (needsBetterCaptainData && canRefreshCaptain) {
                            missingCaptainIds.add(captId)
                        }

                        baseOrder.copy(
                            captainName = enrichedName,
                            captainPhone = enrichedPhone,
                            captainPhotoUrl = enrichedPhoto
                        )
                    } else {
                        val immediatePhoto = FirebaseStorageManager.selectBestCaptainPhotoUrl(
                            captainDocPhotoUrl = null,
                            resolvedOrderStorageUrl = cachedResolvedPath,
                            orderDocPhotoUrl = rawPhoto
                        )
                        if (canRefreshCaptain) {
                            missingCaptainIds.add(captId)
                        }
                        baseOrder.copy(captainPhotoUrl = immediatePhoto)
                    }
                } else {
                    val isAssignedByName = FirebaseStorageManager.isValidCaptainName(baseOrder.captainName)
                    val cleanPhone = baseOrder.captainPhone.trim()
                    val cachedByPhone = cleanPhone.takeIf { it.isNotBlank() }?.let { captainInfoByPhoneCache[it] }
                    val resolvedName = if (cachedByPhone != null) {
                        FirebaseStorageManager.selectAuthoritativeCaptainName(
                            captainDocName = cachedByPhone.name,
                            orderDocName = baseOrder.captainName,
                            captainId = cachedByPhone.captainId
                        )
                    } else baseOrder.captainName
                    val resolvedPhoto = FirebaseStorageManager.selectBestCaptainPhotoUrl(
                        captainDocPhotoUrl = cachedByPhone?.photoUrl,
                        resolvedOrderStorageUrl = cachedResolvedPath,
                        orderDocPhotoUrl = rawPhoto
                    )
                    if ((isAssignedByName || cleanPhone.isNotBlank()) &&
                        cleanPhone.isNotBlank() &&
                        (cachedByPhone == null || !FirebaseStorageManager.isFirebaseStorageUrl(cachedByPhone.photoUrl))
                    ) {
                        val lastPhoneAttempt = captainLookupTimestampMs["phone:$cleanPhone"] ?: 0L
                        if (nowMs - lastPhoneAttempt >= 12_000L) {
                            captainsWithoutIdByPhone.add(cleanPhone)
                        }
                    }
                    baseOrder.copy(
                        captainId = baseOrder.captainId ?: cachedByPhone?.captainId?.takeIf { it.isNotBlank() },
                        captainName = resolvedName,
                        captainPhotoUrl = resolvedPhoto
                    )
                }
            } catch (e: Exception) {
                Log.w("OrderViewModel", "Failed to map order doc ${doc.id}: ${e.message}")
                null
            }
        }

        _firestoreOrders.value = mappedOrders
        lastServerActiveCaptainsCount = null
        recalculateActiveCaptainsForSubscription(mappedOrders)

        // Alert merchant when a captain sends customer/order details awaiting confirmation
        val newlyAwaiting = mappedOrders.filter { order ->
            isOrderActiveForCaptainLimit(order.status) &&
                (order.status == "بانتظار تأكيد البيانات" || (order.customerDetailsSubmitted && !order.customerDetailsConfirmed && !order.customerDetailsRejected))
        }
        for (awaitingOrder in newlyAwaiting) {
            val notifyKey = "${awaitingOrder.firestoreOrderId.ifBlank { awaitingOrder.id.toString() }}_${awaitingOrder.receiptImageUrl}_${awaitingOrder.customerPhone}"
            if (notifiedAwaitingConfirmationKeys.add(notifyKey)) {
                val timeStr = SimpleDateFormat("hh:mm a", Locale("ar")).format(Date())
                val codeLabel = if (awaitingOrder.orderCode.isNotBlank()) "#${awaitingOrder.orderCode}" else "#${awaitingOrder.id}"
                val captLabel = awaitingOrder.captainName.ifBlank { "الكابتن" }
                _pushNotificationState.value = PushNotificationBanner(
                    title = "بانتظار تأكيد البيانات ($codeLabel) 📋",
                    body = "أرسل $captLabel بيانات الطلب — يرجى تأكيد البيانات لكي يكمل الكابتن التوصيل.",
                    timestamp = timeStr
                )
            }
        }

        if (missingCaptainIds.isNotEmpty() || unresolvedStoragePaths.isNotEmpty() || captainsWithoutIdByPhone.isNotEmpty()) {
            fetchAndCacheCaptains(missingCaptainIds, unresolvedStoragePaths, captainsWithoutIdByPhone)
        }
    }

    private val notifiedAwaitingConfirmationKeys = mutableSetOf<String>()
    private val resolvedOrderStoragePhotoCache = ConcurrentHashMap<String, String>()
    private val captainInfoByPhoneCache = ConcurrentHashMap<String, CaptainLocationInfo>()
    private val captainLookupTimestampMs = ConcurrentHashMap<String, Long>()

    fun isOrderActiveForCaptainLimit(status: String): Boolean {
        val s = status.trim().lowercase(Locale.ROOT)
        if (s.isEmpty()) return false
        val isCompleted = s in listOf("completed", "delivered", "تم التسليم", "تم التوصيل", "مكتمل", "مكتملة", "تم") ||
            s.contains("مكتمل") || s.contains("تسليم") || s.contains("توصيل بنجاح") || s.contains("delivered")
        val isCancelled = s in listOf("cancelled", "canceled", "ملغي", "ملغية", "تم الإلغاء", "تم الالغاء") ||
            s.contains("ملغ") || s.contains("cancel")
        return !isCompleted && !isCancelled
    }

    private fun recalculateActiveCaptainsForSubscription(orders: List<DeliveryOrderEntity> = _firestoreOrders.value) {
        if (latestSubscriptionSnapshot.exists || latestMerchantSubSnapshot.exists) {
            recomputeSubscriptionState(orders)
            return
        }
        val currentSub = _subscriptionStatus.value
        val activeOrdersCount = orders.count { isOrderActiveForCaptainLimit(it.status) }
        val allowed = if (currentSub.canRequestCaptain()) currentSub.maxCaptains else 0
        val available = (allowed - activeOrdersCount).coerceAtLeast(0)
        _subscriptionStatus.value = currentSub.copy(
            activeCaptainsCount = activeOrdersCount,
            availableCaptainSlots = available
        )
    }

    private suspend fun extractCaptainPhotoFromDoc(doc: DocumentSnapshot, vararg lookupIds: String): String {
        val storagePathFields = listOf(
            "storagePath", "profileStoragePath", "photoPath", "imagePath", "avatarPath", "logoStoragePath"
        )
        val urlFields = listOf(
            "profileImageUrl", "photoUrl", "driverPhotoUrl", "captainPhotoUrl",
            "avatarUrl", "imageUrl", "logo", "logoUrl", "profileImage", "profilePhoto",
            "photo", "image", "avatar", "picture", "profilePicture"
        )

        val candidateValues = (storagePathFields + urlFields)
            .mapNotNull { field -> doc.safeString(field)?.trim()?.takeIf { it.isNotEmpty() } }
            .filter { !FirebaseStorageManager.isPlaceholderAvatarUrl(it) && !FirebaseStorageManager.isLocalDeviceUri(it) }

        // 1. Direct Firebase Storage URL on the captain document wins immediately
        candidateValues.firstOrNull { FirebaseStorageManager.isFirebaseStorageUrl(it) && FirebaseStorageManager.isRemoteHttpUrl(it) }
            ?.let { return it }

        // 2. Resolve relative Storage paths or `gs://` URLs via Firebase Storage SDK
        val pathCandidates = candidateValues.filter {
            !FirebaseStorageManager.isRemoteHttpUrl(it) || it.startsWith("gs://", ignoreCase = true)
        }
        for (rawPath in pathCandidates) {
            val resolved = FirebaseStorageManager.resolveFirebaseStorageDownloadUrl(rawPath)
            if (resolved.isNotBlank() && FirebaseStorageManager.isRemoteHttpUrl(resolved)) {
                return resolved
            }
        }

        // 3. Search Firebase Storage folders (`captains/{id}/...`, etc.) for the captain's real uploaded photo
        val distinctIds = (listOf(
            doc.id,
            doc.safeString("uid").orEmpty(),
            doc.safeString("captainId").orEmpty()
        ) + lookupIds.toList())
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .distinct()

        for (id in distinctIds) {
            val storageUrl = FirebaseStorageManager.findCaptainPhotoInStorage(id)
            if (storageUrl.isNotBlank()) {
                return storageUrl
            }
        }

        // 4. Fallback to any valid remote HTTP/HTTPS URL on the document
        return candidateValues.firstOrNull { FirebaseStorageManager.isValidDisplayableCaptainPhoto(it) }.orEmpty()
    }

    private fun fetchAndCacheCaptains(
        captainIds: Set<String>,
        storagePathsToResolve: Set<String> = emptySet(),
        captainPhonesToLookup: Set<String> = emptySet()
    ) {
        val attemptTime = System.currentTimeMillis()
        val idsToQuery = captainIds.filter { id ->
            val prev = captainLookupTimestampMs[id] ?: 0L
            attemptTime - prev >= 12_000L
        }
        idsToQuery.forEach { captainLookupTimestampMs[it] = attemptTime }

        val phonesToQuery = captainPhonesToLookup.filter { phone ->
            val prev = captainLookupTimestampMs["phone:$phone"] ?: 0L
            attemptTime - prev >= 12_000L
        }
        phonesToQuery.forEach { captainLookupTimestampMs["phone:$it"] = attemptTime }

        if (idsToQuery.isEmpty() && storagePathsToResolve.isEmpty() && phonesToQuery.isEmpty()) return

        viewModelScope.launch(Dispatchers.IO) {
            var updatedAny = false
            val db = Firebase.firestore

            for (rawPath in storagePathsToResolve) {
                if (resolvedOrderStoragePhotoCache[rawPath]?.isNotBlank() == true) continue
                val resolved = FirebaseStorageManager.resolveFirebaseStorageDownloadUrl(rawPath)
                if (resolved.isNotBlank()) {
                    resolvedOrderStoragePhotoCache[rawPath] = resolved
                    updatedAny = true
                }
            }

            for (captId in idsToQuery) {
                try {
                    // Captain document IDs are Firebase Auth UIDs. Fetch only the assigned captain;
                    // never list/search the entire captains collection from a merchant account.
                    val snap = try { db.collection("captains").document(captId).get().await() } catch (_: Exception) { null }
                    if (snap != null && snap.exists()) {
                        val name = snap.getAnyString(
                            "name", "fullName", "full_name", "driver_name", "driverName", "displayName", "captainName", "userName"
                        ) ?: ""
                        val phone = snap.getAnyString(
                            "phone", "phoneNumber", "phone_number", "mobile", "mobileNumber", "captainPhone", "driverPhone"
                        ) ?: ""
                        val photo = extractCaptainPhotoFromDoc(snap, captId)
                        val info = CaptainLocationInfo(
                            captainId = captId,
                            name = name,
                            phone = phone,
                            photoUrl = photo
                        )
                        val previous = captainInfoCache[captId]
                        captainInfoCache[captId] = info
                        if (phone.isNotBlank()) {
                            captainInfoByPhoneCache[phone.trim()] = info
                        }
                        if (previous != info) updatedAny = true
                    } else {
                        val storagePhoto = FirebaseStorageManager.findCaptainPhotoInStorage(captId)
                        val previous = captainInfoCache[captId]
                        if (storagePhoto.isNotBlank()) {
                            captainInfoCache[captId] = CaptainLocationInfo(
                                captainId = captId,
                                name = previous?.name.orEmpty(),
                                phone = previous?.phone.orEmpty(),
                                photoUrl = storagePhoto
                            )
                            updatedAny = true
                        }
                    }
                } catch (e: Exception) {
                    Log.w("OrderViewModel", "Could not fetch captain $captId info: ${e.message}")
                }
            }

            for (phone in phonesToQuery) {
                try {
                    val phoneFields = listOf("phone", "phoneNumber", "mobile", "captainPhone")
                    var snap: DocumentSnapshot? = null
                    for (field in phoneFields) {
                        snap = try {
                            db.collection("captains")
                                .whereEqualTo(field, phone)
                                .limit(1)
                                .get()
                                .await()
                                .documents
                                .firstOrNull()
                        } catch (_: Exception) { null }
                        if (snap != null && snap.exists()) break
                    }
                    if (snap != null && snap.exists()) {
                        val name = snap.getAnyString(
                            "name", "fullName", "full_name", "driver_name", "driverName", "displayName", "captainName", "userName"
                        ) ?: ""
                        val resolvedPhone = snap.getAnyString(
                            "phone", "phoneNumber", "phone_number", "mobile", "mobileNumber", "captainPhone", "driverPhone"
                        ) ?: phone
                        val photo = extractCaptainPhotoFromDoc(snap)
                        val info = CaptainLocationInfo(
                            captainId = snap.id,
                            name = name,
                            phone = resolvedPhone,
                            photoUrl = photo
                        )
                        captainInfoByPhoneCache[phone] = info
                        captainInfoCache[snap.id] = info
                        updatedAny = true
                    }
                } catch (e: Exception) {
                    Log.w("OrderViewModel", "Could not lookup captain by phone $phone: ${e.message}")
                }
            }

            if (updatedAny) {
                withContext(Dispatchers.Main) {
                    val currentTracked = _trackedCaptainInfo.value
                    if (currentTracked != null && currentTracked.captainId.isNotBlank()) {
                        val cached = captainInfoCache[currentTracked.captainId]
                        if (cached != null) {
                            _trackedCaptainInfo.value = currentTracked.copy(
                                name = FirebaseStorageManager.selectAuthoritativeCaptainName(
                                    cached.name, currentTracked.name, currentTracked.captainId
                                ),
                                phone = FirebaseStorageManager.selectAuthoritativeCaptainPhone(
                                    cached.phone, currentTracked.phone
                                ),
                                photoUrl = FirebaseStorageManager.selectBestCaptainPhotoUrl(
                                    cached.photoUrl, null, currentTracked.photoUrl
                                )
                            )
                        }
                    }
                    rebuildMergedFirestoreOrders()
                }
            }
        }
    }

    fun listenToFirestoreCaptain() {
        // Strictly disabled in Merchant App to guarantee complete separation from captains collection
        Log.d("OrderViewModel", "listenToFirestoreCaptain skipped: Merchant App only handles merchant accounts.")
    }

    @Suppress("UNUSED_PARAMETER")
    fun toggleCaptainOnlineStatus(isOnline: Boolean) {
        Log.w("OrderViewModel", "toggleCaptainOnlineStatus is disabled in Merchant App.")
    }

    @Suppress("UNUSED_PARAMETER")
    fun updateCaptainLocation(lat: Double, lng: Double) {
        Log.w("OrderViewModel", "updateCaptainLocation is disabled in Merchant App.")
    }

    @Suppress("UNUSED_PARAMETER")
    fun rejectCaptainOrder(orderDocId: String, onSuccess: () -> Unit = {}) {
        Log.w("OrderViewModel", "rejectCaptainOrder is disabled in Merchant App.")
    }

    private fun mapDocToOrder(doc: DocumentSnapshot): DeliveryOrderEntity {
        val rawStatus = doc.getAnyString("status", "orderStatus", "status_ar") ?: "pending"
        val rawStatusLower = rawStatus.trim().lowercase(Locale.ROOT)
        val initialMappedStatus = when (rawStatusLower) {
            "pending", "قيد التحضير", "preparing", "جاري البحث عن كابتن", "في انتظار كابتن", "جاري البحث" -> "جاري البحث"
            "accepted", "الكابتن في الطريق لك", "في الطريق إلى المطعم", "في الطريق لك" -> "في الطريق لك"
            "captain_reached_store", "reached_store", "وصل المتجر" -> "وصل المتجر"
            "order_picked_up", "picked_up", "تم الاستلام" -> "تم الاستلام"
            "waiting_merchant_confirmation", "waiting_confirmation", "waiting_merchant_approval",
            "pending_confirmation", "pending_merchant_confirmation", "awaiting_confirmation",
            "awaiting_merchant_confirmation", "details_submitted", "data_submitted",
            "customer_details_submitted", "بانتظار تأكيد البيانات", "في انتظار تأكيد البيانات",
            "بانتظار التأكيد", "تأكيد البيانات", "تم إرسال البيانات", "تم ارسال البيانات" -> "بانتظار تأكيد البيانات"
            "customer_confirmed", "confirmed_by_merchant", "merchant_confirmed", "en_route", "تم تأكيد البيانات" -> "جاري التوصيل"
            "merchant_rejected", "customer_rejected", "بيانات مرفوضة" -> "بيانات مرفوضة"
            "in_progress", "out_for_delivery", "جاري التوصيل", "delivering", "في الطريق إلى العميل" -> "جاري التوصيل"
            "completed", "delivered", "تم التسليم", "مكتمل", "مكتملة", "تم التوصيل" -> "تم التسليم"
            "cancelled", "canceled", "ملغي", "ملغية", "تم الإلغاء", "تم الالغاء" -> "ملغي"
            else -> rawStatus
        }

        val captainId = doc.getAnyString(
            "captainId", "driverId", "captain_id", "driver_id",
            "assignedCaptainId", "acceptedBy", "captainUid", "driverUid"
        )
        val rawDriverName = doc.getAnyString(
            "captainName", "driver_name", "driverName", "captain_name", "captainFullName", "driverFullName"
        )
        val captName = if (!rawDriverName.isNullOrBlank()) rawDriverName else if (!captainId.isNullOrBlank()) "الكابتن" else "في انتظار كابتن"

        val rawAddress = doc.getAnyString("destinationAddress", "deliveryAddress", "address", "customerAddress", "clientAddress", "receiverAddress") ?: ""

        val rawPrice = doc.getAnyString("serviceFee", "price", "totalAmount") ?: "30 جنيه"

        val formattedDate = (doc.getTimestamp("created_at") ?: doc.getTimestamp("createdAt"))?.let {
            SimpleDateFormat("dd MMMM yyyy - hh:mm a", Locale("ar")).format(it.toDate())
        } ?: doc.getAnyString("orderDate") ?: "الآن"

        val itemsText = doc.getAnyString("orderDetails", "items", "details", "order_details") ?: ""

        val custName = doc.getAnyString("customerName", "customer_name", "clientName", "receiverName") ?: ""
        val custPhone = doc.getAnyString("customerPhone", "customer_phone", "clientPhone", "receiverPhone") ?: ""
        val notes = doc.getAnyString("deliveryNotes", "notes", "customerNotes") ?: ""

        val customerLoc = doc.getGeoPoint("customerLocation")
        val custLat = customerLoc?.latitude ?: (doc.get("customerLatitude") as? Number)?.toDouble()
        val custLng = customerLoc?.longitude ?: (doc.get("customerLongitude") as? Number)?.toDouble()

        val docRating = (doc.get("rating") as? Number)?.toInt() ?: 0
        val captPhone = doc.getAnyString(
            "captainPhone", "captain_phone", "driverPhone", "driver_phone",
            "captainMobile", "driverMobile", "captainPhoneNumber"
        ) ?: ""
        val rawCaptPhoto = doc.getAnyString(
            "captainPhotoUrl", "captain_photo_url", "captain_photo", "captainPhoto",
            "driverPhotoUrl", "driver_photo_url", "driverPhoto", "driver_photo",
            "captainImage", "captainImageUrl", "driverImage", "driverImageUrl",
            "captainAvatar", "driverAvatar", "captainProfileImage", "driverProfileImage",
            "captainLogo", "driverLogo", "captainStoragePath", "captainPhotoPath", "driverStoragePath"
        ) ?: ""
        val captPhoto = if (
            FirebaseStorageManager.isPlaceholderAvatarUrl(rawCaptPhoto) ||
            FirebaseStorageManager.isLocalDeviceUri(rawCaptPhoto)
        ) "" else rawCaptPhoto.trim()
        val captEta = doc.getAnyString("estimatedArrival", "captainEta", "eta")
            ?: (if (captName != "في انتظار كابتن" && captName != "في انتظار قبول كابتن") "20 دقيقة" else "")

        val receiptUrl = doc.getAnyString(
            "receiptImageUrl", "receiptUrl", "customerReceiptUrl",
            "invoiceImageUrl", "receiptImage", "receipt_image",
            "receipt", "receiptPhotoUrl", "invoiceUrl", "orderImageUrl"
        ) ?: ""

        val custConfirmed = (doc.getBoolean("customerDetailsConfirmed") == true) ||
            (doc.getBoolean("merchantConfirmed") == true) ||
            (doc.getBoolean("isConfirmed") == true) ||
            rawStatusLower in listOf("customer_confirmed", "confirmed_by_merchant", "تم تأكيد البيانات")

        val custRejected = !custConfirmed && (
            initialMappedStatus == "بيانات مرفوضة" ||
                (doc.getBoolean("customerDetailsRejected") == true && initialMappedStatus != "بانتظار تأكيد البيانات")
            )

        val custSubmitted = (doc.getBoolean("customerDetailsSubmitted") == true) ||
            (doc.getBoolean("detailsSubmitted") == true) ||
            (doc.getBoolean("isDetailsSubmitted") == true) ||
            (doc.getBoolean("dataSubmitted") == true) ||
            (doc.getBoolean("waitingMerchantConfirmation") == true) ||
            (doc.getBoolean("waitingConfirmation") == true) ||
            receiptUrl.isNotBlank() ||
            initialMappedStatus == "بانتظار تأكيد البيانات" ||
            custConfirmed

        val isFinalDoneOrCancelled = initialMappedStatus in listOf("تم التسليم", "ملغي")
        val mappedStatus = when {
            isFinalDoneOrCancelled -> initialMappedStatus
            custConfirmed -> "جاري التوصيل"
            custRejected -> "بيانات مرفوضة"
            custSubmitted -> "بانتظار تأكيد البيانات"
            else -> initialMappedStatus
        }

        val rejReason = doc.getAnyString("rejectionReason", "rejectReason") ?: ""
        val reachedStore = doc.getBoolean("captainReachedStore") ?: (mappedStatus == "وصل المتجر")
        val pickedUp = doc.getBoolean("orderPickedUp") ?: (mappedStatus in listOf("تم الاستلام", "بانتظار تأكيد البيانات", "جاري التوصيل", "تم التسليم"))

        val realFirestoreId = doc.safeString("orderId") ?: doc.id
        val orderCode = doc.getAnyString("orderCode", "code") ?: ""

        val isCaptainAssignedNow = captName.isNotBlank() &&
            captName != "في انتظار كابتن" &&
            captName != "في انتظار قبول كابتن"
        if (isCaptainAssignedNow) {
            val serverAcceptedMs = (doc.getTimestamp("acceptedAt")
                ?: doc.getTimestamp("assignedAt")
                ?: doc.getTimestamp("accepted_at"))?.toDate()?.time
                ?: (doc.get("acceptedAtMillis") as? Number)?.toLong()
                ?: (doc.get("assignedAtMillis") as? Number)?.toLong()
            if (serverAcceptedMs != null && serverAcceptedMs > 0L) {
                OrderCountdownTracker.registerServerAcceptanceTimestamp(
                    orderKey = realFirestoreId,
                    orderNumericKey = realFirestoreId.hashCode().toString(),
                    captainKey = "${captName.trim()}_${captPhone.trim()}",
                    timestampMs = serverAcceptedMs
                )
            }
        }

        return DeliveryOrderEntity(
            id = realFirestoreId.hashCode(),
            firestoreOrderId = realFirestoreId,
            orderCode = orderCode,
            orderDate = formattedDate,
            destinationAddress = rawAddress,
            serviceFee = rawPrice,
            customerName = custName,
            customerPhone = custPhone,
            orderDetails = itemsText,
            deliveryNotes = notes,
            customerLatitude = custLat,
            customerLongitude = custLng,
            status = mappedStatus,
            rating = docRating,
            captainId = captainId,
            captainName = captName,
            captainPhone = captPhone,
            captainPhotoUrl = captPhoto,
            estimatedArrival = captEta,
            receiptImageUrl = receiptUrl,
            customerDetailsSubmitted = custSubmitted,
            customerDetailsConfirmed = custConfirmed,
            customerDetailsRejected = custRejected,
            rejectionReason = rejReason,
            captainReachedStore = reachedStore,
            orderPickedUp = pickedUp
        )
    }

    @Suppress("UNUSED_PARAMETER")
    fun acceptOrderInFirestore(orderDocId: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        onError("هذا التطبيق مخصص للتاجر فقط ولا يدعم قبول الطلبات ككابتن")
    }

    @Suppress("UNUSED_PARAMETER")
    fun completeOrderInFirestore(orderDocId: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        onError("هذا التطبيق مخصص للتاجر فقط ولا يدعم إنهاء التوصيل ككابتن")
    }

    fun clearSnackbarMessage() {
        _snackbarMessage.value = null
    }

    override fun onCleared() {
        super.onCleared()
        try {
            Firebase.auth.removeAuthStateListener(authStateListener)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        clearListeners()
    }

    companion object {
        /**
         * Generates a 6-digit code satisfying:
         * 1. Exactly 6 digits.
         * 2. Does not start with 0 (first digit is 1..9).
         * 3. All 6 digits are distinct with no repeats inside the code.
         */
        fun generateDistinct6DigitCode(): String {
            val firstDigit = (1..9).random()
            val remainingDigits = (0..9).filter { it != firstDigit }.shuffled().take(5)
            return buildString(6) {
                append(firstDigit)
                remainingDigits.forEach { append(it) }
            }
        }
    }

    fun addOrder(
        destinationAddress: String,
        serviceFee: String,
        customerName: String = "",
        customerPhone: String = "",
        orderDetails: String = "",
        deliveryNotes: String = "",
        customerLatitude: Double? = null,
        customerLongitude: Double? = null,
        onSuccess: (orderCode: String) -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        if (!orderSubmissionInFlight.compareAndSet(false, true)) {
            val dupMsg = "جاري إرسال طلب كابتن حالياً، يرجى الانتظار وعدم تكرار الضغط."
            _snackbarMessage.value = dupMsg
            onError?.invoke(dupMsg)
            return
        }
        _isSubmittingOrder.value = true

        viewModelScope.launch {
            try {
                if (!NetworkHelper.isNetworkAvailable(getApplication()) || !NetworkHelper.isOnline.value) {
                    val noNetMsg = "لا يمكن إجراء طلب كابتن: أنت غير متصل بالإنترنت! يرجى الاتصال لمزامنة الاشتراك مع السيرفر."
                    _snackbarMessage.value = noNetMsg
                    NetworkHelper.notifyNoInternet(getApplication(), "إجراء طلب كابتن جديد")
                    onError?.invoke(noNetMsg)
                    return@launch
                }

                val currentUser = Firebase.auth.currentUser
                if (currentUser == null || !currentUser.isEmailVerified) {
                    val authMsg = if (currentUser == null) {
                        "يرجى تسجيل الدخول أولاً لطلب كابتن"
                    } else {
                        "يجب تأكيد البريد الإلكتروني أولاً قبل طلب كابتن."
                    }
                    _snackbarMessage.value = authMsg
                    onError?.invoke(authMsg)
                    return@launch
                }

                val currentSub = _subscriptionStatus.value

                // Fast client guard (Server Cloud Function `merchantCreateOrder` enforces authoritatively inside transaction)
                if (!currentSub.canRequestCaptain()) {
                    val normalizedPayStatus = currentSub.paymentStatus.trim().lowercase(Locale.ROOT)
                    val subMsg = when {
                        currentSub.isSuspended || currentSub.status == "suspended" || currentSub.status == "inactive" ->
                            currentSub.message ?: "لا يمكن طلب كابتن: اشتراكك موقوف حالياً من قبل الإدارة. يرجى التواصل مع الإدارة أو تجديد الاشتراك."
                        currentSub.isCancelled || currentSub.status == "cancelled" ->
                            currentSub.message ?: "لا يمكن طلب كابتن: تم إلغاء اشتراكك. يرجى التواصل مع الإدارة أو الاشتراك في باقة جديدة."
                        currentSub.isResubmissionRequired || normalizedPayStatus == "resubmission_requested" || normalizedPayStatus == "resubmission_required" ->
                            currentSub.resubmissionReason ?: currentSub.message ?: "لا يمكن طلب كابتن: مطلوب إعادة رفع إيصال التحويل وموافقة الإدارة أولاً."
                        currentSub.isRejected || normalizedPayStatus == "rejected" ->
                            currentSub.rejectionReason ?: currentSub.message ?: "لا يمكن طلب كابتن: تم رفض طلب الاشتراك أو الدفع. يرجى تقديم طلب جديد."
                        normalizedPayStatus == "pending_verification" || normalizedPayStatus == "pending_review" || normalizedPayStatus == "pending" ||
                            currentSub.isPending || currentSub.isPendingReview || currentSub.status == "pending" || currentSub.status == "pending_review" ->
                            currentSub.message ?: "لا يمكن طلب كابتن: دفعة الاشتراك قيد المراجعة والتحقق من الإدارة. سيتم التفعيل فور الموافقة."
                        currentSub.isOfflineUnverified ->
                            currentSub.message ?: "جاري التحقق من حالة الاشتراك مع السيرفر. يرجى المحاولة بعد ثوانٍ أو التحقق من اتصال الإنترنت."
                        normalizedPayStatus == "failed" || currentSub.status == "payment_failed" ->
                            "لا يمكن عمل طلب كابتن: فشلت عملية الدفع للباقة ولم يتم تأكيد الاشتراك من السيرفر. يرجى إتمام الدفع بنجاح أولاً."
                        normalizedPayStatus == "unpaid" ->
                            "لا يمكن عمل طلب كابتن: لم يتم سداد قيمة الباقة بعد. يرجى سداد الاشتراك وتأكيده من الإدارة أولاً."
                        currentSub.isExpired || currentSub.status == "expired" ->
                            "لا يمكن إنشاء الطلب: باقة اشتراك المطعم منتهية. يرجى تجديد الاشتراك."
                        else ->
                            "لا يمكن عمل طلب كابتن: أنت غير مشترك في أي باقة حالياً. يرجى الاشتراك في باقة أولاً."
                    }
                    _snackbarMessage.value = subMsg
                    onError?.invoke(subMsg)
                    return@launch
                }

                // Client capacity pre-check (also enforced atomically on Cloud Functions server)
                val allowedCaptains = currentSub.maxCaptains
                val liveActiveOrdersCount = _firestoreOrders.value.count { isOrderActiveForCaptainLimit(it.status) }
                val activeCaptains = maxOf(currentSub.activeCaptainsCount, liveActiveOrdersCount)
                if (activeCaptains >= allowedCaptains && allowedCaptains > 0) {
                    val nextCaptainNumber = allowedCaptains + 1
                    val limitMsg = "لا يمكنك طلب الكابتن رقم $nextCaptainNumber حالياً لأنك مشترك في باقة $allowedCaptains كابتن ولديك $activeCaptains طلبات نشطة في نفس الوقت. يمكنك طلب كابتن جديد فور انتهاء أي طلب من الطلبات النشطة."
                    _snackbarMessage.value = limitMsg
                    onError?.invoke(limitMsg)
                    return@launch
                }

                val trimmedAddress = destinationAddress.trim()
                if (trimmedAddress.isBlank()) {
                    val addrMsg = "بيانات عنوان العميل غير مكتملة. يرجى إدخال عنوان التوصيل."
                    _snackbarMessage.value = addrMsg
                    onError?.invoke(addrMsg)
                    return@launch
                }

                val sdf = SimpleDateFormat("dd MMMM yyyy - hh:mm a", Locale("ar"))
                val currentDate = sdf.format(Date())
                // Delivery fee must be entered by the merchant; never invent a default.
                val normalizedFeeInput = serviceFee.trim()
                    .replace("جنيه", "")
                    .replace("EGP", "", ignoreCase = true)
                    .replace(",", "")
                    .replace("٬", "")
                    .trim()
                    .map { ch ->
                        when (ch) {
                            in '٠'..'٩' -> ('0'.code + (ch.code - '٠'.code)).toChar()
                            in '۰'..'۹' -> ('0'.code + (ch.code - '۰'.code)).toChar()
                            else -> ch
                        }
                    }.joinToString("")
                val feeAmount = normalizedFeeInput.toIntOrNull()
                if (feeAmount == null || feeAmount <= 0 || feeAmount > 100000) {
                    val feeMsg = "يرجى إدخال رسوم توصيل صحيحة من تطبيق التاجر (أكبر من صفر)."
                    _snackbarMessage.value = feeMsg
                    onError?.invoke(feeMsg)
                    return@launch
                }
                val formattedFee = "${feeAmount} جنيه"
                val uid = currentUser.uid
                val idempotencyKey = UUID.randomUUID().toString()
                val profile = _restaurantProfile.value
                val currentRestaurantName = profile.name.ifBlank {
                    currentUser.displayName
                        ?: currentUser.email?.substringBefore("@")
                        ?: "مطعم"
                }

                val requestPayload = MerchantSubscriptionRepository.buildMerchantCreateOrderPayload(
                    merchantId = uid,
                    destinationAddress = trimmedAddress,
                    deliveryFeeAmount = feeAmount,
                    formattedServiceFee = formattedFee,
                    customerName = customerName,
                    customerPhone = customerPhone,
                    orderDetails = orderDetails,
                    deliveryNotes = deliveryNotes,
                    merchantName = currentRestaurantName,
                    merchantPhone = profile.phone,
                    merchantLatitude = profile.latitude ?: profile.location?.latitude,
                    merchantLongitude = profile.longitude ?: profile.location?.longitude,
                    customerLatitude = customerLatitude,
                    customerLongitude = customerLongitude,
                    idempotencyKey = idempotencyKey,
                    requestedCaptains = 1
                )

                val serverValidation = subscriptionRepository.validateSubscriptionOnServer(requestedCaptains = 1)
                if (serverValidation != null) {
                    latestSubscriptionSnapshot = serverValidation.snapshot
                    lastServerActiveCaptainsCount = serverValidation.activeCaptainsCount
                    hasVerifiedServerSubscriptionOnce = true
                    isInitialSubscriptionCheckInProgress = false
                    latestSubscriptionServerError = null
                    recomputeSubscriptionState()
                    if (!serverValidation.allowed) {
                        val deniedMsg = serverValidation.message
                            ?: "تم رفض طلب الكابتن من السيرفر: يرجى التأكد من أن اشتراكك فعال ومتاح."
                        _snackbarMessage.value = deniedMsg
                        onError?.invoke(deniedMsg)
                        return@launch
                    }
                }

                val serverResult = subscriptionRepository.createMerchantOrder(requestPayload)
                if (serverResult.isFailure) {
                    val errorMsg = serverResult.exceptionOrNull()?.message
                        ?: "تم رفض إنشاء الطلب من السيرفر: يرجى التأكد من أن اشتراكك نشط وغير موقوف."
                    Log.w("OrderViewModel", "merchantCreateOrder Cloud Function rejected order: $errorMsg")
                    refreshSubscriptionFromServer(uid)
                    _snackbarMessage.value = errorMsg
                    onError?.invoke(errorMsg)
                    return@launch
                }

                val created = serverResult.getOrThrow()
                lastServerActiveCaptainsCount = created.activeCaptainsCount
                recomputeSubscriptionState()

                // Save to local Room database so immediate UI state reflects server-allocated order & 6-digit code
                val newOrder = DeliveryOrderEntity(
                    firestoreOrderId = created.orderId,
                    orderCode = created.orderCode,
                    orderDate = currentDate,
                    destinationAddress = destinationAddress,
                    serviceFee = formattedFee,
                    customerName = customerName,
                    customerPhone = customerPhone,
                    orderDetails = orderDetails,
                    deliveryNotes = deliveryNotes,
                    customerLatitude = customerLatitude,
                    customerLongitude = customerLongitude,
                    status = "جاري البحث",
                    captainName = "في انتظار كابتن",
                    captainPhone = "",
                    captainPhotoUrl = ""
                )
                repository.insertOrder(newOrder)
                _latestCreatedOrderCode.value = created.orderCode
                onSuccess(created.orderCode)
            } finally {
                _isSubmittingOrder.value = false
                orderSubmissionInFlight.set(false)
            }
        }
    }

    // Overload for callers not receiving the orderCode parameter directly
    fun addOrder(
        destinationAddress: String,
        serviceFee: String,
        customerName: String = "",
        customerPhone: String = "",
        orderDetails: String = "",
        deliveryNotes: String = "",
        customerLatitude: Double? = null,
        customerLongitude: Double? = null,
        onSuccess: () -> Unit,
        onError: ((String) -> Unit)? = null
    ) {
        addOrder(
            destinationAddress = destinationAddress,
            serviceFee = serviceFee,
            customerName = customerName,
            customerPhone = customerPhone,
            orderDetails = orderDetails,
            deliveryNotes = deliveryNotes,
            customerLatitude = customerLatitude,
            customerLongitude = customerLongitude,
            onSuccess = { _ -> onSuccess() },
            onError = onError
        )
    }

    @Deprecated("Order lifecycle transitions must use Dashboard Cloud Functions.")
    fun advanceOrderStatus(orderId: Int, currentStatus: String) {
        Log.w(
            "OrderViewModel",
            "Ignoring legacy direct status update for order $orderId ($currentStatus). Use the server-authorized merchant confirmation flow."
        )
        _snackbarMessage.value = "تحديث حالة الطلب يتم من خلال إجراءات التأكيد المعتمدة على الخادم."
    }

    private suspend fun findMerchantOrderDocument(orderId: Any): DocumentSnapshot? {
        val idStr = orderId.toString().trim()
        val numericId = idStr.toIntOrNull()
        val firestore = Firebase.firestore

        // 1. Check in-memory live orders cache for exact Firestore document ID
        val cachedMatch = _firestoreOrders.value.find {
            it.firestoreOrderId == idStr || it.id.toString() == idStr || (numericId != null && it.id == numericId)
        }
        val candidateDocId = cachedMatch?.firestoreOrderId?.takeIf { it.isNotBlank() } ?: idStr

        // 2. Direct document lookup (allowed by Firestore rules for owner)
        if (candidateDocId.isNotBlank()) {
            try {
                val directSnap = firestore.collection("orders").document(candidateDocId).get().await()
                if (directSnap.exists()) return directSnap
            } catch (e: Exception) {
                Log.w("OrderViewModel", "Direct order lookup note for $candidateDocId: ${e.message}")
            }
        }

        // 3. Scoped queries restricted to current merchant UID (compliant with Firestore rules)
        val uid = Firebase.auth.currentUser?.uid ?: return null
        val ownerFields = listOf("restaurantId", "merchantId", "userId")
        for (field in ownerFields) {
            try {
                val snap = firestore.collection("orders")
                    .whereEqualTo(field, uid)
                    .get()
                    .await()
                val match = snap.documents.find { doc ->
                    doc.id == idStr ||
                        doc.safeString("orderId") == idStr ||
                        (numericId != null && (doc.id.hashCode() == numericId || doc.safeString("orderId")?.hashCode() == numericId))
                }
                if (match != null) return match
            } catch (e: Exception) {
                Log.w("OrderViewModel", "Scoped order query ($field) note: ${e.message}")
            }
        }
        return null
    }

    fun confirmCustomerDetails(
        orderId: Any,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            if (!NetworkHelper.isNetworkAvailable(getApplication())) {
                val noNetMsg = "أنت غير متصل بالإنترنت!"
                _snackbarMessage.value = noNetMsg
                onError(noNetMsg)
                return@launch
            }
            try {
                _isLoading.value = true
                val matchedDoc = findMerchantOrderDocument(orderId)
                    ?: throw IllegalStateException("الطلب غير موجود في قاعدة البيانات")

                // Status/approval changes are server-authoritative; no direct client Firestore write.
                subscriptionRepository.confirmMerchantOrder(
                    orderId = matchedDoc.id,
                    confirmed = true
                ).getOrElse { throw it }

                val docRealId = matchedDoc.id
                _firestoreOrders.value = _firestoreOrders.value.map { item ->
                    if (item.firestoreOrderId == docRealId || item.id == docRealId.hashCode() || item.id.toString() == orderId.toString()) {
                        item.copy(
                            status = "جاري التوصيل",
                            customerDetailsConfirmed = true,
                            customerDetailsRejected = false,
                            rejectionReason = ""
                        )
                    } else item
                }
                runCatching {
                    repository.updateOrderStatus(docRealId.hashCode(), "جاري التوصيل")
                }.onFailure { Log.w("OrderViewModel", "Failed to update local status: ${it.message}") }

                _snackbarMessage.value = "تم تأكيد البيانات بنجاح ✓ يمكن للكابتن إكمال توصيل الطلب الآن."
                onSuccess()
            } catch (e: Exception) {
                Log.e("OrderViewModel", "Error confirming customer details: ${e.message}", e)
                val err = "حدث خطأ أثناء تأكيد البيانات: ${e.localizedMessage ?: "يرجى المحاولة مرة أخرى"}"
                _snackbarMessage.value = err
                onError(err)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun rejectCustomerDetails(
        orderId: Any,
        reason: String,
        onSuccess: () -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        viewModelScope.launch {
            if (!NetworkHelper.isNetworkAvailable(getApplication())) {
                val noNetMsg = "أنت غير متصل بالإنترنت!"
                _snackbarMessage.value = noNetMsg
                onError(noNetMsg)
                return@launch
            }
            if (reason.isBlank()) {
                val prompt = "يرجى كتابة سبب رفض البيانات والإيصال"
                _snackbarMessage.value = prompt
                onError(prompt)
                return@launch
            }
            try {
                _isLoading.value = true
                val matchedDoc = findMerchantOrderDocument(orderId)
                    ?: throw IllegalStateException("الطلب غير موجود في قاعدة البيانات")

                subscriptionRepository.confirmMerchantOrder(
                    orderId = matchedDoc.id,
                    confirmed = false,
                    rejectionReason = reason.trim()
                ).getOrElse { throw it }

                val docRealId = matchedDoc.id
                _firestoreOrders.value = _firestoreOrders.value.map { item ->
                    if (item.firestoreOrderId == docRealId || item.id == docRealId.hashCode() || item.id.toString() == orderId.toString()) {
                        item.copy(
                            status = "بيانات مرفوضة",
                            customerDetailsSubmitted = false,
                            customerDetailsConfirmed = false,
                            customerDetailsRejected = true,
                            rejectionReason = reason.trim()
                        )
                    } else item
                }
                runCatching {
                    repository.updateOrderStatus(docRealId.hashCode(), "بيانات مرفوضة")
                }.onFailure { Log.w("OrderViewModel", "Failed to update local status: ${it.message}") }

                _snackbarMessage.value = "تم رفض بيانات العميل مع إرسال السبب للكابتن لإعادة الرفع."
                onSuccess()
            } catch (e: Exception) {
                Log.e("OrderViewModel", "Error rejecting customer details: ${e.message}", e)
                val err = "حدث خطأ أثناء رفض البيانات: ${e.localizedMessage ?: "يرجى المحاولة مرة أخرى"}"
                _snackbarMessage.value = err
                onError(err)
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun cancelOrder(
        orderId: Any,
        reason: String = "إلغاء من قبل التاجر",
        onSuccess: () -> Unit = {},
        onError: ((String) -> Unit)? = null
    ) {
        viewModelScope.launch {
            if (!NetworkHelper.isNetworkAvailable(getApplication())) {
                val noNetMsg = "لا يمكن إلغاء الطلب: أنت غير متصل بالإنترنت!"
                _snackbarMessage.value = noNetMsg
                onError?.invoke(noNetMsg)
                return@launch
            }
            try {
                val matchedDoc = findMerchantOrderDocument(orderId)
                    ?: throw IllegalStateException("الطلب غير موجود في قاعدة البيانات")

                subscriptionRepository.cancelMerchantOrder(
                    orderId = matchedDoc.id,
                    reason = reason
                ).getOrElse { throw it }

                val docRealId = matchedDoc.id
                _firestoreOrders.value = _firestoreOrders.value.map { item ->
                    if (item.firestoreOrderId == docRealId || item.id == docRealId.hashCode() || item.id.toString() == orderId.toString()) {
                        item.copy(status = "ملغي")
                    } else item
                }
                runCatching {
                    repository.updateOrderStatus(docRealId.hashCode(), "ملغي")
                }.onFailure { Log.w("OrderViewModel", "Failed to update local status: ${it.message}") }

                _snackbarMessage.value = "تم إلغاء الطلب بنجاح"
                onSuccess()
            } catch (e: Exception) {
                Log.e("OrderViewModel", "Error cancelling order through server", e)
                val error = "تعذر إلغاء الطلب: ${e.localizedMessage ?: "يرجى المحاولة مرة أخرى"}"
                _snackbarMessage.value = error
                onError?.invoke(error)
            }
        }
    }

    fun dismissPushNotification() {
        _pushNotificationState.value = null
    }

    fun setOrderRating(orderId: Int, rating: Int) {
        viewModelScope.launch {
            // 1. Update local Room database
            repository.updateOrderRating(orderId, rating)

            // 2. Update owning Firestore Order document (scoped lookup; never scan whole orders collection or touch unrelated driver docs)
            try {
                val matchedOrderDoc = findMerchantOrderDocument(orderId)
                if (matchedOrderDoc != null) {
                    matchedOrderDoc.reference.update(
                        mapOf(
                            "rating" to rating,
                            "ratedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()
                }
            } catch (e: Exception) {
                Log.e("OrderViewModel", "Error saving rating to Firestore", e)
            }
        }
    }

    fun updateOrderDetails(
        orderId: Int,
        destinationAddress: String,
        orderDetails: String,
        customerPhone: String = "",
        customerName: String = "",
        serviceFee: String
    ) {
        if (!NetworkHelper.isNetworkAvailable(getApplication())) {
            val noNetMsg = "لا يمكن تعديل بيانات الطلب: أنت غير متصل بالإنترنت!"
            _snackbarMessage.value = noNetMsg
            NetworkHelper.notifyNoInternet(getApplication(), "تعديل بيانات الطلب")
            return
        }
        viewModelScope.launch {
            try {
                val matchedDoc = findMerchantOrderDocument(orderId)
                    ?: throw IllegalStateException("الطلب غير موجود في قاعدة البيانات")

                val resolvedCustomerName = customerName.trim().ifBlank {
                    matchedDoc.safeString("customerName").orEmpty()
                }
                val resolvedCustomerPhone = customerPhone.trim().ifBlank {
                    matchedDoc.safeString("customerPhone").orEmpty()
                }
                val existingNotes = matchedDoc.getString("deliveryNotes")
                    ?: matchedDoc.getString("notes")
                    ?: ""

                subscriptionRepository.updateMerchantOrderDetails(
                    orderId = matchedDoc.id,
                    destinationAddress = destinationAddress,
                    orderDetails = orderDetails,
                    customerPhone = resolvedCustomerPhone,
                    customerName = resolvedCustomerName,
                    deliveryNotes = existingNotes,
                    serviceFee = serviceFee
                ).getOrElse { throw it }

                // Update local Room only after the server has accepted the change.
                repository.updateOrderDetails(
                    orderId = orderId,
                    destinationAddress = destinationAddress,
                    orderDetails = orderDetails,
                    customerPhone = resolvedCustomerPhone,
                    customerName = resolvedCustomerName,
                    serviceFee = serviceFee
                )
                _snackbarMessage.value = "تم تحديث بيانات الطلب بنجاح"
                refreshRestaurantFeed()
            } catch (e: Exception) {
                Log.e("OrderViewModel", "Error updating order details through server", e)
                _snackbarMessage.value = "تعذر تعديل بيانات الطلب: ${e.localizedMessage ?: "يرجى المحاولة مرة أخرى"}"
            }
        }
    }

    private fun parseCoordinatesFromUrl(url: String): GeoPoint? {
        if (url.isBlank()) return null
        val coordRegex = Regex("""(-?\d{1,2}\.\d{3,})\s*,\s*(-?\d{1,3}\.\d{3,})""")
        val match = coordRegex.find(url) ?: return null
        val lat = match.groupValues.getOrNull(1)?.toDoubleOrNull() ?: return null
        val lng = match.groupValues.getOrNull(2)?.toDoubleOrNull() ?: return null
        if (lat in -90.0..90.0 && lng in -180.0..180.0) {
            return GeoPoint(lat, lng)
        }
        return null
    }

    fun saveRestaurantProfile(
        name: String,
        phone: String,
        address: String,
        imageUri: String?,
        latitude: Double? = null,
        longitude: Double? = null,
        locationUrl: String = _restaurantProfile.value.locationUrl,
        category: String = _restaurantProfile.value.category
    ) {
        if (!NetworkHelper.isNetworkAvailable(getApplication())) {
            val noNetMsg = "لا يمكن حفظ وتعديل البيانات: أنت غير متصل بالإنترنت!"
            Log.e("FIREBASE_DEBUG", "NETWORK_ERROR: $noNetMsg")
            _errorMessage.value = noNetMsg
            _snackbarMessage.value = noNetMsg
            _isSaving.value = false
            _isSaveSuccess.value = false
            NetworkHelper.notifyNoInternet(getApplication(), "تعديل وحفظ بيانات المطعم")
            return
        }

        val currentUser = Firebase.auth.currentUser
        val projectId = try { Firebase.app.options.projectId } catch (e: Exception) { "unknown" }
        Log.d("FIREBASE_DEBUG", "saveRestaurantProfile called | Current UID: ${currentUser?.uid} | Firebase Project ID: $projectId")

        if (currentUser == null || !currentUser.isEmailVerified) {
            val err = "يرجى تسجيل الدخول بحساب تاجر مؤكد البريد الإلكتروني أولاً"
            Log.e("FIREBASE_DEBUG", "AUTH_ERROR: $err")
            _errorMessage.value = err
            return
        }

        val uid = currentUser.uid
        val finalName = name.ifBlank { getApplication<Application>().getString(R.string.restaurant_name_default) }
        val cleanLocationUrl = locationUrl.trim()

        _isSaving.value = true
        _errorMessage.value = null
        _isSaveSuccess.value = false

        viewModelScope.launch(Dispatchers.IO) {
            try {
                withTimeout(20000L) {
                    Log.d("FIREBASE_DEBUG", "START_SAVING merchants/$uid: Name='$finalName', Phone='$phone', Address='$address'")

                    val merchantDocRef = Firebase.firestore.collection("merchants").document(uid)
                    val existingDoc = merchantDocRef.get().await()
                    if (!existingDoc.exists() || existingDoc.safeString("accountType")?.trim()?.lowercase() != "merchant") {
                        throw IllegalStateException("لا يوجد حساب تاجر صالح مسجل لهذا المستخدم في merchants/$uid")
                    }

                    val existingRemoteLogoUrl = existingDoc.safeString("logo")
                        ?: existingDoc.safeString("photoUrl")
                        ?: existingDoc.safeString("logoUrl")
                        ?: _restaurantProfile.value.photoUrl
                        ?: _restaurantProfile.value.logoUrl
                        ?: ""
                    val existingStoragePath = existingDoc.safeString("storagePath")
                        ?: _restaurantProfile.value.storagePath

                    // 1. رفع الصورة الجديدة حصرياً إلى Firebase Storage مع حماية الصورة السابقة عند الفشل
                    val currentImg = imageUri?.trim()
                    val uploadOutcome = if (FirebaseStorageManager.isLocalDeviceUri(currentImg)) {
                        val result = FirebaseStorageManager.uploadMerchantProfileImage(
                            context = getApplication(),
                            uid = uid,
                            localUriString = currentImg!!
                        )
                        if (result.isFailure) {
                            throw IllegalStateException("تعذر رفع الصورة الجديدة إلى Firebase Storage؛ تم الاحتفاظ بصورتك السابقة دون تغيير")
                        }
                        result
                    } else {
                        null
                    }

                    val (finalLogoUrl, finalStoragePath) = FirebaseStorageManager.resolveSafeLogoUpdate(
                        existingRemoteUrl = existingRemoteLogoUrl,
                        existingStoragePath = existingStoragePath,
                        candidateUri = currentImg,
                        uploadResult = uploadOutcome
                    )

                    val targetLocation = parseCoordinatesFromUrl(cleanLocationUrl)
                        ?: if (latitude != null && longitude != null) {
                            GeoPoint(latitude, longitude)
                        } else {
                            _restaurantProfile.value.location
                        }

                    val finalCategory = category.trim().ifBlank {
                        existingDoc.safeString("category")?.takeIf { it.isNotBlank() }
                            ?: _restaurantProfile.value.category.ifBlank { "مطعم" }
                    }

                    // التحديث الحصري في مستند merchants/{uid} فقط دون لمس restaurants أو captains أو users
                    val updateMap = mutableMapOf<String, Any>(
                        "uid" to uid,
                        "name" to finalName,
                        "phone" to phone.trim(),
                        "email" to (currentUser.email ?: ""),
                        "address" to address.trim(),
                        "category" to finalCategory,
                        "locationUrl" to cleanLocationUrl,
                        "mapLink" to cleanLocationUrl,
                        "logo" to finalLogoUrl,
                        "photoUrl" to finalLogoUrl,
                        "logoUrl" to finalLogoUrl,
                        "accountType" to "merchant",
                        "updatedAt" to FieldValue.serverTimestamp()
                    )
                    if (finalStoragePath.isNotBlank()) {
                        updateMap["storagePath"] = finalStoragePath
                    }
                    if (targetLocation != null) {
                        updateMap["location"] = targetLocation
                        updateMap["latitude"] = targetLocation.latitude
                        updateMap["longitude"] = targetLocation.longitude
                    }

                    merchantDocRef.set(updateMap, SetOptions.merge()).await()
                    Log.d("FIREBASE_DEBUG", "MERCHANTS_DOC_UPDATED_EXCLUSIVELY: merchants/$uid")

                    _restaurantProfile.value = RestaurantProfile(
                        name = finalName,
                        phone = phone.trim(),
                        address = address.trim(),
                        imageUri = finalLogoUrl.ifBlank { existingRemoteLogoUrl.ifBlank { null } },
                        logoUrl = finalLogoUrl.ifBlank { existingRemoteLogoUrl.ifBlank { null } },
                        photoUrl = finalLogoUrl.ifBlank { existingRemoteLogoUrl.ifBlank { null } },
                        storagePath = finalStoragePath,
                        email = Firebase.auth.currentUser?.email ?: "",
                        category = finalCategory,
                        ownerId = uid,
                        uid = uid,
                        location = targetLocation,
                        locationUrl = cleanLocationUrl
                    )

                    _isSaveSuccess.value = true
                    Log.d("FIREBASE_DEBUG", "SAVE_COMPLETED_SUCCESSFULLY for UID: $uid")
                }
            } catch (timeoutEx: TimeoutCancellationException) {
                val timeoutError = "فشل الاتصال: استغرقت عملية الحفظ وقتاً أطول من اللازم، يرجى التحقق من الشبكة"
                Log.e("FIREBASE_DEBUG", "TIMEOUT_ERROR: $timeoutError", timeoutEx)
                _errorMessage.value = timeoutError
                _isSaveSuccess.value = false
            } catch (e: Exception) {
                Log.e("FIREBASE_DEBUG", "FIRESTORE_WRITE_EXCEPTION: ${e.javaClass.simpleName} - ${e.message}", e)
                val detailedError = e.localizedMessage ?: e.message ?: "فشل حفظ بيانات التاجر"
                _errorMessage.value = detailedError
                _isSaveSuccess.value = false
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun updateRestaurantProfile(name: String, phone: String, address: String, imageUri: String?) {
        saveRestaurantProfile(
            name = name,
            phone = phone,
            address = address,
            imageUri = imageUri
        )
    }

    fun checkAndListenToRestaurantSubscription() {
        val currentUser = Firebase.auth.currentUser
        val uid = currentUser?.uid ?: run {
            latestSubscriptionSnapshot = RawSubscriptionSnapshot()
            latestQuerySubscriptionSnapshots = emptyList()
            latestMerchantSubSnapshot = RawSubscriptionSnapshot()
            lastServerActiveCaptainsCount = null
            hasVerifiedServerSubscriptionOnce = false
            isInitialSubscriptionCheckInProgress = false
            latestSubscriptionServerError = null
            hasEmittedSubscriptionOnce = false
            _subscriptionStatus.value = SubscriptionStatusState(isChecking = false, isExpired = false)
            _subscriptionHistory.value = emptyList()
            _manualSubscriptionRequests.value = emptyList()
            _merchantPayments.value = emptyList()
            return
        }

        val isOnlineNow = NetworkHelper.isOnline.value
        if (isOnlineNow && !hasVerifiedServerSubscriptionOnce) {
            isInitialSubscriptionCheckInProgress = true
        }
        latestSubscriptionServerError = null
        _subscriptionStatus.value = _subscriptionStatus.value.copy(
            isChecking = isOnlineNow && !_subscriptionStatus.value.canRequestCaptain(),
            serverError = null
        )
        restaurantSubListener?.remove()
        merchantSubscriptionsQueryListener?.remove()
        subscriptionPackagesListener?.remove()
        subscriptionHistoryListener?.remove()
        manualRequestsListener?.remove()
        merchantPaymentsListener?.remove()
        vodafoneSettingsListener?.remove()

        // 1. Real-time snapshot listener on canonical subscriptions/{uid}
        restaurantSubListener = subscriptionRepository.observeSubscriptionDocument(
            uid = uid,
            onSnapshot = { raw, fromServer ->
                val currentVer = latestSubscriptionSnapshot.subscriptionVersion ?: 0L
                val incomingVer = raw.subscriptionVersion ?: 0L
                // Always trust live server snapshots so Admin creations/cancellations without version bumps apply immediately
                val shouldAccept = fromServer ||
                    !latestSubscriptionSnapshot.exists ||
                    latestSubscriptionSnapshot.isFromCache ||
                    incomingVer >= currentVer
                if (shouldAccept) {
                    latestSubscriptionSnapshot = raw
                }
                if (fromServer && NetworkHelper.isOnline.value) {
                    hasVerifiedServerSubscriptionOnce = true
                    isInitialSubscriptionCheckInProgress = false
                    latestSubscriptionServerError = null
                } else if (!hasVerifiedServerSubscriptionOnce && NetworkHelper.isOnline.value) {
                    refreshSubscriptionFromServer(uid)
                }
                recomputeSubscriptionState()
            },
            onError = { error ->
                Log.w("OrderViewModel", "Error listening to subscriptions/$uid: ${error.message}")
                if (!NetworkHelper.isOnline.value) {
                    hasVerifiedServerSubscriptionOnce = false
                    isInitialSubscriptionCheckInProgress = false
                } else if (!hasVerifiedServerSubscriptionOnce) {
                    latestSubscriptionServerError = "تعذر مزامنة الاشتراك مع السيرفر (${error.localizedMessage ?: "خطأ في الاتصال"})."
                    isInitialSubscriptionCheckInProgress = false
                }
                recomputeSubscriptionState()
            }
        )

        // 2. Real-time query listener on subscriptions where merchantId == uid
        // Captures Admin-created auto-ID subscriptions AND preserves full renewal history.
        merchantSubscriptionsQueryListener = subscriptionRepository.observeMerchantSubscriptionsQuery(
            uid = uid,
            onSnapshots = { docs, fromServer ->
                latestQuerySubscriptionSnapshots = docs
                if (fromServer && NetworkHelper.isOnline.value) {
                    hasVerifiedServerSubscriptionOnce = true
                    isInitialSubscriptionCheckInProgress = false
                    latestSubscriptionServerError = null
                }
                recomputeSubscriptionState()
            },
            onError = { error ->
                Log.w("OrderViewModel", "Error listening to subscriptions query for $uid: ${error.message}")
                if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    merchantSubscriptionsQueryListener?.remove()
                    merchantSubscriptionsQueryListener = null
                }
            }
        )

        // 2b. Real-time listener on subscription_packages where isActive == true (dynamic plans from Dashboard)
        _isLoadingPackages.value = _subscriptionPackages.value.isEmpty()
        subscriptionPackagesListener = subscriptionRepository.observeSubscriptionPackages(
            onPackages = { packages ->
                _isLoadingPackages.value = false
                _packagesError.value = null
                _subscriptionPackages.value = packages
                publishMergedVodafoneSettings()
                recomputeSubscriptionState()
            },
            onError = { error ->
                Log.w("OrderViewModel", "Error listening to subscription_packages: ${error.message}")
                _isLoadingPackages.value = false
                _packagesError.value = "تعذر تحميل الباقات المتاحة (${error.localizedMessage ?: "خطأ في الاتصال"})."
                if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    subscriptionPackagesListener?.remove()
                    subscriptionPackagesListener = null
                }
            }
        )

        // 2c. Real-time listener on subscription_history where merchantId == uid (archived past periods, local sort)
        subscriptionHistoryListener = subscriptionRepository.observeSubscriptionHistory(
            uid = uid,
            onHistory = { archivedHistory ->
                latestArchivedHistoryItems = archivedHistory
                recomputeSubscriptionState()
            },
            onError = { error ->
                Log.w("OrderViewModel", "Error listening to subscription_history for $uid: ${error.message}")
                if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    subscriptionHistoryListener?.remove()
                    subscriptionHistoryListener = null
                }
            }
        )

        // 3. Real-time listener on manual_subscription_requests where merchantId == uid
        manualRequestsListener = subscriptionRepository.observeManualSubscriptionRequests(
            uid = uid,
            onRequests = { requests ->
                handleManualRequestsUpdated(requests)
            },
            onError = { error ->
                Log.w("OrderViewModel", "Error listening to manual_subscription_requests for $uid: ${error.message}")
                if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    manualRequestsListener?.remove()
                    manualRequestsListener = null
                }
            }
        )

        // 4. Real-time listener on payments where merchantId == uid
        _isLoadingPayments.value = _merchantPayments.value.isEmpty()
        merchantPaymentsListener = subscriptionRepository.observeMerchantPayments(
            uid = uid,
            onPayments = { payments ->
                _isLoadingPayments.value = false
                _paymentsError.value = null
                handleMerchantPaymentsUpdated(payments)
            },
            onError = { error ->
                Log.w("OrderViewModel", "Error listening to payments for $uid: ${error.message}")
                _isLoadingPayments.value = false
                _paymentsError.value = "تعذر تحميل سجل المدفوعات (${error.localizedMessage ?: "خطأ في الاتصال"})."
                if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    merchantPaymentsListener?.remove()
                    merchantPaymentsListener = null
                }
            }
        )

        // 5. Real-time listener on settings/manual_subscription_config so Admin edits to wallet/instructions reflect live
        vodafoneSettingsListener = subscriptionRepository.observeVodafoneCashSettings(
            onSettings = { settings ->
                latestRawVodafoneSettings = settings
                publishMergedVodafoneSettings()
            },
            onError = { error ->
                Log.w("OrderViewModel", "Error listening to settings/manual_subscription_config: ${error.message}")
                if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                    vodafoneSettingsListener?.remove()
                    vodafoneSettingsListener = null
                }
            }
        )

        if (isOnlineNow) {
            refreshSubscriptionFromServer(uid)
            refreshPaymentsAndRequestsFromServer(uid)
        }
    }

    private fun publishMergedVodafoneSettings() {
        val raw = latestRawVodafoneSettings
        val livePackages = _subscriptionPackages.value
        val effectivePlans = if (livePackages.isNotEmpty()) livePackages else raw.plans
        val walletValid = raw.walletNumber.isNotBlank()
        val configured = walletValid && effectivePlans.isNotEmpty()
        _vodafoneCashSettings.value = raw.copy(
            plans = effectivePlans,
            isConfiguredOnServer = configured,
            isLoading = raw.isLoading && effectivePlans.isEmpty(),
            loadError = when {
                !walletValid && !raw.isLoading -> raw.loadError ?: "رقم محفظة فودافون كاش غير مضبوط في إعدادات الإدارة."
                effectivePlans.isEmpty() && !_isLoadingPackages.value && !raw.isLoading ->
                    _packagesError.value ?: "لا توجد باقات نشطة حالياً في إعدادات الإدارة."
                !raw.isAcceptingSubscriptions && configured ->
                    raw.pauseMessage ?: "استقبال طلبات الاشتراك متوقف مؤقتاً من الإدارة."
                else -> null
            }
        )
    }

    private fun handleManualRequestsUpdated(requests: List<ManualSubscriptionRequestRecord>) {
        _manualSubscriptionRequests.value = requests
        if (!hasLoadedManualRequestsOnce) {
            requests.forEach { knownRequestStatuses[it.requestId] = it.status }
            hasLoadedManualRequestsOnce = true
            return
        }
        for (req in requests) {
            val prevStatus = knownRequestStatuses[req.requestId]
            knownRequestStatuses[req.requestId] = req.status
            if (prevStatus != null && prevStatus != req.status) {
                when (req.status) {
                    "approved" -> emitSubscriptionNotification(
                        title = "تمت الموافقة على طلب الاشتراك ✓",
                        body = "تم قبول طلبك لـ ${req.planName} (${req.expectedAmount} ج.م) وتفعيل اشتراكك.",
                        notificationId = ("req_approved_${req.requestId}").hashCode()
                    )
                    "rejected" -> emitSubscriptionNotification(
                        title = "تم رفض طلب الاشتراك ❌",
                        body = req.rejectionReason?.let { "تم رفض طلب ${req.planName}: $it" }
                            ?: "تم رفض طلب ${req.planName} من الإدارة. يمكنك مراجعة التفاصيل وإعادة الإرسال.",
                        notificationId = ("req_rejected_${req.requestId}").hashCode()
                    )
                    "resubmission_required" -> emitSubscriptionNotification(
                        title = "مطلوب إعادة رفع إيصال التحويل 📄",
                        body = req.resubmissionReason ?: "يرجى إعادة رفع صورة واضحة لإيصال تحويل ${req.planName}.",
                        notificationId = ("req_resubmit_${req.requestId}").hashCode()
                    )
                }
            }
        }
    }

    private fun handleMerchantPaymentsUpdated(payments: List<MerchantPaymentRecord>) {
        // NOTE: Unlinked payments in `payments` NEVER auto-activate subscriptions.
        // Only authoritative subscription documents control subscription activation.
        _merchantPayments.value = payments
        if (!hasLoadedPaymentsOnce) {
            payments.forEach { knownPaymentIds.add(it.paymentId) }
            hasLoadedPaymentsOnce = true
            return
        }
        for (payment in payments) {
            if (knownPaymentIds.add(payment.paymentId)) {
                val amountFormatted = if (payment.amount % 1.0 == 0.0) {
                    payment.amount.toInt().toString()
                } else {
                    String.format(Locale.US, "%.2f", payment.amount)
                }
                val pkgPart = payment.packageName?.takeIf { it.isNotBlank() }?.let { " - $it" }.orEmpty()
                emitSubscriptionNotification(
                    title = "تم تسجيل دفعة جديدة 💳",
                    body = "تم تسجيل دفعة بقيمة $amountFormatted ج.م$pkgPart (${SubscriptionSyncResolver.formatPaymentMethodArabic(payment.paymentMethod)}).",
                    notificationId = ("payment_${payment.paymentId}").hashCode()
                )
            }
        }
    }

    private fun emitSubscriptionNotification(
        title: String,
        body: String,
        notificationId: Int = System.currentTimeMillis().toInt()
    ) {
        _snackbarMessage.value = body
        val timeStr = SimpleDateFormat("hh:mm a", Locale("ar")).format(Date())
        _pushNotificationState.value = PushNotificationBanner(
            title = title,
            body = body,
            timestamp = timeStr
        )
        NotificationHelper.showSubscriptionSyncNotification(
            context = getApplication(),
            title = title,
            body = body,
            notificationId = notificationId
        )
    }

    fun refreshPaymentsAndRequestsFromServer(uid: String = Firebase.auth.currentUser?.uid.orEmpty()) {
        if (uid.isBlank() || !NetworkHelper.isOnline.value) return
        viewModelScope.launch {
            _isLoadingPayments.value = true
            _paymentsError.value = null
            val payRes = subscriptionRepository.fetchMerchantPaymentsFromServer(uid)
            payRes.onSuccess { list ->
                handleMerchantPaymentsUpdated(list)
                _paymentsError.value = null
            }.onFailure { err ->
                if (_merchantPayments.value.isEmpty()) {
                    _paymentsError.value = "تعذر تحديث سجل المدفوعات (${err.localizedMessage ?: "خطأ في الاتصال"})."
                }
            }
            _isLoadingPayments.value = false

            subscriptionRepository.fetchManualSubscriptionRequestsFromServer(uid).onSuccess { reqs ->
                handleManualRequestsUpdated(reqs)
            }

            subscriptionRepository.fetchSubscriptionHistoryFromServer(uid).onSuccess { archived ->
                latestArchivedHistoryItems = archived
                recomputeSubscriptionState()
            }

            subscriptionRepository.fetchSubscriptionPackagesFromServer().onSuccess { pkgs ->
                _isLoadingPackages.value = false
                _packagesError.value = null
                _subscriptionPackages.value = pkgs
                publishMergedVodafoneSettings()
            }
        }
    }

    fun refreshSubscriptionFromServer(uid: String = Firebase.auth.currentUser?.uid.orEmpty()) {
        if (uid.isBlank() || !NetworkHelper.isOnline.value) return
        viewModelScope.launch {
            if (!hasVerifiedServerSubscriptionOnce) {
                isInitialSubscriptionCheckInProgress = true
                recomputeSubscriptionState()
            }
            try {
                val result = subscriptionRepository.fetchAuthoritativeSubscription(uid)
                if (result.subscriptionSnapshot != null) {
                    // Authoritative Source.SERVER result always replaces potentially stale state
                    latestSubscriptionSnapshot = result.subscriptionSnapshot
                } else if (result.verifiedFromServer) {
                    latestSubscriptionSnapshot = RawSubscriptionSnapshot(exists = false, isFromCache = false)
                }
                if (result.subscriptionHistoryDocs.isNotEmpty() || result.verifiedFromServer) {
                    latestQuerySubscriptionSnapshots = result.subscriptionHistoryDocs
                }
                val merchSnap = result.merchantDocument
                if (merchSnap != null && merchSnap.exists()) {
                    val accountType = merchSnap.safeString("accountType")?.trim()?.lowercase()
                    val role = merchSnap.safeString("role")?.trim()?.lowercase()
                    if (accountType == "merchant" || (accountType.isNullOrBlank() && role == "merchant")) {
                        _restaurantProfile.value = mapDocToRestaurantProfile(merchSnap, uid)
                        latestMerchantSubSnapshot = SubscriptionSyncResolver.fromFirestoreSnapshot(merchSnap)
                    }
                }
                if (result.activeCaptainsCount != null) {
                    lastServerActiveCaptainsCount = result.activeCaptainsCount
                }
                isInitialSubscriptionCheckInProgress = false
                if (result.verifiedFromServer) {
                    hasVerifiedServerSubscriptionOnce = true
                    latestSubscriptionServerError = null
                } else if (!hasVerifiedServerSubscriptionOnce) {
                    latestSubscriptionServerError = result.errorMessage
                        ?: "تعذر التحقق من حالة الاشتراك مع السيرفر. يرجى المحاولة مرة أخرى."
                }
                recomputeSubscriptionState()
            } catch (e: Exception) {
                Log.w("OrderViewModel", "Server subscription verification failed (will retry on reconnect): ${e.message}")
                isInitialSubscriptionCheckInProgress = false
                if (!NetworkHelper.isOnline.value) {
                    hasVerifiedServerSubscriptionOnce = false
                } else if (!hasVerifiedServerSubscriptionOnce) {
                    latestSubscriptionServerError = "تعذر التحقق من حالة الاشتراك مع السيرفر. يرجى التحقق من الاتصال والمحاولة مرة أخرى."
                }
                recomputeSubscriptionState()
            }
        }
    }

    private fun recomputeSubscriptionState(orders: List<DeliveryOrderEntity> = _firestoreOrders.value) {
        val liveCount = orders.count { isOrderActiveForCaptainLimit(it.status) }
        val activeOrdersCount = maxOf(liveCount, lastServerActiveCaptainsCount ?: 0)
        val nowMillis = System.currentTimeMillis()
        val previous = _subscriptionStatus.value

        // Reconcile canonical subscriptions/{uid} + auto-ID subscriptions where merchantId == uid
        val effectiveSubSnapshot = SubscriptionSyncResolver.selectEffectiveSubscriptionSnapshot(
            canonicalDoc = latestSubscriptionSnapshot,
            queryDocs = latestQuerySubscriptionSnapshots,
            nowMillis = nowMillis
        )
        _subscriptionHistory.value = SubscriptionSyncResolver.buildSubscriptionHistory(
            canonicalDoc = latestSubscriptionSnapshot,
            queryDocs = latestQuerySubscriptionSnapshots,
            archivedHistoryDocs = latestArchivedHistoryItems,
            nowMillis = nowMillis
        )

        val baseResolved = SubscriptionSyncResolver.resolve(
            subscriptionDoc = effectiveSubSnapshot,
            merchantDoc = latestMerchantSubSnapshot,
            isOnline = NetworkHelper.isOnline.value,
            nowMillis = nowMillis,
            activeCaptainsCount = activeOrdersCount,
            hasVerifiedServerOnce = hasVerifiedServerSubscriptionOnce,
            isInitialServerCheckInProgress = isInitialSubscriptionCheckInProgress,
            serverError = latestSubscriptionServerError
        )
        val matchedPackage = _subscriptionPackages.value.firstOrNull { pkg ->
            SubscriptionSyncResolver.planIdsMatch(pkg.id, baseResolved.planId) ||
                SubscriptionSyncResolver.planIdsMatch(pkg.planKey, baseResolved.planId) ||
                SubscriptionSyncResolver.planIdsMatch(pkg.packageId, baseResolved.planId)
        }
        val resolved = if (matchedPackage != null) {
            baseResolved.copy(
                durationDays = if (baseResolved.durationDays > 0) baseResolved.durationDays else matchedPackage.durationDays,
                features = baseResolved.features.ifEmpty { matchedPackage.features },
                price = if (baseResolved.price > 0) baseResolved.price else matchedPackage.priceEgp
            )
        } else {
            baseResolved
        }
        _subscriptionStatus.value = resolved

        if (resolved.canRequestCaptain(nowMillis)) {
            _errorMessage.value = null
            // Notify once per subscription expiry cycle when <= 3 days remain
            val expiringNotice = SubscriptionSyncResolver.buildExpiringSoonNotification(resolved, nowMillis)
            val expiryKey = "${resolved.subscriptionId ?: resolved.planId}_${resolved.endDateMillis ?: 0L}"
            if (expiringNotice != null && notifiedExpiringSoonKeys.add(expiryKey)) {
                emitSubscriptionNotification(
                    title = expiringNotice.title,
                    body = expiringNotice.body,
                    notificationId = ("expiring_soon_$expiryKey").hashCode()
                )
            }
        }

        val transition = SubscriptionSyncResolver.detectTransitionNotification(
            previous = previous,
            current = resolved,
            hadPreviousState = hasEmittedSubscriptionOnce,
            nowMillis = nowMillis
        )
        if (!resolved.isChecking && resolved.status != "checking") {
            hasEmittedSubscriptionOnce = true
        }
        if (transition != null) {
            emitSubscriptionNotification(
                title = transition.title,
                body = transition.body,
                notificationId = ("sub_transition_${resolved.status}_${resolved.subscriptionVersion}").hashCode()
            )
        }
    }

    fun loadVodafoneCashSettings() {
        viewModelScope.launch {
            _vodafoneCashSettings.value = _vodafoneCashSettings.value.copy(isLoading = true, loadError = null)
            _isLoadingPackages.value = _subscriptionPackages.value.isEmpty()
            val pkgsResult = subscriptionRepository.fetchSubscriptionPackagesFromServer()
            pkgsResult.onSuccess { pkgs ->
                _isLoadingPackages.value = false
                _packagesError.value = null
                _subscriptionPackages.value = pkgs
            }.onFailure { err ->
                _isLoadingPackages.value = false
                if (_subscriptionPackages.value.isEmpty()) {
                    _packagesError.value = "تعذر تحميل الباقات المتاحة (${err.localizedMessage ?: "خطأ في الاتصال"})."
                }
            }
            val fetched = subscriptionRepository.fetchVodafoneCashSettings(
                knownPackages = _subscriptionPackages.value
            )
            latestRawVodafoneSettings = fetched
            publishMergedVodafoneSettings()
        }
    }

    fun resetManualSubscriptionUiState() {
        _manualSubscriptionUiState.value = ManualSubscriptionUiState.Idle
    }

    /**
     * Full manual Vodafone Cash subscription submission flow:
     * 1. Validates inputs & ensures no active/pending_review duplicate exists.
     * 2. Uploads receipt image EXCLUSIVELY to Firebase Storage (`merchants/{uid}/subscription_receipts/{reqId}.jpg`).
     * 3. Calls Cloud Function `submitManualSubscriptionRequest` (region us-central1) to record `pending_review`.
     * 4. Refreshes server subscription state (never activates locally).
     */
    fun submitManualVodafoneSubscription(
        plan: SubscriptionPlanOption,
        senderPhone: String,
        transactionReference: String,
        receiptLocalUri: String?,
        notes: String = "",
        onSuccess: (ManualSubscriptionSubmissionResult) -> Unit = {},
        onError: (String) -> Unit = {}
    ) {
        // Guard against concurrent double-taps
        if (_manualSubscriptionUiState.value is ManualSubscriptionUiState.Submitting) {
            return
        }

        if (!NetworkHelper.isNetworkAvailable(getApplication())) {
            val noNetMsg = "لا يوجد اتصال بالإنترنت! يرجى الاتصال بالإنترنت لإرسال طلب الاشتراك."
            _manualSubscriptionUiState.value = ManualSubscriptionUiState.Error(noNetMsg)
            _snackbarMessage.value = noNetMsg
            onError(noNetMsg)
            return
        }

        val currentUser = Firebase.auth.currentUser
        if (currentUser == null || !currentUser.isEmailVerified) {
            val authMsg = "يجب تسجيل الدخول بحساب تاجر مؤكد البريد الإلكتروني أولاً."
            _manualSubscriptionUiState.value = ManualSubscriptionUiState.Error(authMsg)
            _snackbarMessage.value = authMsg
            onError(authMsg)
            return
        }

        val validationError = MerchantSubscriptionRepository.validateManualSubscriptionInput(
            plan = plan,
            senderPhone = senderPhone,
            receiptLocalUri = receiptLocalUri,
            currentSubscription = _subscriptionStatus.value
        )
        val paymentSettings = _vodafoneCashSettings.value
        if (!paymentSettings.isConfiguredOnServer) {
            val configError = paymentSettings.loadError
                ?: "إعدادات الاشتراك غير جاهزة على السيرفر. يرجى التواصل مع الإدارة."
            _manualSubscriptionUiState.value = ManualSubscriptionUiState.Error(configError)
            _snackbarMessage.value = configError
            onError(configError)
            return
        }
        if (!paymentSettings.isAcceptingSubscriptions) {
            val pausedError = paymentSettings.pauseMessage
                ?: paymentSettings.loadError
                ?: "استقبال طلبات الاشتراك متوقف مؤقتاً من الإدارة."
            _manualSubscriptionUiState.value = ManualSubscriptionUiState.Error(pausedError)
            _snackbarMessage.value = pausedError
            onError(pausedError)
            return
        }
        val activePackages = _subscriptionPackages.value.ifEmpty { paymentSettings.plans }
        val trustedPlan = activePackages.firstOrNull {
            SubscriptionSyncResolver.planIdsMatch(it.id, plan.id) ||
                SubscriptionSyncResolver.planIdsMatch(it.planKey, plan.planKey) ||
                SubscriptionSyncResolver.planIdsMatch(it.packageId, plan.packageId)
        }
        if (trustedPlan == null || !trustedPlan.isActive) {
            val planError = "الباقة المختارة لم تعد متاحة أو مفعّلة في إعدادات الإدارة الحالية. أعد تحميل الباقات ثم اختر الباقة من جديد."
            _manualSubscriptionUiState.value = ManualSubscriptionUiState.Error(planError)
            _snackbarMessage.value = planError
            onError(planError)
            return
        }
        if (validationError != null) {
            _manualSubscriptionUiState.value = ManualSubscriptionUiState.Error(validationError)
            _snackbarMessage.value = validationError
            onError(validationError)
            return
        }

        val normalizedPhone = MerchantSubscriptionRepository.normalizeEgyptianMobile(senderPhone)!!
        val uid = currentUser.uid
        val currentSubscription = _subscriptionStatus.value
        val idempotencyKey = if (currentSubscription.isResubmissionRequired && !currentSubscription.pendingRequestId.isNullOrBlank()) {
            currentSubscription.pendingRequestId.trim()
        } else {
            "subreq_${System.currentTimeMillis()}_${UUID.randomUUID().toString().replace("-", "").take(10)}"
        }

        viewModelScope.launch {
            _manualSubscriptionUiState.value = ManualSubscriptionUiState.Submitting("جاري رفع صورة إيصال التحويل إلى Firebase Storage...")

            val uploadResult = FirebaseStorageManager.uploadSubscriptionReceiptImage(
                context = getApplication(),
                uid = uid,
                requestId = idempotencyKey,
                localUriString = receiptLocalUri!!
            )
            if (uploadResult.isFailure) {
                val uploadErr = uploadResult.exceptionOrNull()?.message
                    ?: "تعذر رفع صورة إيصال التحويل. يرجى التأكد من الإنترنت والمحاولة مرة أخرى."
                _manualSubscriptionUiState.value = ManualSubscriptionUiState.Error(uploadErr)
                _snackbarMessage.value = uploadErr
                onError(uploadErr)
                return@launch
            }

            val uploadedReceipt = uploadResult.getOrThrow()
            _manualSubscriptionUiState.value = ManualSubscriptionUiState.Submitting("جاري إرسال طلب الاشتراك للمراجعة...")

            // Server authoritatively computes amount, captain limit, and duration from `planId`
            val payload = ManualSubscriptionRequestPayload(
                merchantId = uid,
                planId = trustedPlan.id,
                planKey = trustedPlan.planKey,
                packageId = trustedPlan.packageId,
                senderPhone = normalizedPhone,
                transactionReference = transactionReference.trim(),
                receiptImagePath = uploadedReceipt.storagePath,
                idempotencyKey = idempotencyKey,
                notes = notes.trim().take(500)
            )

            val submitResult = subscriptionRepository.submitManualSubscriptionRequest(
                payload = payload,
                fallbackPlanName = trustedPlan.name,
                fallbackMaxCaptains = trustedPlan.maxCaptains,
                fallbackDurationDays = trustedPlan.durationDays
            )
            if (submitResult.isFailure) {
                val submitErr = submitResult.exceptionOrNull()?.message
                    ?: "تعذر إرسال طلب الاشتراك للمراجعة. يرجى المحاولة مرة أخرى."
                _manualSubscriptionUiState.value = ManualSubscriptionUiState.Error(submitErr)
                _snackbarMessage.value = submitErr
                onError(submitErr)
                return@launch
            }

            val submitted = submitResult.getOrThrow()
            _manualSubscriptionUiState.value = ManualSubscriptionUiState.Submitted(submitted)
            val amountLabel = if (submitted.amount % 1.0 == 0.0) {
                submitted.amount.toInt().toString()
            } else {
                String.format(Locale.US, "%.2f", submitted.amount)
            }
            emitSubscriptionNotification(
                title = "تم إرسال طلب الاشتراك للمراجعة ⏳",
                body = "تم إرسال طلب ${submitted.planName} ($amountLabel ج.م) برقم ${submitted.requestNumber} وبانتظار مراجعة الإدارة.",
                notificationId = ("sub_submitted_${submitted.requestId}").hashCode()
            )
            refreshSubscriptionFromServer(uid)
            refreshPaymentsAndRequestsFromServer(uid)
            onSuccess(submitted)
        }
    }

    fun recheckSubscription() {
        hasVerifiedServerSubscriptionOnce = false
        checkAndListenToRestaurantSubscription()
        refreshSubscriptionFromServer()
        refreshPaymentsAndRequestsFromServer()
        loadVodafoneCashSettings()
    }

    fun clearListeners() {
        profileListener?.remove()
        profileListener = null
        merchantProfileListener?.remove()
        merchantProfileListener = null
        ordersListener?.remove()
        ordersListener = null
        restaurantOrdersListener?.remove()
        restaurantOrdersListener = null
        merchantOrdersListener?.remove()
        merchantOrdersListener = null
        pendingListener?.remove()
        pendingListener = null
        captainOrdersListener?.remove()
        captainOrdersListener = null
        driverListener?.remove()
        driverListener = null
        restaurantSubListener?.remove()
        restaurantSubListener = null
        merchantSubscriptionsQueryListener?.remove()
        merchantSubscriptionsQueryListener = null
        subscriptionPackagesListener?.remove()
        subscriptionPackagesListener = null
        subscriptionHistoryListener?.remove()
        subscriptionHistoryListener = null
        manualRequestsListener?.remove()
        manualRequestsListener = null
        merchantPaymentsListener?.remove()
        merchantPaymentsListener = null
        vodafoneSettingsListener?.remove()
        vodafoneSettingsListener = null
        orderTrackingListener?.remove()
        orderTrackingListener = null
        captainTrackingListener?.remove()
        captainTrackingListener = null
        currentTrackedCaptainId = null
        activeListenersUid = null
        latestSubscriptionSnapshot = RawSubscriptionSnapshot()
        latestQuerySubscriptionSnapshots = emptyList()
        latestArchivedHistoryItems = emptyList()
        latestRawVodafoneSettings = VodafoneCashSettings()
        latestMerchantSubSnapshot = RawSubscriptionSnapshot()
        lastServerActiveCaptainsCount = null
        hasVerifiedServerSubscriptionOnce = false
        isInitialSubscriptionCheckInProgress = false
        latestSubscriptionServerError = null
        hasEmittedSubscriptionOnce = false
        hasLoadedPaymentsOnce = false
        hasLoadedManualRequestsOnce = false
        knownPaymentIds.clear()
        knownRequestStatuses.clear()
        notifiedExpiringSoonKeys.clear()
        userOrdersDocs.clear()
        restaurantOrdersDocs.clear()
        merchantOrdersDocs.clear()
        captainInfoCache.clear()
        captainInfoByPhoneCache.clear()
        captainLookupTimestampMs.clear()
        resolvedOrderStoragePhotoCache.clear()
        orderSubmissionInFlight.set(false)
        _isSubmittingOrder.value = false
        _firestoreOrders.value = emptyList()
        _restaurantProfile.value = RestaurantProfile()
        _trackedCaptainInfo.value = null
        _subscriptionStatus.value = SubscriptionStatusState(isChecking = false, isExpired = false)
        _subscriptionPackages.value = emptyList()
        _isLoadingPackages.value = false
        _packagesError.value = null
        _subscriptionHistory.value = emptyList()
        _manualSubscriptionRequests.value = emptyList()
        _merchantPayments.value = emptyList()
        _vodafoneCashSettings.value = VodafoneCashSettings()
        _manualSubscriptionUiState.value = ManualSubscriptionUiState.Idle
        _isLoadingPayments.value = false
        _paymentsError.value = null
        _snackbarMessage.value = null
        _errorMessage.value = null
        _pushNotificationState.value = null
        _latestCreatedOrderCode.value = null
    }
}

