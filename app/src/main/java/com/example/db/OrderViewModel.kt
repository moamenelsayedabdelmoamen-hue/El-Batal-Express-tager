package com.example.db

import android.app.Application
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.R
import com.example.network.NetworkHelper
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
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import com.google.firebase.storage.storage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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
import kotlinx.coroutines.tasks.await

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
    val subscriptionStatus: String = "active",
    val subscriptionId: String? = null,
    val ownerId: String = "",
    val uid: String = "",
    val location: GeoPoint? = null,
    val latitude: Double? = location?.latitude,
    val longitude: Double? = location?.longitude
)

data class DriverProfile(
    val name: String = "",
    val phone: String = "",
    val vehicleType: String = "دراجة نارية",
    val rating: String = "5.0",
    val totalTrips: Int = 0,
    val isOnline: Boolean = true,
    val photoUrl: String = "https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=250&q=80"
)

data class CaptainProfile(
    val uid: String = "",
    val name: String = "",
    val phone: String = "",
    val profileImageUrl: String = "https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=250&q=80",
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

data class SubscriptionStatusState(
    val isExpired: Boolean = false,
    val status: String = "active",
    val endDateMillis: Long? = null,
    val message: String? = null,
    val isChecking: Boolean = false,
    val planId: Int = 1,
    val planName: String = "الباقة الأساسية",
    val maxCaptains: Int = 2,
    val activeCaptainsCount: Int = 0,
    val availableCaptainSlots: Int = 2,
    val paymentStatus: String = "paid"
)

class OrderViewModel(application: Application) : AndroidViewModel(application) {
    private val repository: OrderRepository

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
                // Fetch restaurant profile and orders from Firestore to ensure immediate fresh data
                val uid = Firebase.auth.currentUser?.uid
                if (uid != null) {
                    try {
                        val doc = Firebase.firestore.collection("restaurants").document(uid).get().await()
                        if (doc != null && doc.exists()) {
                            _restaurantProfile.value = mapDocToRestaurantProfile(doc, uid)
                        }
                    } catch (e: Exception) {
                        Log.w("OrderViewModel", "Failed to refresh profile directly: ${e.message}")
                    }

                    try {
                        val ordersSnap = Firebase.firestore.collection("orders")
                            .whereEqualTo("userId", uid)
                            .get()
                            .await()
                        val firestoreList = ordersSnap.documents.mapNotNull { oDoc ->
                            try {
                                mapDocToOrder(oDoc)
                            } catch (e: Exception) {
                                Log.w("OrderViewModel", "Failed to map order doc ${oDoc.id}: ${e.message}")
                                null
                            }
                        }
                        _firestoreOrders.value = firestoreList
                    } catch (e: Exception) {
                        Log.w("OrderViewModel", "Failed to refresh orders directly: ${e.message}")
                    }
                }
                delay(600)
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
    private var pendingListener: ListenerRegistration? = null
    private var captainOrdersListener: ListenerRegistration? = null
    private var driverListener: ListenerRegistration? = null
    private var restaurantSubListener: ListenerRegistration? = null
    private var orderTrackingListener: ListenerRegistration? = null

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
        val uid = Firebase.auth.currentUser?.uid ?: return
        viewModelScope.launch {
            try {
                val restDocRef = Firebase.firestore.collection("restaurants").document(uid)
                val updates = hashMapOf<String, Any>(
                    "location" to geoPoint,
                    "latitude" to lat,
                    "longitude" to lng,
                    "updatedAt" to FieldValue.serverTimestamp()
                )
                restDocRef.set(updates, SetOptions.merge()).await()
                _snackbarMessage.value = "تم حفظ موقع المطعم بنجاح"
                onComplete?.invoke(true)
            } catch (e: Exception) {
                Log.e("OrderViewModel", "Failed to update restaurant location: ${e.message}", e)
                _snackbarMessage.value = "تعذر حفظ الموقع: ${e.localizedMessage}"
                onComplete?.invoke(false)
            }
        }
    }

    fun startTrackingOrderCaptain(orderId: String) {
        stopTrackingOrderCaptain()
        val uid = Firebase.auth.currentUser?.uid ?: return

        orderTrackingListener = Firebase.firestore.collection("orders")
            .document(orderId)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w("OrderViewModel", "Error tracking order $orderId: ${error.message}")
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val orderOwnerId = snapshot.safeString("userId") ?: snapshot.safeString("restaurantId")
                    // Only track if this order belongs to current restaurant
                    if (orderOwnerId != uid) {
                        _trackedCaptainInfo.value = null
                        return@addSnapshotListener
                    }

                    val captId = snapshot.safeString("captainId") ?: snapshot.safeString("driverId") ?: ""
                    val captName = snapshot.getAnyString("captainName", "driver_name", "driverName") ?: ""
                    val captPhone = snapshot.getAnyString("captainPhone", "driverPhone") ?: ""
                    val captPhoto = snapshot.getAnyString("captainPhotoUrl", "driverPhoto") ?: ""
                    val status = snapshot.safeString("status") ?: ""
                    val eta = snapshot.safeString("estimatedArrival") ?: ""

                    val captainGeo = snapshot.getGeoPoint("captainLocation")
                    val captLat = captainGeo?.latitude ?: (snapshot.get("captainLatitude") as? Number)?.toDouble()
                    val captLng = captainGeo?.longitude ?: (snapshot.get("captainLongitude") as? Number)?.toDouble()

                    if (captId.isNotBlank() || captName.isNotBlank()) {
                        _trackedCaptainInfo.value = CaptainLocationInfo(
                            captainId = captId,
                            name = captName,
                            phone = captPhone,
                            photoUrl = captPhoto,
                            latitude = captLat,
                            longitude = captLng,
                            status = status,
                            estimatedArrival = eta
                        )
                    } else {
                        _trackedCaptainInfo.value = null
                    }
                } else {
                    _trackedCaptainInfo.value = null
                }
            }
    }

    fun stopTrackingOrderCaptain() {
        orderTrackingListener?.remove()
        orderTrackingListener = null
        _trackedCaptainInfo.value = null
    }

    private val authStateListener = com.google.firebase.auth.FirebaseAuth.AuthStateListener { auth ->
        val user = auth.currentUser
        if (user != null && user.isEmailVerified) {
            listenToFirestore()
            listenToFirestoreCaptain()
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

        val currentUser = Firebase.auth.currentUser
        if (currentUser != null && currentUser.isEmailVerified) {
            listenToFirestore()
            listenToFirestoreCaptain()
        }
    }

    fun mapDocToRestaurantProfile(doc: DocumentSnapshot, defaultUid: String): RestaurantProfile {
        val name = doc.safeString("name")
            ?: doc.safeString("restaurantName")
            ?: Firebase.auth.currentUser?.displayName
            ?: "مطعم"
        val phone = doc.safeString("phone") ?: ""
        val address = doc.safeString("address") ?: "العنوان غير محدد"
        val logoUrl = doc.safeString("photoUrl")
            ?: doc.safeString("logoUrl")
            ?: doc.getAnyString("imageUrl", "imageUri", "image")
            ?: ""
        val storagePath = doc.safeString("storagePath") ?: ""
        val email = doc.safeString("email") ?: (Firebase.auth.currentUser?.email ?: "")
        val category = doc.safeString("category") ?: "مطعم"
        val status = doc.safeString("status") ?: "active"
        val rating = (doc.get("rating") as? Number)?.toDouble() ?: 5.0
        val ratingCount = (doc.get("ratingCount") as? Number)?.toLong() ?: 0L
        val subStatus = doc.safeString("subscriptionStatus") ?: "active"
        val subId = doc.safeString("subscriptionId")
        val ownerId = doc.safeString("ownerId") ?: defaultUid
        val uid = doc.safeString("uid") ?: defaultUid

        val locationGeoPoint = doc.getGeoPoint("location")
            ?: run {
                val lat = (doc.get("latitude") as? Number)?.toDouble()
                val lng = (doc.get("longitude") as? Number)?.toDouble()
                if (lat != null && lng != null) GeoPoint(lat, lng) else null
            }

        return RestaurantProfile(
            name = name,
            phone = phone,
            address = address,
            imageUri = logoUrl.takeIf { it.isNotBlank() },
            logoUrl = logoUrl.takeIf { it.isNotBlank() },
            photoUrl = logoUrl.takeIf { it.isNotBlank() },
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
            location = locationGeoPoint
        )
    }

    fun cleanAndNormalizeRestaurantDoc(doc: DocumentSnapshot) {
        val data = doc.data ?: return
        val docId = doc.id
        val updates = mutableMapOf<String, Any>()

        // 1. name vs restaurantName
        val currentName = (data["name"] as? String)?.trim()
        val currentRestaurantName = (data["restaurantName"] as? String)?.trim()
        val finalName = when {
            !currentName.isNullOrBlank() -> currentName
            !currentRestaurantName.isNullOrBlank() -> currentRestaurantName
            else -> "مطعم"
        }
        if (currentName != finalName) {
            updates["name"] = finalName
        }
        if (data.containsKey("restaurantName")) {
            updates["restaurantName"] = FieldValue.delete()
        }

        // 2. Images: adopt logoUrl only.
        val currentLogoUrl = (data["logoUrl"] as? String)?.trim()
        val currentImageUrl = (data["imageUrl"] as? String)?.trim()
        val currentImageUri = (data["imageUri"] as? String)?.trim()
        val currentImage = (data["image"] as? String)?.trim()

        val finalLogoUrl = when {
            !currentLogoUrl.isNullOrBlank() -> currentLogoUrl
            !currentImageUrl.isNullOrBlank() -> currentImageUrl
            !currentImageUri.isNullOrBlank() -> currentImageUri
            !currentImage.isNullOrBlank() -> currentImage
            else -> ""
        }
        if (currentLogoUrl != finalLogoUrl) {
            updates["logoUrl"] = finalLogoUrl
        }
        if (data.containsKey("imageUrl")) {
            updates["imageUrl"] = FieldValue.delete()
        }
        if (data.containsKey("imageUri")) {
            updates["imageUri"] = FieldValue.delete()
        }
        if (data.containsKey("image")) {
            updates["image"] = FieldValue.delete()
        }

        // Note: Protected fields (uid, ownerId, createdAt, subscriptionStatus, subscriptionId,
        // subscriptionStartDate, subscriptionEndDate, rating, ratingCount, status) are strictly preserved and never modified on update.
        if (updates.isNotEmpty()) {
            updates["updatedAt"] = FieldValue.serverTimestamp()
            Log.d("RESTAURANTS_CLEANUP", "Normalizing restaurant doc $docId: ${updates.keys}")
            Firebase.firestore.collection("restaurants").document(docId)
                .update(updates)
                .addOnSuccessListener {
                    Log.d("RESTAURANTS_CLEANUP", "Doc $docId normalized successfully in Firestore.")
                }
                .addOnFailureListener { e ->
                    Log.w("RESTAURANTS_CLEANUP", "Could not normalize doc $docId: ${e.message}")
                }
        }
    }

    fun checkAndMigrateRestaurantDoc(uid: String) {
        val prefs = getApplication<Application>().getSharedPreferences("restaurant_migration_prefs", android.content.Context.MODE_PRIVATE)
        val isMigrated = prefs.getBoolean("schema_migrated_$uid", false)
        if (isMigrated) return

        viewModelScope.launch {
            try {
                val doc = Firebase.firestore.collection("restaurants").document(uid).get().await()
                if (doc != null && doc.exists()) {
                    cleanAndNormalizeRestaurantDoc(doc)
                }
                prefs.edit().putBoolean("schema_migrated_$uid", true).apply()
                Log.d("RESTAURANTS_CLEANUP", "Single-run migration completed and flag set for $uid")
            } catch (e: Exception) {
                Log.w("RESTAURANTS_CLEANUP", "Migration note for restaurant $uid: ${e.message}")
            }
        }
    }

    fun listenToFirestore() {
        val currentUser = Firebase.auth.currentUser
        if (currentUser == null || !currentUser.isEmailVerified) {
            clearListeners()
            return
        }
        val uid = currentUser.uid

        checkAndListenToRestaurantSubscription()

        // 1. Read / Listen to current user profile from Firestore (restaurants and merchants)
        profileListener?.remove()
        merchantProfileListener?.remove()
        checkAndMigrateRestaurantDoc(uid)

        merchantProfileListener = Firebase.firestore.collection("merchants").document(uid)
            .addSnapshotListener { mSnap, mErr ->
                if (mErr != null) {
                    if (mErr.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        merchantProfileListener?.remove()
                        merchantProfileListener = null
                    }
                    return@addSnapshotListener
                }
                if (mSnap != null && mSnap.exists()) {
                    val mName = mSnap.safeString("name")
                    val mPhone = mSnap.safeString("phone")
                    val mPhoto = mSnap.safeString("photoUrl")
                    val mStoragePath = mSnap.safeString("storagePath")
                    val mEmail = mSnap.safeString("email")
                    val mAddress = mSnap.safeString("address")

                    _restaurantProfile.value = _restaurantProfile.value.copy(
                        name = if (!mName.isNullOrBlank()) mName else _restaurantProfile.value.name,
                        phone = if (!mPhone.isNullOrBlank()) mPhone else _restaurantProfile.value.phone,
                        photoUrl = if (!mPhoto.isNullOrBlank()) mPhoto else _restaurantProfile.value.photoUrl,
                        logoUrl = if (!mPhoto.isNullOrBlank()) mPhoto else _restaurantProfile.value.logoUrl,
                        imageUri = if (!mPhoto.isNullOrBlank()) mPhoto else _restaurantProfile.value.imageUri,
                        storagePath = if (!mStoragePath.isNullOrBlank()) mStoragePath else _restaurantProfile.value.storagePath,
                        email = if (!mEmail.isNullOrBlank()) mEmail else _restaurantProfile.value.email,
                        address = if (!mAddress.isNullOrBlank()) mAddress else _restaurantProfile.value.address
                    )
                }
            }

        profileListener = Firebase.firestore.collection("restaurants").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        Log.w("OrderViewModel", "Profile read PERMISSION_DENIED; detaching listener")
                        profileListener?.remove()
                        profileListener = null
                    } else {
                        Log.w("OrderViewModel", "Error reading restaurant profile: ${error.message}")
                    }
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    _restaurantProfile.value = mapDocToRestaurantProfile(snapshot, uid)
                } else {
                    // Fallback to users collection
                    Firebase.firestore.collection("users").document(uid).get()
                        .addOnSuccessListener { userSnap ->
                            if (userSnap != null && userSnap.exists()) {
                                val name = userSnap.safeString("name")
                                    ?: userSnap.safeString("restaurantName")
                                    ?: Firebase.auth.currentUser?.displayName
                                    ?: "مستخدم جديد"
                                val phone = userSnap.safeString("phone") ?: ""
                                val address = userSnap.safeString("address") ?: "العنوان غير محدد"
                                val imageUri = userSnap.safeString("logoUrl")
                                    ?: userSnap.getAnyString("imageUri", "imageUrl", "photoUrl")

                                _restaurantProfile.value = RestaurantProfile(
                                    name = name,
                                    phone = phone,
                                    address = address,
                                    imageUri = imageUri,
                                    logoUrl = imageUri,
                                    ownerId = uid,
                                    uid = uid
                                )
                            } else {
                                val emailName = Firebase.auth.currentUser?.email?.substringBefore("@") ?: "مستخدم جديد"
                                _restaurantProfile.value = RestaurantProfile(
                                    name = emailName,
                                    phone = Firebase.auth.currentUser?.phoneNumber ?: "",
                                    address = "العنوان غير محدد",
                                    imageUri = null,
                                    ownerId = uid,
                                    uid = uid
                                )
                            }
                        }
                }
            }

        // 2. Read / Listen to real-time orders for current user from Firestore
        ordersListener?.remove()
        ordersListener = Firebase.firestore.collection("orders")
            .whereEqualTo("userId", uid)
            .addSnapshotListener { querySnapshot, error ->
                if (error != null) {
                    if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        Log.w("OrderViewModel", "Orders stream PERMISSION_DENIED; detaching listener")
                        ordersListener?.remove()
                        ordersListener = null
                    } else {
                        Log.w("OrderViewModel", "Error reading orders from Firestore: ${error.message}")
                    }
                    return@addSnapshotListener
                }
                if (querySnapshot != null) {
                    val firestoreList = querySnapshot.documents.mapNotNull { doc ->
                        try {
                            mapDocToOrder(doc)
                        } catch (e: Exception) {
                            Log.w("OrderViewModel", "Failed to map order doc ${doc.id}: ${e.message}")
                            null
                        }
                    }
                    _firestoreOrders.value = firestoreList
                }
            }
    }

    fun listenToFirestoreCaptain() {
        val uid = Firebase.auth.currentUser?.uid ?: return

        // 1. Listen to captains collection for current captain profile & status
        driverListener?.remove()
        driverListener = Firebase.firestore.collection("captains").document(uid)
            .addSnapshotListener { snapshot, error ->
                if (error != null) {
                    if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        Log.w("OrderViewModel", "Captain profile PERMISSION_DENIED; detaching listener")
                        driverListener?.remove()
                        driverListener = null
                    } else {
                        Log.w("OrderViewModel", "Error reading captain profile: ${error.message}")
                    }
                    return@addSnapshotListener
                }
                if (snapshot != null && snapshot.exists()) {
                    val name = snapshot.getAnyString("name", "driver_name")
                        ?: Firebase.auth.currentUser?.displayName
                        ?: "الكابتن"
                    val phone = snapshot.safeString("phone") ?: ""
                    val vehicle = snapshot.getAnyString("vehicleType", "vehicle") ?: "دراجة نارية"
                    val rating = snapshot.get("rating")?.toString() ?: "5.0"
                    val trips = (snapshot.get("totalTrips") as? Number)?.toInt() ?: 0
                    val isOnline = snapshot.getBoolean("isOnline") ?: true
                    val availability = snapshot.safeString("availabilityStatus") ?: "available"
                    val currentOrderId = snapshot.safeString("currentOrderId")
                    val approvalStatus = snapshot.safeString("status") ?: "approved"
                    val loc = snapshot.getGeoPoint("location")
                    val lat = loc?.latitude ?: (snapshot.get("latitude") as? Number)?.toDouble() ?: 30.0444
                    val lng = loc?.longitude ?: (snapshot.get("longitude") as? Number)?.toDouble() ?: 31.2357
                    val lastUpdate = snapshot.getTimestamp("lastLocationUpdate")
                    val photoUrl = snapshot.getAnyString("profileImageUrl", "photoUrl", "driverPhotoUrl")
                        ?: "https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=250&q=80"

                    _captainProfile.value = CaptainProfile(
                        uid = uid,
                        name = name,
                        phone = phone,
                        profileImageUrl = photoUrl,
                        role = "captain",
                        status = approvalStatus,
                        isOnline = isOnline,
                        availabilityStatus = availability,
                        currentOrderId = currentOrderId,
                        latitude = lat,
                        longitude = lng,
                        lastLocationUpdate = lastUpdate,
                        rating = rating,
                        totalTrips = trips,
                        vehicleType = vehicle
                    )

                    _driverProfile.value = DriverProfile(
                        name = name,
                        phone = phone,
                        vehicleType = vehicle,
                        rating = rating,
                        totalTrips = trips,
                        isOnline = isOnline,
                        photoUrl = photoUrl
                    )
                } else {
                    val defaultName = Firebase.auth.currentUser?.displayName
                        ?: Firebase.auth.currentUser?.email?.substringBefore("@")
                        ?: "الكابتن"
                    val initialCaptainMap = hashMapOf<String, Any?>(
                        "uid" to uid,
                        "name" to defaultName,
                        "driver_name" to defaultName,
                        "phone" to (Firebase.auth.currentUser?.phoneNumber ?: "01000000000"),
                        "vehicleType" to "دراجة نارية",
                        "rating" to 5.0,
                        "ratingCount" to 10L,
                        "totalTrips" to 0,
                        "isOnline" to true,
                        "availabilityStatus" to "available",
                        "status" to "approved",
                        "currentOrderId" to null,
                        "location" to GeoPoint(30.0444, 31.2357),
                        "latitude" to 30.0444,
                        "longitude" to 31.2357,
                        "lastLocationUpdate" to FieldValue.serverTimestamp(),
                        "profileImageUrl" to "https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=250&q=80"
                    )
                    Firebase.firestore.collection("captains").document(uid).set(initialCaptainMap)
                    _captainProfile.value = CaptainProfile(uid = uid, name = defaultName)
                    _driverProfile.value = DriverProfile(name = defaultName)
                }
            }

        // 2. Real-time stream for pending orders (status == "pending")
        _isLoading.value = true
        pendingListener?.remove()
        pendingListener = Firebase.firestore.collection("orders")
            .whereEqualTo("status", "pending")
            .addSnapshotListener { querySnapshot, error ->
                _isLoading.value = false
                if (error != null) {
                    if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        Log.w("OrderViewModel", "Pending orders PERMISSION_DENIED; detaching listener")
                        pendingListener?.remove()
                        pendingListener = null
                    } else {
                        Log.w("OrderViewModel", "Error fetching pending orders: ${error.message}")
                    }
                    _snackbarMessage.value = "تعذر جلب الطلبات المتاحة، يرجى التأكد من الاتصال"
                    return@addSnapshotListener
                }
                if (querySnapshot != null) {
                    val list = querySnapshot.documents.mapNotNull {
                        try {
                            mapDocToOrder(it)
                        } catch (e: Exception) {
                            Log.w("OrderViewModel", "Failed to map pending order ${it.id}: ${e.message}")
                            null
                        }
                    }
                    _pendingOrders.value = list
                }
            }

        // 3. Real-time stream for captain's assigned/completed orders
        captainOrdersListener?.remove()
        captainOrdersListener = Firebase.firestore.collection("orders")
            .addSnapshotListener { querySnapshot, error ->
                if (error != null) {
                    if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        Log.w("OrderViewModel", "Captain orders PERMISSION_DENIED; detaching listener")
                        captainOrdersListener?.remove()
                        captainOrdersListener = null
                    } else {
                        Log.w("OrderViewModel", "Error fetching captain orders: ${error.message}")
                    }
                    return@addSnapshotListener
                }
                if (querySnapshot != null) {
                    val activeList = mutableListOf<DeliveryOrderEntity>()
                    val completedList = mutableListOf<DeliveryOrderEntity>()
                    var totalEarnings = 0.0

                    querySnapshot.documents.forEach { doc ->
                        try {
                            val order = mapDocToOrder(doc)
                            val captId = doc.safeString("captainId")
                            val captName = doc.getAnyString("driver_name", "captainName")
                            val status = doc.safeString("status")?.lowercase(Locale.ROOT) ?: ""

                            val isMyOrder = (captId == uid) || (!captName.isNullOrBlank() && captName == _driverProfile.value.name)

                            if (isMyOrder) {
                                if (status in listOf("accepted", "in_progress", "out_for_delivery", "جاري التوصيل", "قيد التحضير", "الكابتن في الطريق لك", "في الطريق لك", "جاري البحث عن كابتن", "جاري البحث")) {
                                    activeList.add(order)
                                } else if (status in listOf("delivered", "completed", "تم التسليم", "مكتمل", "مكتملة", "تم التوصيل")) {
                                    completedList.add(order)
                                    val priceStr = doc.getAnyString("price", "totalAmount") ?: "0"
                                    val numericPrice = priceStr.replace(Regex("[^0-9.]"), "").toDoubleOrNull() ?: 0.0
                                    totalEarnings += numericPrice
                                }
                            }
                        } catch (e: Exception) {
                            Log.w("OrderViewModel", "Error processing captain order ${doc.id}: ${e.message}")
                        }
                    }
                    _captainActiveOrders.value = activeList
                    _captainCompletedOrders.value = completedList
                    _walletEarnings.value = totalEarnings
                }
            }
    }

    fun toggleCaptainOnlineStatus(isOnline: Boolean) {
        if (!NetworkHelper.isNetworkAvailable(getApplication())) {
            val noNetMsg = "لا يمكن تغيير الحالة: أنت غير متصل بالإنترنت!"
            _snackbarMessage.value = noNetMsg
            NetworkHelper.notifyNoInternet(getApplication(), "تغيير حالة اتصال الكابتن")
            return
        }
        val uid = Firebase.auth.currentUser?.uid ?: return
        viewModelScope.launch {
            try {
                val availability = if (isOnline) {
                    if (!_captainProfile.value.currentOrderId.isNullOrBlank()) "busy" else "available"
                } else {
                    "offline"
                }
                Firebase.firestore.collection("captains").document(uid).update(
                    mapOf(
                        "isOnline" to isOnline,
                        "availabilityStatus" to availability,
                        "lastLocationUpdate" to FieldValue.serverTimestamp()
                    )
                ).await()
                _captainProfile.value = _captainProfile.value.copy(
                    isOnline = isOnline,
                    availabilityStatus = availability
                )
                _driverProfile.value = _driverProfile.value.copy(isOnline = isOnline)
                _snackbarMessage.value = if (isOnline) "أنت الآن متصل ومستعد لاستقبال الطلبات 🟢" else "تم تغيير حالتك إلى غير متصل 🔴"
            } catch (e: Exception) {
                Log.e("OrderViewModel", "Error toggling online status: ${e.message}", e)
            }
        }
    }

    fun updateCaptainLocation(lat: Double, lng: Double) {
        if (!NetworkHelper.isNetworkAvailable(getApplication())) {
            val noNetMsg = "لا يمكن تحديث الموقع: أنت غير متصل بالإنترنت!"
            _snackbarMessage.value = noNetMsg
            NetworkHelper.notifyNoInternet(getApplication(), "تحديث موقع الكابتن")
            return
        }
        val uid = Firebase.auth.currentUser?.uid ?: return
        viewModelScope.launch {
            try {
                val geoPoint = GeoPoint(lat, lng)
                val updates = mapOf(
                    "location" to geoPoint,
                    "latitude" to lat,
                    "longitude" to lng,
                    "lastLocationUpdate" to FieldValue.serverTimestamp()
                )
                Firebase.firestore.collection("captains").document(uid).set(updates, com.google.firebase.firestore.SetOptions.merge()).await()
                _captainProfile.value = _captainProfile.value.copy(
                    latitude = lat,
                    longitude = lng,
                    lastLocationUpdate = Timestamp.now()
                )

                val activeOrderId = _captainProfile.value.currentOrderId
                if (!activeOrderId.isNullOrBlank()) {
                    val ordersSnap = Firebase.firestore.collection("orders").get().await()
                    val activeDoc = ordersSnap.documents.find { it.id == activeOrderId || it.safeString("orderId") == activeOrderId }
                    activeDoc?.reference?.update(
                        mapOf(
                            "captainLocation" to geoPoint,
                            "captainLatitude" to lat,
                            "captainLongitude" to lng
                        )
                    )
                }
                _snackbarMessage.value = "تم تحديث موقعك الجغرافي بنجاح 📍"
            } catch (e: Exception) {
                Log.e("OrderViewModel", "Error updating captain location: ${e.message}", e)
            }
        }
    }

    fun rejectCaptainOrder(orderDocId: String, onSuccess: () -> Unit = {}) {
        val uid = Firebase.auth.currentUser?.uid ?: return
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val collectionRef = Firebase.firestore.collection("orders")
                val querySnapshot = collectionRef.get().await()
                val matchedDoc = querySnapshot.documents.find { doc ->
                    doc.id == orderDocId || doc.safeString("orderId") == orderDocId || doc.id.hashCode() == orderDocId.toIntOrNull()
                }

                if (matchedDoc != null) {
                    matchedDoc.reference.update(
                        mapOf(
                            "captainId" to null,
                            "driver_name" to null,
                            "captainName" to null,
                            "status" to "pending",
                            "dispatchStatus" to "searching"
                        )
                    ).await()
                }

                Firebase.firestore.collection("captains").document(uid).update(
                    mapOf(
                        "currentOrderId" to null,
                        "availabilityStatus" to "available"
                    )
                ).await()

                if (matchedDoc != null) {
                    val realOrderId = matchedDoc.safeString("orderId") ?: matchedDoc.id
                    AutoDispatchManager.dispatchOrder(getApplication(), realOrderId)
                }

                _snackbarMessage.value = "تم الاعتذار عن الطلب وإعادة توجيهه لكابتن آخر بنجاح"
                onSuccess()
            } catch (e: Exception) {
                Log.e("OrderViewModel", "Error rejecting order: ${e.message}", e)
                _snackbarMessage.value = "حدث خطأ أثناء رفض الطلب: ${e.localizedMessage}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    private fun mapDocToOrder(doc: DocumentSnapshot): DeliveryOrderEntity {
        val rawStatus = doc.getAnyString("status", "orderStatus", "status_ar") ?: "pending"
        val mappedStatus = when (rawStatus.trim().lowercase(Locale.ROOT)) {
            "pending", "قيد التحضير", "preparing", "جاري البحث عن كابتن", "في انتظار كابتن", "جاري البحث" -> "جاري البحث"
            "accepted", "الكابتن في الطريق لك", "في الطريق إلى المطعم", "في الطريق لك" -> "في الطريق لك"
            "captain_reached_store", "reached_store", "وصل المتجر" -> "وصل المتجر"
            "order_picked_up", "picked_up", "تم الاستلام" -> "تم الاستلام"
            "waiting_merchant_confirmation", "waiting_confirmation", "بانتظار تأكيد البيانات" -> "بانتظار تأكيد البيانات"
            "customer_confirmed", "تم تأكيد البيانات" -> "جاري التوصيل"
            "customer_rejected", "بيانات مرفوضة" -> "بيانات مرفوضة"
            "in_progress", "out_for_delivery", "جاري التوصيل", "delivering", "في الطريق إلى العميل" -> "جاري التوصيل"
            "completed", "delivered", "تم التسليم", "مكتمل", "مكتملة", "تم التوصيل" -> "تم التسليم"
            "cancelled", "canceled", "ملغي", "ملغية", "تم الإلغاء", "تم الالغاء" -> "ملغي"
            else -> rawStatus
        }

        val captainId = doc.safeString("captainId")
        val rawDriverName = doc.getAnyString("driver_name", "captainName", "driverName")
        val captName = if (!rawDriverName.isNullOrBlank()) rawDriverName else if (!captainId.isNullOrBlank()) "الكابتن" else "في انتظار كابتن"

        val rawAddress = doc.getAnyString("address", "deliveryAddress", "destinationAddress") ?: ""

        val rawPrice = doc.getAnyString("price", "totalAmount", "serviceFee") ?: "30 جنيه"

        val formattedDate = (doc.getTimestamp("created_at") ?: doc.getTimestamp("createdAt"))?.let {
            SimpleDateFormat("dd MMMM yyyy - hh:mm a", Locale("ar")).format(it.toDate())
        } ?: doc.getAnyString("orderDate") ?: "الآن"

        val restaurant = doc.getAnyString("restaurant name", "restaurantName", "restaurant") ?: "مطعم"

        val itemsText = doc.getAnyString("items", "orderDetails", "details", "order_details") ?: ""

        val custName = doc.getAnyString("customerName", "customer_name", "clientName") ?: restaurant
        val custPhone = doc.getAnyString("customerPhone", "customer_phone", "clientPhone", "captainPhone", "phone") ?: ""
        val notes = doc.getAnyString("deliveryNotes", "notes", "customerNotes") ?: ""

        val customerLoc = doc.getGeoPoint("customerLocation")
        val custLat = customerLoc?.latitude ?: (doc.get("customerLatitude") as? Number)?.toDouble()
        val custLng = customerLoc?.longitude ?: (doc.get("customerLongitude") as? Number)?.toDouble()

        val docRating = (doc.get("rating") as? Number)?.toInt() ?: 0
        val captPhone = doc.getAnyString("captainPhone", "captain_phone", "driverPhone") ?: ""
        val captPhoto = doc.getAnyString("captainPhotoUrl", "captain_photo", "driverPhoto")
            ?: "https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=250&q=80"
        val captEta = doc.getAnyString("estimatedArrival", "captainEta", "eta")
            ?: (if (captName != "في انتظار كابتن" && captName != "في انتظار قبول كابتن") "10 - 15 دقيقة" else "")

        val receiptUrl = doc.getAnyString("receiptImageUrl", "receiptUrl", "customerReceiptUrl") ?: ""
        val custSubmitted = doc.getBoolean("customerDetailsSubmitted") ?: (receiptUrl.isNotBlank() || mappedStatus == "بانتظار تأكيد البيانات")
        val custConfirmed = doc.getBoolean("customerDetailsConfirmed") ?: (mappedStatus == "جاري التوصيل" && custSubmitted)
        val custRejected = doc.getBoolean("customerDetailsRejected") ?: (mappedStatus == "بيانات مرفوضة")
        val rejReason = doc.getAnyString("rejectionReason", "rejectReason") ?: ""
        val reachedStore = doc.getBoolean("captainReachedStore") ?: (mappedStatus == "وصل المتجر")
        val pickedUp = doc.getBoolean("orderPickedUp") ?: (mappedStatus in listOf("تم الاستلام", "بانتظار تأكيد البيانات", "جاري التوصيل", "تم التسليم"))

        val realFirestoreId = doc.safeString("orderId") ?: doc.id
        val orderCode = doc.getAnyString("orderCode", "code") ?: ""

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

    fun acceptOrderInFirestore(orderDocId: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val uid = Firebase.auth.currentUser?.uid ?: return
        val currentCaptainName = _driverProfile.value.name.ifBlank {
            Firebase.auth.currentUser?.displayName
                ?: Firebase.auth.currentUser?.email?.substringBefore("@")
                ?: "الكابتن أحمد محمود"
        }
        val currentCaptainPhone = _driverProfile.value.phone.ifBlank {
            Firebase.auth.currentUser?.phoneNumber ?: "01098765432"
        }
        val currentCaptainPhoto = _driverProfile.value.photoUrl.ifBlank {
            "https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=250&q=80"
        }
        val estimatedArrivalDuration = "12 - 15 دقيقة"

        viewModelScope.launch {
            try {
                _isLoading.value = true
                val updates = hashMapOf<String, Any?>(
                    "status" to "accepted",
                    "driver_name" to currentCaptainName,
                    "captainName" to currentCaptainName,
                    "captainPhone" to currentCaptainPhone,
                    "captainPhotoUrl" to currentCaptainPhoto,
                    "captainId" to uid,
                    "estimatedArrival" to estimatedArrivalDuration
                )

                val collectionRef = Firebase.firestore.collection("orders")
                val querySnapshot = collectionRef.get().await()

                var matchedDoc = querySnapshot.documents.find { doc ->
                    doc.id == orderDocId || doc.safeString("orderId") == orderDocId || doc.id.hashCode() == orderDocId.toIntOrNull()
                }

                if (matchedDoc == null && querySnapshot.documents.isNotEmpty()) {
                    matchedDoc = querySnapshot.documents.firstOrNull { doc -> doc.safeString("status") == "pending" }
                }

                if (matchedDoc != null) {
                    matchedDoc.reference.update(updates).await()

                    // Also update local Room database if order matches
                    try {
                        val orders = repository.allOrders.firstOrNull() ?: emptyList()
                        val localMatch = orders.find { it.id.toString() == orderDocId || it.id == orderDocId.toIntOrNull() || it.id == matchedDoc?.id.hashCode() }
                        if (localMatch != null) {
                            repository.updateOrder(
                                localMatch.copy(
                                    status = "في الطريق لك",
                                    captainName = currentCaptainName,
                                    captainPhone = currentCaptainPhone,
                                    captainPhotoUrl = currentCaptainPhoto,
                                    estimatedArrival = estimatedArrivalDuration
                                )
                            )
                        }
                    } catch (e: Exception) {
                        Log.w("OrderViewModel", "Failed to update local room on accept: ${e.message}")
                    }

                    _snackbarMessage.value = "تم قبول الطلب بنجاح! أنت الآن في الطريق للمطعم."
                    onSuccess()
                } else {
                    _snackbarMessage.value = "تعذر العثور على الطلب المحدد"
                    onError("الطلب غير موجود")
                }
            } catch (e: Exception) {
                Log.e("OrderViewModel", "Error accepting order in Firestore", e)
                _snackbarMessage.value = "حدث خطأ أثناء قبول الطلب: ${e.localizedMessage}"
                onError(e.localizedMessage ?: "خطأ بالاتصال")
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun completeOrderInFirestore(orderDocId: String, onSuccess: () -> Unit, onError: (String) -> Unit) {
        val uid = Firebase.auth.currentUser?.uid
        viewModelScope.launch {
            try {
                _isLoading.value = true
                val updates = hashMapOf<String, Any?>(
                    "status" to "delivered",
                    "dispatchStatus" to "completed"
                )

                val collectionRef = Firebase.firestore.collection("orders")
                val querySnapshot = collectionRef.get().await()

                val matchedDoc = querySnapshot.documents.find { doc ->
                    doc.id == orderDocId || doc.safeString("orderId") == orderDocId || doc.id.hashCode() == orderDocId.toIntOrNull()
                }

                if (matchedDoc != null) {
                    matchedDoc.reference.update(updates).await()
                    try {
                        val orders = repository.allOrders.firstOrNull() ?: emptyList()
                        val localMatch = orders.find { it.id.toString() == orderDocId || it.id == orderDocId.toIntOrNull() || it.id == matchedDoc?.id.hashCode() }
                        if (localMatch != null) {
                            repository.updateOrderStatus(localMatch.id, "تم التسليم")
                        }
                    } catch (e: Exception) {
                        Log.w("OrderViewModel", "Failed to update local room on complete: ${e.message}")
                    }

                    // Free the captain and increment trips
                    if (uid != null) {
                        try {
                            Firebase.firestore.collection("captains").document(uid).update(
                                mapOf(
                                    "currentOrderId" to null,
                                    "availabilityStatus" to "available",
                                    "totalTrips" to FieldValue.increment(1)
                                )
                            ).await()
                        } catch (e: Exception) {
                            Log.w("OrderViewModel", "Failed to update captain availability on complete: ${e.message}")
                        }
                    }

                    _snackbarMessage.value = "تم إكمال وتسليم الطلب بنجاح! تم إضافة الأرباح للمحفظة."
                    onSuccess()
                } else {
                    _snackbarMessage.value = "تعذر العثور على الطلب لإنهائه"
                    onError("الطلب غير موجود")
                }
            } catch (e: Exception) {
                Log.e("OrderViewModel", "Error completing order", e)
                _snackbarMessage.value = "خطأ أثناء إكمال الطلب: ${e.localizedMessage}"
                onError(e.localizedMessage ?: "خطأ بالاتصال")
            } finally {
                _isLoading.value = false
            }
        }
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
        viewModelScope.launch {
            if (!NetworkHelper.isNetworkAvailable(getApplication())) {
                val noNetMsg = "لا يمكن إجراء طلب كابتن: أنت غير متصل بالإنترنت!"
                _snackbarMessage.value = noNetMsg
                NetworkHelper.notifyNoInternet(getApplication(), "إجراء طلب كابتن جديد")
                onError?.invoke(noNetMsg)
                return@launch
            }

            val currentUser = Firebase.auth.currentUser
            val email = currentUser?.email.orEmpty().trim().lowercase()
            val isSysAdmin = email == "moamen.elsayed.abdelmoamen@gmail.com" || email == "admin@elbatalexpress.com"

            // Local cache subscription check (strictly for verified expired state, skipping admins)
            if (!isSysAdmin && _subscriptionStatus.value.isExpired && _subscriptionStatus.value.status == "expired") {
                val subMsg = "لا يمكن إنشاء الطلب: باقة اشتراك المطعم منتهية. يرجى تجديد الاشتراك."
                _snackbarMessage.value = subMsg
                onError?.invoke(subMsg)
                return@launch
            }

            // Check concurrent captain capacity limit (skipping admins)
            val allowedCaptains = _subscriptionStatus.value.maxCaptains
            val activeCaptains = _subscriptionStatus.value.activeCaptainsCount
            if (!isSysAdmin && activeCaptains >= allowedCaptains && allowedCaptains > 0) {
                val limitMsg = "لقد وصلت للحد الأقصى لعدد الكباتن المتزامنين المسموح بهم في باقتك ($allowedCaptains كباتن). يرجى انتظار انتهاء أحد الطلبات الجارية أو ترقية الباقة."
                _snackbarMessage.value = limitMsg
                onError?.invoke(limitMsg)
                return@launch
            }

            val sdf = SimpleDateFormat("dd MMMM yyyy - hh:mm a", Locale("ar"))
            val currentDate = sdf.format(Date())
            val formattedFee = if (serviceFee.isBlank()) "30 جنيه" else if (serviceFee.contains("جنيه")) serviceFee else "$serviceFee جنيه"
            val uid = currentUser?.uid ?: ""
            val orderId = UUID.randomUUID().toString()
            val idempotencyKey = UUID.randomUUID().toString()
            val currentRestaurantName = _restaurantProfile.value.name.ifBlank {
                currentUser?.displayName
                    ?: currentUser?.email?.substringBefore("@")
                    ?: "مطعم"
            }

            val restaurantLoc = _restaurantProfile.value.location ?: GeoPoint(30.0444, 31.2357)
            val orderMap = hashMapOf<String, Any?>(
                "orderId" to orderId,
                "address" to destinationAddress,
                "created_at" to FieldValue.serverTimestamp(),
                "driver_name" to null,
                "captainName" to null,
                "captainPhone" to null,
                "captainPhotoUrl" to null,
                "estimatedArrival" to null,
                "price" to formattedFee,
                "restaurant name" to currentRestaurantName,
                "status" to "pending",
                "dispatchStatus" to "searching",
                "restaurantName" to currentRestaurantName,
                "restaurantId" to uid,
                "merchantId" to uid,
                "items" to orderDetails,
                "totalAmount" to formattedFee,
                "createdAt" to FieldValue.serverTimestamp(),
                "deliveryAddress" to destinationAddress,
                "captainId" to null,
                "userId" to uid,
                "customerName" to customerName,
                "customerPhone" to customerPhone,
                "deliveryNotes" to deliveryNotes,
                "notes" to deliveryNotes,
                "orderDate" to currentDate,
                "serviceFee" to formattedFee,
                "orderDetails" to orderDetails,
                "destinationAddress" to destinationAddress,
                "restaurantLocation" to restaurantLoc,
                "pickupLocation" to restaurantLoc,
                "restaurantLatitude" to restaurantLoc.latitude,
                "restaurantLongitude" to restaurantLoc.longitude,
                "customerDetailsSubmitted" to false,
                "customerDetailsConfirmed" to false,
                "customerDetailsRejected" to false
            )

            if (customerLatitude != null && customerLongitude != null) {
                val customerGeo = GeoPoint(customerLatitude, customerLongitude)
                orderMap["customerLocation"] = customerGeo
                orderMap["customerLatitude"] = customerLatitude
                orderMap["customerLongitude"] = customerLongitude
            }

            var allocatedCode = ""
            val firestore = Firebase.firestore

            if (uid.isNotEmpty()) {
                try {
                    firestore.runTransaction { transaction ->
                        // 1. Subscription verification (admins always pass)
                        if (!isSysAdmin) {
                            val subRef = firestore.collection("subscriptions").document(uid)
                            val subDoc = transaction.get(subRef)
                            if (subDoc.exists()) {
                                val subStatus = subDoc.safeString("status")
                                    ?: subDoc.safeString("subscriptionStatus")
                                    ?: "active"
                                val endDate = subDoc.getTimestamp("endDate")
                                    ?: subDoc.getTimestamp("subscriptionEndDate")
                                if (subStatus in listOf("expired", "suspended", "cancelled")) {
                                    throw IllegalStateException("باقة الاشتراك غير نشطة ($subStatus). يرجى تجديد الاشتراك.")
                                }
                                if (endDate != null && endDate.toDate().before(Date())) {
                                    throw IllegalStateException("باقة الاشتراك منتهية الصلاحية. يرجى تجديد الاشتراك.")
                                }
                            } else {
                                // Fallback to restaurants document
                                val restRef = firestore.collection("restaurants").document(uid)
                                val restDoc = transaction.get(restRef)
                                if (restDoc.exists()) {
                                    val restSubStatus = restDoc.safeString("subscriptionStatus")
                                        ?: restDoc.safeString("status")
                                        ?: "active"
                                    val restEndDate = restDoc.getTimestamp("subscriptionEndDate")
                                        ?: restDoc.getTimestamp("endDate")
                                    if (restSubStatus in listOf("expired", "suspended", "cancelled")) {
                                        throw IllegalStateException("اشتراك المطعم غير نشط ($restSubStatus).")
                                    }
                                    if (restEndDate != null && restEndDate.toDate().before(Date())) {
                                        throw IllegalStateException("باقة اشتراك المطعم منتهية الصلاحية.")
                                    }
                                }
                            }
                        }

                        // 2. Uniqueness generation: Exactly 6 digits, non-zero start, distinct digits
                        var candidateCode = ""
                        for (attempt in 1..15) {
                            val candidate = generateDistinct6DigitCode()
                            val codeRef = firestore.collection("order_codes").document(candidate)
                            val codeDoc = transaction.get(codeRef)
                            if (!codeDoc.exists()) {
                                candidateCode = candidate
                                break
                            }
                        }

                        if (candidateCode.isEmpty()) {
                            candidateCode = generateDistinct6DigitCode()
                        }

                        allocatedCode = candidateCode

                        // 3. Atomically reserve order code in order_codes/{code}
                        val codeDocRef = firestore.collection("order_codes").document(candidateCode)
                        val codeReservation = hashMapOf(
                            "code" to candidateCode,
                            "orderCode" to candidateCode,
                            "orderId" to orderId,
                            "merchantId" to uid,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                        transaction.set(codeDocRef, codeReservation)

                        // 4. Atomically store idempotency key
                        val idempotencyRef = firestore.collection("idempotency_keys").document(idempotencyKey)
                        val idempotencyData = hashMapOf(
                            "idempotencyKey" to idempotencyKey,
                            "orderId" to orderId,
                            "orderCode" to candidateCode,
                            "merchantId" to uid,
                            "createdAt" to FieldValue.serverTimestamp()
                        )
                        transaction.set(idempotencyRef, idempotencyData)

                        // 5. Store order with orderCode in orders/{orderId}
                        orderMap["orderCode"] = candidateCode
                        orderMap["code"] = candidateCode
                        val orderDocRef = firestore.collection("orders").document(orderId)
                        transaction.set(orderDocRef, orderMap)
                    }.await()
                } catch (e: Exception) {
                    Log.w("OrderViewModel", "Transaction failed, attempting direct order creation fallback: ${e.message}", e)
                    try {
                        val fallbackCode = if (allocatedCode.isNotBlank()) allocatedCode else generateDistinct6DigitCode()
                        allocatedCode = fallbackCode
                        orderMap["orderCode"] = fallbackCode
                        orderMap["code"] = fallbackCode
                        firestore.collection("orders").document(orderId).set(orderMap).await()

                        // Best-effort code reservation
                        try {
                            firestore.collection("order_codes").document(fallbackCode).set(
                                hashMapOf(
                                    "code" to fallbackCode,
                                    "orderCode" to fallbackCode,
                                    "orderId" to orderId,
                                    "merchantId" to uid,
                                    "createdAt" to FieldValue.serverTimestamp()
                                ),
                                SetOptions.merge()
                            )
                        } catch (_: Exception) {}
                    } catch (fallbackError: Exception) {
                        Log.e("OrderViewModel", "Fatal order creation error: ${fallbackError.message}", fallbackError)
                        val errorMsg = fallbackError.localizedMessage ?: "حدث خطأ أثناء إجراء طلب الكابتن"
                        _snackbarMessage.value = errorMsg
                        onError?.invoke(errorMsg)
                        return@launch
                    }
                }
            } else {
                allocatedCode = generateDistinct6DigitCode()
            }

            // Save to local Room database
            val newOrder = DeliveryOrderEntity(
                firestoreOrderId = orderId,
                orderCode = allocatedCode,
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
                captainPhotoUrl = "https://images.unsplash.com/photo-1535713875002-d1d0cf377fde?auto=format&fit=crop&w=250&q=80"
            )
            repository.insertOrder(newOrder)

            _latestCreatedOrderCode.value = allocatedCode

            // Trigger intelligent Auto Dispatch
            try {
                AutoDispatchManager.dispatchOrder(getApplication(), orderId)
            } catch (e: Exception) {
                Log.w("OrderViewModel", "AutoDispatch invocation: ${e.message}")
            }

            onSuccess(allocatedCode)
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

    fun advanceOrderStatus(orderId: Int, currentStatus: String) {
        val nextStatus = when (currentStatus.trim()) {
            "جاري البحث", "جاري البحث عن كابتن", "في انتظار كابتن", "قيد التحضير", "Preparing", "pending" -> "في الطريق لك"
            "في الطريق لك", "الكابتن في الطريق لك", "accepted", "في الطريق إلى المطعم" -> "جاري التوصيل"
            "جاري التوصيل", "قيد التوصيل", "Out for Delivery", "delivering" -> "تم التسليم"
            else -> "تم التسليم"
        }

        viewModelScope.launch {
            val orders = repository.allOrders.firstOrNull() ?: emptyList()
            val targetOrder = orders.find { it.id == orderId }

            if ((nextStatus == "في الطريق لك" || nextStatus == "جاري التوصيل") && targetOrder != null && (targetOrder.captainName.isBlank() || targetOrder.captainName == "في انتظار كابتن" || targetOrder.captainName == "في انتظار قبول كابتن")) {
                val assignedCaptainName = _driverProfile.value.name.ifBlank { "الكابتن أحمد محمود" }
                val assignedCaptainPhone = _driverProfile.value.phone.ifBlank { "01098765432" }
                val assignedCaptainPhoto = _driverProfile.value.photoUrl.ifBlank {
                    "https://images.unsplash.com/photo-1534528741775-53994a69daeb?auto=format&fit=crop&w=250&q=80"
                }
                val assignedEta = "12 - 15 دقيقة"

                repository.updateOrder(
                    targetOrder.copy(
                        status = nextStatus,
                        captainName = assignedCaptainName,
                        captainPhone = assignedCaptainPhone,
                        captainPhotoUrl = assignedCaptainPhoto,
                        estimatedArrival = assignedEta
                    )
                )
            } else {
                repository.updateOrderStatus(orderId, nextStatus)
            }

            // Sync with Firestore
            try {
                val firestoreStatus = when (nextStatus) {
                    "في الطريق لك" -> "accepted"
                    "جاري التوصيل" -> "in_progress"
                    "تم التسليم" -> "delivered"
                    else -> nextStatus
                }
                val collectionRef = Firebase.firestore.collection("orders")
                val querySnapshot = collectionRef.get().await()
                val matchedDoc = querySnapshot.documents.find { doc ->
                    doc.id == orderId.toString() || doc.safeString("orderId") == orderId.toString() || doc.id.hashCode() == orderId
                }
                matchedDoc?.reference?.update(
                    mapOf(
                        "status" to firestoreStatus,
                        "driver_name" to (_driverProfile.value.name.ifBlank { "الكابتن أحمد محمود" }),
                        "captainName" to (_driverProfile.value.name.ifBlank { "الكابتن أحمد محمود" }),
                        "captainPhone" to (_driverProfile.value.phone.ifBlank { "01098765432" })
                    )
                )?.await()
            } catch (e: Exception) {
                Log.w("OrderViewModel", "Could not sync advance status to Firestore: ${e.message}")
            }

            // Trigger simulated push notification if transitioning from 'Preparing' to 'Out for Delivery'
            if (nextStatus == "جاري التوصيل") {
                val destination = targetOrder?.destinationAddress?.ifBlank { "العنوان المتردد" } ?: "العنوان المحدد"

                val title = getApplication<Application>().getString(R.string.notification_out_for_delivery_title)
                val body = getApplication<Application>().getString(R.string.notification_out_for_delivery_body, destination)
                val timeSdf = SimpleDateFormat("hh:mm a", Locale("ar"))
                val timeStr = timeSdf.format(Date())

                // 1. Post actual System Notification
                try {
                    NotificationHelper.showOutForDeliveryNotification(getApplication(), destination)
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // 2. Emit simulated push notification banner state
                _pushNotificationState.value = PushNotificationBanner(
                    title = title,
                    body = body,
                    timestamp = timeStr
                )
            }
        }
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
                val uid = Firebase.auth.currentUser?.uid ?: ""
                val collectionRef = Firebase.firestore.collection("orders")
                val querySnapshot = collectionRef.get().await()

                val matchedDoc = querySnapshot.documents.find { doc ->
                    doc.id == orderId.toString() ||
                    doc.safeString("orderId") == orderId.toString() ||
                    doc.id.hashCode() == orderId.toString().toIntOrNull() ||
                    doc.safeString("orderId")?.hashCode() == orderId.toString().toIntOrNull()
                }

                if (matchedDoc != null) {
                    val updates = hashMapOf<String, Any?>(
                        "customerDetailsConfirmed" to true,
                        "customerDetailsRejected" to false,
                        "confirmedAt" to FieldValue.serverTimestamp(),
                        "status" to "customer_confirmed",
                        "status_ar" to "جاري التوصيل"
                    )
                    matchedDoc.reference.update(updates).await()

                    // Also update local Room database
                    try {
                        val intId = orderId.toString().toIntOrNull() ?: matchedDoc.id.hashCode()
                        repository.updateOrderStatus(intId, "جاري التوصيل")
                    } catch (e: Exception) {
                        Log.w("OrderViewModel", "Failed to update local status: ${e.message}")
                    }

                    _snackbarMessage.value = "تم تأكيد بيانات العميل والإيصال بنجاح ✓ الكابتن سيواصل التوصيل الآن."
                    onSuccess()
                } else {
                    val notFound = "الطلب غير موجود في قاعدة البيانات"
                    _snackbarMessage.value = notFound
                    onError(notFound)
                }
            } catch (e: Exception) {
                Log.e("OrderViewModel", "Error confirming customer details: ${e.message}", e)
                val err = "حدث خطأ أثناء تأكيد البيانات: ${e.localizedMessage}"
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
                val collectionRef = Firebase.firestore.collection("orders")
                val querySnapshot = collectionRef.get().await()

                val matchedDoc = querySnapshot.documents.find { doc ->
                    doc.id == orderId.toString() ||
                    doc.safeString("orderId") == orderId.toString() ||
                    doc.id.hashCode() == orderId.toString().toIntOrNull() ||
                    doc.safeString("orderId")?.hashCode() == orderId.toString().toIntOrNull()
                }

                if (matchedDoc != null) {
                    val updates = hashMapOf<String, Any?>(
                        "customerDetailsRejected" to true,
                        "customerDetailsConfirmed" to false,
                        "rejectionReason" to reason.trim(),
                        "rejectedAt" to FieldValue.serverTimestamp(),
                        "status" to "customer_rejected",
                        "status_ar" to "بيانات مرفوضة"
                    )
                    matchedDoc.reference.update(updates).await()

                    try {
                        val intId = orderId.toString().toIntOrNull() ?: matchedDoc.id.hashCode()
                        repository.updateOrderStatus(intId, "بيانات مرفوضة")
                    } catch (e: Exception) {
                        Log.w("OrderViewModel", "Failed to update local status: ${e.message}")
                    }

                    _snackbarMessage.value = "تم رفض بيانات العميل مع إرسال السبب للكابتن لإعادة الرفع."
                    onSuccess()
                } else {
                    val notFound = "الطلب غير موجود في قاعدة البيانات"
                    _snackbarMessage.value = notFound
                    onError(notFound)
                }
            } catch (e: Exception) {
                Log.e("OrderViewModel", "Error rejecting customer details: ${e.message}", e)
                val err = "حدث خطأ أثناء رفض البيانات: ${e.localizedMessage}"
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
                val intId = orderId.toString().toIntOrNull() ?: 0
                if (intId != 0) {
                    repository.updateOrderStatus(intId, "ملغي")
                }
                val uid = Firebase.auth.currentUser?.uid ?: ""
                if (uid.isNotEmpty()) {
                    val snapshot = Firebase.firestore.collection("orders")
                        .whereEqualTo("userId", uid)
                        .get().await()
                    for (doc in snapshot.documents) {
                        val idHash = doc.safeString("orderId")?.hashCode() ?: doc.id.hashCode()
                        if (idHash == intId || doc.id == orderId.toString() || doc.safeString("orderId") == orderId.toString()) {
                            doc.reference.update(
                                mapOf(
                                    "status" to "cancelled",
                                    "status_ar" to "ملغي",
                                    "cancelReason" to reason,
                                    "cancelledAt" to FieldValue.serverTimestamp()
                                )
                            ).await()
                            break
                        }
                    }
                }
                _snackbarMessage.value = "تم إلغاء الطلب بنجاح"
                onSuccess()
            } catch (e: Exception) {
                Log.e("OrderViewModel", "Error cancelling order in Firestore", e)
                _snackbarMessage.value = "تعذر إلغاء الطلب: ${e.localizedMessage}"
                onError?.invoke(e.localizedMessage ?: "فشل الإلغاء")
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

            // 2. Update Firestore Order document & Captain/Driver rating
            try {
                val ordersSnapshot = Firebase.firestore.collection("orders").get().await()
                var matchedOrderDoc: DocumentSnapshot? = null

                for (doc in ordersSnapshot.documents) {
                    val idHash = doc.safeString("orderId")?.hashCode() ?: doc.id.hashCode()
                    if (idHash == orderId || doc.id == orderId.toString()) {
                        matchedOrderDoc = doc
                        break
                    }
                }

                if (matchedOrderDoc != null) {
                    val captId = matchedOrderDoc.safeString("captainId")
                    val captName = matchedOrderDoc.getAnyString("driver_name", "captainName")

                    // Update order rating in Firestore
                    matchedOrderDoc.reference.update(
                        mapOf(
                            "rating" to rating,
                            "ratedAt" to FieldValue.serverTimestamp()
                        )
                    ).await()

                    // If order has a linked captain, update captain's average rating in 'drivers' collection
                    if (!captId.isNullOrBlank()) {
                        try {
                            val driverRef = Firebase.firestore.collection("drivers").document(captId)
                            val driverDoc = driverRef.get().await()
                            if (driverDoc.exists()) {
                                val currentRating = (driverDoc.get("rating") as? Number)?.toDouble() ?: 5.0
                                val totalRatingsCount = (driverDoc.get("ratingCount") as? Number)?.toInt() ?: 1
                                val newCount = totalRatingsCount + 1
                                val newAverage = ((currentRating * totalRatingsCount) + rating) / newCount
                                val roundedRating = String.format(Locale.US, "%.1f", newAverage).toDoubleOrNull() ?: newAverage

                                driverRef.update(
                                    mapOf(
                                        "rating" to roundedRating,
                                        "ratingCount" to newCount
                                    )
                                ).await()
                            }
                        } catch (driverEx: Exception) {
                            Log.e("OrderViewModel", "Error updating driver rating profile in Firestore", driverEx)
                        }
                    }
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
            repository.updateOrderDetails(
                orderId = orderId,
                destinationAddress = destinationAddress,
                orderDetails = orderDetails,
                customerPhone = customerPhone,
                customerName = customerName,
                serviceFee = serviceFee
            )
            try {
                val snapshot = Firebase.firestore.collection("orders").get().await()
                for (doc in snapshot.documents) {
                    val idHash = doc.safeString("orderId")?.hashCode() ?: doc.id.hashCode()
                    if (idHash == orderId) {
                        doc.reference.update(
                            mapOf(
                                "address" to destinationAddress,
                                "deliveryAddress" to destinationAddress,
                                "destinationAddress" to destinationAddress,
                                "items" to orderDetails,
                                "orderDetails" to orderDetails,
                                "price" to serviceFee,
                                "totalAmount" to serviceFee,
                                "serviceFee" to serviceFee
                            )
                        ).await()
                        break
                    }
                }
            } catch (e: Exception) {
                Log.e("OrderViewModel", "Error updating order details in Firestore", e)
            }
        }
    }

    fun saveRestaurantProfile(
        name: String,
        phone: String,
        address: String,
        imageUri: String?,
        latitude: Double? = null,
        longitude: Double? = null
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

        if (currentUser == null) {
            val err = "يرجى تسجيل الدخول أولاً لحفظ البيانات (UID is null)"
            Log.e("FIREBASE_DEBUG", "AUTH_ERROR: $err")
            _errorMessage.value = err
            return
        }

        val uid = currentUser.uid
        val finalName = name.ifBlank { getApplication<Application>().getString(R.string.restaurant_name_default) }

        _isSaving.value = true
        _errorMessage.value = null
        _isSaveSuccess.value = false

        viewModelScope.launch(Dispatchers.IO) {
            try {
                withTimeout(20000L) {
                    Log.d("FIREBASE_DEBUG", "START_SAVING: UID=$uid, Name='$finalName', Phone='$phone', Address='$address', RawImageUri='$imageUri'")

                    val restDocRef = Firebase.firestore.collection("restaurants").document(uid)
                    val merchantDocRef = Firebase.firestore.collection("merchants").document(uid)
                    val userDocRef = Firebase.firestore.collection("users").document(uid)

                    val existingDoc = restDocRef.get().await()
                    val existingRemoteLogoUrl = existingDoc?.safeString("photoUrl")
                        ?: existingDoc?.safeString("logoUrl")
                        ?: _restaurantProfile.value.photoUrl
                        ?: _restaurantProfile.value.logoUrl
                        ?: ""
                    val existingStoragePath = existingDoc?.safeString("storagePath")
                        ?: _restaurantProfile.value.storagePath

                    // 1. معالجة ورفع الصورة الجديدة حصرياً إلى Firebase Storage مع حماية الصورة السابقة عند الفشل
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

                    Log.d("FIREBASE_DEBUG", "FIRESTORE_WRITE_STARTED | Target Project: $projectId | UID: $uid | finalLogoUrl=$finalLogoUrl | storagePath=$finalStoragePath")

                    val targetLocation = if (latitude != null && longitude != null) {
                        GeoPoint(latitude, longitude)
                    } else {
                        _restaurantProfile.value.location
                    }

                    if (existingDoc == null || !existingDoc.exists()) {
                        // المستند غير موجود بعد: إنشاؤه بالكامل مع الحقول الأساسية المطابقة للقواعد
                        val initialData = hashMapOf<String, Any>(
                            "uid" to uid,
                            "ownerId" to uid,
                            "name" to finalName,
                            "phone" to phone.trim(),
                            "email" to (currentUser.email ?: ""),
                            "address" to address.trim(),
                            "category" to "مطعم",
                            "logoUrl" to finalLogoUrl,
                            "photoUrl" to finalLogoUrl,
                            "storagePath" to finalStoragePath,
                            "status" to "active",
                            "rating" to 5.0,
                            "ratingCount" to 0L,
                            "subscriptionStatus" to "active",
                            "subscriptionId" to "",
                            "createdAt" to FieldValue.serverTimestamp(),
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                        if (targetLocation != null) {
                            initialData["location"] = targetLocation
                            initialData["latitude"] = targetLocation.latitude
                            initialData["longitude"] = targetLocation.longitude
                        }
                        restDocRef.set(initialData).await()
                        Log.d("FIREBASE_DEBUG", "RESTAURANTS_DOC_CREATED: restaurants/$uid")
                    } else {
                        // المستند موجود بالفعل: تحديث الحقول المسموح بها فقط دون لمس الحقول الإدارية المحمية
                        val updateMap = mutableMapOf<String, Any>(
                            "name" to finalName,
                            "phone" to phone.trim(),
                            "email" to (currentUser.email ?: ""),
                            "address" to address.trim(),
                            "category" to "مطعم",
                            "logoUrl" to finalLogoUrl,
                            "photoUrl" to finalLogoUrl,
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
                        // تنظيف الحقول القديمة المكررة فقط إذا كانت موجودة في المستند
                        if (existingDoc.contains("restaurantName")) updateMap["restaurantName"] = FieldValue.delete()
                        if (existingDoc.contains("image")) updateMap["image"] = FieldValue.delete()
                        if (existingDoc.contains("imageUri")) updateMap["imageUri"] = FieldValue.delete()
                        if (existingDoc.contains("imageUrl")) updateMap["imageUrl"] = FieldValue.delete()

                        restDocRef.update(updateMap).await()
                        Log.d("FIREBASE_DEBUG", "RESTAURANTS_DOC_UPDATED: restaurants/$uid")
                    }

                    // 3. مزامنة وحفظ مستند التاجر merchants/$uid لضمان بقاء البيانات عند تسجيل الدخول من أي جهاز
                    try {
                        val merchantMap = hashMapOf<String, Any>(
                            "uid" to uid,
                            "name" to finalName,
                            "phone" to phone.trim(),
                            "email" to (currentUser.email ?: ""),
                            "address" to address.trim(),
                            "role" to "merchant",
                            "status" to "approved",
                            "isApproved" to true,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                        if (finalLogoUrl.isNotBlank()) {
                            merchantMap["photoUrl"] = finalLogoUrl
                        }
                        if (finalStoragePath.isNotBlank()) {
                            merchantMap["storagePath"] = finalStoragePath
                        }
                        merchantDocRef.set(merchantMap, SetOptions.merge()).await()
                        Log.d("FIREBASE_DEBUG", "MERCHANTS_DOC_SYNCED: merchants/$uid")
                    } catch (mEx: Exception) {
                        Log.w("FIREBASE_DEBUG", "merchants/$uid sync warning: ${mEx.message}")
                    }

                    // مزامنة مستند users/$uid بأمان
                    try {
                        val userData = hashMapOf<String, Any>(
                            "name" to finalName,
                            "displayName" to finalName,
                            "phone" to phone.trim(),
                            "address" to address.trim(),
                            "logoUrl" to finalLogoUrl,
                            "photoUrl" to finalLogoUrl,
                            "updatedAt" to FieldValue.serverTimestamp()
                        )
                        if (finalStoragePath.isNotBlank()) {
                            userData["storagePath"] = finalStoragePath
                        }
                        userDocRef.set(userData, SetOptions.merge()).await()
                    } catch (userEx: Exception) {
                        Log.w("FIREBASE_DEBUG", "Users collection sync note: ${userEx.message}")
                    }

                    // تحديث الـ StateFlow الخاص بالبروفايل فوراً
                    _restaurantProfile.value = RestaurantProfile(
                        name = finalName,
                        phone = phone.trim(),
                        address = address.trim(),
                        imageUri = finalLogoUrl.ifBlank { existingRemoteLogoUrl.ifBlank { null } },
                        logoUrl = finalLogoUrl.ifBlank { existingRemoteLogoUrl.ifBlank { null } },
                        photoUrl = finalLogoUrl.ifBlank { existingRemoteLogoUrl.ifBlank { null } },
                        storagePath = finalStoragePath,
                        email = Firebase.auth.currentUser?.email ?: "",
                        ownerId = uid,
                        uid = uid,
                        location = targetLocation
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
                e.printStackTrace()
                val detailedError = e.localizedMessage ?: e.message ?: "فشل حفظ بيانات المطعم"
                _errorMessage.value = detailedError
                _isSaveSuccess.value = false
            } finally {
                _isSaving.value = false
                Log.d("FIREBASE_DEBUG", "saveRestaurantProfile finished | isSaving reset to false")
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
            _subscriptionStatus.value = SubscriptionStatusState(isChecking = false, isExpired = false)
            return
        }

        val email = currentUser.email.orEmpty().trim().lowercase()
        val isSysAdmin = email == "moamen.elsayed.abdelmoamen@gmail.com" || email == "admin@elbatalexpress.com"

        if (isSysAdmin) {
            _subscriptionStatus.value = SubscriptionStatusState(
                isChecking = false,
                isExpired = false,
                status = "active",
                planId = 4,
                planName = "الباقة الإدارية الشاملة",
                maxCaptains = 99,
                activeCaptainsCount = 0,
                availableCaptainSlots = 99,
                paymentStatus = "paid"
            )
            return
        }

        _subscriptionStatus.value = _subscriptionStatus.value.copy(isChecking = true)

        restaurantSubListener?.remove()

        // 1. Listen directly to 'subscriptions' collection document for this merchant (Official primary source)
        restaurantSubListener = Firebase.firestore.collection("subscriptions").document(uid)
            .addSnapshotListener { subSnapshot, error ->
                if (error != null) {
                    if (error.code == FirebaseFirestoreException.Code.PERMISSION_DENIED) {
                        Log.w("OrderViewModel", "Subscriptions read PERMISSION_DENIED; falling back to query")
                    } else {
                        Log.w("OrderViewModel", "Error listening to subscriptions/$uid: ${error.message}")
                    }
                }

                if (subSnapshot != null && subSnapshot.exists()) {
                    processSubscriptionDocument(uid, "subscriptions", subSnapshot)
                } else {
                    // Try querying 'subscriptions' collection by merchantId or restaurantId
                    Firebase.firestore.collection("subscriptions")
                        .whereEqualTo("merchantId", uid)
                        .limit(1)
                        .get()
                        .addOnSuccessListener { qSnap ->
                            if (!qSnap.isEmpty) {
                                processSubscriptionDocument(uid, "subscriptions", qSnap.documents[0])
                            } else {
                                // Try where restaurantId == uid
                                Firebase.firestore.collection("subscriptions")
                                    .whereEqualTo("restaurantId", uid)
                                    .limit(1)
                                    .get()
                                    .addOnSuccessListener { qSnap2 ->
                                        if (!qSnap2.isEmpty) {
                                            processSubscriptionDocument(uid, "subscriptions", qSnap2.documents[0])
                                        } else {
                                            // Fallback to 'restaurants' document
                                            Firebase.firestore.collection("restaurants").document(uid).get()
                                                .addOnSuccessListener { restSnapshot ->
                                                    if (restSnapshot != null && restSnapshot.exists() && (restSnapshot.contains("subscriptionStatus") || restSnapshot.contains("subscriptionEndDate") || restSnapshot.contains("status"))) {
                                                        processSubscriptionDocument(uid, "restaurants", restSnapshot)
                                                    } else {
                                                        // Auto-provision an active default subscription so the merchant is never blocked
                                                        val thirtyDaysLater = Timestamp(Date(System.currentTimeMillis() + 30L * 24 * 3600 * 1000))
                                                        val defaultSub = hashMapOf<String, Any>(
                                                            "id" to uid,
                                                            "merchantId" to uid,
                                                            "restaurantId" to uid,
                                                            "status" to "active",
                                                            "subscriptionStatus" to "active",
                                                            "planId" to 1,
                                                            "planName" to "الباقة الأساسية",
                                                            "maxCaptains" to 4,
                                                            "startDate" to FieldValue.serverTimestamp(),
                                                            "endDate" to thirtyDaysLater,
                                                            "subscriptionEndDate" to thirtyDaysLater,
                                                            "paymentStatus" to "paid",
                                                            "createdAt" to FieldValue.serverTimestamp()
                                                        )
                                                        Firebase.firestore.collection("subscriptions").document(uid)
                                                            .set(defaultSub, SetOptions.merge())

                                                        _subscriptionStatus.value = SubscriptionStatusState(
                                                            isChecking = false,
                                                            isExpired = false,
                                                            status = "active",
                                                            planId = 1,
                                                            planName = "الباقة الأساسية",
                                                            maxCaptains = 4,
                                                            activeCaptainsCount = 0,
                                                            availableCaptainSlots = 4,
                                                            paymentStatus = "paid"
                                                        )
                                                    }
                                                }
                                                .addOnFailureListener {
                                                    // In case of network glitch, keep active fallback to prevent blocking
                                                    _subscriptionStatus.value = SubscriptionStatusState(
                                                        isChecking = false,
                                                        isExpired = false,
                                                        status = "active",
                                                        planId = 1,
                                                        planName = "الباقة الأساسية",
                                                        maxCaptains = 4,
                                                        activeCaptainsCount = 0,
                                                        availableCaptainSlots = 4,
                                                        paymentStatus = "paid"
                                                    )
                                                }
                                        }
                                    }
                                    .addOnFailureListener {
                                        // Fallback to restaurants
                                        Firebase.firestore.collection("restaurants").document(uid).get()
                                            .addOnSuccessListener { restSnapshot ->
                                                if (restSnapshot != null && restSnapshot.exists()) {
                                                    processSubscriptionDocument(uid, "restaurants", restSnapshot)
                                                }
                                            }
                                    }
                            }
                        }
                        .addOnFailureListener {
                            // Fallback to restaurants
                            Firebase.firestore.collection("restaurants").document(uid).get()
                                .addOnSuccessListener { restSnapshot ->
                                    if (restSnapshot != null && restSnapshot.exists()) {
                                        processSubscriptionDocument(uid, "restaurants", restSnapshot)
                                    }
                                }
                        }
                }
            }
    }

    private fun processSubscriptionDocument(
        uid: String,
        collectionName: String,
        snapshot: DocumentSnapshot
    ) {
        val email = Firebase.auth.currentUser?.email.orEmpty().trim().lowercase()
        val isSysAdmin = email == "moamen.elsayed.abdelmoamen@gmail.com" || email == "admin@elbatalexpress.com"
        if (isSysAdmin) {
            _subscriptionStatus.value = SubscriptionStatusState(
                isExpired = false,
                status = "active",
                planId = 4,
                planName = "الباقة الإدارية الشاملة",
                maxCaptains = 99,
                activeCaptainsCount = 0,
                availableCaptainSlots = 99,
                paymentStatus = "paid"
            )
            return
        }

        val rawStatus = snapshot.safeString("status")
            ?: snapshot.safeString("subscriptionStatus")
            ?: "active"
        val endDateMillis = parseSubscriptionEndDate(snapshot)
        val currentTime = System.currentTimeMillis()

        var isExpired = false
        if (rawStatus.equals("expired", ignoreCase = true) || rawStatus.equals("suspended", ignoreCase = true) || rawStatus.equals("cancelled", ignoreCase = true)) {
            isExpired = true
        } else if (endDateMillis != null && currentTime >= endDateMillis) {
            isExpired = true
            Firebase.firestore.collection(collectionName).document(uid)
                .set(mapOf("subscriptionStatus" to "expired", "status" to "expired"), com.google.firebase.firestore.SetOptions.merge())
                .addOnFailureListener { e ->
                    Log.e("OrderViewModel", "Failed to update subscriptionStatus to expired in $collectionName", e)
                }
        }

        val displayMessage = if (isExpired) {
            "انتهت فترة اشتراكك، يرجى التواصل مع الإدارة لتجديد الاشتراك"
        } else null

        val pId = (snapshot.get("planId") as? Number)?.toInt() ?: 1
        val pName = snapshot.safeString("planName") ?: when (pId) {
            2 -> "الباقة المتوسطة"
            3 -> "الباقة المميزة"
            4 -> "الباقة المتقدمة"
            else -> "الباقة الأساسية"
        }
        val allowedCaptains = (snapshot.get("maxCaptains") as? Number)?.toInt() ?: when (pId) {
            2 -> 4
            3 -> 6
            4 -> 8
            else -> 4
        }

        // Calculate active concurrent captains for this restaurant
        val currentOrders = _firestoreOrders.value
        val activeConcurrentCaptains = currentOrders.filter { o ->
            !o.captainId.isNullOrBlank() &&
            o.status !in listOf("completed", "delivered", "تم التسليم", "مكتمل", "مكتملة", "cancelled", "ملغي")
        }.mapNotNull { it.captainId }.distinct().size

        val availableSlots = (allowedCaptains - activeConcurrentCaptains).coerceAtLeast(0)

        _subscriptionStatus.value = SubscriptionStatusState(
            isExpired = isExpired,
            status = if (isExpired) "expired" else rawStatus,
            endDateMillis = endDateMillis,
            message = displayMessage,
            isChecking = false,
            planId = pId,
            planName = pName,
            maxCaptains = allowedCaptains,
            activeCaptainsCount = activeConcurrentCaptains,
            availableCaptainSlots = availableSlots,
            paymentStatus = snapshot.safeString("paymentStatus") ?: "paid"
        )
    }

    private fun parseSubscriptionEndDate(snapshot: DocumentSnapshot): Long? {
        for (field in listOf("endDate", "subscriptionEndDate")) {
            try {
                val timestamp = snapshot.getTimestamp(field)
                if (timestamp != null) return timestamp.toDate().time
            } catch (_: Exception) {}

            try {
                val date = snapshot.getDate(field)
                if (date != null) return date.time
            } catch (_: Exception) {}

            try {
                val num = snapshot.get(field) as? Number
                if (num != null) return num.toLong()
            } catch (_: Exception) {}

            try {
                val str = snapshot.safeString(field)
                if (!str.isNullOrBlank()) {
                    val asLong = str.toLongOrNull()
                    if (asLong != null) return asLong
                    val sdf = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
                    val parsed = sdf.parse(str)
                    if (parsed != null) return parsed.time
                }
            } catch (_: Exception) {}
        }

        return null
    }

    fun recheckSubscription() {
        checkAndListenToRestaurantSubscription()
    }

    fun clearListeners() {
        profileListener?.remove()
        profileListener = null
        merchantProfileListener?.remove()
        merchantProfileListener = null
        ordersListener?.remove()
        pendingListener?.remove()
        captainOrdersListener?.remove()
        driverListener?.remove()
        restaurantSubListener?.remove()
        orderTrackingListener?.remove()
        orderTrackingListener = null
        _subscriptionStatus.value = SubscriptionStatusState(isChecking = false, isExpired = false)
    }
}

