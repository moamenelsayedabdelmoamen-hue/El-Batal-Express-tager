package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.widget.Toast
import com.google.firebase.Firebase
import com.google.firebase.FirebaseException
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.FirebaseAuthException
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FieldValue
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.FirebaseFirestoreException
import com.google.firebase.firestore.SetOptions
import com.google.firebase.firestore.firestore
import com.google.firebase.storage.FirebaseStorage
import com.google.firebase.storage.StorageException
import com.google.firebase.storage.storage
import kotlinx.coroutines.tasks.await
import com.example.storage.FirebaseStorageManager
import com.example.storage.MigrationStepStatus
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.LayoutDirection
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.db.DeliveryOrderEntity
import com.example.db.OrderViewModel
import com.example.db.PushNotificationBanner
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Warning
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material.icons.Icons
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Phone
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DirectionsBike
import com.example.ui.components.OpenStreetMapLocationPicker
import com.example.ui.components.OpenStreetMapCaptainTracker
import androidx.compose.material.icons.filled.HourglassTop
import androidx.compose.material.icons.filled.AccessTime
import androidx.compose.material.icons.filled.Info
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.draw.scale
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.foundation.horizontalScroll
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.DarkMode
import com.example.ui.theme.ThemeManager
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import coil.compose.AsyncImage
import com.example.db.RestaurantProfile
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.material3.*
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.material3.pulltorefresh.PullToRefreshDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.RadioButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.runtime.*
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import coil.compose.AsyncImage
import com.example.ui.theme.Primary
import com.example.R
import android.app.Activity
import com.example.auth.MerchantAuthManager
import com.example.auth.MerchantSignInResult

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit, onRegisterClick: () -> Unit) {
    val context = LocalContext.current
    val sharedPreferences = remember {
        context.getSharedPreferences("login_prefs", Context.MODE_PRIVATE)
    }

    var rememberMe by remember {
        mutableStateOf(sharedPreferences.getBoolean("remember_me", false))
    }
    var email by remember {
        mutableStateOf(if (rememberMe) sharedPreferences.getString("saved_email", "") ?: "" else "")
    }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var errorMessageText by remember { mutableStateOf<String?>(null) }
    var showForgotPasswordDialog by remember { mutableStateOf(false) }
    var showUnverifiedEmailDialog by remember { mutableStateOf(false) }
    var resetEmail by remember { mutableStateOf("") }
    var isSendingReset by remember { mutableStateOf(false) }
    var isResendingVerification by remember { mutableStateOf(false) }
    var isGoogleLoading by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val focusManager = LocalFocusManager.current
    val isDark by ThemeManager.isDarkModeState.collectAsStateWithLifecycle()
    val logoBackgroundColor = if (isDark) Color.Transparent else Color.Black

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Theme Toggle at top left (أعلى اليسار)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Start
            ) {
                DayNightThemeToggle(
                    isDark = isDark,
                    onToggle = { ThemeManager.toggleTheme(context) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Box(
            modifier = Modifier
                .size(140.dp)
                .clip(CircleShape)
                .background(logoBackgroundColor)
                .border(2.dp, Primary, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.img_elbatal_logo_1782909588131),
                contentDescription = "شعار البطل إكسبريس",
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop
            )
        }

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = stringResource(R.string.login_title),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(24.dp))

        if (errorMessageText != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 16.dp),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = errorMessageText.orEmpty(),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = { errorMessageText = null },
                        modifier = Modifier.size(24.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "إغلاق",
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                OutlinedTextField(
                    value = email,
                    onValueChange = {
                        email = it
                        errorMessageText = null
                    },
                    label = { Text(stringResource(R.string.email_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    enabled = !isLoading,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Email,
                        imeAction = ImeAction.Next
                    ),
                    keyboardActions = KeyboardActions(
                        onNext = { focusManager.moveFocus(FocusDirection.Down) }
                    ),
                    leadingIcon = {
                        Icon(Icons.Default.Person, contentDescription = null, tint = Primary)
                    }
                )

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = {
                        password = it
                        errorMessageText = null
                    },
                    label = { Text(stringResource(R.string.password_hint)) },
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(12.dp),
                    singleLine = true,
                    enabled = !isLoading,
                    visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Password,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(
                        onDone = { focusManager.clearFocus() }
                    ),
                    leadingIcon = {
                        Icon(Icons.Default.Edit, contentDescription = null, tint = Primary)
                    },
                    trailingIcon = {
                        IconButton(onClick = { passwordVisible = !passwordVisible }) {
                            Icon(
                                imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                                contentDescription = if (passwordVisible) "إخفاء كلمة المرور" else "إظهار كلمة المرور",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                )

                Spacer(modifier = Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Row(
                        modifier = Modifier.clickable(enabled = !isLoading) { rememberMe = !rememberMe },
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Checkbox(
                            checked = rememberMe,
                            onCheckedChange = { rememberMe = it },
                            enabled = !isLoading,
                            colors = CheckboxDefaults.colors(
                                checkedColor = Primary,
                                checkmarkColor = Color.Black
                            )
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "تذكرني",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }

                    TextButton(
                        onClick = {
                            resetEmail = email.trim()
                            showForgotPasswordDialog = true
                        },
                        enabled = !isLoading
                    ) {
                        Text(
                            text = "نسيت كلمة المرور؟",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = {
                        errorMessageText = null
                        if (!com.example.network.NetworkHelper.isNetworkAvailable(context)) {
                            val msg = "أنت غير متصل بالإنترنت! يرجى التحقق من اتصال الشبكة لتسجيل الدخول."
                            errorMessageText = msg
                            com.example.network.NetworkHelper.notifyNoInternet(context, "تسجيل الدخول")
                            return@Button
                        }
                        val trimmedEmail = email.trim().lowercase()
                        val rawPassword = password.trim()
                        if (trimmedEmail.isEmpty()) {
                            val msg = "يرجى إدخال البريد الإلكتروني"
                            errorMessageText = msg
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (rawPassword.isEmpty()) {
                            val msg = "يرجى إدخال كلمة المرور"
                            errorMessageText = msg
                            Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                            return@Button
                        }

                        if (!isLoading) {
                            isLoading = true
                            scope.launch {
                                println("بدء عملية تسجيل الدخول...")
                                Log.d("FirebaseAuth", "بدء عملية تسجيل الدخول...")

                                try {
                                    val authResult = FirebaseAuth.getInstance().signInWithEmailAndPassword(
                                        trimmedEmail,
                                        rawPassword
                                    ).await()

                                    val user = authResult.user
                                        ?: throw Exception("Firebase user is null")
                                    val uid = user.uid

                                    Log.d("FIREBASE_DEBUG", "AUTH_SUCCESS uid=$uid")

                                    user.reload().await()
                                    if (user.isEmailVerified) {
                                        println("تم تسجيل الدخول بنجاح! الـ UID هو: $uid")
                                        Log.d("FIREBASE_DEBUG", "LOGIN_VERIFIED uid=$uid")

                                        sharedPreferences.edit().apply {
                                            putBoolean("remember_me", rememberMe)
                                            if (rememberMe) {
                                                putString("saved_email", trimmedEmail)
                                            } else {
                                                remove("saved_email")
                                            }
                                            apply()
                                        }

                                        // Ensure user document exists in 'users' collection
                                        try {
                                            Log.d("FIREBASE_DEBUG", "FIRESTORE_CHECK_STARTED path=users/$uid")
                                            val userDoc = FirebaseFirestore.getInstance()
                                                .collection("users")
                                                .document(uid)
                                                .get()
                                                .await()

                                            if (!userDoc.exists()) {
                                                Log.d("FIREBASE_DEBUG", "USER_DOC_NOT_FOUND_CREATING path=users/$uid")
                                                val userName = user.displayName ?: trimmedEmail.substringBefore("@")
                                                val userData = hashMapOf<String, Any>(
                                                    "uid" to uid,
                                                    "email" to (user.email ?: trimmedEmail),
                                                    "name" to userName,
                                                    "displayName" to userName,
                                                    "photoURL" to (user.photoUrl?.toString() ?: ""),
                                                    "createdAt" to FieldValue.serverTimestamp()
                                                )
                                                FirebaseFirestore.getInstance()
                                                    .collection("users")
                                                    .document(uid)
                                                    .set(userData, SetOptions.merge())
                                                    .await()
                                                Log.d("FIREBASE_DEBUG", "FIRESTORE_WRITE_SUCCESS path=users/$uid")
                                            } else {
                                                Log.d("FIREBASE_DEBUG", "USER_DOC_EXISTS path=users/$uid")
                                            }
                                        } catch (uErr: Exception) {
                                            Log.w("FIREBASE_DEBUG", "users/$uid background sync note: ${uErr.message}")
                                        }

                                        // Ensure restaurant document exists in 'restaurants' collection
                                        try {
                                            val resDoc = FirebaseFirestore.getInstance().collection("restaurants").document(uid).get().await()
                                            if (!resDoc.exists()) {
                                                Log.w("FIREBASE_DEBUG", "Restaurant document missing on login, initializing restaurants/$uid...")
                                                val thirtyDaysInMillis = 30L * 24 * 60 * 60 * 1000
                                                val endDate = java.util.Date(System.currentTimeMillis() + thirtyDaysInMillis)
                                                val defaultName = user.displayName ?: user.email?.substringBefore("@") ?: "مطعم"
                                                val fallbackMap = hashMapOf<String, Any>(
                                                    "uid" to uid,
                                                    "ownerId" to uid,
                                                    "name" to defaultName,
                                                    "phone" to (user.phoneNumber ?: ""),
                                                    "email" to (user.email ?: ""),
                                                    "address" to "العنوان غير محدد",
                                                    "category" to "مطعم",
                                                    "logoUrl" to "",
                                                    "status" to "active",
                                                    "rating" to 5.0,
                                                    "ratingCount" to 0L,
                                                    "subscriptionStatus" to "active",
                                                    "subscriptionId" to "",
                                                    "subscriptionStartDate" to com.google.firebase.Timestamp.now(),
                                                    "subscriptionEndDate" to com.google.firebase.Timestamp(endDate),
                                                    "createdAt" to FieldValue.serverTimestamp(),
                                                    "updatedAt" to FieldValue.serverTimestamp()
                                                )
                                                FirebaseFirestore.getInstance().collection("restaurants").document(uid).set(fallbackMap, SetOptions.merge()).await()
                                                Log.d("FIREBASE_DEBUG", "Restaurant document created successfully on login for UID: $uid")
                                            }
                                        } catch (docErr: Exception) {
                                            Log.w("FIREBASE_DEBUG", "Could not check/create restaurant document on login: ${docErr.message}")
                                        }

                                        Toast.makeText(context, "تم تسجيل الدخول بنجاح!", Toast.LENGTH_SHORT).show()
                                        onLoginSuccess()
                                    } else {
                                        println("البريد الإلكتروني غير مفعّل. يرجى تفعيله عبر الرابط المرسل لبريدك.")
                                        Log.w("FirebaseAuth", "البريد الإلكتروني غير مفعّل. جاري تسجيل الخروج...")

                                        showUnverifiedEmailDialog = true
                                        val unverifiedMsg = "البريد الإلكتروني غير مفعل بعد. يرجى تفعيل الحساب عبر الرابط المرسل لبريدك."
                                        errorMessageText = unverifiedMsg
                                        Toast.makeText(context, unverifiedMsg, Toast.LENGTH_LONG).show()
                                        FirebaseAuth.getInstance().signOut()
                                    }
                                } catch (e: FirebaseAuthException) {
                                    println("خطأ من Firebase Auth: ${e.errorCode} - ${e.message}")
                                    Log.w("FirebaseAuth", "فشل تسجيل الدخول: ${e.errorCode} - ${e.message}")

                                    val errorCode = e.errorCode.lowercase()
                                    val errorMsgText = (e.message ?: "").lowercase()
                                    val errorMessage = when {
                                        errorCode.contains("invalid-credential") ||
                                        errorCode.contains("invalid_credential") ||
                                        errorCode.contains("wrong-password") ||
                                        errorCode.contains("wrong_password") ||
                                        errorCode.contains("user-not-found") ||
                                        errorCode.contains("user_not_found") ||
                                        errorMsgText.contains("credential") ||
                                        errorMsgText.contains("password") ->
                                            "البريد الإلكتروني أو كلمة المرور غير صحيحة. يرجى التأكد من كتابة البيانات بدقة أو استخدام 'نسيت كلمة المرور' أو إنشاء حساب جديد."

                                        errorCode.contains("invalid-email") || errorCode.contains("invalid_email") ->
                                            "صيغة البريد الإلكتروني غير صحيحة."

                                        errorCode.contains("user-disabled") || errorCode.contains("user_disabled") ->
                                            "هذا الحساب معطل من قبل الإدارة."

                                        errorCode.contains("too-many-requests") || errorCode.contains("too_many_requests") ->
                                            "تم حظر المحاولات مؤقتاً لكثرة المحاولات غير الصحيحة. يرجى الانتظار دقيقة أو إعادة تعيين كلمة المرور."

                                        errorCode.contains("network") ->
                                            "تعذر الاتصال بالشبكة، يرجى التحقق من اتصال الإنترنت."

                                        else -> "خطأ في تسجيل الدخول: ${e.localizedMessage ?: e.message ?: e.errorCode}"
                                    }
                                    errorMessageText = errorMessage
                                    Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    println("استثناء عام: $e")
                                    Log.w("FirebaseAuth", "استثناء أثناء تسجيل الدخول: ${e.message}")

                                    val errorStr = (e.message ?: "").lowercase()
                                    val errorMessage = if (errorStr.contains("invalid") && (errorStr.contains("credential") || errorStr.contains("password"))) {
                                        "البريد الإلكتروني أو كلمة المرور غير صحيحة. يرجى التأكد من البيانات أو استخدام خيار 'نسيت كلمة المرور'."
                                    } else if (errorStr.contains("network")) {
                                        "تعذر الاتصال بالشبكة، يرجى التحقق من الإنترنت."
                                    } else {
                                        "حدث خطأ: ${e.localizedMessage ?: e.message}"
                                    }
                                    errorMessageText = errorMessage
                                    Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                                } finally {
                                    isLoading = false
                                }
                            }
                        }
                    },
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("login_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        disabledContainerColor = Primary.copy(alpha = 0.75f),
                        contentColor = Color.White,
                        disabledContentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isLoading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(22.dp)
                                    .testTag("login_progress_indicator"),
                                color = Color.White,
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "جاري تسجيل الدخول...",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.login_button),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "أو عبر حساب Google",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    )
                    HorizontalDivider(
                        modifier = Modifier.weight(1f),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                // زر «الدخول باستخدام Google» للتاجر
                OutlinedButton(
                    onClick = {
                        val activity = context as? Activity
                        if (activity == null) {
                            Toast.makeText(context, "تعذر بدء تسجيل الدخول بجوجل", Toast.LENGTH_SHORT).show()
                            return@OutlinedButton
                        }
                        if (!com.example.network.NetworkHelper.isNetworkAvailable(context)) {
                            com.example.network.NetworkHelper.notifyNoInternet(context, "تسجيل الدخول باستخدام Google")
                            return@OutlinedButton
                        }
                        isGoogleLoading = true
                        errorMessageText = null
                        scope.launch {
                            try {
                                val result = MerchantAuthManager.signInWithGoogle(context, activity)
                                when (result) {
                                    is MerchantSignInResult.Success -> {
                                        Toast.makeText(context, "مرحباً بك ${result.merchant.name}", Toast.LENGTH_SHORT).show()
                                        onLoginSuccess()
                                    }
                                    is MerchantSignInResult.NotRegistered -> {
                                        errorMessageText = result.message
                                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                    }
                                    is MerchantSignInResult.Error -> {
                                        errorMessageText = result.message
                                        Toast.makeText(context, result.message, Toast.LENGTH_LONG).show()
                                    }
                                    is MerchantSignInResult.Cancelled -> {
                                        // User dismissed Google picker
                                    }
                                }
                            } finally {
                                isGoogleLoading = false
                            }
                        }
                    },
                    enabled = !isLoading && !isGoogleLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("google_sign_in_merchant_button"),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline.copy(alpha = 0.5f)),
                    colors = ButtonDefaults.outlinedButtonColors(
                        containerColor = if (isDark) MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f) else Color.White
                    )
                ) {
                    if (isGoogleLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            color = Primary,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "جاري التحقق من حساب التاجر...",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    } else {
                        Image(
                            painter = painterResource(id = R.drawable.ic_google_logo),
                            contentDescription = "شعار Google",
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Text(
                            text = "الدخول باستخدام Google",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))

                TextButton(onClick = onRegisterClick, enabled = !isLoading && !isGoogleLoading) {
                    Text(
                        text = stringResource(R.string.register_link),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Medium,
                        color = Primary
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(32.dp))
    }

    if (showForgotPasswordDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isSendingReset) showForgotPasswordDialog = false
            },
            title = {
                Text(
                    text = "إعادة ضبط كلمة المرور",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column {
                    Text(
                        text = "أدخل بريدك الإلكتروني وسيتم إرسال رابط لإعادة ضبط كلمة المرور الخاص بك:",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = resetEmail,
                        onValueChange = { resetEmail = it },
                        label = { Text("البريد الإلكتروني") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
                        enabled = !isSendingReset
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmedEmail = resetEmail.trim()
                        if (trimmedEmail.isEmpty()) {
                            Toast.makeText(context, "يرجى إدخال البريد الإلكتروني", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (!com.example.network.NetworkHelper.isNetworkAvailable(context)) {
                            com.example.network.NetworkHelper.notifyNoInternet(context, "إعادة ضبط كلمة المرور")
                            return@Button
                        }
                        isSendingReset = true
                        Firebase.auth.sendPasswordResetEmail(trimmedEmail)
                            .addOnSuccessListener {
                                isSendingReset = false
                                showForgotPasswordDialog = false
                                Toast.makeText(
                                    context,
                                    "تم إرسال رابط إعادة ضبط كلمة المرور إلى بريدك الإلكتروني بنجاح.",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                            .addOnFailureListener { e ->
                                isSendingReset = false
                                val errMessage = if (e is FirebaseAuthException) {
                                    when (e.errorCode) {
                                        "user-not-found", "ERROR_USER_NOT_FOUND" -> "البريد الإلكتروني غير مسجل."
                                        "invalid-email", "ERROR_INVALID_EMAIL" -> "صيغة البريد الإلكتروني غير صحيحة."
                                        else -> e.localizedMessage ?: e.message ?: "حدث خطأ أثناء إرسال البريد."
                                    }
                                } else {
                                    e.localizedMessage ?: e.message ?: "حدث خطأ أثناء إرسال البريد."
                                }
                                Toast.makeText(context, "فشل الإرسال: $errMessage", Toast.LENGTH_LONG).show()
                            }
                    },
                    enabled = !isSendingReset,
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    if (isSendingReset) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("إرسال", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showForgotPasswordDialog = false },
                    enabled = !isSendingReset
                ) {
                    Text("إلغاء")
                }
            }
        )
    }

    if (showUnverifiedEmailDialog) {
        AlertDialog(
            onDismissRequest = {
                if (!isResendingVerification) showUnverifiedEmailDialog = false
            },
            title = {
                Text(
                    text = "الحساب غير مفعّل",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column {
                    Text(
                        text = "البريد الإلكتروني لم يتم تفعيله بعد. يرجى فتح صندوق الوارد (أو مجلد الرسائل غير المرغوب فيها Spam) والضغط على رابط التفعيل.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "إذا لم تجد رسالة التفعيل، يمكنك طلب إعادة إرسالها الآن.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmedEmail = email.trim()
                        val rawPassword = password.trim()
                        if (trimmedEmail.isEmpty() || rawPassword.isEmpty()) {
                            Toast.makeText(context, "يرجى كتابة البريد وكلمة المرور لإعادة الإرسال", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (!com.example.network.NetworkHelper.isNetworkAvailable(context)) {
                            com.example.network.NetworkHelper.notifyNoInternet(context, "إعادة إرسال رابط التفعيل")
                            return@Button
                        }
                        isResendingVerification = true
                        Firebase.auth.signInWithEmailAndPassword(trimmedEmail, rawPassword)
                            .addOnSuccessListener { authRes ->
                                authRes.user?.sendEmailVerification()
                                    ?.addOnSuccessListener {
                                        isResendingVerification = false
                                        showUnverifiedEmailDialog = false
                                        Firebase.auth.signOut()
                                        Toast.makeText(
                                            context,
                                            "تم إرسال رابط تفعيل جديد إلى بريدك الإلكتروني بنجاح.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    ?.addOnFailureListener { e ->
                                        isResendingVerification = false
                                        Firebase.auth.signOut()
                                        Toast.makeText(
                                            context,
                                            "فشل إرسال الرابط: ${e.localizedMessage ?: e.message}",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                            }
                            .addOnFailureListener { e ->
                                isResendingVerification = false
                                Toast.makeText(
                                    context,
                                    "تعذر إعادة الإرسال: تأكد من صحة كلمة المرور والبريد",
                                    Toast.LENGTH_LONG
                                ).show()
                            }
                    },
                    enabled = !isResendingVerification,
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    if (isResendingVerification) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("إعادة إرسال الرابط", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showUnverifiedEmailDialog = false },
                    enabled = !isResendingVerification
                ) {
                    Text("حسناً")
                }
            }
        )
    }
}

fun getFirebaseErrorMessage(error: Throwable): String {
    return when (error) {
        is FirebaseAuthException -> {
            when (error.errorCode) {
                "auth/email-already-in-use", "ERROR_EMAIL_ALREADY_IN_USE", "email-already-in-use" ->
                    "البريد الإلكتروني مستخدم مسبقًا"
                "auth/invalid-email", "ERROR_INVALID_EMAIL", "invalid-email" ->
                    "البريد الإلكتروني غير صحيح"
                "auth/weak-password", "ERROR_WEAK_PASSWORD", "weak-password" ->
                    "كلمة المرور ضعيفة"
                "auth/operation-not-allowed", "ERROR_OPERATION_NOT_ALLOWED", "operation-not-allowed" ->
                    "تسجيل الدخول بالبريد الإلكتروني غير مفعل في Firebase"
                "auth/network-request-failed", "ERROR_NETWORK_REQUEST_FAILED", "network-request-failed" ->
                    "تعذر الاتصال بالشبكة، يرجى التحقق من الإنترنت"
                else -> error.localizedMessage ?: error.message ?: "حدث خطأ غير معروف"
            }
        }
        is FirebaseFirestoreException -> {
            when (error.code) {
                FirebaseFirestoreException.Code.PERMISSION_DENIED ->
                    "ليس لديك صلاحية لحفظ البيانات في Firestore"
                FirebaseFirestoreException.Code.UNAVAILABLE ->
                    "خدمة Firestore غير متاحة حالياً، يرجى التحقق من الإنترنت"
                else -> error.localizedMessage ?: error.message ?: "حدث خطأ غير معروف"
            }
        }
        is StorageException -> {
            when (error.errorCode) {
                StorageException.ERROR_NOT_AUTHORIZED ->
                    "ليس لديك صلاحية لرفع الشعار"
                else -> error.localizedMessage ?: error.message ?: "حدث خطأ غير معروف"
            }
        }
        else -> {
            val msg = error.localizedMessage ?: error.message.orEmpty()
            when {
                msg.contains("email-already-in-use", ignoreCase = true) ->
                    "البريد الإلكتروني مستخدم مسبقًا"
                msg.contains("invalid-email", ignoreCase = true) ->
                    "البريد الإلكتروني غير صحيح"
                msg.contains("weak-password", ignoreCase = true) ->
                    "كلمة المرور ضعيفة"
                msg.contains("operation-not-allowed", ignoreCase = true) ->
                    "تسجيل الدخول بالبريد الإلكتروني غير مفعل في Firebase"
                msg.contains("permission-denied", ignoreCase = true) ->
                    "ليس لديك صلاحية لحفظ البيانات في Firestore"
                msg.contains("storage/unauthorized", ignoreCase = true) || msg.contains("unauthorized", ignoreCase = true) ->
                    "ليس لديك صلاحية لرفع الشعار"
                msg.isNotBlank() -> msg
                else -> "حدث خطأ غير معروف"
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RegisterScreen(onRegisterSuccess: () -> Unit) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var email by remember { mutableStateOf("") }
    var phone by remember { mutableStateOf("") }
    var address by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var isLoading by remember { mutableStateOf(false) }
    var isRegistrationSuccessful by remember { mutableStateOf(false) }
    var registeredEmail by remember { mutableStateOf("") }
    var diagnosticStatus by remember { mutableStateOf<String?>(null) }
    var diagnosticLogs by remember { mutableStateOf<List<String>>(emptyList()) }
    var diagnosticError by remember { mutableStateOf<String?>(null) }
    var showDiagnosticDialog by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        if (uri != null) {
            selectedImageUri = uri
        }
    }

    if (isRegistrationSuccessful) {
        Surface(
            modifier = Modifier.fillMaxSize(),
            color = MaterialTheme.colorScheme.background
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(horizontal = 24.dp, vertical = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                Spacer(modifier = Modifier.height(16.dp))

                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Big Green Checkmark
                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE8F5E9))
                            .border(3.dp, Color(0xFF4CAF50), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = "Success",
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(64.dp)
                        )
                    }

                    Spacer(modifier = Modifier.height(24.dp))

                    Text(
                        text = stringResource(R.string.email_verification_success_title),
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onBackground,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    Text(
                        text = stringResource(R.string.email_verification_sent_desc),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = Primary,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    if (registeredEmail.isNotBlank()) {
                        Surface(
                            shape = RoundedCornerShape(12.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Email,
                                    contentDescription = null,
                                    tint = Primary,
                                    modifier = Modifier.size(20.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = registeredEmail,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // بطاقة الإرشاد والتوضيح
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(16.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                        ),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                    ) {
                        Column(
                            modifier = Modifier.padding(16.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = stringResource(R.string.email_verification_hint),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.85f),
                                textAlign = TextAlign.Center,
                                lineHeight = 22.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // زر إعادة إرسال رابط التأكيد
                    var isResending by remember { mutableStateOf(false) }
                    var resendMessage by remember { mutableStateOf<String?>(null) }

                    OutlinedButton(
                        onClick = {
                            val currentUser = Firebase.auth.currentUser
                            if (currentUser != null) {
                                scope.launch {
                                    isResending = true
                                    try {
                                        currentUser.sendEmailVerification().await()
                                        resendMessage = "تمت إعادة إرسال رابط التحقق بنجاح!"
                                        Toast.makeText(context, "تمت إعادة إرسال رابط التأكيد", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        resendMessage = "تعذر الإرسال: ${e.message}"
                                    } finally {
                                        isResending = false
                                    }
                                }
                            }
                        },
                        enabled = !isResending,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (isResending) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = if (isResending) "جاري إعادة الإرسال..." else "لم يصلك الرابط؟ إعادة الإرسال",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (resendMessage != null) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = resendMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = Primary,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Return to Login Button at bottom
                Button(
                    onClick = onRegisterSuccess,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary
                    ),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.back_to_login_button),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
        return
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // Theme Toggle at top left (أعلى اليسار)
        CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.Start
            ) {
                val isDark by ThemeManager.isDarkModeState.collectAsStateWithLifecycle()
                DayNightThemeToggle(
                    isDark = isDark,
                    onToggle = { ThemeManager.toggleTheme(context) }
                )
            }
        }

        // Profile Photo Picker
        Text(
            text = "صورة الملف الشخصي للتاجر",
            style = MaterialTheme.typography.titleSmall,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground
        )
        Spacer(modifier = Modifier.height(8.dp))

        Box(
            modifier = Modifier
                .size(120.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .border(2.5.dp, Primary, CircleShape)
                .clickable(enabled = !isLoading) {
                    photoPickerLauncher.launch(
                        PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            if (selectedImageUri != null) {
                AsyncImage(
                    model = selectedImageUri,
                    contentDescription = "صورة الملف الشخصي",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            } else {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Add,
                        contentDescription = "إضافة صورة",
                        tint = Primary,
                        modifier = Modifier.size(36.dp)
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "إضافة صورة",
                        style = MaterialTheme.typography.labelSmall,
                        color = Primary,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Name Field
        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("الاسم الكامل / اسم المطعم") },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Default.Person, contentDescription = null, tint = Primary)
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Email Field
        OutlinedTextField(
            value = email,
            onValueChange = { email = it },
            label = { Text(stringResource(R.string.email_hint)) },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
            leadingIcon = {
                Icon(Icons.Default.Person, contentDescription = null, tint = Primary)
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Phone Field
        OutlinedTextField(
            value = phone,
            onValueChange = { phone = it },
            label = { Text(stringResource(R.string.phone_hint)) },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
            leadingIcon = {
                Icon(Icons.Default.Phone, contentDescription = null, tint = Primary)
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Address Field
        OutlinedTextField(
            value = address,
            onValueChange = { address = it },
            label = { Text("عنوان المطعم / الموقع") },
            enabled = !isLoading,
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Default.LocationOn, contentDescription = null, tint = Primary)
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Password Field
        OutlinedTextField(
            value = password,
            onValueChange = { password = it },
            label = { Text(stringResource(R.string.password_hint)) },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isLoading,
            singleLine = true,
            visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            leadingIcon = {
                Icon(Icons.Default.Edit, contentDescription = null, tint = Primary)
            },
            trailingIcon = {
                IconButton(onClick = { passwordVisible = !passwordVisible }) {
                    Icon(
                        imageVector = if (passwordVisible) Icons.Default.Visibility else Icons.Default.VisibilityOff,
                        contentDescription = if (passwordVisible) "إخفاء كلمة المرور" else "إظهار كلمة المرور",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        )

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                if (!com.example.network.NetworkHelper.isNetworkAvailable(context)) {
                    com.example.network.NetworkHelper.notifyNoInternet(context, "إنشاء حساب جديد")
                    return@Button
                }
                if (name.trim().isEmpty()) {
                    Toast.makeText(context, "يرجى إدخال اسم المطعم", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                if (email.trim().isEmpty() || !email.contains("@")) {
                    Toast.makeText(context, "يرجى إدخال بريد إلكتروني صحيح", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                if (phone.trim().isEmpty()) {
                    Toast.makeText(context, "يرجى إدخال رقم الهاتف", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                if (password.trim().length < 6) {
                    Toast.makeText(context, "كلمة المرور يجب أن لا تقل عن 6 أحرف", Toast.LENGTH_SHORT).show()
                    return@Button
                }

                if (!isLoading) {
                    isLoading = true
                    diagnosticError = null
                    val authApp = Firebase.auth.app
                    val firestoreApp = Firebase.firestore.app
                    val authProject = authApp.options.projectId ?: "unknown"
                    val firestoreProject = firestoreApp.options.projectId ?: "unknown"
                    val authAppName = authApp.name
                    val firestoreAppName = firestoreApp.name

                    Log.d("FirestoreDiagnostics", "==================================================")
                    Log.d("FirestoreDiagnostics", "Firebase App Name   : Auth=$authAppName, Firestore=$firestoreAppName")
                    Log.d("FirestoreDiagnostics", "Firebase Project ID : Auth=$authProject, Firestore=$firestoreProject")
                    Log.d("FirestoreDiagnostics", "Auth CREATE STARTED : ${email.trim()}")
                    Log.d("FirestoreDiagnostics", "==================================================")

                    val initialLogs = mutableListOf(
                        "Firebase Project ID: $authProject",
                        "Auth CREATE STARTED: ${email.trim()}"
                    )
                    diagnosticLogs = initialLogs
                    diagnosticStatus = "Auth CREATE STARTED"

                    scope.launch {
                        try {
                            // 1. إنشاء حساب للمستخدم باستخدام البريد الإلكتروني وكلمة المرور
                            val authResult = Firebase.auth
                                .createUserWithEmailAndPassword(email.trim(), password.trim())
                                .await()

                            val user = authResult.user
                                ?: throw Exception("تعذر استرجاع بيانات المستخدم بعد إنشاء الحساب")

                            // 2. الحصول على uid والتحقق من جلسة Auth
                            val uid = user.uid
                            val currentAuthUid = Firebase.auth.currentUser?.uid ?: ""
                            val currentEmail = Firebase.auth.currentUser?.email ?: (user.email ?: email.trim())

                            Log.d("FirestoreDiagnostics", "Auth user created: ${user.email}")
                            Log.d("FirestoreDiagnostics", "User UID: $uid")
                            Log.d("FirestoreDiagnostics", "Current Auth UID: $currentAuthUid")
                            Log.d("FirestoreDiagnostics", "Firebase Project ID: $authProject")

                            diagnosticLogs = diagnosticLogs + listOf(
                                "Auth user created: $currentEmail",
                                "User UID: $uid",
                                "Current Auth UID: $currentAuthUid",
                                "Firebase Project ID: $authProject"
                            )

                            if (currentAuthUid.isEmpty() || currentAuthUid != uid) {
                                val syncMsg = "تنبيه: الجلسة الحالية غير متطابقة (user=$uid, current=$currentAuthUid)"
                                Log.e("FirestoreDiagnostics", syncMsg)
                                diagnosticLogs = diagnosticLogs + syncMsg
                                throw Exception("فشل التحقق من جلسة المستخدم: $syncMsg")
                            }

                            // 3. تحديد المسار المباشر restaurants/{uid}
                            val restaurantPath = "restaurants/$uid"
                            val restaurantDocRef = Firebase.firestore.collection("restaurants").document(uid)

                            Log.d("FirestoreDiagnostics", "Firestore document path: $restaurantPath")
                            Log.d("FirestoreDiagnostics", "Firestore write started: $restaurantPath")

                            diagnosticLogs = diagnosticLogs + listOf(
                                "Firestore document path: $restaurantPath",
                                "Firestore write started: $restaurantPath"
                            )

                            var logoUrl = ""
                            var storagePath = ""
                            if (selectedImageUri != null) {
                                val uploadResult = FirebaseStorageManager.uploadMerchantProfileImage(
                                    context = context,
                                    uid = uid,
                                    localUriString = selectedImageUri!!.toString()
                                )
                                uploadResult.onSuccess { uploaded ->
                                    logoUrl = uploaded.downloadUrl
                                    storagePath = uploaded.storagePath
                                    Log.d("FirestoreDiagnostics", "Firebase Storage upload success: $logoUrl ($storagePath)")
                                }.onFailure { storageEx ->
                                    Log.w("FirestoreDiagnostics", "Storage upload failed: ${storageEx.message}")
                                    logoUrl = ""
                                    storagePath = ""
                                }
                            }

                            // 4. إنشاء بيانات التاجر والمطعم والكتابة إلى Firestore
                            val finalAddress = if (address.trim().isNotBlank()) address.trim() else "العنوان غير محدد"

                            // أ) حفظ مستند التاجر في merchants/{uid}
                            val merchantDocRef = Firebase.firestore.collection("merchants").document(uid)
                            val merchantData = hashMapOf<String, Any>(
                                "uid" to uid,
                                "name" to name.trim(),
                                "email" to currentEmail,
                                "phone" to phone.trim(),
                                "address" to finalAddress,
                                "photoUrl" to logoUrl,
                                "storagePath" to storagePath,
                                "role" to "merchant",
                                "status" to "approved",
                                "isApproved" to true,
                                "authProvider" to "password",
                                "createdAt" to FieldValue.serverTimestamp(),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )
                            merchantDocRef.set(merchantData, SetOptions.merge()).addOnCompleteListener { task ->
                                if (task.isSuccessful) {
                                    Log.d("FirestoreDiagnostics", "merchants/$uid written successfully")
                                } else {
                                    Log.w("FirestoreDiagnostics", "merchants/$uid write warning: ${task.exception?.message}")
                                }
                            }

                            // ب) حفظ بيانات المطعم في restaurants/{uid}
                            val restaurantData = hashMapOf<String, Any>(
                                "uid" to uid,
                                "ownerId" to uid,
                                "email" to currentEmail,
                                "name" to name.trim(),
                                "phone" to phone.trim(),
                                "address" to finalAddress,
                                "category" to "مطعم",
                                "logoUrl" to logoUrl,
                                "photoUrl" to logoUrl,
                                "storagePath" to storagePath,
                                "status" to "active",
                                "isApproved" to true,
                                "rating" to 5.0,
                                "ratingCount" to 0L,
                                "subscriptionStatus" to "active",
                                "subscriptionId" to "",
                                "createdAt" to FieldValue.serverTimestamp(),
                                "updatedAt" to FieldValue.serverTimestamp()
                            )

                            restaurantDocRef.set(restaurantData).addOnCompleteListener { task ->
                                if (task.isSuccessful) {
                                    Log.d("FirestoreDiagnostics", "Firestore write acknowledged by server: $restaurantPath")
                                } else {
                                    Log.w("FirestoreDiagnostics", "Firestore write server sync note: ${task.exception?.message}")
                                }
                            }
                            Log.d("FirestoreDiagnostics", "Firestore write initiated & queued: $restaurantPath")
                            diagnosticLogs = diagnosticLogs + "Firestore write initiated: $restaurantPath"

                            // ج) مزامنة مستند المستخدم في users/$uid
                            try {
                                val userDocRef = Firebase.firestore.collection("users").document(uid)
                                val userData = hashMapOf<String, Any>(
                                    "uid" to uid,
                                    "email" to currentEmail,
                                    "name" to name.trim(),
                                    "displayName" to name.trim(),
                                    "phone" to phone.trim(),
                                    "address" to finalAddress,
                                    "role" to "merchant",
                                    "logoUrl" to logoUrl,
                                    "photoUrl" to logoUrl,
                                    "createdAt" to FieldValue.serverTimestamp(),
                                    "updatedAt" to FieldValue.serverTimestamp()
                                )
                                userDocRef.set(userData, SetOptions.merge())
                                Log.d("FirestoreDiagnostics", "User doc write initiated: users/$uid")
                                diagnosticLogs = diagnosticLogs + "User doc write initiated: users/$uid"
                            } catch (uEx: Exception) {
                                Log.w("FirestoreDiagnostics", "users/$uid sync warning: ${uEx.message}")
                                diagnosticLogs = diagnosticLogs + "users/$uid sync: ${uEx.message}"
                            }

                            // إرسال رابط تأكيد البريد الإلكتروني
                            try {
                                user.sendEmailVerification().await()
                                Log.d("FirestoreDiagnostics", "Verification email sent to: $currentEmail")
                            } catch (e: Exception) {
                                Log.w("FirestoreDiagnostics", "Verification email error: ${e.message}")
                            }

                            // عرض شاشة النجاح فقط بعد إتمام الكتابة في Firestore
                            registeredEmail = currentEmail
                            isLoading = false
                            isRegistrationSuccessful = true
                        } catch (e: Exception) {
                            isLoading = false
                            val errorCode = when (e) {
                                is FirebaseFirestoreException -> e.code.name
                                is FirebaseAuthException -> e.errorCode
                                else -> e.javaClass.simpleName
                            }
                            Log.w("FirestoreDiagnostics", "REGISTRATION FAILED: code=$errorCode, message=${e.message}")

                            val isFirestoreIssue = e is FirebaseFirestoreException
                            val errorSummary = if (isFirestoreIssue) {
                                "FIRESTORE_WRITE_FAILED: [$errorCode] ${e.localizedMessage ?: e.message}"
                            } else {
                                "REGISTRATION_ERROR: [$errorCode] ${e.localizedMessage ?: e.message}"
                            }
                            diagnosticError = errorSummary
                            diagnosticStatus = if (isFirestoreIssue) "FIRESTORE_WRITE_FAILED" else "AUTH_FAILED"
                            diagnosticLogs = diagnosticLogs + listOf(
                                ">>> $errorSummary <<<",
                                "Exception type: ${e.javaClass.name}"
                            )
                            if (isFirestoreIssue) {
                                showDiagnosticDialog = true
                            }

                            val errorMessage = getFirebaseErrorMessage(e)
                            Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                        } finally {
                            isLoading = false
                        }
                    }
                }
            },
                    enabled = !isLoading,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp)
                        .testTag("register_button"),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        disabledContainerColor = Primary.copy(alpha = 0.75f),
                        contentColor = Color.White,
                        disabledContentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    if (isLoading) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier
                                    .size(22.dp)
                                    .testTag("register_progress_indicator"),
                                color = Color.White,
                                strokeWidth = 2.5.dp
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "جاري إنشاء الحساب...",
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    } else {
                        Text(
                            text = stringResource(R.string.register_button),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }

        if (diagnosticLogs.isNotEmpty() || isLoading) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
                ),
                border = BorderStroke(1.dp, if (diagnosticError != null) Color.Red else MaterialTheme.colorScheme.outlineVariant)
            ) {
                Column(modifier = Modifier.padding(12.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "سجل التشخيص المباشر (Firebase Diagnostics):",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (isLoading) {
                            CircularProgressIndicator(modifier = Modifier.size(14.dp), strokeWidth = 2.dp)
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    diagnosticLogs.forEach { logLine ->
                        val isErr = logLine.contains("FAILED") || logLine.contains("Exception") || logLine.contains(">>>")
                        val isSuccess = logLine.contains("SUCCESS")
                        Text(
                            text = logLine,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = when {
                                isErr -> Color.Red
                                isSuccess -> Color(0xFF2E7D32)
                                else -> MaterialTheme.colorScheme.onSurfaceVariant
                            },
                            modifier = Modifier.padding(vertical = 2.dp)
                        )
                    }
                }
            }
        }

        if (showDiagnosticDialog && diagnosticError != null) {
            AlertDialog(
                onDismissRequest = { showDiagnosticDialog = false },
                title = {
                    Text(
                        text = "تفاصيل خطأ Firestore / Auth",
                        fontWeight = FontWeight.Bold,
                        color = Color.Red
                    )
                },
                text = {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        Text(
                            text = diagnosticError ?: "",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                },
                confirmButton = {
                    Button(
                        onClick = { showDiagnosticDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Text("إغلاق", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    }
}

data class Plan(val id: Int, val captains: Int, val price: Int, val name: String, val subtitle: String, val isPopular: Boolean = false)

@Composable
fun RegistrationSuccessScreen() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(stringResource(R.string.registration_success_message), style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
fun SubscriptionPlansScreen(onSkip: () -> Unit = {}) {
    val plans = listOf(
        Plan(1, 2, 400, "2 كابتن", "الباقة الأساسية"),
        Plan(2, 4, 600, "4 كابتن", "الباقة المتوسطة"),
        Plan(3, 6, 800, "6 كابتن", "الباقة المميزة", true),
        Plan(4, 8, 1000, "8 كابتن", "الباقة المتقدمة")
    )
    var selectedPlanId by remember { mutableStateOf<Int?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var showPaymentDialog by remember { mutableStateOf(false) }
    var paymentMethod by remember { mutableStateOf("card") }
    var phoneNumber by remember { mutableStateOf("") }
    var fallbackUrl by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    if (fallbackUrl != null) {
        AlertDialog(
            onDismissRequest = { fallbackUrl = null },
            modifier = Modifier.fillMaxSize(0.9f),
            text = {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            webViewClient = WebViewClient()
                            loadUrl(fallbackUrl!!)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            },
            confirmButton = {
                Button(onClick = { fallbackUrl = null }) { Text("إغلاق") }
            }
        )
    }

    if (showPaymentDialog) {
        AlertDialog(
            onDismissRequest = { showPaymentDialog = false },
            title = { Text("اختر طريقة الدفع") },
            text = {
                Column {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = paymentMethod == "card", onClick = { paymentMethod = "card" })
                        Text("بطاقة ائتمان")
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = paymentMethod == "wallet", onClick = { paymentMethod = "wallet" })
                        Text("محفظة إلكترونية")
                    }
                    if (paymentMethod == "wallet") {
                        OutlinedTextField(value = phoneNumber, onValueChange = { phoneNumber = it }, label = { Text("رقم الهاتف") })
                    }
                }
            },
            confirmButton = {
                Button(onClick = {
                    showPaymentDialog = false
                    isLoading = true
                    val plan = plans.find { it.id == selectedPlanId }
                    if (plan != null) {
                        scope.launch(Dispatchers.IO) {
                            try {
                                val request = PaymentRequest(
                                    amount = plan.price,
                                    email = "user@email.com",
                                    first_name = "Moamen",
                                    last_name = "Test",
                                    payment_method = paymentMethod,
                                    phone_number = if (paymentMethod == "wallet") phoneNumber else null
                                )
                                val response = RetrofitClient.instance.createPayment(request)
                                withContext(Dispatchers.Main) {
                                    isLoading = false
                                    try {
                                        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(response.url))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        android.util.Log.e("PaymentError", "External browser failed, using fallback", e)
                                        fallbackUrl = response.url
                                    }
                                }
                            } catch (e: Exception) {
                                android.util.Log.e("PaymentError", "Detailed error creating payment", e)
                                withContext(Dispatchers.Main) {
                                    isLoading = false
                                    android.widget.Toast.makeText(
                                        context,
                                        "خطأ: ${e.message ?: "غير معروف"}",
                                        android.widget.Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        }
                    }
                }) {
                    Text("متابعة")
                }
            }
        )
    }

    Column(
        modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        ElBatalHeader(
            title = stringResource(R.string.subscription_title),
            actionText = stringResource(R.string.skip_button),
            onActionClick = onSkip
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Skip Button Banner
        OutlinedButton(
            onClick = onSkip,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Primary),
            border = androidx.compose.foundation.BorderStroke(1.5.dp, Primary),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = stringResource(R.string.skip_to_order),
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.bodyLarge
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        if (isLoading) {
            Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = Primary)
            }
        } else {
            LazyVerticalGrid(
                columns = GridCells.Fixed(2),
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                items(plans) { plan ->
                    PlanCard(
                        plan = plan,
                        isSelected = selectedPlanId == plan.id,
                        onSelect = { selectedPlanId = plan.id },
                        onSubscribe = {
                            selectedPlanId = plan.id
                            showPaymentDialog = true
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PlanCard(plan: Plan, isSelected: Boolean, onSelect: () -> Unit, onSubscribe: () -> Unit) {
    val borderColor = if (isSelected) Primary else MaterialTheme.colorScheme.outlineVariant
    val borderWidth = if (isSelected) 2.dp else 1.dp
    val backgroundColor = MaterialTheme.colorScheme.surface

    Card(
        modifier = Modifier.fillMaxWidth().border(borderWidth, borderColor, RoundedCornerShape(16.dp)).clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (plan.isPopular) {
                Text(stringResource(R.string.most_popular), color = Primary, style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.height(4.dp))
            }
            
            // Icon
            Box(modifier = Modifier.size(50.dp).clip(CircleShape).background(Primary.copy(alpha = 0.15f)), contentAlignment = Alignment.Center) {
                Icon(Icons.Filled.Person, contentDescription = null, tint = Primary, modifier = Modifier.size(30.dp))
            }
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(plan.name, color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            Text(plan.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(stringResource(R.string.plan_price, plan.price), color = Primary, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(8.dp))
            
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))
            
            // Features
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Primary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.feature_fast_delivery), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = Primary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.feature_tech_support), color = MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(12.dp))
            
            Button(onClick = onSubscribe, shape = RoundedCornerShape(20.dp), colors = ButtonDefaults.buttonColors(containerColor = Primary)) {
                Text(stringResource(R.string.subscribe_now), color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestaurantDashboardScreen(
    onRequestCaptain: () -> Unit,
    onViewAllOrders: () -> Unit,
    onRestaurantClick: () -> Unit = {},
    onPlansClick: () -> Unit = {},
    onLogout: () -> Unit = {},
    onCaptainDashboardClick: () -> Unit = {},
    onEditOrder: ((Int) -> Unit)? = null,
    orderViewModel: OrderViewModel = viewModel()
) {
    val drawerState = rememberDrawerState(initialValue = DrawerValue.Closed)
    val scope = rememberCoroutineScope()
    var showLogoutDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        orderViewModel.listenToFirestore()
    }

    val firestoreOrders by orderViewModel.firestoreOrders.collectAsStateWithLifecycle()
    val localOrders by orderViewModel.ordersState.collectAsStateWithLifecycle()
    val orders = if (firestoreOrders.isNotEmpty()) firestoreOrders else localOrders
    val profile by orderViewModel.restaurantProfile.collectAsStateWithLifecycle()
    val isRefreshing by orderViewModel.isRefreshing.collectAsStateWithLifecycle()

    ModalNavigationDrawer(
        drawerState = drawerState,
        gesturesEnabled = true,
        drawerContent = {
            ModalDrawerSheet(
                drawerContainerColor = MaterialTheme.colorScheme.surface,
                modifier = Modifier.width(320.dp)
            ) {
                SideMenuContent(
                    profile = profile,
                    onRestaurantClick = {
                        scope.launch { drawerState.close() }
                        onRestaurantClick()
                    },
                    onViewAllOrders = {
                        scope.launch { drawerState.close() }
                        onViewAllOrders()
                    },
                    onPlansClick = {
                        scope.launch { drawerState.close() }
                        onPlansClick()
                    },
                    onCaptainDashboardClick = {
                        scope.launch { drawerState.close() }
                        onCaptainDashboardClick()
                    },
                    onLogoutClick = {
                        scope.launch { drawerState.close() }
                        showLogoutDialog = true
                    },
                    onCloseMenu = {
                        scope.launch { drawerState.close() }
                    }
                )
            }
        }
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            ElBatalHeader(
                title = stringResource(R.string.restaurant_dashboard_title),
                onMenuClick = {
                    scope.launch {
                        if (drawerState.isClosed) drawerState.open() else drawerState.close()
                    }
                }
            )

            PullToRefreshBox(
                isRefreshing = isRefreshing,
                onRefresh = { orderViewModel.refreshRestaurantFeed() },
                modifier = Modifier.fillMaxSize(),
                indicator = {
                    PullToRefreshDefaults.Indicator(
                        state = rememberPullToRefreshState(),
                        isRefreshing = isRefreshing,
                        modifier = Modifier.align(Alignment.TopCenter),
                        containerColor = MaterialTheme.colorScheme.surface,
                        color = Primary
                    )
                }
            ) {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // 1. Restaurant Profile Card (أيقونة/كارت للمطعم)
                    item {
                        RestaurantProfileCard(
                            profile = profile,
                            onClick = onRestaurantClick
                        )
                    }

                    // 1.5 Subscription & Concurrent Captain Capacity Card
                    item {
                        val subStatus by orderViewModel.subscriptionStatus.collectAsStateWithLifecycle()
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable { onPlansClick() },
                            shape = RoundedCornerShape(16.dp),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                            border = BorderStroke(1.dp, Primary.copy(alpha = 0.4f))
                        ) {
                            Column(modifier = Modifier.padding(16.dp)) {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                                    ) {
                                        Text(text = "💳", style = MaterialTheme.typography.titleMedium)
                                        Column {
                                            Text(
                                                text = "باقة الاشتراك: ${subStatus.planName}",
                                                style = MaterialTheme.typography.titleSmall,
                                                fontWeight = FontWeight.Bold,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                            val expiryDateStr = subStatus.endDateMillis?.let {
                                                java.text.SimpleDateFormat("dd MMMM yyyy", java.util.Locale("ar")).format(java.util.Date(it))
                                            } ?: "سارية"
                                            Text(
                                                text = if (subStatus.isExpired) "منتهية - يرجى التجديد" else "تنتهي في: $expiryDateStr",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = if (subStatus.isExpired) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                    }

                                    Surface(
                                        color = if (subStatus.isExpired) MaterialTheme.colorScheme.error
                                        else if (subStatus.activeCaptainsCount >= subStatus.maxCaptains) Color(0xFFE65100)
                                        else Color(0xFF2E7D32),
                                        shape = RoundedCornerShape(20.dp)
                                    ) {
                                        Text(
                                            text = if (subStatus.isExpired) "منتهية"
                                            else "${subStatus.activeCaptainsCount} / ${subStatus.maxCaptains} كابتن",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Color.White,
                                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                        )
                                    }
                                }

                                Spacer(modifier = Modifier.height(10.dp))
                                val progressRatio = if (subStatus.maxCaptains > 0) {
                                    (subStatus.activeCaptainsCount.toFloat() / subStatus.maxCaptains.toFloat()).coerceIn(0f, 1f)
                                } else 0f

                                LinearProgressIndicator(
                                    progress = { progressRatio },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(6.dp)
                                        .clip(RoundedCornerShape(3.dp)),
                                    color = if (progressRatio >= 1f) MaterialTheme.colorScheme.error else Primary,
                                    trackColor = MaterialTheme.colorScheme.outlineVariant
                                )

                                Spacer(modifier = Modifier.height(6.dp))
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Text(
                                        text = "الكباتن النشطون حالياً: ${subStatus.activeCaptainsCount}",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Text(
                                        text = "المتاح: ${subStatus.availableCaptainSlots} كابتن",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Bold,
                                        color = if (subStatus.availableCaptainSlots > 0) Color(0xFF2E7D32) else MaterialTheme.colorScheme.error
                                    )
                                }
                            }
                        }
                    }

                    // 2. Driver Request Hero Button (زر طلب كابتن)
                    item {
                        DriverRequestHeroButton(
                            onRequestCaptain = onRequestCaptain
                        )
                        Spacer(modifier = Modifier.height(14.dp))
                    }

                    // 3. Organized Order Management Section
                    item {
                        OrderManagementSection(
                            orders = orders,
                            showTabs = false,
                            onAdvanceStatus = { id, status ->
                                orderViewModel.advanceOrderStatus(id, status)
                            },
                            onRateOrder = { id, rating ->
                                orderViewModel.setOrderRating(id, rating)
                            },
                            onEditOrder = onEditOrder,
                            onRequestNewOrder = onRequestCaptain,
                            onCancelOrder = { id ->
                                orderViewModel.cancelOrder(id)
                            }
                        )
                    }
                }
            }
        }
    }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.logout_confirm_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.logout_confirm_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showLogoutDialog = false
                        onLogout()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(
                        text = stringResource(R.string.menu_logout),
                        color = MaterialTheme.colorScheme.onError,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showLogoutDialog = false }) {
                    Text(text = stringResource(R.string.cancel_button))
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }
}

@Composable
fun SideMenuContent(
    profile: RestaurantProfile,
    onRestaurantClick: () -> Unit,
    onViewAllOrders: () -> Unit,
    onPlansClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onCloseMenu: () -> Unit,
    onCaptainDashboardClick: () -> Unit = {}
) {
    Column(
        modifier = Modifier
            .fillMaxHeight()
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface)
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Header of Side Menu
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(36.dp)
                            .clip(CircleShape)
                            .background(Primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "☰", style = MaterialTheme.typography.titleMedium, color = Primary)
                    }
                    Text(
                        text = stringResource(R.string.side_menu_title),
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                IconButton(onClick = onCloseMenu) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "إغلاق القائمة",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))

            // 1. 🏪 بيانات المطعم (Restaurant Data Card)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onRestaurantClick() },
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Primary.copy(alpha = 0.08f)
                ),
                border = BorderStroke(1.dp, Primary.copy(alpha = 0.3f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        // Restaurant Cover / Merchant Avatar
                        val merchantAvatar = profile.photoUrl ?: profile.imageUri ?: profile.logoUrl
                        Box(
                            modifier = Modifier
                                .size(56.dp)
                                .clip(CircleShape)
                                .background(MaterialTheme.colorScheme.surface)
                                .border(2.dp, Primary, CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            if (!merchantAvatar.isNullOrEmpty()) {
                                AsyncImage(
                                    model = merchantAvatar,
                                    contentDescription = profile.name,
                                    modifier = Modifier.fillMaxSize().clip(CircleShape),
                                    contentScale = ContentScale.Crop
                                )
                            } else {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = profile.name,
                                    tint = Primary,
                                    modifier = Modifier.size(32.dp)
                                )
                            }
                        }

                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(2.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "🏪",
                                    style = MaterialTheme.typography.bodySmall
                                )
                                Text(
                                    text = profile.name.ifBlank { "تاجر البطل" },
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            if (profile.phone.isNotBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Phone,
                                        contentDescription = null,
                                        tint = Primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = profile.phone,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                            val merchantEmail = profile.email.ifBlank { Firebase.auth.currentUser?.email.orEmpty() }
                            if (merchantEmail.isNotBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Email,
                                        contentDescription = null,
                                        tint = Primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = merchantEmail,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                            if (profile.address.isNotBlank()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    horizontalArrangement = Arrangement.spacedBy(4.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.LocationOn,
                                        contentDescription = null,
                                        tint = Primary,
                                        modifier = Modifier.size(14.dp)
                                    )
                                    Text(
                                        text = profile.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 1
                                    )
                                }
                            }
                        }
                    }

                    val isProfileIncomplete = profile.name.isBlank() || profile.phone.isBlank() || (profile.imageUri.isNullOrBlank() && profile.photoUrl.isNullOrBlank())
                    if (isProfileIncomplete) {
                        Surface(
                            color = Color(0xFFFFF3E0),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Color(0xFFFFB74D)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "⚠️ بيانات الحساب غير مكتملة - اضغط للإكمال",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFFE65100),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    } else {
                        Surface(
                            color = Primary.copy(alpha = 0.15f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = stringResource(R.string.edit_data_hint),
                                style = MaterialTheme.typography.labelSmall,
                                color = Primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }
            }

            // 2. 📦 سجل الطلبات (Order History)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onViewAllOrders() },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "📦", style = MaterialTheme.typography.titleMedium)
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.menu_order_history),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "عرض كافة الطلبات السابقة والحالية وحالة المندوب",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(text = "❮", fontWeight = FontWeight.Bold, color = Primary)
                }
            }

            // 3. 💳 الباقة الحالية (Current Subscription Plan)
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onPlansClick() },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                border = BorderStroke(1.dp, Primary.copy(alpha = 0.4f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Primary.copy(alpha = 0.2f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(text = "💳", style = MaterialTheme.typography.titleMedium)
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.menu_current_plan),
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Surface(
                                color = Primary,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "Pro ✨",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = stringResource(R.string.plan_expiration_date),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Text(text = "❮", fontWeight = FontWeight.Bold, color = Primary)
                }
            }

            // 4. 🌓 مظهر التطبيق (الوضع النهاري / الوضع الداكن)
            val sideMenuContext = androidx.compose.ui.platform.LocalContext.current
            val isDarkSideMenu by ThemeManager.isDarkModeState.collectAsStateWithLifecycle()
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { ThemeManager.toggleTheme(sideMenuContext) },
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                ),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(if (isDarkSideMenu) Primary.copy(alpha = 0.15f) else Color(0xFF3F51B5).copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = if (isDarkSideMenu) Icons.Filled.LightMode else Icons.Filled.DarkMode,
                            contentDescription = null,
                            tint = if (isDarkSideMenu) Primary else Color(0xFF3F51B5),
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = stringResource(R.string.app_display_mode),
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = if (isDarkSideMenu) stringResource(R.string.theme_dark_mode) else stringResource(R.string.theme_day_mode),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = !isDarkSideMenu,
                        onCheckedChange = { ThemeManager.toggleTheme(sideMenuContext) },
                        thumbContent = {
                            Icon(
                                imageVector = if (isDarkSideMenu) Icons.Filled.DarkMode else Icons.Filled.LightMode,
                                contentDescription = null,
                                modifier = Modifier.size(14.dp)
                            )
                        }
                    )
                }
            }
        }

        // Bottom Area: 🚪 تسجيل الخروج (Logout Button)
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 24.dp, bottom = 8.dp)
        ) {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(16.dp))

            Button(
                onClick = onLogoutClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer
                )
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = "🚪",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = stringResource(R.string.menu_logout),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
        }
    }
}

@Composable
fun RestaurantProfileCard(
    profile: RestaurantProfile,
    onClick: () -> Unit
) {
    val displayName = profile.name.ifBlank { Firebase.auth.currentUser?.email?.substringBefore("@") ?: "مستخدم جديد" }
    val displayAddress = profile.address.ifBlank { "العنوان غير محدد" }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(60.dp)
                    .clip(CircleShape)
                    .background(Primary.copy(alpha = 0.2f))
                    .border(2.dp, Primary, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                if (!profile.imageUri.isNullOrBlank()) {
                    AsyncImage(
                        model = profile.imageUri,
                        contentDescription = "Logo",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(CircleShape),
                        contentScale = ContentScale.Crop
                    )
                } else {
                    Icon(
                        imageVector = Icons.Default.Person,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(32.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "مرحبا",
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                    color = Primary
                )

                Spacer(modifier = Modifier.height(2.dp))

                Text(
                    text = displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = displayAddress,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
fun RestaurantDetailsScreen(
    onBack: () -> Unit,
    orderViewModel: OrderViewModel = viewModel()
) {
    val currentProfile by orderViewModel.restaurantProfile.collectAsStateWithLifecycle()
    val isSaving by orderViewModel.isSaving.collectAsStateWithLifecycle()
    val isSaveSuccess by orderViewModel.isSaveSuccess.collectAsStateWithLifecycle()
    val errorMessage by orderViewModel.errorMessage.collectAsStateWithLifecycle()
    val isMigratingImage by orderViewModel.isMigratingImage.collectAsStateWithLifecycle()
    val migrationRecord by orderViewModel.migrationRecordState.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current

    var restaurantName by remember(currentProfile) { mutableStateOf(currentProfile.name) }
    var phoneNumber by remember(currentProfile) { mutableStateOf(currentProfile.phone) }
    var address by remember(currentProfile) { mutableStateOf(currentProfile.address) }
    var imageUri by remember(currentProfile) { mutableStateOf(currentProfile.imageUri) }
    var selectedLat by remember(currentProfile) { mutableStateOf(currentProfile.latitude) }
    var selectedLng by remember(currentProfile) { mutableStateOf(currentProfile.longitude) }

    // الاستماع لحالة نجاح الحفظ لإظهار التنبيه والعودة للشاشة السابقة
    LaunchedEffect(isSaveSuccess) {
        if (isSaveSuccess) {
            Toast.makeText(
                context,
                "تم حفظ بيانات المطعم بنجاح",
                Toast.LENGTH_SHORT
            ).show()
            orderViewModel.resetSaveRestaurantState()
            onBack()
        }
    }

    // الاستماع لحالة الخطأ لإظهار التنبيه للمستخدم
    LaunchedEffect(errorMessage) {
        errorMessage?.let { error ->
            Toast.makeText(
                context,
                error,
                Toast.LENGTH_LONG
            ).show()
        }
    }

    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri: Uri? ->
        uri?.let {
            imageUri = it.toString()
        }
    }

    val isMissingData = restaurantName.isBlank() || phoneNumber.isBlank() || imageUri.isNullOrBlank()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ElBatalHeader(
            title = "الملف الشخصي وبيانات التاجر",
            onBackClick = onBack
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // تنبيه استكمال البيانات في حال وجود نقص
            if (isMissingData) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                    border = BorderStroke(1.dp, Color(0xFFFF9800)),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Warning, contentDescription = null, tint = Color(0xFFE65100))
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = "بيانات حساب التاجر غير مكتملة",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFE65100)
                            )
                            Text(
                                text = "يرجى استكمال البيانات الناقصة (الاسم، رقم الهاتف، أو الصورة الشخصية) والضغط على حفظ التعديلات.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFB73D00)
                            )
                        }
                    }
                }
            }

            // Description Banner
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = Primary.copy(alpha = 0.08f)
                ),
                shape = RoundedCornerShape(16.dp)
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Column {
                        Text(
                            text = "بيانات حساب التاجر والملف الشخصي",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Text(
                            text = "يمكنك تعديل الاسم الكامل ورقم الهاتف وصورة الملف الشخصي وحفظها مباشرة",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // 1. Restaurant Image / Logo Container (صورة المطعم / التاجر)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "صورة الملف الشخصي / لوجو المتجر",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.align(Alignment.Start)
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Box(
                        modifier = Modifier
                            .size(110.dp)
                            .clip(CircleShape)
                            .background(Primary.copy(alpha = 0.15f))
                            .border(3.dp, Primary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!imageUri.isNullOrBlank()) {
                            AsyncImage(
                                model = imageUri,
                                contentDescription = "Restaurant Image",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(54.dp)
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    if (FirebaseStorageManager.isFirebaseStorageUrl(imageUri)) {
                        Surface(
                            color = Color(0xFFE8F5E9),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color(0xFF4CAF50).copy(alpha = 0.5f))
                        ) {
                            Text(
                                text = "✓ مخزنة بأمان على Firebase Storage الرسمي",
                                style = MaterialTheme.typography.labelSmall,
                                color = Color(0xFF2E7D32),
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                            )
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    } else if (FirebaseStorageManager.isLegacyExternalUrl(imageUri)) {
                        Surface(
                            color = Color(0xFFFFF3E0),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color(0xFFFF9800).copy(alpha = 0.6f))
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(12.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "صورة حالية من رابط خارجي قديم (معروضة بالتوافق الكامل)",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFFE65100),
                                    fontWeight = FontWeight.Bold,
                                    textAlign = TextAlign.Center
                                )
                                Spacer(modifier = Modifier.height(6.dp))
                                OutlinedButton(
                                    onClick = { orderViewModel.migrateCurrentProfileImageToFirebaseStorage() },
                                    enabled = !isMigratingImage,
                                    shape = RoundedCornerShape(8.dp),
                                    border = BorderStroke(1.dp, Primary),
                                    modifier = Modifier.testTag("migrate_legacy_image_button")
                                ) {
                                    if (isMigratingImage) {
                                        CircularProgressIndicator(
                                            modifier = Modifier.size(16.dp),
                                            color = Primary,
                                            strokeWidth = 2.dp
                                        )
                                        Spacer(modifier = Modifier.width(6.dp))
                                        Text(
                                            text = "جاري ترحيل الصورة بأمان...",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = Primary
                                        )
                                    } else {
                                        Text(
                                            text = "ترحيل الصورة الحالية إلى Firebase Storage (اختياري)",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Primary
                                        )
                                    }
                                }
                                if (migrationRecord?.status == MigrationStepStatus.FAILED) {
                                    Spacer(modifier = Modifier.height(6.dp))
                                    Text(
                                        text = migrationRecord?.errorMessage ?: "تعذر الترحيل، تم الاحتفاظ بالصورة القديمة",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.error,
                                        textAlign = TextAlign.Center
                                    )
                                    TextButton(
                                        onClick = { orderViewModel.retryFailedImageMigration() },
                                        modifier = Modifier.testTag("retry_migration_button")
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.Refresh,
                                            contentDescription = null,
                                            tint = Primary,
                                            modifier = Modifier.size(15.dp)
                                        )
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text(
                                            text = "إعادة محاولة الترحيل (محاولة #${migrationRecord?.attemptCount ?: 1})",
                                            style = MaterialTheme.typography.labelSmall,
                                            fontWeight = FontWeight.Bold,
                                            color = Primary
                                        )
                                    }
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(10.dp))
                    }

                    Button(
                        onClick = {
                            photoPickerLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                            )
                        },
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Edit,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "تغيير الصورة الشخصية",
                            color = Color.White,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // 2. Restaurant Editable Information Fields (Cards / Containers)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(20.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Restaurant Name (الاسم الكامل / اسم المطعم)
                    OutlinedTextField(
                        value = restaurantName,
                        onValueChange = { restaurantName = it },
                        label = { Text("الاسم الكامل / اسم المطعم") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Home,
                                contentDescription = null,
                                tint = Primary
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    // Phone Number (رقم الهاتف)
                    OutlinedTextField(
                        value = phoneNumber,
                        onValueChange = { phoneNumber = it },
                        label = { Text(stringResource(R.string.restaurant_phone_label)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = null,
                                tint = Primary
                            )
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    // Email Field (البريد الإلكتروني المعتمد - للعرض)
                    val merchantEmail = currentProfile.email.ifBlank { Firebase.auth.currentUser?.email.orEmpty() }
                    OutlinedTextField(
                        value = merchantEmail,
                        onValueChange = {},
                        readOnly = true,
                        label = { Text("البريد الإلكتروني للحساب") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Email,
                                contentDescription = null,
                                tint = Primary
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f),
                            unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.25f)
                        )
                    )

                    // Address (العنوان)
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text(stringResource(R.string.restaurant_address_label)) },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = Primary
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        minLines = 2,
                        maxLines = 4
                    )

                    Spacer(modifier = Modifier.height(14.dp))

                    // موقع المطعم على الخريطة (GPS وتحديد يدوي)
                    Text(
                        text = "موقع المطعم على الخريطة (GPS وتحديد يدوي):",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "اضغط على زر GPS لتحديد موقعك الحالي، أو اسحب الخريطة لتحديده يدوياً",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    OpenStreetMapLocationPicker(
                        initialLatitude = selectedLat,
                        initialLongitude = selectedLng,
                        onLocationSelected = { lat, lng ->
                            selectedLat = lat
                            selectedLng = lng
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 3. Save Button (حفظ التعديلات)
            Button(
                onClick = {
                    if (isSaving) return@Button
                    if (!com.example.network.NetworkHelper.isNetworkAvailable(context)) {
                        com.example.network.NetworkHelper.notifyNoInternet(context, "حفظ وتعديل بيانات المطعم")
                        return@Button
                    }
                    orderViewModel.saveRestaurantProfile(
                        name = restaurantName,
                        phone = phoneNumber,
                        address = address,
                        imageUri = imageUri,
                        latitude = selectedLat,
                        longitude = selectedLng
                    )
                },
                enabled = !isSaving,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(54.dp)
                    .testTag("save_restaurant_button"),
                shape = RoundedCornerShape(16.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary),
                elevation = ButtonDefaults.buttonElevation(defaultElevation = 4.dp)
            ) {
                if (isSaving) {
                    CircularProgressIndicator(
                        modifier = Modifier
                            .size(24.dp)
                            .testTag("save_restaurant_progress"),
                        color = Color.White,
                        strokeWidth = 2.5.dp
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "جاري الحفظ...",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                } else {
                    Text(
                        text = stringResource(R.string.save_changes_btn),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }
        }
    }
}

@Composable
fun DriverRequestHeroButton(
    onRequestCaptain: () -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onRequestCaptain() },
        colors = CardDefaults.cardColors(containerColor = Primary),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = stringResource(R.string.request_captain_btn),
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.ExtraBold,
                    color = Color.White
                )

                Spacer(modifier = Modifier.height(4.dp))

                Text(
                    text = stringResource(R.string.request_captain_subtitle),
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White.copy(alpha = 0.9f),
                    fontWeight = FontWeight.Medium
                )
            }

            Spacer(modifier = Modifier.width(12.dp))

            Box(
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.White.copy(alpha = 0.2f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.Add,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(32.dp)
                )
            }
        }
    }
}

@Composable
fun CaptainInfoSection(
    captainName: String,
    captainPhone: String,
    captainPhotoUrl: String?,
    estimatedArrival: String = "",
    showCallButton: Boolean = true,
    isCancelled: Boolean = false,
    isCompleted: Boolean = false,
    restaurantLat: Double = 30.0444,
    restaurantLng: Double = 31.2357,
    captainLat: Double? = null,
    captainLng: Double? = null,
    showLiveMap: Boolean = true
) {
    val context = LocalContext.current
    val isAssigned = captainName.isNotBlank() &&
            captainName != "في انتظار كابتن" &&
            captainName != "في انتظار قبول كابتن"

    if (isCancelled) {
        // حالة الطلب ملغي: إيقاف البحث عن كابتن تماماً
        Surface(
            color = MaterialTheme.colorScheme.error.copy(alpha = 0.08f),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.error.copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "الطلب ملغي",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "تم إيقاف البحث عن كابتن لأن هذا الطلب تم إلغاؤه",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    } else if (isCompleted) {
        // حالة الطلب مكتمل: تم التسليم بنجاح
        Surface(
            color = Color(0xFF2E7D32).copy(alpha = 0.08f),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Color(0xFF2E7D32).copy(alpha = 0.35f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF2E7D32).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(24.dp)
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "تم تسليم الطلب بنجاح",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32)
                    )
                    if (isAssigned) {
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "الكابتن: $captainName",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    } else if (!isAssigned) {
        // الحالة الأولى: جاري البحث عن كابتن
        val infiniteTransition = rememberInfiniteTransition(label = "pulseTransition")
        val pulseScale by infiniteTransition.animateFloat(
            initialValue = 0.92f,
            targetValue = 1.08f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseScale"
        )
        val pulseAlpha by infiniteTransition.animateFloat(
            initialValue = 0.45f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1000, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            ),
            label = "pulseAlpha"
        )

        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Primary.copy(alpha = 0.45f * pulseAlpha)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Pulsating radar / bike icon
                    Box(
                        modifier = Modifier
                            .size(46.dp)
                            .scale(pulseScale)
                            .clip(CircleShape)
                            .background(Primary.copy(alpha = 0.15f * pulseAlpha))
                            .border(1.5.dp, Primary.copy(alpha = 0.8f * pulseAlpha), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.DirectionsBike,
                            contentDescription = stringResource(R.string.searching_captain_status),
                            tint = Primary,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = stringResource(R.string.searching_captain_status),
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = Primary
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            CircularProgressIndicator(
                                strokeWidth = 2.dp,
                                color = Primary,
                                modifier = Modifier.size(12.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(3.dp))

                        Text(
                            text = stringResource(R.string.searching_captain_desc),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    } else {
        // الحالة الثانية: حينما الكابتن يستقبل الطلب علي التطبيق الثاني
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Primary.copy(alpha = 0.5f)),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp)
            ) {
                // Header Row
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "🛵 " + stringResource(R.string.captain_info_title),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Primary
                    )

                    Surface(
                        color = Color(0xFF2E7D32).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(6.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF4CAF50))
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.captain_accepted_status),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF4CAF50)
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Captain Row: Photo, Name & Phone
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Captain Avatar (صورة المندوب)
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(CircleShape)
                            .background(Primary.copy(alpha = 0.2f))
                            .border(2.dp, Primary, CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        if (!captainPhotoUrl.isNullOrBlank()) {
                            AsyncImage(
                                model = captainPhotoUrl,
                                contentDescription = "صورة المندوب",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop
                            )
                        } else {
                            Icon(
                                imageVector = Icons.Default.Person,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(30.dp)
                            )
                        }

                        // Active online status badge
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .align(Alignment.BottomEnd)
                                .clip(CircleShape)
                                .background(Color(0xFF4CAF50))
                                .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Captain Name & Phone
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = captainName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        val phoneToDisplay = captainPhone.ifBlank { "01098765432" }
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.clickable {
                                try {
                                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneToDisplay"))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            }
                        ) {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = phoneToDisplay,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (showCallButton) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                try {
                                    val phoneToCall = captainPhone.ifBlank { "01098765432" }
                                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneToCall"))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.call_action),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // ويصلك في غضون المدة المحددة بين مكان المطعم والكابتن (Estimated Arrival Duration)
                val etaText = estimatedArrival.ifBlank { "10 - 15 دقيقة" }
                Surface(
                    color = Primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Primary.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(30.dp)
                                    .clip(CircleShape)
                                    .background(Primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = stringResource(R.string.captain_arrival_eta_label),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = stringResource(R.string.captain_arrival_eta_format, etaText),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Primary
                                )
                            }
                        }

                        Surface(
                            color = Primary.copy(alpha = 0.2f),
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.captain_route_distance_note),
                                style = MaterialTheme.typography.labelSmall,
                                color = Primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                            )
                        }
                    }
                }

                if (showLiveMap && isAssigned && !isCancelled && !isCompleted) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.DirectionsBike,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "خريطة تتبع الكابتن المباشر (OpenStreetMap)",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Primary
                            )
                        }
                        Surface(
                            color = Color(0xFF00E676).copy(alpha = 0.15f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(6.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF00E676))
                                )
                                Spacer(modifier = Modifier.width(4.dp))
                                Text(
                                    text = "مباشر",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Color(0xFF00E676),
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    OpenStreetMapCaptainTracker(
                        restaurantLat = restaurantLat,
                        restaurantLng = restaurantLng,
                        captainLat = captainLat ?: (restaurantLat + 0.007),
                        captainLng = captainLng ?: (restaurantLng + 0.005),
                        captainName = captainName,
                        estimatedArrival = etaText,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(190.dp)
                            .clip(RoundedCornerShape(12.dp))
                    )
                }
            }
        }
    }
}

@Composable
fun RestaurantOrderHistoryCard(
    order: DeliveryOrderEntity,
    onEditOrder: ((Int) -> Unit)? = null,
    onRateOrder: ((Int) -> Unit)? = null,
    orderViewModel: OrderViewModel = viewModel()
) {
    val isInProgress = isOrderInProgress(order.status)
    val isCancelled = isOrderCancelled(order.status)
    val isCompleted = isOrderCompleted(order.status)

    val (statusLabel, badgeColor, badgeTextColor) = when {
        isCancelled -> Triple(stringResource(R.string.status_cancelled), Color(0xFFFFEBEE), Color(0xFFC62828))
        isCompleted -> Triple(stringResource(R.string.status_completed), Color(0xE8E8F5E9), Color(0xFF2E7D32))
        else -> {
            val label = when (order.status.trim()) {
                "جاري البحث عن كابتن", "في انتظار كابتن", "قيد التحضير", "pending", "جاري البحث" -> "جاري البحث"
                "الكابتن في الطريق لك", "في الطريق لك", "accepted", "في الطريق إلى المطعم" -> "في الطريق لك"
                "جاري التوصيل", "delivering", "out_for_delivery" -> "جاري التوصيل"
                else -> if (order.status.isNotBlank()) order.status else stringResource(R.string.status_in_progress)
            }
            Triple(label, Color(0xFFFFF3E0), Color(0xFFE65100))
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            // Part 1: Order Info
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(R.string.order_number_format, order.id),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )

                Surface(
                    color = badgeColor,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = badgeTextColor,
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp)
                    )
                }
            }

            if (order.orderDetails.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "تفاصيل الشحنة: ${order.orderDetails}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            val isCaptainAssigned = order.captainName.isNotBlank() &&
                order.captainName != "في انتظار كابتن" &&
                order.captainName != "في انتظار قبول كابتن"
            val isCaptainAccepted = isCaptainAssigned || order.status.trim() in listOf(
                "الكابتن في الطريق لك", "في الطريق لك", "accepted", "في الطريق إلى المطعم",
                "جاري التوصيل", "delivering", "out_for_delivery"
            )

            if (isInProgress && !isCaptainAccepted && onEditOrder != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { onEditOrder(order.id) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(40.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "👉 " + stringResource(R.string.edit_order_btn),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(12.dp))

            val trackedCaptain by orderViewModel.trackedCaptainInfo.collectAsStateWithLifecycle()
            val restaurantProfile by orderViewModel.restaurantProfile.collectAsStateWithLifecycle()
            val restLat = restaurantProfile.latitude ?: 30.0444
            val restLng = restaurantProfile.longitude ?: 31.2357

            LaunchedEffect(order.id, isCaptainAssigned) {
                if (isCaptainAssigned && isInProgress) {
                    orderViewModel.startTrackingOrderCaptain(order.id.toString())
                }
            }

            // Part 2: Captain Info
            CaptainInfoSection(
                captainName = order.captainName,
                captainPhone = order.captainPhone,
                captainPhotoUrl = order.captainPhotoUrl,
                estimatedArrival = order.estimatedArrival,
                isCancelled = isCancelled,
                isCompleted = isCompleted,
                restaurantLat = restLat,
                restaurantLng = restLng,
                captainLat = trackedCaptain?.latitude,
                captainLng = trackedCaptain?.longitude
            )

            // Star Rating Feature when Delivered
            if (isCompleted) {
                Spacer(modifier = Modifier.height(12.dp))
                OrderRatingSection(
                    rating = order.rating,
                    onRateOrder = { rating -> onRateOrder?.invoke(rating) }
                )
            }
        }
    }
}

@Composable
fun OrderCourierScreen(
    onBack: () -> Unit = {},
    onOrderSubmitted: () -> Unit = {},
    orderViewModel: OrderViewModel = viewModel()
) {
    var deliveryAddress by remember { mutableStateOf("") }
    var deliveryFee by remember { mutableStateOf("") }
    var customerName by remember { mutableStateOf("") }
    var customerPhone by remember { mutableStateOf("") }
    var orderDetails by remember { mutableStateOf("") }
    var deliveryNotes by remember { mutableStateOf("") }
    var customerLat by remember { mutableStateOf<Double?>(null) }
    var customerLng by remember { mutableStateOf<Double?>(null) }
    var showMapPicker by remember { mutableStateOf(false) }
    var showSummaryScreen by remember { mutableStateOf(false) }
    var createdOrderCodeModal by remember { mutableStateOf<String?>(null) }
    var isSubmittingOrder by remember { mutableStateOf(false) }
    val clipboardManager = LocalClipboardManager.current

    val subscriptionStatus by orderViewModel.subscriptionStatus.collectAsStateWithLifecycle()
    val isLimitReached = subscriptionStatus.activeCaptainsCount >= subscriptionStatus.maxCaptains && subscriptionStatus.maxCaptains > 0
    val isExpired = subscriptionStatus.isExpired

    val context = androidx.compose.ui.platform.LocalContext.current

    if (createdOrderCodeModal != null) {
        val code = createdOrderCodeModal ?: ""
        AlertDialog(
            onDismissRequest = {
                createdOrderCodeModal = null
                onOrderSubmitted()
            },
            icon = {
                Box(
                    modifier = Modifier
                        .size(60.dp)
                        .clip(CircleShape)
                        .background(Color(0xFFE8F5E9)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = Color(0xFF2E7D32),
                        modifier = Modifier.size(38.dp)
                    )
                }
            },
            title = {
                Text(
                    text = "تم إنشاء طلب التوصيل بنجاح",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "كود الطلب المعتمد والمكون من 6 أرقام فريدة:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    Surface(
                        color = Primary.copy(alpha = 0.08f),
                        shape = RoundedCornerShape(16.dp),
                        border = BorderStroke(2.dp, Primary)
                    ) {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 16.dp, horizontal = 24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "كود الطلب الفريد",
                                style = MaterialTheme.typography.labelMedium,
                                color = Primary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = code,
                                style = MaterialTheme.typography.headlineLarge,
                                fontWeight = FontWeight.Black,
                                color = Primary,
                                letterSpacing = 6.sp
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    OutlinedButton(
                        onClick = {
                            clipboardManager.setText(AnnotatedString(code))
                            Toast.makeText(context, "تم نسخ كود الطلب: $code", Toast.LENGTH_SHORT).show()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(44.dp),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Primary)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ContentCopy,
                            contentDescription = "نسخ",
                            tint = Primary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "نسخ كود الطلب بنقرة واحدة",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = Primary
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        createdOrderCodeModal = null
                        onOrderSubmitted()
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "متابعة الطلب في القائمة",
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            },
            shape = RoundedCornerShape(20.dp)
        )
    }

    if (showSummaryScreen) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            ElBatalHeader(
                title = stringResource(R.string.summary_page_title),
                onBackClick = { showSummaryScreen = false }
            )

            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(20.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(24.dp)
                            .verticalScroll(rememberScrollState()),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Primary.copy(alpha = 0.2f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(36.dp)
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = stringResource(R.string.summary_page_title),
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(16.dp))
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        Spacer(modifier = Modifier.height(16.dp))

                        // 1. عنوان التوصيل
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = "عنوان التوصيل",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                color = Primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = deliveryAddress.ifBlank { "غير محدد" },
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Medium
                            )
                            if (customerLat != null && customerLng != null) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "📍 إحداثيات GPS: $customerLat, $customerLng",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(14.dp))

                        // 2. بيانات العميل
                        if (customerName.isNotBlank() || customerPhone.isNotBlank()) {
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = "بيانات العميل المستلم",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Primary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                if (customerName.isNotBlank()) {
                                    Text(
                                        text = "الاسم: $customerName",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                                if (customerPhone.isNotBlank()) {
                                    Text(
                                        text = "الهاتف: $customerPhone",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.height(14.dp))
                        }

                        // 3. خدمة التوصيل
                        Column(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalAlignment = Alignment.Start
                        ) {
                            Text(
                                text = "خدمة التوصيل",
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium,
                                color = Primary
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            val formattedPrice = if (deliveryFee.isBlank()) "30 جنيه" else if (deliveryFee.contains("جنيه")) deliveryFee else "$deliveryFee جنيه"
                            Text(
                                text = formattedPrice,
                                style = MaterialTheme.typography.titleLarge,
                                color = MaterialTheme.colorScheme.onSurface,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (orderDetails.isNotBlank()) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = "تفاصيل الطلب",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Primary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = orderDetails,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        if (deliveryNotes.isNotBlank()) {
                            Spacer(modifier = Modifier.height(14.dp))
                            Column(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalAlignment = Alignment.Start
                            ) {
                                Text(
                                    text = "ملاحظات التوصيل",
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Primary
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = deliveryNotes,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(28.dp))

                        Button(
                            onClick = {
                                if (isSubmittingOrder) return@Button
                                if (!com.example.network.NetworkHelper.isNetworkAvailable(context)) {
                                    com.example.network.NetworkHelper.notifyNoInternet(context, "إجراء طلب كابتن جديد")
                                    return@Button
                                }
                                isSubmittingOrder = true
                                orderViewModel.addOrder(
                                    destinationAddress = deliveryAddress.trim(),
                                    serviceFee = deliveryFee.trim(),
                                    customerName = customerName.trim(),
                                    customerPhone = customerPhone.trim(),
                                    orderDetails = orderDetails.trim(),
                                    deliveryNotes = deliveryNotes.trim(),
                                    customerLatitude = customerLat,
                                    customerLongitude = customerLng,
                                    onSuccess = { generatedCode ->
                                        isSubmittingOrder = false
                                        createdOrderCodeModal = generatedCode
                                    },
                                    onError = { err ->
                                        isSubmittingOrder = false
                                        android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show()
                                    }
                                )
                            },
                            enabled = !isSubmittingOrder,
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(50.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            if (isSubmittingOrder) {
                                CircularProgressIndicator(
                                    modifier = Modifier.size(22.dp),
                                    color = Color.White,
                                    strokeWidth = 2.dp
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "جاري حجز الكود وإنشاء الطلب...",
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            } else {
                                Text(
                                    text = stringResource(R.string.confirm_order_button),
                                    color = Color.White,
                                    fontWeight = FontWeight.Bold,
                                    style = MaterialTheme.typography.bodyLarge
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        OutlinedButton(
                            onClick = { showSummaryScreen = false },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp),
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outline)
                        ) {
                            Text(
                                text = stringResource(R.string.edit_order_button),
                                style = MaterialTheme.typography.bodyMedium
                            )
                        }
                    }
                }
            }
        }
    } else {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.background)
        ) {
            ElBatalHeader(
                title = "طلب مندوب جديد",
                onBackClick = onBack
            )

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                // Subscription capacity card
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp),
                    shape = RoundedCornerShape(14.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isLimitReached || isExpired) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                        else Primary.copy(alpha = 0.08f)
                    ),
                    border = BorderStroke(1.dp, if (isLimitReached || isExpired) MaterialTheme.colorScheme.error else Primary.copy(alpha = 0.4f))
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        Text(
                            text = if (isLimitReached || isExpired) "⚠️" else "🛵",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "الباقة: ${subscriptionStatus.planName}",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = "الكباتن النشطون: ${subscriptionStatus.activeCaptainsCount} من أصل ${subscriptionStatus.maxCaptains} كابتن مسموح",
                                style = MaterialTheme.typography.bodySmall,
                                color = if (isLimitReached) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    shape = RoundedCornerShape(16.dp),
                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp)) {
                        Text(
                            text = "بيانات الطلب",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = Primary
                        )
                        Spacer(modifier = Modifier.height(16.dp))

                        // 1. عنوان التوصيل
                        OutlinedTextField(
                            value = deliveryAddress,
                            onValueChange = { deliveryAddress = it },
                            label = { Text("عنوان التوصيل") },
                            placeholder = { Text("مثال: شارع الجمهورية - عمارة 5 - شقة 3") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.LocationOn,
                                    contentDescription = null,
                                    tint = Primary
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        // GPS Location Picker Toggle Button
                        OutlinedButton(
                            onClick = { showMapPicker = !showMapPicker },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Primary.copy(alpha = 0.6f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (customerLat != null) "تم تحديد موقع GPS للعميل ✓ (تعديل)" else "تحديد موقع العميل على الخريطة (GPS)",
                                style = MaterialTheme.typography.labelMedium,
                                color = Primary,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (showMapPicker) {
                            Spacer(modifier = Modifier.height(8.dp))
                            com.example.ui.components.OpenStreetMapLocationPicker(
                                initialLatitude = customerLat ?: 30.0444,
                                initialLongitude = customerLng ?: 31.2357,
                                onLocationSelected = { lat, lng ->
                                    customerLat = lat
                                    customerLng = lng
                                    if (deliveryAddress.isBlank()) {
                                        deliveryAddress = "موقع محدد عبر الخريطة ($lat, $lng)"
                                    }
                                }
                            )
                        }

                        Spacer(modifier = Modifier.height(16.dp))

                        // 2. اسم العميل
                        OutlinedTextField(
                            value = customerName,
                            onValueChange = { customerName = it },
                            label = { Text(stringResource(R.string.customer_name_hint)) },
                            placeholder = { Text("اسم العميل المستلم") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Person,
                                    contentDescription = null,
                                    tint = Primary
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // 3. رقم هاتف العميل
                        OutlinedTextField(
                            value = customerPhone,
                            onValueChange = { customerPhone = it },
                            label = { Text(stringResource(R.string.customer_phone_hint)) },
                            placeholder = { Text("مثال: 01012345678") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Phone,
                                    contentDescription = null,
                                    tint = Primary
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // 4. خدمة التوصيل
                        OutlinedTextField(
                            value = deliveryFee,
                            onValueChange = { deliveryFee = it },
                            label = { Text("خدمة التوصيل") },
                            placeholder = { Text("مثال: 30 جنيه") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = Primary
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // 5. تفاصيل الطلب
                        OutlinedTextField(
                            value = orderDetails,
                            onValueChange = { orderDetails = it },
                            label = { Text(stringResource(R.string.order_details_hint)) },
                            placeholder = { Text("مثال: 2 وجبة كرسبي + 1 كانز كولا") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Edit,
                                    contentDescription = null,
                                    tint = Primary
                                )
                            },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            maxLines = 3
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        // 6. ملاحظات التوصيل
                        OutlinedTextField(
                            value = deliveryNotes,
                            onValueChange = { deliveryNotes = it },
                            label = { Text(stringResource(R.string.customer_notes_label)) },
                            placeholder = { Text("ملاحظات خاصة للكابتن أو العميل") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(12.dp),
                            maxLines = 2
                        )

                        Spacer(modifier = Modifier.height(24.dp))

                        if (isLimitReached) {
                            Surface(
                                color = MaterialTheme.colorScheme.errorContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 12.dp)
                            ) {
                                Text(
                                    text = stringResource(R.string.captains_limit_reached_warning, subscriptionStatus.maxCaptains),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onErrorContainer,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(10.dp)
                                )
                            }
                        }

                        Button(
                            onClick = {
                                if (deliveryAddress.isBlank()) {
                                    android.widget.Toast.makeText(context, "برجاء إدخال عنوان التوصيل أولاً", android.widget.Toast.LENGTH_SHORT).show()
                                } else if (isLimitReached) {
                                    android.widget.Toast.makeText(context, "تم الوصول للحد الأقصى للكباتن النشطين في باقتك", android.widget.Toast.LENGTH_SHORT).show()
                                } else {
                                    showSummaryScreen = true
                                }
                            },
                            enabled = !isLimitReached && (!isExpired || subscriptionStatus.status != "expired"),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.request_courier_action),
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                style = MaterialTheme.typography.titleMedium
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onRequestCourier: () -> Unit = {},
    onViewOrdersHistory: () -> Unit = {},
    orderViewModel: OrderViewModel = viewModel()
) {
    val firestoreOrders by orderViewModel.firestoreOrders.collectAsStateWithLifecycle()
    val localOrders by orderViewModel.ordersState.collectAsStateWithLifecycle()
    val orders = if (firestoreOrders.isNotEmpty()) firestoreOrders else localOrders
    val isRefreshing by orderViewModel.isRefreshing.collectAsStateWithLifecycle()

    val activeOrders = orders.filter { it.status !in listOf("تم", "تم التسليم", "مكتمل", "مكتملة", "Delivered", "ملغي") && !isOrderCompleted(it.status) && !isOrderCancelled(it.status) }

    val newOrders = orders.count { it.status in listOf("جاري البحث", "جاري البحث عن كابتن", "قيد التحضير", "Preparing", "pending") }.toString()
    val inProgress = orders.count { it.status in listOf("في الطريق لك", "الكابتن في الطريق لك", "جاري التوصيل", "Out for Delivery", "قيد التوصيل") }.toString()
    val completed = orders.count { isOrderCompleted(it.status) }.toString()

    Column(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
        ElBatalHeader(title = stringResource(R.string.dashboard_title))
        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { orderViewModel.refreshRestaurantFeed() },
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = rememberPullToRefreshState(),
                    isRefreshing = isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = MaterialTheme.colorScheme.surface,
                    color = Primary
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp)
            ) {
            // Summary Cards
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                InfoCard(stringResource(R.string.new_orders_label), newOrders)
                InfoCard(stringResource(R.string.in_progress_label), inProgress)
                InfoCard(stringResource(R.string.completed_label), completed)
            }

            Spacer(modifier = Modifier.height(24.dp))

            // Active Orders Live Tracking Component Section
            if (activeOrders.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                    border = BorderStroke(1.5.dp, Primary.copy(alpha = 0.8f))
                ) {
                    Column(modifier = Modifier.padding(16.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Primary)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "تتبع الطلبات النشطة (مباشر)",
                                    style = MaterialTheme.typography.titleMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                            Surface(
                                color = Primary.copy(alpha = 0.15f),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Text(
                                    text = "${activeOrders.size} طلب نشط",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = Primary,
                                    fontWeight = FontWeight.Bold,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        activeOrders.forEachIndexed { index, activeOrder ->
                            if (index > 0) {
                                HorizontalDivider(
                                    modifier = Modifier.padding(vertical = 12.dp),
                                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                                )
                            }
                            OrderStatusTracker(
                                order = activeOrder,
                                onAdvanceStatus = {
                                    val nextStatus = when (activeOrder.status.trim()) {
                                        "جاري البحث", "جاري البحث عن كابتن", "في انتظار كابتن", "قيد التحضير", "Preparing", "pending" -> "في الطريق لك"
                                        "في الطريق لك", "الكابتن في الطريق لك", "accepted" -> "جاري التوصيل"
                                        "جاري التوصيل", "قيد التوصيل", "Out for Delivery", "delivering" -> "تم التسليم"
                                        else -> "تم التسليم"
                                    }
                                    orderViewModel.advanceOrderStatus(activeOrder.id, nextStatus)
                                },
                                onCancelOrder = {
                                    orderViewModel.cancelOrder(activeOrder.id)
                                }
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(20.dp))
            }

            Button(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                onClick = onRequestCourier,
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = Primary)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.order_courier_title), color = Color.White, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedButton(
                modifier = Modifier.fillMaxWidth().height(48.dp),
                onClick = onViewOrdersHistory,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, Primary)
            ) {
                Icon(Icons.Default.List, contentDescription = null, tint = Primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text(stringResource(R.string.order_history_button), color = Primary, fontWeight = FontWeight.Bold)
            }
        }
        }
    }
}

enum class OrderTab {
    TODAY,
    PREVIOUS
}

enum class OrderStatusFilter(val labelRes: Int) {
    ALL(R.string.filter_all),
    IN_PROGRESS(R.string.filter_in_progress),
    WAITING_CONFIRMATION(R.string.filter_waiting_confirmation),
    REJECTED_DATA(R.string.filter_rejected_data),
    COMPLETED(R.string.filter_completed),
    CANCELLED(R.string.filter_cancelled)
}

fun isOrderCancelled(status: String): Boolean {
    val s = status.trim().lowercase(Locale.ROOT)
    return s in listOf("ملغي", "ملغية", "تم الإلغاء", "تم الالغاء", "cancelled", "canceled") ||
           s.contains("ملغ") || s.contains("cancel")
}

fun isOrderCompleted(status: String): Boolean {
    val s = status.trim().lowercase(Locale.ROOT)
    return s in listOf("مكتملة", "مكتمل", "تم التسليم", "تم التوصيل", "تم", "completed", "delivered") ||
           s.contains("مكتمل") || s.contains("تسليم") || s.contains("توصيل بنجاح") || s.contains("deliver")
}

fun isOrderInProgress(status: String): Boolean {
    return !isOrderCancelled(status) && !isOrderCompleted(status)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderManagementSection(
    orders: List<DeliveryOrderEntity>,
    showTabs: Boolean = true,
    showAllFilter: Boolean = showTabs,
    onAdvanceStatus: ((Int, String) -> Unit)? = null,
    onRateOrder: ((Int, Int) -> Unit)? = null,
    onEditOrder: ((Int) -> Unit)? = null,
    onRequestNewOrder: (() -> Unit)? = null,
    onCancelOrder: ((Int) -> Unit)? = null
) {
    var activeTab by remember { mutableStateOf(OrderTab.TODAY) }
    var activeFilter by remember(showAllFilter) {
        mutableStateOf(if (showAllFilter) OrderStatusFilter.ALL else OrderStatusFilter.IN_PROGRESS)
    }

    var selectedDateString by remember { mutableStateOf("20 يوليو 2026") }
    var showDatePickerDialog by remember { mutableStateOf(false) }

    val todayDateString = remember {
        val sdf = SimpleDateFormat("dd MMMM yyyy", Locale("ar"))
        sdf.format(Date())
    }

    if (showDatePickerDialog) {
        val datePickerState = rememberDatePickerState()
        DatePickerDialog(
            onDismissRequest = { showDatePickerDialog = false },
            confirmButton = {
                TextButton(
                    onClick = {
                        datePickerState.selectedDateMillis?.let { millis ->
                            val sdf = SimpleDateFormat("dd MMMM yyyy", Locale("ar"))
                            selectedDateString = sdf.format(Date(millis))
                        }
                        showDatePickerDialog = false
                    }
                ) {
                    Text("موافق", fontWeight = FontWeight.Bold, color = Primary)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDatePickerDialog = false }) {
                    Text("إلغاء")
                }
            }
        ) {
            DatePicker(state = datePickerState)
        }
    }

    Column(modifier = Modifier.fillMaxWidth()) {
        // 📌 1. Top Buttons: Today's Orders vs Previous Orders (hidden on Home Screen)
        if (showTabs) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f),
                shape = RoundedCornerShape(18.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)),
                modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    // Button 1: طلبات اليوم
                    val isToday = activeTab == OrderTab.TODAY
                    Button(
                        onClick = { activeTab = OrderTab.TODAY },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isToday) Primary else Color.Transparent,
                            contentColor = if (isToday) Color.White else MaterialTheme.colorScheme.onSurface
                        ),
                        elevation = if (isToday) ButtonDefaults.buttonElevation(defaultElevation = 4.dp) else ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.tab_today_orders),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (isToday) FontWeight.ExtraBold else FontWeight.Medium
                        )
                    }

                    // Button 2: الطلبات السابقة
                    val isPrevious = activeTab == OrderTab.PREVIOUS
                    Button(
                        onClick = { activeTab = OrderTab.PREVIOUS },
                        modifier = Modifier
                            .weight(1f)
                            .height(48.dp),
                        shape = RoundedCornerShape(14.dp),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isPrevious) Primary else Color.Transparent,
                            contentColor = if (isPrevious) Color.White else MaterialTheme.colorScheme.onSurface
                        ),
                        elevation = if (isPrevious) ButtonDefaults.buttonElevation(defaultElevation = 4.dp) else ButtonDefaults.buttonElevation(defaultElevation = 0.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.tab_previous_orders),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = if (isPrevious) FontWeight.ExtraBold else FontWeight.Medium
                        )
                    }
                }
            }
        }

        // 📁 2. Previous Orders Date Picker Tool
        if (showTabs && activeTab == OrderTab.PREVIOUS) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
                border = BorderStroke(1.2.dp, Primary.copy(alpha = 0.6f))
            ) {
                Column(modifier = Modifier.padding(14.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(
                                text = "📅 " + stringResource(R.string.select_date_title),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = selectedDateString,
                                style = MaterialTheme.typography.titleMedium,
                                fontWeight = FontWeight.ExtraBold,
                                color = Primary
                            )
                        }

                        Button(
                            onClick = { showDatePickerDialog = true },
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                            shape = RoundedCornerShape(12.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            modifier = Modifier.height(40.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.select_date),
                                style = MaterialTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold,
                                color = Color.White
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    val dynamicDateList = remember {
                        val sdf = java.text.SimpleDateFormat("dd MMMM yyyy", java.util.Locale("ar"))
                        (0..4).map { daysAgo ->
                            val cal = java.util.Calendar.getInstance()
                            cal.add(java.util.Calendar.DAY_OF_YEAR, -daysAgo)
                            sdf.format(cal.time)
                        }
                    }

                    // Quick Date Chips
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        dynamicDateList.forEach { dateStr ->
                            val isSelected = selectedDateString == dateStr
                            FilterChip(
                                selected = isSelected,
                                onClick = { selectedDateString = dateStr },
                                label = {
                                    Text(
                                        text = dateStr,
                                        style = MaterialTheme.typography.bodySmall,
                                        fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                                    )
                                },
                                colors = FilterChipDefaults.filterChipColors(
                                    selectedContainerColor = Primary,
                                    selectedLabelColor = Color.White,
                                    containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                                ),
                                shape = RoundedCornerShape(10.dp)
                            )
                        }
                    }
                }
            }
        }

        // 💡 3. Status Filter Chips (جارية / مكتملة / ملغية)
        val displayedFilters = remember(showAllFilter) {
            if (showAllFilter) {
                OrderStatusFilter.entries
            } else {
                OrderStatusFilter.entries.filter { it != OrderStatusFilter.ALL }
            }
        }

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            displayedFilters.forEach { filter ->
                val isSelected = activeFilter == filter
                FilterChip(
                    selected = isSelected,
                    onClick = { activeFilter = filter },
                    label = {
                        Text(
                            text = stringResource(filter.labelRes),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Primary,
                        selectedLabelColor = Color.White,
                        containerColor = MaterialTheme.colorScheme.surface,
                        labelColor = MaterialTheme.colorScheme.onSurface
                    ),
                    shape = RoundedCornerShape(12.dp)
                )
            }
        }

        // 🔍 4. Filter Orders by Tab, Selected Date, and Status
        val tabFilteredOrders = remember(orders, activeTab, selectedDateString, todayDateString, showTabs) {
            if (!showTabs) {
                // Home Screen: Show all orders so active, completed, and cancelled filters work accurately
                orders
            } else if (activeTab == OrderTab.TODAY) {
                val matchingToday = orders.filter {
                    it.orderDate.contains(todayDateString) || isOrderInProgress(it.status)
                }
                if (matchingToday.isNotEmpty()) matchingToday else orders
            } else {
                orders.filter {
                    it.orderDate.contains(selectedDateString) ||
                    (!it.orderDate.contains(todayDateString) && (isOrderCompleted(it.status) || isOrderCancelled(it.status)))
                }
            }
        }

        val finalFilteredOrders = remember(tabFilteredOrders, activeFilter) {
            when (activeFilter) {
                OrderStatusFilter.ALL -> tabFilteredOrders
                OrderStatusFilter.IN_PROGRESS -> tabFilteredOrders.filter {
                    isOrderInProgress(it.status)
                }
                OrderStatusFilter.WAITING_CONFIRMATION -> tabFilteredOrders.filter {
                    it.status == "بانتظار تأكيد البيانات" || it.customerDetailsSubmitted && !it.customerDetailsConfirmed
                }
                OrderStatusFilter.REJECTED_DATA -> tabFilteredOrders.filter {
                    it.status == "بيانات مرفوضة" || it.customerDetailsRejected
                }
                OrderStatusFilter.COMPLETED -> tabFilteredOrders.filter {
                    isOrderCompleted(it.status)
                }
                OrderStatusFilter.CANCELLED -> tabFilteredOrders.filter {
                    isOrderCancelled(it.status)
                }
            }
        }

        // ⚡ 5. Empty State vs Card List Display
        if (finalFilteredOrders.isEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 12.dp),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(CircleShape)
                            .background(Primary.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = if (orders.isEmpty()) "📦" else "🔍",
                            style = MaterialTheme.typography.headlineLarge
                        )
                    }
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = if (orders.isEmpty()) "لا توجد رحلات أو طلبات حالياً" else if (activeTab == OrderTab.PREVIOUS) stringResource(R.string.no_orders_for_date) else "لا توجد طلبات مطابقة للفلتر المحدد",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = if (orders.isEmpty()) "لم يتم تسجيل أو إنشاء أي طلبات لهذا الحساب حتى الآن. يمكنك البدء بطلب كابتن توصيل جديد الآن." else if (activeTab == OrderTab.PREVIOUS) "لم يتم تسجيل أي طلبات في تاريخ $selectedDateString. يمكنك اختيار يوم آخر من أداة التواريخ أعلاه." else "لم يتم العثور على طلبات لهذا التصنيف. اضغط على أزرار الفلتر أو أضف طلباً جديداً.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center
                    )

                    if (onRequestNewOrder != null) {
                        Spacer(modifier = Modifier.height(20.dp))
                        Button(
                            onClick = onRequestNewOrder,
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                            shape = RoundedCornerShape(12.dp)
                        ) {
                            Text(
                                text = "+ " + stringResource(R.string.order_courier_title),
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        } else {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                finalFilteredOrders.forEach { order ->
                    OrganizedOrderCard(
                        order = order,
                        onAdvanceStatus = {
                            onAdvanceStatus?.invoke(order.id, order.status)
                        },
                        onRateOrder = { rating ->
                            onRateOrder?.invoke(order.id, rating)
                        },
                        onEditOrder = onEditOrder,
                        onCancelOrder = {
                            onCancelOrder?.invoke(order.id)
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun OrganizedOrderCard(
    order: DeliveryOrderEntity,
    onAdvanceStatus: (() -> Unit)? = null,
    onRateOrder: ((Int) -> Unit)? = null,
    onEditOrder: ((Int) -> Unit)? = null,
    onCancelOrder: (() -> Unit)? = null,
    orderViewModel: OrderViewModel = viewModel()
) {
    val context = LocalContext.current
    val isInProgress = isOrderInProgress(order.status)
    val isCancelled = isOrderCancelled(order.status)
    val isCompleted = isOrderCompleted(order.status)

    val (statusLabel, badgeColor, badgeTextColor) = when {
        isCancelled -> Triple(stringResource(R.string.status_cancelled), Color(0xFFFFEBEE), Color(0xFFC62828))
        isCompleted -> Triple(stringResource(R.string.status_completed), Color(0xE8E8F5E9), Color(0xFF2E7D32))
        else -> {
            val label = when (order.status.trim()) {
                "جاري البحث عن كابتن", "في انتظار كابتن", "قيد التحضير", "pending", "جاري البحث" -> "جاري البحث"
                "الكابتن في الطريق لك", "في الطريق لك", "accepted", "في الطريق إلى المطعم" -> "في الطريق لك"
                "جاري التوصيل", "delivering", "out_for_delivery" -> "جاري التوصيل"
                else -> if (order.status.isNotBlank()) order.status else stringResource(R.string.status_in_progress)
            }
            Triple(label, Color(0xFFFFF3E0), Color(0xFFE65100))
        }
    }

    var showReviewDialog by remember { mutableStateOf(false) }
    var showFullImageDialog by remember { mutableStateOf(false) }
    var rejectionReasonInput by remember { mutableStateOf("") }
    var showRejectPrompt by remember { mutableStateOf(false) }

    if (showFullImageDialog && order.receiptImageUrl.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { showFullImageDialog = false },
            confirmButton = {
                Button(onClick = { showFullImageDialog = false }) {
                    Text("إغلاق")
                }
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(380.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = order.receiptImageUrl,
                        contentDescription = "صورة الإيصال بالحجم الكامل",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(12.dp)),
                        contentScale = ContentScale.Fit
                    )
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }

    if (showReviewDialog) {
        AlertDialog(
            onDismissRequest = {
                showReviewDialog = false
                showRejectPrompt = false
            },
            title = {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "📋 مراجعة إيصال وبيانات العميل",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = Primary
                    )
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Customer Info
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Column(modifier = Modifier.padding(10.dp)) {
                            Text(
                                text = "👤 اسم العميل: ${order.customerName.ifBlank { "غير مسجل" }}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "📞 الهاتف: ${order.customerPhone.ifBlank { "غير مسجل" }}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = "📍 العنوان: ${order.destinationAddress.ifBlank { "غير محدد" }}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (order.customerLatitude != null && order.customerLongitude != null) {
                                Spacer(modifier = Modifier.height(2.dp))
                                Text(
                                    text = "🌐 GPS: ${order.customerLatitude}, ${order.customerLongitude}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Primary
                                )
                            }
                            if (order.deliveryNotes.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = "📝 ملاحظات: ${order.deliveryNotes}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    // Receipt Image
                    if (order.receiptImageUrl.isNotBlank()) {
                        Text(
                            text = "📸 إيصال الاستلام المحمل من الكابتن:",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold
                        )
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(180.dp)
                                .clip(RoundedCornerShape(10.dp))
                                .border(1.dp, Primary.copy(alpha = 0.5f), RoundedCornerShape(10.dp))
                                .clickable { showFullImageDialog = true },
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = order.receiptImageUrl,
                                contentDescription = "إيصال الطلب",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            Surface(
                                color = Color.Black.copy(alpha = 0.6f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "🔍 اضغط للتكبير",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "⚠️ لم يقم الكابتن برفع صورة الإيصال بعد",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(10.dp)
                            )
                        }
                    }

                    if (order.customerDetailsRejected && order.rejectionReason.isNotBlank()) {
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "❌ سبب الرفض السابق: ${order.rejectionReason}",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(8.dp)
                            )
                        }
                    }

                    if (showRejectPrompt) {
                        Spacer(modifier = Modifier.height(6.dp))
                        OutlinedTextField(
                            value = rejectionReasonInput,
                            onValueChange = { rejectionReasonInput = it },
                            label = { Text("سبب الرفض (إلزامي)") },
                            placeholder = { Text("مثال: الإيصال غير واضح أو المبلغ غير مطابق") },
                            modifier = Modifier.fillMaxWidth(),
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                if (!showRejectPrompt) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                orderViewModel.confirmCustomerDetails(
                                    orderId = order.firestoreOrderId.ifBlank { order.id.toString() },
                                    onSuccess = { showReviewDialog = false },
                                    onError = { err -> android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show() }
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("تأكيد وموافقة ✓", color = Color.White, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { showRejectPrompt = true },
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("رفض البيانات ✗", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                orderViewModel.rejectCustomerDetails(
                                    orderId = order.firestoreOrderId.ifBlank { order.id.toString() },
                                    reason = rejectionReasonInput,
                                    onSuccess = {
                                        showReviewDialog = false
                                        showRejectPrompt = false
                                    },
                                    onError = { err -> android.widget.Toast.makeText(context, err, android.widget.Toast.LENGTH_LONG).show() }
                                )
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("إرسال الرفض", color = Color.White, fontWeight = FontWeight.Bold)
                        }

                        OutlinedButton(
                            onClick = { showRejectPrompt = false },
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("رجوع")
                        }
                    }
                }
            },
            dismissButton = {
                if (!showRejectPrompt) {
                    TextButton(onClick = { showReviewDialog = false }) {
                        Text("إغلاق")
                    }
                }
            },
            shape = RoundedCornerShape(18.dp)
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            val clipboardManager = LocalClipboardManager.current

            // Order Code & Status Header
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (order.orderCode.isNotBlank()) {
                    Surface(
                        color = Primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Primary.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier
                                .clickable {
                                    clipboardManager.setText(AnnotatedString(order.orderCode))
                                    Toast.makeText(context, "تم نسخ كود الطلب: ${order.orderCode}", Toast.LENGTH_SHORT).show()
                                }
                                .padding(horizontal = 10.dp, vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "كود الطلب: #${order.orderCode}",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Primary
                            )
                            Icon(
                                imageVector = Icons.Default.ContentCopy,
                                contentDescription = "نسخ كود الطلب",
                                tint = Primary,
                                modifier = Modifier.size(15.dp)
                            )
                        }
                    }
                } else {
                    Text(
                        text = "طلب #${order.id}",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                Surface(
                    color = badgeColor,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        color = badgeTextColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                    )
                }
            }

            val isCaptainAssigned = order.captainName.isNotBlank() &&
                order.captainName != "في انتظار كابتن" &&
                order.captainName != "في انتظار قبول كابتن"

            val trackedCaptain by orderViewModel.trackedCaptainInfo.collectAsStateWithLifecycle()
            val restaurantProfile by orderViewModel.restaurantProfile.collectAsStateWithLifecycle()
            val restLat = restaurantProfile.latitude ?: 30.0444
            val restLng = restaurantProfile.longitude ?: 31.2357

            LaunchedEffect(order.id, isCaptainAssigned) {
                if (isCaptainAssigned && isInProgress) {
                    orderViewModel.startTrackingOrderCaptain(order.id.toString())
                }
            }

            // Captain Info Section: Shows searching status or Captain profile with ETA (or cancelled state)
            CaptainInfoSection(
                captainName = order.captainName,
                captainPhone = order.captainPhone,
                captainPhotoUrl = order.captainPhotoUrl,
                estimatedArrival = order.estimatedArrival,
                showCallButton = false,
                isCancelled = isCancelled,
                isCompleted = isCompleted,
                restaurantLat = restLat,
                restaurantLng = restLng,
                captainLat = trackedCaptain?.latitude,
                captainLng = trackedCaptain?.longitude
            )

            Spacer(modifier = Modifier.height(10.dp))

            val currentStageIndex = when (order.status.trim()) {
                "جاري البحث", "جاري البحث عن كابتن", "في انتظار كابتن", "قيد التحضير", "Preparing", "pending" -> 0
                "في الطريق لك", "الكابتن في الطريق لك", "accepted", "في الطريق إلى المطعم" -> 1
                "جاري التوصيل", "قيد التوصيل", "Out for Delivery", "delivering" -> 2
                "تم التسليم", "مكتمل", "مكتملة", "Delivered" -> 3
                else -> 0
            }
            val isCaptainAccepted = isCaptainAssigned || currentStageIndex > 0

            // Action Buttons
            if (isCancelled || isCompleted || isCaptainAssigned || (isInProgress && !isCaptainAccepted && onEditOrder != null)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if (isCancelled) {
                        OutlinedButton(
                            onClick = {},
                            enabled = false,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.4f)),
                            modifier = Modifier.fillMaxWidth().height(38.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "الطلب ملغي",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.error,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else if (isCompleted) {
                        OutlinedButton(
                            onClick = {},
                            enabled = false,
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Color(0xFF2E7D32).copy(alpha = 0.5f)),
                            modifier = Modifier.fillMaxWidth().height(38.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.CheckCircle,
                                contentDescription = null,
                                tint = Color(0xFF2E7D32),
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "تم تسليم الطلب بنجاح",
                                style = MaterialTheme.typography.labelMedium,
                                color = Color(0xFF2E7D32),
                                fontWeight = FontWeight.Bold
                            )
                        }
                    } else if (isCaptainAssigned) {
                        val captainPhoneToCall = order.captainPhone.ifBlank { "01098765432" }
                        OutlinedButton(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$captainPhoneToCall"))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            },
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, Primary),
                            modifier = Modifier.fillMaxWidth().height(38.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Default.Phone, contentDescription = null, tint = Primary, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.call_captain_btn), style = MaterialTheme.typography.labelMedium, color = Primary, fontWeight = FontWeight.Bold)
                        }
                    } else if (isInProgress && !isCaptainAccepted && onEditOrder != null) {
                        Button(
                            onClick = { onEditOrder(order.id) },
                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().height(38.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = Color.White, modifier = Modifier.size(14.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(stringResource(R.string.edit_order_btn), style = MaterialTheme.typography.labelMedium, color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            // Customer Receipt & Details Review Section (إذا قام الكابتن برفع الإيصال أو الحالة بانتظار التأكيد)
            val showReceiptPrompt = order.customerDetailsSubmitted ||
                order.receiptImageUrl.isNotBlank() ||
                order.status == "بانتظار تأكيد البيانات" ||
                order.status == "بيانات مرفوضة" ||
                order.customerDetailsRejected

            if (showReceiptPrompt) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = if (order.customerDetailsConfirmed) Color(0xFFE8F5E9)
                    else if (order.customerDetailsRejected) MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.5f)
                    else Color(0xFFFFF3E0),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(
                        1.dp,
                        if (order.customerDetailsConfirmed) Color(0xFF4CAF50)
                        else if (order.customerDetailsRejected) MaterialTheme.colorScheme.error
                        else Color(0xFFFF9800)
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = if (order.customerDetailsConfirmed) "✓"
                                    else if (order.customerDetailsRejected) "❌"
                                    else "📸",
                                    style = MaterialTheme.typography.titleMedium
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = if (order.customerDetailsConfirmed) "تم تأكيد بيانات العميل والإيصال ✓"
                                    else if (order.customerDetailsRejected) "تم رفض البيانات: ${order.rejectionReason.ifBlank { "غير مطابقة" }}"
                                    else "رفع الكابتن إيصال الاستلام بانتظار مراجعتك",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (order.customerDetailsConfirmed) Color(0xFF2E7D32)
                                    else if (order.customerDetailsRejected) MaterialTheme.colorScheme.error
                                    else Color(0xFFE65100)
                                )
                            }

                            Button(
                                onClick = { showReviewDialog = true },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (order.customerDetailsConfirmed) Color(0xFF2E7D32) else Primary
                                ),
                                shape = RoundedCornerShape(8.dp),
                                contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                                modifier = Modifier.height(34.dp)
                            ) {
                                Text(
                                    text = if (order.customerDetailsConfirmed) "عرض الإيصال" else "فحص واعتماد",
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White
                                )
                            }
                        }
                    }
                }
            }

            if (isInProgress && onAdvanceStatus != null) {
                Spacer(modifier = Modifier.height(10.dp))
                OrderStatusTracker(
                    order = order,
                    onAdvanceStatus = onAdvanceStatus,
                    onCancelOrder = onCancelOrder
                )
            }

            // Star Rating Section for Delivered/Completed orders
            if (isCompleted) {
                Spacer(modifier = Modifier.height(10.dp))
                OrderRatingSection(
                    rating = order.rating,
                    onRateOrder = { rating -> onRateOrder?.invoke(rating) }
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderHistoryScreen(
    onBack: () -> Unit = {},
    onRequestNewOrder: () -> Unit = {},
    onEditOrder: ((Int) -> Unit)? = null,
    orderViewModel: OrderViewModel = viewModel()
) {
    val orders by orderViewModel.ordersState.collectAsStateWithLifecycle()
    val isRefreshing by orderViewModel.isRefreshing.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        ElBatalHeader(
            title = stringResource(R.string.order_history_title),
            onBackClick = onBack
        )

        PullToRefreshBox(
            isRefreshing = isRefreshing,
            onRefresh = { orderViewModel.refreshRestaurantFeed() },
            modifier = Modifier.fillMaxSize(),
            indicator = {
                PullToRefreshDefaults.Indicator(
                    state = rememberPullToRefreshState(),
                    isRefreshing = isRefreshing,
                    modifier = Modifier.align(Alignment.TopCenter),
                    containerColor = MaterialTheme.colorScheme.surface,
                    color = Primary
                )
            }
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 16.dp, vertical = 12.dp)
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.total_orders_count, orders.size),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    TextButton(onClick = onRequestNewOrder) {
                        Text(
                            text = "+ " + stringResource(R.string.request_courier_action),
                            color = Primary,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                OrderManagementSection(
                    orders = orders,
                    onAdvanceStatus = { id, status ->
                        orderViewModel.advanceOrderStatus(id, status)
                    },
                    onRateOrder = { id, rating ->
                        orderViewModel.setOrderRating(id, rating)
                    },
                    onEditOrder = onEditOrder,
                    onRequestNewOrder = onRequestNewOrder,
                    onCancelOrder = { id ->
                        orderViewModel.cancelOrder(id)
                    }
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }
}

@Composable
fun OrderHistoryCard(
    order: DeliveryOrderEntity,
    onAdvanceStatus: (() -> Unit)? = null,
    onRateOrder: ((Int) -> Unit)? = null,
    onEditOrder: ((Int) -> Unit)? = null,
    onCancelOrder: (() -> Unit)? = null,
    orderViewModel: OrderViewModel = viewModel()
) {
    val isDelivered = isOrderCompleted(order.status)
    val isInProgress = isOrderInProgress(order.status)
    val isCancelled = isOrderCancelled(order.status)

    val (statusLabel, badgeColor, badgeTextColor) = when {
        isCancelled -> Triple(stringResource(R.string.status_cancelled), Color(0xFFFFEBEE), Color(0xFFC62828))
        isDelivered -> Triple(stringResource(R.string.status_completed), Color(0xE8E8F5E9), Color(0xFF2E7D32))
        else -> {
            val label = when (order.status.trim()) {
                "جاري البحث عن كابتن", "في انتظار كابتن", "قيد التحضير", "pending", "جاري البحث" -> "جاري البحث"
                "الكابتن في الطريق لك", "في الطريق لك", "accepted", "في الطريق إلى المطعم" -> "في الطريق لك"
                "جاري التوصيل", "delivering", "out_for_delivery" -> "جاري التوصيل"
                else -> if (order.status.isNotBlank()) order.status else stringResource(R.string.status_in_progress)
            }
            Triple(label, Color(0xFFFFF3E0), Color(0xFFE65100))
        }
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp)
        ) {
            val context = LocalContext.current
            val clipboardManager = LocalClipboardManager.current

            // Header: Order Code, Order ID & Status chip
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    if (order.orderCode.isNotBlank()) {
                        Surface(
                            color = Primary.copy(alpha = 0.1f),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.dp, Primary.copy(alpha = 0.4f))
                        ) {
                            Row(
                                modifier = Modifier
                                    .clickable {
                                        clipboardManager.setText(AnnotatedString(order.orderCode))
                                        Toast.makeText(context, "تم نسخ كود الطلب: ${order.orderCode}", Toast.LENGTH_SHORT).show()
                                    }
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(4.dp)
                            ) {
                                Text(
                                    text = "كود: #${order.orderCode}",
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Primary
                                )
                                Icon(
                                    imageVector = Icons.Default.ContentCopy,
                                    contentDescription = "نسخ",
                                    tint = Primary,
                                    modifier = Modifier.size(13.dp)
                                )
                            }
                        }
                    }
                    Text(
                        text = stringResource(R.string.order_number_format, order.id),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
                Surface(
                    color = badgeColor,
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Text(
                        text = statusLabel,
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = badgeTextColor,
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            // Real-time Order Status Tracker Component
            OrderStatusTracker(
                order = order,
                onAdvanceStatus = onAdvanceStatus,
                onCancelOrder = onCancelOrder
            )

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(12.dp))

            // Total Service Fee (مبلغ خدمة التوصيل)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = stringResource(R.string.delivery_fee_label) + ": ",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.bodyMedium,
                    color = Primary
                )
                Text(
                    text = order.serviceFee,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            val isCaptainAssigned = order.captainName.isNotBlank() &&
                order.captainName != "في انتظار كابتن" &&
                order.captainName != "في انتظار قبول كابتن"
            val isCaptainAccepted = isCaptainAssigned || order.status.trim() in listOf(
                "الكابتن في الطريق لك", "في الطريق لك", "accepted", "في الطريق إلى المطعم",
                "جاري التوصيل", "delivering", "out_for_delivery"
            )

            if (isInProgress && !isCaptainAccepted && onEditOrder != null) {
                Spacer(modifier = Modifier.height(10.dp))
                Button(
                    onClick = { onEditOrder(order.id) },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(42.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "👉 " + stringResource(R.string.edit_order_btn),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
            Spacer(modifier = Modifier.height(12.dp))

            val trackedCaptain by orderViewModel.trackedCaptainInfo.collectAsStateWithLifecycle()
            val restaurantProfile by orderViewModel.restaurantProfile.collectAsStateWithLifecycle()
            val restLat = restaurantProfile.latitude ?: 30.0444
            val restLng = restaurantProfile.longitude ?: 31.2357

            LaunchedEffect(order.id, isCaptainAssigned) {
                if (isCaptainAssigned && isInProgress) {
                    orderViewModel.startTrackingOrderCaptain(order.id.toString())
                }
            }

            // Captain Information (بيانات المندوب)
            val isCancelled = order.status in listOf("ملغي", "Cancelled")
            CaptainInfoSection(
                captainName = order.captainName,
                captainPhone = order.captainPhone,
                captainPhotoUrl = order.captainPhotoUrl,
                estimatedArrival = order.estimatedArrival,
                isCancelled = isCancelled,
                isCompleted = isDelivered,
                restaurantLat = restLat,
                restaurantLng = restLng,
                captainLat = trackedCaptain?.latitude,
                captainLng = trackedCaptain?.longitude
            )

            // Star Rating Feature when Delivered
            if (isDelivered) {
                Spacer(modifier = Modifier.height(12.dp))
                OrderRatingSection(
                    rating = order.rating,
                    onRateOrder = { rating -> onRateOrder?.invoke(rating) }
                )
            }
        }
    }
}

@Composable
fun OrderRatingSection(
    rating: Int,
    onRateOrder: (Int) -> Unit
) {
    var hoverOrSelectedRating by remember(rating) { mutableStateOf(rating) }
    val context = androidx.compose.ui.platform.LocalContext.current

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        colors = CardDefaults.cardColors(
            containerColor = Primary.copy(alpha = 0.08f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = if (rating > 0) stringResource(R.string.your_rating_label) else stringResource(R.string.rate_experience_title),
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )

            Spacer(modifier = Modifier.height(6.dp))

            Row(
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                for (starIndex in 1..5) {
                    val isFilled = starIndex <= hoverOrSelectedRating
                    IconButton(
                        onClick = {
                            hoverOrSelectedRating = starIndex
                            onRateOrder(starIndex)
                            android.widget.Toast.makeText(
                                context,
                                context.getString(R.string.rate_experience_submitted),
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        },
                        modifier = Modifier.size(38.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = "Star $starIndex",
                            tint = if (isFilled) Color(0xFFFFB300) else MaterialTheme.colorScheme.outlineVariant,
                            modifier = Modifier.size(28.dp)
                        )
                    }
                }
            }

            if (rating > 0) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = stringResource(R.string.rate_experience_submitted),
                    style = MaterialTheme.typography.bodySmall,
                    color = Color(0xFF2E7D32),
                    fontWeight = FontWeight.Medium
                )
            }
        }
    }
}

@Composable
fun OrderStatusTracker(
    order: DeliveryOrderEntity,
    onAdvanceStatus: (() -> Unit)? = null,
    onCancelOrder: (() -> Unit)? = null
) {
    val context = LocalContext.current
    val currentStageIndex = when (order.status.trim()) {
        "جاري البحث", "جاري البحث عن كابتن", "في انتظار كابتن", "قيد التحضير", "Preparing", "pending" -> 0
        "في الطريق لك", "الكابتن في الطريق لك", "accepted", "في الطريق إلى المطعم" -> 1
        "جاري التوصيل", "قيد التوصيل", "Out for Delivery", "delivering" -> 2
        "تم التسليم", "مكتمل", "مكتملة", "Delivered" -> 3
        else -> 0
    }
    val isSearching = currentStageIndex == 0
    var showCancelConfirmDialog by remember { mutableStateOf(false) }

    if (showCancelConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showCancelConfirmDialog = false },
            title = {
                Text(
                    text = stringResource(R.string.cancel_trip_confirm_title),
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Text(
                    text = stringResource(R.string.cancel_trip_confirm_message),
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showCancelConfirmDialog = false
                        onCancelOrder?.invoke()
                        android.widget.Toast.makeText(
                            context,
                            context.getString(R.string.cancel_trip_success),
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) {
                    Text(
                        text = stringResource(R.string.cancel_trip_button),
                        color = MaterialTheme.colorScheme.onError,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showCancelConfirmDialog = false }) {
                    Text(text = stringResource(R.string.cancel_button))
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }

    val targetProgress = when (currentStageIndex) {
        0 -> 0.12f
        1 -> 0.40f
        2 -> 0.72f
        3 -> 1.0f
        else -> 0.12f
    }

    val animatedProgress by animateFloatAsState(
        targetValue = targetProgress,
        animationSpec = tween(durationMillis = 600),
        label = "OrderProgress"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f)
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            // Live Status Header
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(8.dp)
                            .clip(CircleShape)
                            .background(if (currentStageIndex >= 3) Color(0xFF4CAF50) else Primary)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = stringResource(R.string.tracking_title),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                Text(
                    text = when (currentStageIndex) {
                        0 -> stringResource(R.string.stage_searching)
                        1 -> stringResource(R.string.stage_on_the_way)
                        2 -> stringResource(R.string.stage_out_for_delivery)
                        else -> stringResource(R.string.stage_delivered)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = Primary
                )
            }

            Spacer(modifier = Modifier.height(14.dp))

            // Step Progress Bar and Nodes (4 Steps)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(4.dp)
                        .align(Alignment.Center)
                        .clip(RoundedCornerShape(2.dp)),
                    color = Primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TrackingNode(
                        isCompleted = currentStageIndex >= 0,
                        isActive = currentStageIndex == 0,
                        stepNumber = "1"
                    )

                    TrackingNode(
                        isCompleted = currentStageIndex >= 1,
                        isActive = currentStageIndex == 1,
                        stepNumber = "2"
                    )

                    TrackingNode(
                        isCompleted = currentStageIndex >= 2,
                        isActive = currentStageIndex == 2,
                        stepNumber = "3"
                    )

                    TrackingNode(
                        isCompleted = currentStageIndex >= 3,
                        isActive = currentStageIndex == 3,
                        stepNumber = "4"
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Stage Labels below progress bar
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = stringResource(R.string.stage_searching),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (currentStageIndex == 0) FontWeight.Bold else FontWeight.Normal,
                    color = if (currentStageIndex >= 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.stage_on_the_way),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (currentStageIndex == 1) FontWeight.Bold else FontWeight.Normal,
                    color = if (currentStageIndex >= 1) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.stage_out_for_delivery),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (currentStageIndex == 2) FontWeight.Bold else FontWeight.Normal,
                    color = if (currentStageIndex >= 2) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Text(
                    text = stringResource(R.string.stage_delivered),
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (currentStageIndex == 3) FontWeight.Bold else FontWeight.Normal,
                    color = if (currentStageIndex >= 3) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            val isCaptainAssigned = order.captainName.isNotBlank() &&
                order.captainName != "في انتظار كابتن" &&
                order.captainName != "في انتظار قبول كابتن"
            val captainDisplayName = if (isCaptainAssigned) order.captainName else "الكابتن"
            val eta = order.estimatedArrival.ifBlank { "10 - 15 دقيقة" }

            val descriptionText = when (currentStageIndex) {
                0 -> ""
                1 -> "استقبل $captainDisplayName الطلب وهو في الطريق لمطعمك 🛵 (الوصول خلال $eta)"
                2 -> "استلم $captainDisplayName الطلب وهو الآن في طريقه لتسليمه للعميل 🚀"
                else -> "تم تسليم الطلب للعميل بنجاح ✅"
            }

            if (descriptionText.isNotBlank()) {
                Text(
                    text = descriptionText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (currentStageIndex < 3) {
                Spacer(modifier = Modifier.height(10.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Cancel Trip Button (زر إلغاء الرحلة في حالة جاري البحث فقط ويختفي بمجرد استقبال الكابتن للطلب)
                    val isCaptainAccepted = isCaptainAssigned || currentStageIndex > 0
                    if (isSearching && !isCaptainAccepted && onCancelOrder != null) {
                        OutlinedButton(
                            onClick = { showCancelConfirmDialog = true },
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(38.dp),
                            shape = RoundedCornerShape(8.dp),
                            border = BorderStroke(1.2.dp, MaterialTheme.colorScheme.error),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.error,
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = stringResource(R.string.cancel_trip_button),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                    }

                    // Note: Order pickup and delivery phases are executed strictly by the Captain app
                }
            }
        }
    }
}

@Composable
fun TrackingNode(
    isCompleted: Boolean,
    isActive: Boolean,
    stepNumber: String
) {
    val nodeColor = if (isCompleted) Primary else MaterialTheme.colorScheme.outlineVariant
    val iconColor = if (isCompleted) Color.White else MaterialTheme.colorScheme.onSurfaceVariant

    Box(
        modifier = Modifier
            .size(26.dp)
            .clip(CircleShape)
            .background(nodeColor)
            .border(
                width = if (isActive) 2.dp else 0.dp,
                color = if (isActive) MaterialTheme.colorScheme.onSurface else Color.Transparent,
                shape = CircleShape
            ),
        contentAlignment = Alignment.Center
    ) {
        if (isCompleted) {
            Icon(
                imageVector = Icons.Default.Check,
                contentDescription = null,
                tint = iconColor,
                modifier = Modifier.size(16.dp)
            )
        } else {
            Text(
                text = stepNumber,
                style = MaterialTheme.typography.labelSmall,
                color = iconColor,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

@Composable
fun DayNightThemeToggle(
    isDark: Boolean,
    onToggle: () -> Unit,
    modifier: Modifier = Modifier
) {
    IconButton(
        onClick = onToggle,
        modifier = modifier
            .size(42.dp)
            .clip(CircleShape)
            .background(if (isDark) Color(0xFF2C2C2C) else Color(0xFFEEEEEE))
            .border(
                width = 1.dp,
                color = if (isDark) Primary.copy(alpha = 0.6f) else Color(0xFFCCCCCC),
                shape = CircleShape
            )
    ) {
        Icon(
            imageVector = if (isDark) Icons.Filled.LightMode else Icons.Filled.DarkMode,
            contentDescription = if (isDark) stringResource(R.string.theme_day_mode) else stringResource(R.string.theme_dark_mode),
            tint = if (isDark) Primary else Color(0xFF3F51B5),
            modifier = Modifier.size(24.dp)
        )
    }
}

@Composable
fun ElBatalHeader(
    title: String,
    onBackClick: (() -> Unit)? = null,
    actionText: String? = null,
    onActionClick: (() -> Unit)? = null,
    onMenuClick: (() -> Unit)? = null,
    showThemeToggle: Boolean = true
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val isDark by ThemeManager.isDarkModeState.collectAsStateWithLifecycle()
    val logoBackgroundColor = if (isDark) Color.Transparent else Color.Black
    val logoBorderColor = Primary

    CompositionLocalProvider(LocalLayoutDirection provides LayoutDirection.Ltr) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // الزر أعلى اليسار (أيقونة الشمس والقمر وبدون تسمية)
            if (showThemeToggle) {
                DayNightThemeToggle(
                    isDark = isDark,
                    onToggle = { ThemeManager.toggleTheme(context) }
                )
                Spacer(modifier = Modifier.width(8.dp))
            }

            if (actionText != null && onActionClick != null) {
                TextButton(onClick = onActionClick) {
                    Text(
                        text = actionText,
                        color = Primary,
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                Spacer(modifier = Modifier.width(6.dp))
            }

            // العنوان
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(1f)
            )

            Spacer(modifier = Modifier.width(10.dp))

            // الشعار
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(logoBackgroundColor)
                    .border(1.5.dp, logoBorderColor, CircleShape),
                contentAlignment = Alignment.Center
            ) {
                Image(
                    painter = painterResource(id = R.drawable.img_elbatal_logo_1782909588131),
                    contentDescription = "El Batal Logo",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop
                )
            }

            // زر القائمة في أعلى اليمين
            if (onMenuClick != null) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onMenuClick,
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(Primary.copy(alpha = 0.15f))
                        .testTag("menu_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Menu,
                        contentDescription = stringResource(R.string.side_menu_title),
                        tint = Primary,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }

            // زر الرجوع إذا وجد
            if (onBackClick != null) {
                Spacer(modifier = Modifier.width(8.dp))
                IconButton(
                    onClick = onBackClick,
                    modifier = Modifier.size(36.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "رجوع",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(24.dp)
                    )
                }
            }
        }
    }
}

@Composable
fun InfoCard(title: String, value: String) {
    Card(modifier = Modifier.width(100.dp)) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(title, style = MaterialTheme.typography.bodySmall)
            Text(value, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
fun SimulatedPushNotificationOverlay(
    notification: PushNotificationBanner?,
    onDismiss: () -> Unit
) {
    LaunchedEffect(notification?.id) {
        if (notification != null) {
            kotlinx.coroutines.delay(6000)
            onDismiss()
        }
    }

    AnimatedVisibility(
        visible = notification != null,
        enter = slideInVertically(initialOffsetY = { -it }) + fadeIn(),
        exit = slideOutVertically(targetOffsetY = { -it }) + fadeOut(),
        modifier = Modifier
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        if (notification != null) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { onDismiss() },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surface
                ),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
                border = BorderStroke(1.dp, Primary.copy(alpha = 0.5f))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.Top
                ) {
                    Box(
                        modifier = Modifier
                            .size(42.dp)
                            .clip(CircleShape)
                            .background(Primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Notifications,
                            contentDescription = "Notification",
                            tint = Color.White,
                            modifier = Modifier.size(24.dp)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    text = stringResource(R.string.app_name),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Primary
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "• " + notification.timestamp,
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }

                            IconButton(
                                onClick = onDismiss,
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = "Dismiss",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                    modifier = Modifier.size(16.dp)
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = notification.title,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )

                        Spacer(modifier = Modifier.height(2.dp))

                        Text(
                            text = notification.body,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun EditOrderScreen(
    orderId: Int,
    onBack: () -> Unit = {},
    orderViewModel: OrderViewModel = viewModel()
) {
    LaunchedEffect(Unit) {
        orderViewModel.listenToFirestore()
    }

    val orders by orderViewModel.ordersState.collectAsStateWithLifecycle()
    val firestoreOrders by orderViewModel.firestoreOrders.collectAsStateWithLifecycle()
    val allOrders = remember(orders, firestoreOrders) {
        (firestoreOrders + orders).distinctBy { it.id }
    }
    val currentOrder = remember(allOrders, orderId) {
        allOrders.find { it.id == orderId }
            ?: firestoreOrders.find { it.id == orderId }
            ?: orders.find { it.id == orderId }
    }
    val context = LocalContext.current

    var address by remember(currentOrder?.id, currentOrder?.destinationAddress) {
        mutableStateOf(currentOrder?.destinationAddress ?: "")
    }
    var serviceFee by remember(currentOrder?.id, currentOrder?.serviceFee) {
        mutableStateOf(currentOrder?.serviceFee ?: "")
    }
    var details by remember(currentOrder?.id, currentOrder?.orderDetails) {
        mutableStateOf(currentOrder?.orderDetails ?: "")
    }

    LaunchedEffect(currentOrder) {
        currentOrder?.let {
            if (address.isBlank()) address = it.destinationAddress
            if (serviceFee.isBlank()) serviceFee = it.serviceFee
            if (details.isBlank()) details = it.orderDetails
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            ElBatalHeader(
                title = stringResource(R.string.edit_order_title),
                onBackClick = onBack
            )

            Spacer(modifier = Modifier.height(16.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                shape = RoundedCornerShape(16.dp),
                elevation = CardDefaults.cardElevation(defaultElevation = 3.dp),
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp)
                ) {
                    Text(
                        text = "قم بتحديث عنوان التوصيل ومبلغ الخدمة والملاحظات لهذا الطلب",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(16.dp))

                    // تاريخ ووقت إنشاء الطلب الحقيقي (غير قابل للتعديل)
                    Surface(
                        modifier = Modifier.fillMaxWidth(),
                        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 14.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.weight(1f)
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(38.dp)
                                        .clip(CircleShape)
                                        .background(Primary.copy(alpha = 0.15f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.AccessTime,
                                        contentDescription = null,
                                        tint = Primary,
                                        modifier = Modifier.size(20.dp)
                                    )
                                }
                                Spacer(modifier = Modifier.width(12.dp))
                                Column {
                                    Text(
                                        text = "تاريخ ووقت الطلب",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                    Spacer(modifier = Modifier.height(2.dp))
                                    Text(
                                        text = currentOrder?.orderDate?.ifBlank { "غير محدد" } ?: "جاري التحميل...",
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.Bold,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                }
                            }

                            Surface(
                                color = MaterialTheme.colorScheme.surface,
                                shape = RoundedCornerShape(8.dp),
                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))
                            ) {
                                Text(
                                    text = "غير قابل للتعديل",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    fontWeight = FontWeight.Medium,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(14.dp))

                    // Address Field (عنوان التوصيل - مملوء بما تم كتابته عند إنشاء الطلب)
                    OutlinedTextField(
                        value = address,
                        onValueChange = { address = it },
                        label = { Text(stringResource(R.string.order_address_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.LocationOn, contentDescription = null, tint = Primary)
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Service Fee Field (مبلغ خدمة التوصيل - مملوء بما تم كتابته عند إنشاء الطلب)
                    OutlinedTextField(
                        value = serviceFee,
                        onValueChange = { serviceFee = it },
                        label = { Text(stringResource(R.string.service_fee_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        leadingIcon = {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = Primary)
                        }
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    // Order Details Field (تفاصيل وملاحظات الطلب)
                    OutlinedTextField(
                        value = details,
                        onValueChange = { details = it },
                        label = { Text(stringResource(R.string.order_details_label)) },
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        minLines = 3,
                        maxLines = 5,
                        leadingIcon = {
                            Icon(Icons.Default.Edit, contentDescription = null, tint = Primary)
                        }
                    )

                    Spacer(modifier = Modifier.height(24.dp))

                    Button(
                        onClick = {
                            if (!com.example.network.NetworkHelper.isNetworkAvailable(context)) {
                                com.example.network.NetworkHelper.notifyNoInternet(context, "تعديل بيانات الطلب")
                                return@Button
                            }
                            orderViewModel.updateOrderDetails(
                                orderId = orderId,
                                destinationAddress = address,
                                orderDetails = details,
                                customerPhone = currentOrder?.customerPhone ?: "",
                                customerName = currentOrder?.customerName ?: "",
                                serviceFee = serviceFee
                            )
                            Toast.makeText(context, context.getString(R.string.order_updated_success), Toast.LENGTH_SHORT).show()
                            onBack()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(52.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.save_changes_btn),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

enum class CaptainTab {
    AVAILABLE,
    ACTIVE,
    WALLET,
    PROFILE
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CaptainDashboardScreen(
    onBack: () -> Unit = {},
    orderViewModel: OrderViewModel = viewModel()
) {
    val context = LocalContext.current
    var activeTab by remember { mutableStateOf(CaptainTab.AVAILABLE) }

    LaunchedEffect(Unit) {
        orderViewModel.listenToFirestoreCaptain()
    }

    val pendingOrders by orderViewModel.pendingOrders.collectAsStateWithLifecycle()
    val activeOrders by orderViewModel.captainActiveOrders.collectAsStateWithLifecycle()
    val completedOrders by orderViewModel.captainCompletedOrders.collectAsStateWithLifecycle()
    val driverProfile by orderViewModel.driverProfile.collectAsStateWithLifecycle()
    val captainProfile by orderViewModel.captainProfile.collectAsStateWithLifecycle()
    val walletEarnings by orderViewModel.walletEarnings.collectAsStateWithLifecycle()
    val isLoading by orderViewModel.isLoading.collectAsStateWithLifecycle()
    val snackbarMessage by orderViewModel.snackbarMessage.collectAsStateWithLifecycle()

    LaunchedEffect(snackbarMessage) {
        snackbarMessage?.let { msg ->
            Toast.makeText(context, msg, Toast.LENGTH_LONG).show()
            orderViewModel.clearSnackbarMessage()
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // App Header
        ElBatalHeader(
            title = "تطبيق الكابتن - التوزيع الذكي",
            onBackClick = onBack
        )

        // Loading Bar
        if (isLoading) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth(),
                color = Primary,
                trackColor = Primary.copy(alpha = 0.2f)
            )
        }

        // Captain Status & Auto-Dispatch Control Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 8.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, if (captainProfile.isOnline) Primary else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when {
                                            !captainProfile.isOnline -> Color(0xFFD32F2F)
                                            captainProfile.availabilityStatus == "busy" -> Color(0xFFFFA000)
                                            else -> Color(0xFF388E3C)
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (captainProfile.isOnline) "متصل للعمل (Online)" else "غير متصل (Offline)",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(modifier = Modifier.height(3.dp))
                        Text(
                            text = when {
                                captainProfile.status != "approved" -> "⚠️ الحساب بانتظار اعتماد الإدارة"
                                !captainProfile.isOnline -> "لن تستقبل أي طلبات توزيع تلقائي أثناء عدم الاتصال"
                                captainProfile.availabilityStatus == "busy" || activeOrders.isNotEmpty() -> "🟡 لديك طلب نشط حالياً - غير متاح لطلبات جديدة"
                                else -> "🟢 جاهز لاستقبال أقرب الطلبات الذكية تلقائياً (Auto Dispatch)"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Switch(
                        checked = captainProfile.isOnline,
                        onCheckedChange = { isChecked ->
                            orderViewModel.toggleCaptainOnlineStatus(isChecked)
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color.White,
                            checkedTrackColor = Primary,
                            uncheckedThumbColor = Color.Gray,
                            uncheckedTrackColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    )
                }

                Spacer(modifier = Modifier.height(10.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            imageVector = Icons.Default.LocationOn,
                            contentDescription = null,
                            tint = Primary,
                            modifier = Modifier.size(16.dp)
                        )
                        Spacer(modifier = Modifier.width(4.dp))
                        Text(
                            text = "الموقع: (${String.format(java.util.Locale.US, "%.4f", captainProfile.latitude ?: 30.0444)}, ${String.format(java.util.Locale.US, "%.4f", captainProfile.longitude ?: 31.2357)})",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    OutlinedButton(
                        onClick = {
                            // Update location coordinates with a small random jitter to simulate live movement
                            val currentLat = captainProfile.latitude ?: 30.0444
                            val currentLng = captainProfile.longitude ?: 31.2357
                            val deltaLat = (Math.random() - 0.5) * 0.002
                            val deltaLng = (Math.random() - 0.5) * 0.002
                            orderViewModel.updateCaptainLocation(currentLat + deltaLat, currentLng + deltaLng)
                        },
                        shape = RoundedCornerShape(8.dp),
                        contentPadding = PaddingValues(horizontal = 10.dp, vertical = 4.dp),
                        border = BorderStroke(1.dp, Primary.copy(alpha = 0.6f))
                    ) {
                        Text("تحديث موقعي 🔄", style = MaterialTheme.typography.labelSmall, color = Primary)
                    }
                }
            }
        }

        // Navigation Tabs (المتاحة / رحلاتي / المحفظة / الحساب)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(MaterialTheme.colorScheme.surface)
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .horizontalScroll(rememberScrollState()),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            FilterChip(
                selected = activeTab == CaptainTab.AVAILABLE,
                onClick = { activeTab = CaptainTab.AVAILABLE },
                label = { Text("الطلبات المتاحة (${pendingOrders.size})", fontWeight = FontWeight.Bold) },
                leadingIcon = { Text("📦") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Primary,
                    selectedLabelColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )

            FilterChip(
                selected = activeTab == CaptainTab.ACTIVE,
                onClick = { activeTab = CaptainTab.ACTIVE },
                label = { Text("رحلاتي (${activeOrders.size})", fontWeight = FontWeight.Bold) },
                leadingIcon = { Text("🛵") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Primary,
                    selectedLabelColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )

            FilterChip(
                selected = activeTab == CaptainTab.WALLET,
                onClick = { activeTab = CaptainTab.WALLET },
                label = { Text("المحفظة والأرباح", fontWeight = FontWeight.Bold) },
                leadingIcon = { Text("💰") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Primary,
                    selectedLabelColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )

            FilterChip(
                selected = activeTab == CaptainTab.PROFILE,
                onClick = { activeTab = CaptainTab.PROFILE },
                label = { Text("حساب الكابتن", fontWeight = FontWeight.Bold) },
                leadingIcon = { Text("👤") },
                colors = FilterChipDefaults.filterChipColors(
                    selectedContainerColor = Primary,
                    selectedLabelColor = Color.White
                ),
                shape = RoundedCornerShape(12.dp)
            )
        }

        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

        // Content Area based on selected Tab
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            when (activeTab) {
                CaptainTab.AVAILABLE -> {
                    if (pendingOrders.isEmpty()) {
                        Column(
                            modifier = Modifier
                                .fillMaxSize()
                                .padding(24.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(80.dp)
                                    .clip(CircleShape)
                                    .background(Primary.copy(alpha = 0.15f)),
                                contentAlignment = Alignment.Center
                            ) {
                                Text("🛵", style = MaterialTheme.typography.headlineLarge)
                            }
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "لا توجد طلبات جديدة حالياً",
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(
                                text = "يتم استقبال الطلبات المتاحة حياً ومباشرةً من المطاعم عبر قاعدة بيانات Firestore فور إضافتها.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                textAlign = androidx.compose.ui.text.style.TextAlign.Center
                            )
                        }
                    } else {
                        LazyColumn(
                            verticalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(pendingOrders) { order ->
                                Card(
                                    modifier = Modifier.fillMaxWidth(),
                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                    shape = RoundedCornerShape(16.dp),
                                    elevation = CardDefaults.cardElevation(defaultElevation = 4.dp),
                                    border = BorderStroke(1.dp, Primary.copy(alpha = 0.4f))
                                ) {
                                    Column(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(16.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = "🏪 ${order.customerName.ifBlank { "مطعم جديد" }}",
                                                style = MaterialTheme.typography.titleMedium,
                                                fontWeight = FontWeight.Bold
                                            )
                                            Surface(
                                                color = Primary,
                                                shape = RoundedCornerShape(8.dp)
                                            ) {
                                                Text(
                                                    text = order.serviceFee,
                                                    style = MaterialTheme.typography.labelLarge,
                                                    fontWeight = FontWeight.ExtraBold,
                                                    color = Color.White,
                                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                                )
                                            }
                                        }

                                        Spacer(modifier = Modifier.height(8.dp))
                                        if (order.destinationAddress.isNotBlank()) {
                                            Text(
                                                text = "📍 العنوان: ${order.destinationAddress}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                color = MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                        if (order.orderDetails.isNotBlank()) {
                                            Spacer(modifier = Modifier.height(4.dp))
                                            Text(
                                                text = "📝 أصناف الطلب: ${order.orderDetails}",
                                                style = MaterialTheme.typography.bodySmall,
                                                color = MaterialTheme.colorScheme.onSurfaceVariant
                                            )
                                        }
                                        Spacer(modifier = Modifier.height(4.dp))
                                        Text(
                                            text = "⏰ الوقت: ${order.orderDate}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant
                                        )

                                        Spacer(modifier = Modifier.height(14.dp))
                                        Button(
                                            onClick = {
                                                orderViewModel.acceptOrderInFirestore(
                                                    orderDocId = order.id.toString(),
                                                    onSuccess = {
                                                        activeTab = CaptainTab.ACTIVE
                                                    },
                                                    onError = {}
                                                )
                                            },
                                            colors = ButtonDefaults.buttonColors(containerColor = Primary),
                                            shape = RoundedCornerShape(12.dp),
                                            modifier = Modifier
                                                .fillMaxWidth()
                                                .height(44.dp)
                                        ) {
                                            Text(
                                                text = "قبول الطلب والبدء بالتوصيل 🛵",
                                                color = Color.White,
                                                fontWeight = FontWeight.Bold
                                            )
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                CaptainTab.ACTIVE -> {
                    Column(modifier = Modifier.fillMaxSize()) {
                        Text(
                            text = "الرحلات والطلبات الحالية",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 12.dp)
                        )

                        if (activeOrders.isEmpty() && completedOrders.isEmpty()) {
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = "لم تقم بقبول أي طلبات حتى الآن.",
                                    style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        } else {
                            LazyColumn(
                                verticalArrangement = Arrangement.spacedBy(12.dp),
                                modifier = Modifier.fillMaxSize()
                            ) {
                                items(activeOrders) { active ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                                        shape = RoundedCornerShape(16.dp),
                                        border = BorderStroke(1.5.dp, Primary)
                                    ) {
                                        Column(modifier = Modifier.padding(16.dp)) {
                                            Row(
                                                modifier = Modifier.fillMaxWidth(),
                                                horizontalArrangement = Arrangement.SpaceBetween,
                                                verticalAlignment = Alignment.CenterVertically
                                            ) {
                                                Text(
                                                    text = "🛵 طلب مسند لك (Auto Dispatch)",
                                                    style = MaterialTheme.typography.titleMedium,
                                                    fontWeight = FontWeight.Bold,
                                                    color = Primary
                                                )
                                                Surface(
                                                    color = Primary,
                                                    shape = RoundedCornerShape(8.dp)
                                                ) {
                                                    Text(
                                                        text = active.serviceFee,
                                                        style = MaterialTheme.typography.labelMedium,
                                                        fontWeight = FontWeight.Bold,
                                                        color = Color.White,
                                                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                                                    )
                                                }
                                            }

                                            Spacer(modifier = Modifier.height(8.dp))
                                            Text(
                                                text = "🏪 المطعم: ${active.customerName.ifBlank { "مطعم شريك" }}",
                                                style = MaterialTheme.typography.bodyMedium,
                                                fontWeight = FontWeight.SemiBold
                                            )
                                            if (active.destinationAddress.isNotBlank()) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = "📍 عنوان التوصيل: ${active.destinationAddress}",
                                                    style = MaterialTheme.typography.bodyMedium,
                                                    color = MaterialTheme.colorScheme.onSurface
                                                )
                                            }
                                            if (active.orderDetails.isNotBlank()) {
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = "📝 أصناف الطلب: ${active.orderDetails}",
                                                    style = MaterialTheme.typography.bodySmall,
                                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                                )
                                            }

                                            Spacer(modifier = Modifier.height(14.dp))

                                            Button(
                                                onClick = {
                                                    orderViewModel.completeOrderInFirestore(
                                                        orderDocId = active.id.toString(),
                                                        onSuccess = {},
                                                        onError = {}
                                                    )
                                                },
                                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("تم استلام وتسليم الطلب بنجاح ✓", color = Color.White, fontWeight = FontWeight.Bold)
                                            }

                                            Spacer(modifier = Modifier.height(8.dp))

                                            OutlinedButton(
                                                onClick = {
                                                    orderViewModel.rejectCaptainOrder(
                                                        orderDocId = active.id.toString(),
                                                        onSuccess = {}
                                                    )
                                                },
                                                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                                border = BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                                                modifier = Modifier.fillMaxWidth()
                                            ) {
                                                Text("اعتذار عن الطلب وإعادة توجيهه تلقائياً ❌", style = MaterialTheme.typography.bodySmall)
                                            }
                                        }
                                    }
                                }

                                items(completedOrders) { done ->
                                    Card(
                                        modifier = Modifier.fillMaxWidth(),
                                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f)),
                                        shape = RoundedCornerShape(12.dp)
                                    ) {
                                        Column(modifier = Modifier.padding(12.dp)) {
                                            Text("✓ طلب مكتمل (${done.serviceFee})", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                                            Text("العنوان: ${done.destinationAddress}", style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }

                CaptainTab.WALLET -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = Primary),
                            shape = RoundedCornerShape(20.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 6.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(24.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Text(
                                    text = "إجمالي الأرباح المحصلة",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = Color.White.copy(alpha = 0.9f)
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "$walletEarnings جنيه",
                                    style = MaterialTheme.typography.headlineLarge,
                                    fontWeight = FontWeight.ExtraBold,
                                    color = Color.White
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Text(
                                    text = "عدد الرحلات المكتملة: ${completedOrders.size}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = Color.White.copy(alpha = 0.9f)
                                )
                            }
                        }

                        Text(
                            text = "تفاصيل المستحقات اليومية",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("أرباح الطلبات:")
                                    Text("$walletEarnings جنيه", fontWeight = FontWeight.Bold)
                                }
                                HorizontalDivider()
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("عمولة التطبيق:")
                                    Text("0 جنيه", fontWeight = FontWeight.Bold, color = Color(0xFF2E7D32))
                                }
                                HorizontalDivider()
                                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                                    Text("صافي المستحقات:")
                                    Text("$walletEarnings جنيه", fontWeight = FontWeight.Bold, color = Primary)
                                }
                            }
                        }
                    }
                }

                CaptainTab.PROFILE -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(20.dp),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(20.dp),
                                horizontalAlignment = Alignment.CenterHorizontally
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(72.dp)
                                        .clip(CircleShape)
                                        .background(Primary.copy(alpha = 0.2f)),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("👤", style = MaterialTheme.typography.headlineMedium)
                                }
                                Spacer(modifier = Modifier.height(12.dp))
                                Text(
                                    text = driverProfile.name.ifBlank { "كابتن البطل" },
                                    style = MaterialTheme.typography.titleLarge,
                                    fontWeight = FontWeight.Bold
                                )
                                Text(
                                    text = "📱 ${driverProfile.phone.ifBlank { "غير مسجل" }}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Spacer(modifier = Modifier.height(8.dp))
                                Surface(
                                    color = Primary,
                                    shape = RoundedCornerShape(12.dp)
                                ) {
                                    Text(
                                        text = "التقييم: ${driverProfile.rating} ⭐ (${driverProfile.totalTrips} رحلة)",
                                        style = MaterialTheme.typography.labelLarge,
                                        fontWeight = FontWeight.Bold,
                                        color = Color.White,
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                    )
                                }
                            }
                        }

                        Card(
                            modifier = Modifier.fillMaxWidth(),
                            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text("نوع المركبة: ${driverProfile.vehicleType}", fontWeight = FontWeight.Bold)
                                Text("حالة الاتصال: متصل ومستعد للطلبات ✓", color = Color(0xFF2E7D32), fontWeight = FontWeight.Bold)
                                Text("المصدر: Cloud Firestore (مجموعة drivers)", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun SubscriptionExpiredScreen(
    message: String = "انتهت فترة اشتراكك (30 يوماً)، يرجى التواصل مع الإدارة للتجديد",
    onRecheck: () -> Unit,
    onContactAdmin: () -> Unit,
    onLogout: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            elevation = CardDefaults.cardElevation(defaultElevation = 8.dp),
            border = BorderStroke(1.5.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f))
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(28.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier
                        .size(80.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.errorContainer),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "⚠️", style = MaterialTheme.typography.headlineLarge)
                }

                Text(
                    text = "اشتراكك منتهي",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.error
                )

                Surface(
                    color = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(16.dp)
                    )
                }

                Spacer(modifier = Modifier.height(8.dp))

                Button(
                    onClick = onContactAdmin,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Primary)
                ) {
                    Icon(
                        imageVector = Icons.Default.Phone,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "التواصل مع الإدارة للتجديد",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                }

                OutlinedButton(
                    onClick = onRecheck,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(50.dp),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Primary)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "التحقق من حالة التجديد",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = Primary
                    )
                }

                TextButton(
                    onClick = onLogout,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "تسجيل الخروج",
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

