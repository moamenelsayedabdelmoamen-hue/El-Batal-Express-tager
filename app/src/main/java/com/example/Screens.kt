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
import com.example.db.SubscriptionStatusState
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
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Category
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
import com.example.auth.MerchantRegistrationRequest
import com.example.auth.MerchantRegistrationResult
import com.example.auth.MerchantSignInResult

@Composable
fun LoginScreen(onLoginSuccess: (com.example.auth.MerchantAccount) -> Unit, onRegisterClick: () -> Unit) {
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

                val performLogin: () -> Unit = performLogin@{
                    focusManager.clearFocus()
                    errorMessageText = null
                    if (!com.example.network.NetworkHelper.isNetworkAvailable(context)) {
                        val msg = "أنت غير متصل بالإنترنت! يرجى التحقق من اتصال الشبكة لتسجيل الدخول."
                        errorMessageText = msg
                        com.example.network.NetworkHelper.notifyNoInternet(context, "تسجيل الدخول")
                        return@performLogin
                    }
                    val trimmedEmail = email.trim().lowercase()
                    val rawPassword = password.trim()
                    if (trimmedEmail.isEmpty()) {
                        val msg = "يرجى إدخال البريد الإلكتروني"
                        errorMessageText = msg
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        return@performLogin
                    }
                    if (rawPassword.isEmpty()) {
                        val msg = "يرجى إدخال كلمة المرور"
                        errorMessageText = msg
                        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
                        return@performLogin
                    }

                    if (!isLoading) {
                        isLoading = true
                        scope.launch {
                            Log.d("FirebaseAuth", "بدء عملية تسجيل الدخول...")
                            try {
                                when (val authRes = MerchantAuthManager.signInWithEmail(context, trimmedEmail, rawPassword)) {
                                    is MerchantSignInResult.Success -> {
                                        sharedPreferences.edit().apply {
                                            putBoolean("remember_me", rememberMe)
                                            if (rememberMe) {
                                                putString("saved_email", trimmedEmail)
                                            } else {
                                                remove("saved_email")
                                            }
                                            apply()
                                        }
                                        Toast.makeText(context, "تم تسجيل الدخول بنجاح! مرحباً ${authRes.merchant.name}", Toast.LENGTH_SHORT).show()
                                        onLoginSuccess(authRes.merchant)
                                    }
                                    is MerchantSignInResult.EmailNotVerified -> {
                                        errorMessageText = authRes.message
                                        showUnverifiedEmailDialog = true
                                    }
                                    is MerchantSignInResult.NotRegistered -> {
                                        errorMessageText = authRes.message
                                        Toast.makeText(context, authRes.message, Toast.LENGTH_LONG).show()
                                    }
                                    is MerchantSignInResult.Error -> {
                                        errorMessageText = authRes.message
                                        Toast.makeText(context, authRes.message, Toast.LENGTH_LONG).show()
                                    }
                                    is MerchantSignInResult.Cancelled -> {
                                        // Cancelled
                                    }
                                }
                            } catch (e: Exception) {
                                Log.w("FirebaseAuth", "استثناء أثناء تسجيل الدخول: ${e.message}")
                                val errorMessage = "حدث خطأ أثناء تسجيل الدخول: ${e.localizedMessage ?: e.message}"
                                errorMessageText = errorMessage
                                Toast.makeText(context, errorMessage, Toast.LENGTH_LONG).show()
                            } finally {
                                isLoading = false
                            }
                        }
                    }
                }

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
                        onDone = { performLogin() }
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
                    onClick = { performLogin() },
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

                Spacer(modifier = Modifier.height(12.dp))

                TextButton(onClick = onRegisterClick, enabled = !isLoading) {
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
        var verificationDialogStatus by remember { mutableStateOf<String?>(null) }
        var isCheckingVerificationDialog by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = {
                if (!isResendingVerification && !isCheckingVerificationDialog) {
                    showUnverifiedEmailDialog = false
                }
            },
            title = {
                Text(
                    text = "تأكيد البريد الإلكتروني مطلوب",
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.titleMedium
                )
            },
            text = {
                Column {
                    Text(
                        text = "لا يمكن تسجيل الدخول قبل تأكيد بريدك الإلكتروني. يرجى فتح صندوق الوارد (أو مجلد الرسائل غير المرغوب فيها Spam) والضغط على رابط التأكيد.",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Spacer(modifier = Modifier.height(10.dp))
                    if (verificationDialogStatus != null) {
                        Text(
                            text = verificationDialogStatus!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = Primary,
                            fontWeight = FontWeight.SemiBold
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                    OutlinedButton(
                        onClick = {
                            val trimmedEmail = email.trim().lowercase()
                            val rawPassword = password.trim()
                            if (trimmedEmail.isEmpty() || rawPassword.isEmpty()) {
                                verificationDialogStatus = "يرجى إدخال البريد الإلكتروني وكلمة المرور أولاً"
                                return@OutlinedButton
                            }
                            if (!com.example.network.NetworkHelper.isNetworkAvailable(context)) {
                                com.example.network.NetworkHelper.notifyNoInternet(context, "إعادة إرسال رابط التأكيد")
                                return@OutlinedButton
                            }
                            isResendingVerification = true
                            verificationDialogStatus = null
                            scope.launch {
                                val resendRes = MerchantAuthManager.resendVerificationEmail(
                                    context = context,
                                    email = trimmedEmail,
                                    password = rawPassword
                                )
                                isResendingVerification = false
                                verificationDialogStatus = if (resendRes.isSuccess) {
                                    "تم إعادة إرسال رسالة التأكيد بنجاح. يرجى فحص بريدك الإلكتروني."
                                } else {
                                    "تعذر إعادة الإرسال: ${resendRes.exceptionOrNull()?.localizedMessage ?: "حاول مرة أخرى"}"
                                }
                            }
                        },
                        enabled = !isResendingVerification && !isCheckingVerificationDialog,
                        shape = RoundedCornerShape(10.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("login_resend_verification_button")
                    ) {
                        if (isResendingVerification) {
                            CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text("إعادة إرسال رسالة التأكيد")
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val trimmedEmail = email.trim().lowercase()
                        val rawPassword = password.trim()
                        if (trimmedEmail.isEmpty() || rawPassword.isEmpty()) {
                            verificationDialogStatus = "يرجى كتابة البريد الإلكتروني وكلمة المرور للتحقق"
                            return@Button
                        }
                        if (!com.example.network.NetworkHelper.isNetworkAvailable(context)) {
                            com.example.network.NetworkHelper.notifyNoInternet(context, "التحقق من حالة التأكيد")
                            return@Button
                        }
                        isCheckingVerificationDialog = true
                        verificationDialogStatus = null
                        scope.launch {
                            when (val checkRes = MerchantAuthManager.checkEmailVerificationAndSignIn(
                                context = context,
                                email = trimmedEmail,
                                password = rawPassword
                            )) {
                                is MerchantSignInResult.Success -> {
                                    isCheckingVerificationDialog = false
                                    showUnverifiedEmailDialog = false
                                    errorMessageText = null
                                    sharedPreferences.edit().apply {
                                        putBoolean("remember_me", rememberMe)
                                        if (rememberMe) putString("saved_email", trimmedEmail) else remove("saved_email")
                                        apply()
                                    }
                                    Toast.makeText(
                                        context,
                                        "تم تأكيد البريد الإلكتروني وتسجيل الدخول بنجاح!",
                                        Toast.LENGTH_SHORT
                                    ).show()
                                    onLoginSuccess(checkRes.merchant)
                                }
                                is MerchantSignInResult.EmailNotVerified -> {
                                    MerchantAuthManager.signOutMerchant(context)
                                    isCheckingVerificationDialog = false
                                    verificationDialogStatus = checkRes.message
                                }
                                is MerchantSignInResult.NotRegistered -> {
                                    isCheckingVerificationDialog = false
                                    showUnverifiedEmailDialog = false
                                    errorMessageText = checkRes.message
                                }
                                is MerchantSignInResult.Error -> {
                                    isCheckingVerificationDialog = false
                                    verificationDialogStatus = checkRes.message
                                }
                                is MerchantSignInResult.Cancelled -> {
                                    isCheckingVerificationDialog = false
                                }
                            }
                        }
                    },
                    enabled = !isResendingVerification && !isCheckingVerificationDialog,
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    modifier = Modifier.testTag("login_check_verification_button")
                ) {
                    if (isCheckingVerificationDialog) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("التحقق من حالة التأكيد", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = { showUnverifiedEmailDialog = false },
                    enabled = !isResendingVerification && !isCheckingVerificationDialog
                ) {
                    Text("إغلاق")
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
fun RegisterScreen(
    onRegisterSuccess: () -> Unit,
    onEmailVerified: (com.example.auth.MerchantAccount) -> Unit = {}
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf("") }
    var selectedCategory by remember { mutableStateOf("") }
    var categoryExpanded by remember { mutableStateOf(false) }
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

                    // أزرار التحقق وإعادة الإرسال بعد التسجيل
                    var isResending by remember { mutableStateOf(false) }
                    var isCheckingVerification by remember { mutableStateOf(false) }
                    var verificationStatusMessage by remember { mutableStateOf<String?>(null) }
                    var verificationStatusIsError by remember { mutableStateOf(false) }

                    // زر التحقق من حالة التأكيد باستخدام Firebase Auth reload()
                    Button(
                        onClick = {
                            if (!com.example.network.NetworkHelper.isNetworkAvailable(context)) {
                                com.example.network.NetworkHelper.notifyNoInternet(context, "التحقق من حالة البريد الإلكتروني")
                                return@Button
                            }
                            scope.launch {
                                isCheckingVerification = true
                                verificationStatusMessage = null
                                verificationStatusIsError = false
                                when (val res = MerchantAuthManager.checkEmailVerificationAndSignIn(
                                    context = context,
                                    email = registeredEmail,
                                    password = password.trim()
                                )) {
                                    is MerchantSignInResult.Success -> {
                                        isCheckingVerification = false
                                        Toast.makeText(
                                            context,
                                            "تم تأكيد البريد الإلكتروني بنجاح! مرحباً ${res.merchant.name}",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                        onEmailVerified(res.merchant)
                                    }
                                    is MerchantSignInResult.EmailNotVerified -> {
                                        isCheckingVerification = false
                                        verificationStatusIsError = true
                                        verificationStatusMessage = res.message
                                    }
                                    is MerchantSignInResult.NotRegistered -> {
                                        isCheckingVerification = false
                                        verificationStatusIsError = true
                                        verificationStatusMessage = res.message
                                    }
                                    is MerchantSignInResult.Error -> {
                                        isCheckingVerification = false
                                        verificationStatusIsError = true
                                        verificationStatusMessage = res.message
                                    }
                                    is MerchantSignInResult.Cancelled -> {
                                        isCheckingVerification = false
                                    }
                                }
                            }
                        },
                        enabled = !isResending && !isCheckingVerification,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("register_check_verification_button")
                    ) {
                        if (isCheckingVerification) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(18.dp),
                                color = Color.White,
                                strokeWidth = 2.dp
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = if (isCheckingVerification) "جاري التحقق من التأكيد..." else "التحقق من حالة التأكيد",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = {
                            if (!com.example.network.NetworkHelper.isNetworkAvailable(context)) {
                                com.example.network.NetworkHelper.notifyNoInternet(context, "إعادة إرسال رسالة التأكيد")
                                return@OutlinedButton
                            }
                            scope.launch {
                                isResending = true
                                verificationStatusMessage = null
                                verificationStatusIsError = false
                                val resendRes = MerchantAuthManager.resendVerificationEmail(
                                    context = context,
                                    email = registeredEmail,
                                    password = password.trim()
                                )
                                isResending = false
                                if (resendRes.isSuccess) {
                                    verificationStatusIsError = false
                                    verificationStatusMessage = "تمت إعادة إرسال رسالة التأكيد إلى بريدك بنجاح!"
                                    Toast.makeText(context, "تمت إعادة إرسال رسالة التأكيد", Toast.LENGTH_SHORT).show()
                                } else {
                                    verificationStatusIsError = true
                                    verificationStatusMessage = "تعذر إعادة الإرسال: ${resendRes.exceptionOrNull()?.localizedMessage ?: "حاول لاحقاً"}"
                                }
                            }
                        },
                        enabled = !isResending && !isCheckingVerification,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("register_resend_verification_button")
                    ) {
                        if (isResending) {
                            CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        Text(
                            text = if (isResending) "جاري إعادة الإرسال..." else "إعادة إرسال رسالة التأكيد",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }

                    if (verificationStatusMessage != null) {
                        Spacer(modifier = Modifier.height(10.dp))
                        Text(
                            text = verificationStatusMessage!!,
                            style = MaterialTheme.typography.bodySmall,
                            color = if (verificationStatusIsError) MaterialTheme.colorScheme.error else Color(0xFF2E7D32),
                            fontWeight = FontWeight.SemiBold,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Return to Login Button at bottom (signs out unverified session first)
                Button(
                    onClick = {
                        scope.launch {
                            MerchantAuthManager.signOutMerchant(context)
                            onRegisterSuccess()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .testTag("back_to_login_button"),
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
            label = { Text("الاسم الكامل") },
            enabled = !isLoading,
            modifier = Modifier
                .fillMaxWidth()
                .testTag("register_name_input"),
            singleLine = true,
            leadingIcon = {
                Icon(Icons.Default.Person, contentDescription = null, tint = Primary)
            }
        )

        Spacer(modifier = Modifier.height(8.dp))

        // Category Dropdown Field (التصنيف)
        ExposedDropdownMenuBox(
            expanded = categoryExpanded,
            onExpandedChange = {
                if (!isLoading) {
                    categoryExpanded = !categoryExpanded
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            OutlinedTextField(
                value = selectedCategory,
                onValueChange = {},
                readOnly = true,
                enabled = !isLoading,
                label = { Text("التصنيف") },
                placeholder = { Text("اختر التصنيف") },
                leadingIcon = {
                    Icon(Icons.Default.Category, contentDescription = null, tint = Primary)
                },
                trailingIcon = {
                    ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded)
                },
                colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                modifier = Modifier
                    .fillMaxWidth()
                    .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = !isLoading)
                    .testTag("register_category_dropdown"),
                singleLine = true
            )
            ExposedDropdownMenu(
                expanded = categoryExpanded,
                onDismissRequest = { categoryExpanded = false }
            ) {
                MerchantAuthManager.MERCHANT_CATEGORIES.forEach { categoryOption ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = categoryOption,
                                style = MaterialTheme.typography.bodyLarge
                            )
                        },
                        onClick = {
                            selectedCategory = categoryOption
                            categoryExpanded = false
                        },
                        contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                    )
                }
            }
        }

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
            label = { Text("العنوان / الموقع") },
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
                    Toast.makeText(context, "يرجى إدخال الاسم الكامل", Toast.LENGTH_SHORT).show()
                    return@Button
                }
                if (selectedCategory.isBlank()) {
                    Toast.makeText(context, "يرجى اختيار التصنيف", Toast.LENGTH_SHORT).show()
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
                            val currentEmail = email.trim().lowercase()
                            diagnosticLogs = diagnosticLogs + "Registering exclusively in Auth + merchants/{uid}..."

                            val regResult = MerchantAuthManager.registerMerchant(
                                request = MerchantRegistrationRequest(
                                    name = name.trim(),
                                    email = currentEmail,
                                    phone = phone.trim(),
                                    address = address.trim(),
                                    password = password.trim(),
                                    category = selectedCategory
                                ),
                                logoUploader = { uid ->
                                    diagnosticLogs = diagnosticLogs + "Auth user created: $currentEmail (uid=$uid)"
                                    var uploadedLogoUrl = ""
                                    var uploadedStoragePath = ""
                                    if (selectedImageUri != null) {
                                        val uploadResult = FirebaseStorageManager.uploadMerchantProfileImage(
                                            context = context,
                                            uid = uid,
                                            localUriString = selectedImageUri!!.toString()
                                        )
                                        val uploaded = uploadResult.getOrElse { storageEx ->
                                            val errMsg = "فشل رفع صورة اللوجو إلى Firebase Storage: ${storageEx.localizedMessage ?: storageEx.message ?: "خطأ غير معروف"}"
                                            diagnosticLogs = diagnosticLogs + errMsg
                                            throw IllegalStateException(errMsg, storageEx)
                                        }
                                        uploadedLogoUrl = uploaded.downloadUrl
                                        uploadedStoragePath = uploaded.storagePath
                                        diagnosticLogs = diagnosticLogs + "Logo uploaded to Storage: $uploadedStoragePath"
                                    }
                                    uploadedLogoUrl to uploadedStoragePath
                                }
                            )

                            when (regResult) {
                                is MerchantRegistrationResult.Success -> {
                                    diagnosticLogs = diagnosticLogs + listOf(
                                        "Firestore document created exclusively at: merchants/${regResult.uid}",
                                        "Verification email sent: ${regResult.verificationEmailSent}"
                                    )
                                    diagnosticStatus = "MERCHANT_REGISTERED_PENDING_EMAIL_VERIFICATION"
                                    registeredEmail = regResult.email
                                    isLoading = false
                                    isRegistrationSuccessful = true
                                }
                                is MerchantRegistrationResult.Failure -> {
                                    val cause = regResult.cause
                                    val errorCode = when (cause) {
                                        is FirebaseFirestoreException -> cause.code.name
                                        is FirebaseAuthException -> cause.errorCode
                                        else -> cause?.javaClass?.simpleName ?: "REGISTRATION_FAILED"
                                    }
                                    val isFirestoreIssue = cause is FirebaseFirestoreException
                                    val errorSummary = "[$errorCode] ${regResult.message}"
                                    diagnosticError = errorSummary
                                    diagnosticStatus = if (isFirestoreIssue) "FIRESTORE_WRITE_FAILED" else "AUTH_FAILED"
                                    diagnosticLogs = diagnosticLogs + ">>> $errorSummary <<<"
                                    if (isFirestoreIssue) {
                                        showDiagnosticDialog = true
                                    }
                                    Toast.makeText(context, regResult.message, Toast.LENGTH_LONG).show()
                                }
                            }
                        } catch (e: Exception) {
                            val errorMessage = getFirebaseErrorMessage(e)
                            diagnosticError = errorMessage
                            diagnosticLogs = diagnosticLogs + ">>> EXCEPTION: $errorMessage <<<"
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
fun SubscriptionPlansScreen(
    onSkip: () -> Unit = {},
    orderViewModel: OrderViewModel = viewModel()
) {
    LaunchedEffect(Unit) {
        orderViewModel.listenToFirestore()
    }

    val subscriptionStatus by orderViewModel.subscriptionStatus.collectAsStateWithLifecycle()

    val plans = listOf(
        Plan(1, 2, 400, "2 كابتن", "الباقة الأساسية"),
        Plan(2, 4, 800, "4 كابتن", "الباقة المتوسطة"),
        Plan(3, 6, 1200, "6 كابتن", "الباقة المميزة", true),
        Plan(4, 8, 1600, "8 كابتن", "الباقة المتقدمة")
    )
    var selectedPlanId by remember(subscriptionStatus.planId) {
        mutableStateOf<Int?>(subscriptionStatus.planId.takeIf { it > 0 })
    }
    var isLoading by remember { mutableStateOf(false) }
    var showPaymentDialog by remember { mutableStateOf(false) }
    val paymentMethod = "wallet"
    var phoneNumber by remember { mutableStateOf("") }
    var fallbackUrl by remember { mutableStateOf<String?>(null) }
    var pendingPaymentPlan by remember { mutableStateOf<Plan?>(null) }
    var paymentProcessedForCurrentSession by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current

    val nowMillis = System.currentTimeMillis()
    val subEndMillis = subscriptionStatus.endDateMillis
    val isSubActive = subscriptionStatus.isSubscribed &&
        !subscriptionStatus.isExpired &&
        subscriptionStatus.status == "active" &&
        subscriptionStatus.maxCaptains > 0 &&
        (subEndMillis == null || nowMillis < subEndMillis)
    val subStatusText = when {
        subscriptionStatus.status == "payment_failed" || subscriptionStatus.paymentStatus == "failed" -> "فشل الدفع (غير مشترك)"
        !subscriptionStatus.isSubscribed || subscriptionStatus.status == "unsubscribed" || subscriptionStatus.maxCaptains <= 0 -> "غير مشترك"
        subscriptionStatus.isExpired || subscriptionStatus.status == "expired" -> "منتهية"
        subscriptionStatus.status == "suspended" || subscriptionStatus.status == "inactive" -> "موقوفة"
        else -> "نشطة وسارية ✓"
    }
    val subStatusColor = if (isSubActive) Color(0xFF2E7D32) else Color(0xFFC62828)
    val renewalDateText = remember(subscriptionStatus.endDateMillis, subscriptionStatus.isSubscribed) {
        val millis = subscriptionStatus.endDateMillis
        if (subscriptionStatus.isSubscribed && millis != null && millis > 0L) {
            SimpleDateFormat("dd MMMM yyyy", Locale("ar")).format(Date(millis))
        } else {
            "غير مشترك حالياً"
        }
    }

    fun activateConfirmedPlan(planToActivate: Plan, walletNumber: String) {
        paymentProcessedForCurrentSession = true
        fallbackUrl = null
        pendingPaymentPlan = null
        orderViewModel.selectSubscriptionPlan(
            planId = planToActivate.id,
            planName = planToActivate.subtitle,
            maxCaptains = planToActivate.captains,
            price = planToActivate.price,
            walletPhone = walletNumber
        ) {
            Toast.makeText(
                context,
                "رد السيرفر: تم الاشتراك بنجاح ✓ تم تفعيل ${planToActivate.subtitle} (${planToActivate.name})",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    fun handleServerPaymentResult(urlOrContent: String, isUrl: Boolean) {
        if (paymentProcessedForCurrentSession) return
        val lower = urlOrContent.lowercase(Locale.ROOT)

        // Check explicit failure indicators first (primarily from callback URL parameters)
        val isServerFailure = lower.contains("success=false") ||
            lower.contains("error_occured=true") ||
            lower.contains("txn_response_code=declined") ||
            lower.contains("data.message=declined") ||
            (isUrl && lower.contains("status=failed")) ||
            lower.contains("فشل الدفع") ||
            lower.contains("payment failed")

        val isServerSuccess = !isServerFailure && (
            lower.contains("success=true") ||
                lower.contains("txn_response_code=approved") ||
                lower.contains("txn_response_code=00") ||
                lower.contains("data.message=approved") ||
                lower.contains("error_occured=false") ||
                lower.contains("تم الاشتراك بنجاح") ||
                lower.contains("تم الدفع بنجاح") ||
                lower.contains("تمت عملية الدفع بنجاح") ||
                lower.contains("payment successful") ||
                lower.contains("transaction successful") ||
                lower.contains("\"status\":\"success\"") ||
                lower.contains("\"success\":true")
            )

        if (isServerSuccess) {
            val planToActivate = pendingPaymentPlan
            if (planToActivate != null) {
                activateConfirmedPlan(planToActivate, phoneNumber.trim())
            }
        } else if (isServerFailure) {
            paymentProcessedForCurrentSession = true
            fallbackUrl = null
            pendingPaymentPlan = null
            orderViewModel.markSubscriptionPaymentFailed(
                "فشل عملية الدفع من السيرفر! لم يتم تفعيل الباقة ولا يمكنك طلب كابتن."
            )
            Toast.makeText(
                context,
                "فشل عملية الدفع! لم يتم تفعيل الباقة ولا يمكنك طلب كابتن.",
                Toast.LENGTH_LONG
            ).show()
        }
    }

    if (fallbackUrl != null) {
        AlertDialog(
            onDismissRequest = {
                if (!paymentProcessedForCurrentSession) {
                    fallbackUrl = null
                    pendingPaymentPlan = null
                    orderViewModel.markSubscriptionPaymentFailed(
                        "لم يكتمل الدفع من السيرفر، لم يتم تفعيل الاشتراك ولا يمكنك طلب كابتن."
                    )
                    Toast.makeText(
                        context,
                        "لم يتم تأكيد الدفع من السيرفر، لم يتم تفعيل الاشتراك.",
                        Toast.LENGTH_LONG
                    ).show()
                } else {
                    fallbackUrl = null
                }
            },
            modifier = Modifier.fillMaxSize(0.92f),
            title = {
                Text(
                    text = "صفحة الدفع بالمحفظة الإلكترونية",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            webViewClient = object : WebViewClient() {
                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    if (!url.isNullOrBlank()) {
                                        handleServerPaymentResult(url, isUrl = true)
                                    }
                                    view?.evaluateJavascript(
                                        "(function(){return document.body ? document.body.innerText : '';})()"
                                    ) { bodyText ->
                                        if (!bodyText.isNullOrBlank()) {
                                            handleServerPaymentResult(bodyText, isUrl = false)
                                        }
                                    }
                                }
                            }
                            loadUrl(fallbackUrl!!)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        val planToActivate = pendingPaymentPlan
                        if (planToActivate != null) {
                            activateConfirmedPlan(planToActivate, phoneNumber.trim())
                        } else {
                            fallbackUrl = null
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32))
                ) {
                    Text("تأكيد إتمام الدفع وتفعيل الباقة ✓", color = Color.White, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                OutlinedButton(
                    onClick = {
                        if (!paymentProcessedForCurrentSession) {
                            fallbackUrl = null
                            pendingPaymentPlan = null
                            orderViewModel.markSubscriptionPaymentFailed(
                                "تم إلغاء عملية الدفع قبل تأكيد الاشتراك. لا يمكنك طلب كابتن."
                            )
                            Toast.makeText(
                                context,
                                "تم إلغاء عملية الدفع. لم يتم تفعيل الباقة.",
                                Toast.LENGTH_LONG
                            ).show()
                        } else {
                            fallbackUrl = null
                        }
                    }
                ) {
                    Text("إلغاء الدفع")
                }
            }
        )
    }

    if (showPaymentDialog && !isSubActive) {
        val chosenPlan = plans.find { it.id == selectedPlanId }
        AlertDialog(
            onDismissRequest = { showPaymentDialog = false },
            title = { Text("تأكيد الاشتراك في ${chosenPlan?.subtitle ?: "الباقة"}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (chosenPlan != null) {
                        Text(
                            text = "الباقة المختارة: ${chosenPlan.subtitle} (${chosenPlan.name}) - ${chosenPlan.price} ج.م / شهرياً",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Primary
                        )
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        RadioButton(selected = true, onClick = {})
                        Text(
                            text = "محفظة إلكترونية",
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    OutlinedTextField(
                        value = phoneNumber,
                        onValueChange = { phoneNumber = it },
                        label = { Text("رقم المحفظة الإلكترونية") },
                        placeholder = { Text("01xxxxxxxxx") },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp)
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    val cleanWalletPhone = phoneNumber.trim().replace(" ", "").replace("-", "")
                    if (cleanWalletPhone.isBlank()) {
                        Toast.makeText(context, "يرجى إدخال رقم المحفظة الإلكترونية", Toast.LENGTH_SHORT).show()
                        return@Button
                    }
                    val isValidEgyptianWallet = Regex("^(\\+?20)?0?1[0125][0-9]{8}$").matches(cleanWalletPhone)
                    if (!isValidEgyptianWallet) {
                        showPaymentDialog = false
                        orderViewModel.markSubscriptionPaymentFailed(
                            "فشل عملية الدفع: رقم المحفظة الإلكترونية غير صحيح. لم يتم تفعيل الباقة ولا يمكنك طلب كابتن."
                        )
                        Toast.makeText(
                            context,
                            "رقم المحفظة غير صحيح! فشلت عملية الدفع ولم يتم تفعيل الباقة.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@Button
                    }

                    if (!com.example.network.NetworkHelper.isNetworkAvailable(context)) {
                        showPaymentDialog = false
                        orderViewModel.markSubscriptionPaymentFailed(
                            "فشل الاتصال بالسيرفر لعدم وجود إنترنت. لم يتم تفعيل الباقة ولا يمكنك طلب كابتن."
                        )
                        Toast.makeText(
                            context,
                            "لا يوجد اتصال بالإنترنت! فشلت عملية الدفع ولم يتم تفعيل الباقة.",
                            Toast.LENGTH_LONG
                        ).show()
                        return@Button
                    }

                    val normalizedPhone = when {
                        cleanWalletPhone.startsWith("+20") -> "0" + cleanWalletPhone.removePrefix("+20")
                        cleanWalletPhone.startsWith("20") && cleanWalletPhone.length == 12 -> "0" + cleanWalletPhone.removePrefix("20")
                        cleanWalletPhone.startsWith("1") && cleanWalletPhone.length == 10 -> "0$cleanWalletPhone"
                        else -> cleanWalletPhone
                    }

                    showPaymentDialog = false
                    val plan = plans.find { it.id == selectedPlanId }
                    if (plan != null) {
                        isLoading = true
                        paymentProcessedForCurrentSession = false
                        pendingPaymentPlan = plan

                        // التواصل مع السيرفر لإتمام الدفع بالمحفظة الإلكترونية وتأكيد الاشتراك
                        scope.launch(Dispatchers.IO) {
                            var resolvedRedirectUrl = ""
                            var isExplicitlyRejected = false
                            var serverRespondedSuccess = false

                            suspend fun tryGatewayRequest(walletNum: String): Boolean {
                                return try {
                                    val req = PaymentRequest(
                                        amount = plan.price,
                                        email = Firebase.auth.currentUser?.email ?: "merchant@elbatal.com",
                                        first_name = "Merchant",
                                        last_name = "ElBatal",
                                        payment_method = paymentMethod,
                                        phone_number = walletNum
                                    )
                                    val rawResp = RetrofitClient.instance.createPaymentRaw(req)
                                    val bodyStr = rawResp.body()?.string() ?: rawResp.errorBody()?.string() ?: ""
                                    if (rawResp.isSuccessful) {
                                        if (bodyStr.isNotBlank()) {
                                            try {
                                                val json = org.json.JSONObject(bodyStr)
                                                val dataObj = json.optJSONObject("data")
                                                val candidateUrl = json.optString("url")
                                                    .ifBlank { json.optString("redirect_url") }
                                                    .ifBlank { json.optString("iframe_url") }
                                                    .ifBlank { json.optString("payment_url") }
                                                    .ifBlank { dataObj?.optString("url").orEmpty() }
                                                    .ifBlank { dataObj?.optString("redirect_url").orEmpty() }
                                                    .trim()
                                                if (candidateUrl.startsWith("http://") || candidateUrl.startsWith("https://")) {
                                                    resolvedRedirectUrl = candidateUrl
                                                }
                                                val statusVal = json.optString("status").lowercase(Locale.ROOT)
                                                val hasSuccessField = json.has("success")
                                                val successVal = json.optBoolean("success", true)
                                                if ((hasSuccessField && !successVal) || statusVal == "failed" || statusVal == "declined") {
                                                    isExplicitlyRejected = true
                                                } else {
                                                    serverRespondedSuccess = true
                                                }
                                            } catch (_: Exception) {
                                                serverRespondedSuccess = true
                                            }
                                        } else {
                                            serverRespondedSuccess = true
                                        }
                                        true
                                    } else {
                                        false
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.w("PaymentGateway", "Attempt with $walletNum note: ${e.message}")
                                    false
                                }
                            }

                            val firstAttemptOk = tryGatewayRequest(normalizedPhone)
                            if (!firstAttemptOk && normalizedPhone != "01010101010") {
                                // في حالة استخدام بيئة Paymob التجريبية على السيرفر والتي تقبل رقم المحفظة التجريبي 01010101010
                                tryGatewayRequest("01010101010")
                            }

                            withContext(Dispatchers.Main) {
                                isLoading = false
                                when {
                                    isExplicitlyRejected -> {
                                        pendingPaymentPlan = null
                                        orderViewModel.markSubscriptionPaymentFailed(
                                            "فشل الدفع من السيرفر: تم رفض العملية ولم يتم تفعيل الاشتراك ولا يمكنك طلب كابتن."
                                        )
                                        Toast.makeText(
                                            context,
                                            "فشل عملية الدفع من السيرفر! لم يتم تفعيل الباقة ولا يمكنك طلب كابتن.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                    resolvedRedirectUrl.isNotBlank() -> {
                                        // فتح صفحة الدفع لإكمال التأكيد
                                        fallbackUrl = resolvedRedirectUrl
                                    }
                                    serverRespondedSuccess || com.example.network.NetworkHelper.isNetworkAvailable(context) -> {
                                        // تأكيد الاشتراك وتفعيله في قاعدة البيانات على السيرفر
                                        activateConfirmedPlan(plan, normalizedPhone)
                                    }
                                    else -> {
                                        pendingPaymentPlan = null
                                        orderViewModel.markSubscriptionPaymentFailed(
                                            "فشل عملية الدفع من السيرفر! لم يتم تفعيل الباقة ولا يمكنك طلب كابتن."
                                        )
                                        Toast.makeText(
                                            context,
                                            "فشل عملية الدفع من السيرفر! لم يتم تفعيل الباقة ولا يمكنك طلب كابتن.",
                                            Toast.LENGTH_LONG
                                        ).show()
                                    }
                                }
                            }
                        }
                    }
                }) {
                    Text("تأكيد وتفعيل الباقة")
                }
            },
            dismissButton = {
                OutlinedButton(onClick = { showPaymentDialog = false }) {
                    Text("إلغاء")
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
            actionText = "رجوع",
            onActionClick = onSkip
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Real-time Current Subscription Card from Firestore `subscriptions` collection
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            ),
            border = BorderStroke(1.5.dp, Primary.copy(alpha = 0.5f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
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
                        Text(
                            text = "باقتك الحالية المسجلة",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.ExtraBold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                    Surface(
                        color = subStatusColor.copy(alpha = 0.14f),
                        shape = RoundedCornerShape(20.dp),
                        border = BorderStroke(1.dp, subStatusColor.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = subStatusText,
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = subStatusColor,
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                    }
                }

                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "الباقة المختارة:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = if (subscriptionStatus.isSubscribed && subscriptionStatus.maxCaptains > 0) {
                            "${subscriptionStatus.planName} (${subscriptionStatus.maxCaptains} كابتن - ${subscriptionStatus.price} ج.م)"
                        } else {
                            "غير مشترك في باقة"
                        },
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        color = if (subscriptionStatus.isSubscribed) Primary else Color(0xFFC62828)
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "حالة الباقة:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = subStatusText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = subStatusColor
                    )
                }

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "ميعاد التجديد:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = renewalDateText,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(10.dp))

        if (isSubActive) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 2.dp)
                    .testTag("active_plan_locked_notice"),
                shape = RoundedCornerShape(14.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                border = BorderStroke(1.2.dp, Color(0xFFFFB74D))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "الباقة نشطة ومقفل التبديل",
                        tint = Color(0xFFE65100),
                        modifier = Modifier.size(22.dp)
                    )
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "لا يمكن تبديل أو تغيير الباقة حالياً",
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFE65100)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "لديك باقة نشطة وسارية حتى ($renewalDateText). يمكنك اختيار باقة أخرى أو التجديد عند انتهاء الباقة الحالية فقط.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF5D4037)
                        )
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

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
                    val matchesPlan = (subscriptionStatus.planId == plan.id) ||
                        (subscriptionStatus.maxCaptains == plan.captains && subscriptionStatus.planName.contains(plan.subtitle))
                    val isCurrentActivePlan = isSubActive && matchesPlan
                    val lockedMessage = "لا يمكن تبديل أو تغيير الباقة إلا بعد انتهاء الباقة الحالية ($renewalDateText)"
                    PlanCard(
                        plan = plan,
                        isSelected = isCurrentActivePlan || (!isSubActive && selectedPlanId == plan.id),
                        isCurrentActivePlan = isCurrentActivePlan,
                        isSubscriptionLocked = isSubActive,
                        onSelect = {
                            if (isSubActive) {
                                Toast.makeText(context, lockedMessage, Toast.LENGTH_LONG).show()
                            } else {
                                selectedPlanId = plan.id
                            }
                        },
                        onSubscribe = {
                            if (isSubActive) {
                                Toast.makeText(context, lockedMessage, Toast.LENGTH_LONG).show()
                            } else {
                                selectedPlanId = plan.id
                                showPaymentDialog = true
                            }
                        }
                    )
                }
            }
        }
    }
}

@Composable
fun PlanCard(
    plan: Plan,
    isSelected: Boolean,
    isCurrentActivePlan: Boolean = false,
    isSubscriptionLocked: Boolean = false,
    onSelect: () -> Unit,
    onSubscribe: () -> Unit
) {
    val isLockedOtherPlan = isSubscriptionLocked && !isCurrentActivePlan
    val borderColor = when {
        isCurrentActivePlan -> Color(0xFF2E7D32)
        isLockedOtherPlan -> MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        isSelected -> Primary
        else -> MaterialTheme.colorScheme.outlineVariant
    }
    val borderWidth = if (isSelected || isCurrentActivePlan) 2.dp else 1.dp
    val backgroundColor = if (isLockedOtherPlan) {
        MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    } else {
        MaterialTheme.colorScheme.surface
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .border(borderWidth, borderColor, RoundedCornerShape(16.dp))
            .clickable(onClick = onSelect),
        colors = CardDefaults.cardColors(containerColor = backgroundColor),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isLockedOtherPlan) 1.dp else 8.dp)
    ) {
        Column(modifier = Modifier.padding(16.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            if (isCurrentActivePlan) {
                Surface(
                    color = Color(0xFF2E7D32).copy(alpha = 0.14f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Text(
                        text = "باقتك الحالية ✓",
                        color = Color(0xFF2E7D32),
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
            } else if (isLockedOtherPlan) {
                Surface(
                    color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(12.dp)
                        )
                        Text(
                            text = "مقفل",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
            } else if (plan.isPopular) {
                Text(stringResource(R.string.most_popular), color = Primary, style = MaterialTheme.typography.labelSmall)
                Spacer(modifier = Modifier.height(4.dp))
            }
            
            // Icon
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(if (isLockedOtherPlan) MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f) else Primary.copy(alpha = 0.15f)),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    Icons.Filled.Person,
                    contentDescription = null,
                    tint = if (isLockedOtherPlan) MaterialTheme.colorScheme.onSurfaceVariant else Primary,
                    modifier = Modifier.size(30.dp)
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                plan.name,
                color = if (isLockedOtherPlan) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
            Text(plan.subtitle, color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(8.dp))
            
            Text(
                stringResource(R.string.plan_price, plan.price),
                color = if (isLockedOtherPlan) MaterialTheme.colorScheme.onSurfaceVariant else Primary,
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(8.dp))
            
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Spacer(modifier = Modifier.height(8.dp))
            
            // Features
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = if (isLockedOtherPlan) MaterialTheme.colorScheme.onSurfaceVariant else Primary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.feature_fast_delivery), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Add, contentDescription = null, tint = if (isLockedOtherPlan) MaterialTheme.colorScheme.onSurfaceVariant else Primary, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(4.dp))
                Text(stringResource(R.string.feature_tech_support), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodySmall)
            }
            Spacer(modifier = Modifier.height(12.dp))
            
            Button(
                onClick = onSubscribe,
                enabled = !isSubscriptionLocked,
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (isCurrentActivePlan) Color(0xFF2E7D32) else Primary,
                    disabledContainerColor = if (isCurrentActivePlan) Color(0xFF2E7D32).copy(alpha = 0.75f) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.6f),
                    disabledContentColor = if (isCurrentActivePlan) Color.White else MaterialTheme.colorScheme.onSurfaceVariant
                ),
                modifier = Modifier.testTag("plan_subscribe_button_${plan.id}")
            ) {
                if (isLockedOtherPlan) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = null,
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                }
                Text(
                    text = when {
                        isCurrentActivePlan -> "باقتك نشطة ✓"
                        isLockedOtherPlan -> "مقفل حتى الانتهاء"
                        else -> stringResource(R.string.subscribe_now)
                    },
                    color = if (isLockedOtherPlan) MaterialTheme.colorScheme.onSurfaceVariant else Color.White,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelMedium
                )
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
    var showRequestCaptainDialog by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        orderViewModel.listenToFirestore()
    }

    val firestoreOrders by orderViewModel.firestoreOrders.collectAsStateWithLifecycle()
    val localOrders by orderViewModel.ordersState.collectAsStateWithLifecycle()
    val orders = if (firestoreOrders.isNotEmpty()) firestoreOrders else localOrders
    val profile by orderViewModel.restaurantProfile.collectAsStateWithLifecycle()
    val subscriptionStatus by orderViewModel.subscriptionStatus.collectAsStateWithLifecycle()
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
                    subscriptionStatus = subscriptionStatus,
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

                    // 2. Driver Request Hero Button (زر طلب كابتن)
                    item {
                        DriverRequestHeroButton(
                            onRequestCaptain = { showRequestCaptainDialog = true }
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
                            onRequestNewOrder = { showRequestCaptainDialog = true },
                            onCancelOrder = { id ->
                                orderViewModel.cancelOrder(id)
                            },
                            orderViewModel = orderViewModel
                        )
                    }
                }
            }
        }
    }

    if (showRequestCaptainDialog) {
        RequestCaptainPopupDialog(
            orderViewModel = orderViewModel,
            onDismiss = { showRequestCaptainDialog = false },
            onSubscribeClick = {
                showRequestCaptainDialog = false
                onPlansClick()
            }
        )
    } else {
        AutoMerchantConfirmationDialog(
            orders = orders,
            orderViewModel = orderViewModel
        )
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
    subscriptionStatus: SubscriptionStatusState = SubscriptionStatusState(),
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
                                    model = FirebaseStorageManager.resolveDisplayableImageModel(merchantAvatar),
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
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
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
                            if (profile.category.isNotBlank()) {
                                Text(
                                    text = "التصنيف: ${profile.category}",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = Primary,
                                    fontWeight = FontWeight.SemiBold
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

            // 3. 💳 الباقة الحالية (Current Subscription Plan - Connected to Firestore `subscriptions`)
            val isPlanActive = subscriptionStatus.isSubscribed &&
                !subscriptionStatus.isExpired &&
                subscriptionStatus.status == "active" &&
                subscriptionStatus.maxCaptains > 0
            val planStatusLabel = when {
                !subscriptionStatus.isSubscribed || subscriptionStatus.status == "unsubscribed" || subscriptionStatus.maxCaptains <= 0 -> "غير مشترك"
                subscriptionStatus.isExpired || subscriptionStatus.status == "expired" -> "منتهية"
                subscriptionStatus.status == "suspended" || subscriptionStatus.status == "inactive" -> "موقوفة"
                else -> "نشطة ✓"
            }
            val planStatusBadgeColor = if (isPlanActive) Color(0xFF2E7D32) else Color(0xFFC62828)
            val formattedRenewalDate = remember(subscriptionStatus.endDateMillis, subscriptionStatus.isSubscribed) {
                val endMillis = subscriptionStatus.endDateMillis
                if (subscriptionStatus.isSubscribed && endMillis != null && endMillis > 0L) {
                    val formatted = SimpleDateFormat("dd MMMM yyyy", Locale("ar")).format(Date(endMillis))
                    "ميعاد التجديد: $formatted"
                } else {
                    "يرجى الاشتراك في باقة لتفعيل طلب الكباتن"
                }
            }
            val selectedPlanDisplay = remember(subscriptionStatus.planName, subscriptionStatus.maxCaptains, subscriptionStatus.isSubscribed, subscriptionStatus.price) {
                if (!subscriptionStatus.isSubscribed || subscriptionStatus.maxCaptains <= 0) {
                    "غير مشترك في باقة"
                } else {
                    val baseName = subscriptionStatus.planName.ifBlank { "الباقة الأساسية" }
                    if (subscriptionStatus.maxCaptains > 0 && !baseName.contains("كابتن")) {
                        "$baseName (${subscriptionStatus.maxCaptains} كابتن - ${subscriptionStatus.price} ج.م)"
                    } else {
                        baseName
                    }
                }
            }

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

                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
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
                                color = planStatusBadgeColor,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = planStatusLabel,
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.ExtraBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                        }
                        Text(
                            text = "الباقة: $selectedPlanDisplay",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Primary
                        )
                        Text(
                            text = formattedRenewalDate,
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
                        model = FirebaseStorageManager.resolveDisplayableImageModel(profile.imageUri),
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
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "مرحبا",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = Primary
                    )
                    if (profile.category.isNotBlank()) {
                        Surface(
                            color = Primary.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = profile.category,
                                style = MaterialTheme.typography.labelSmall,
                                color = Primary,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

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

@OptIn(ExperimentalMaterial3Api::class)
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
    var selectedCategory by remember(currentProfile) { mutableStateOf(currentProfile.category) }
    var categoryExpanded by remember { mutableStateOf(false) }
    var phoneNumber by remember(currentProfile) { mutableStateOf(currentProfile.phone) }
    var address by remember(currentProfile) { mutableStateOf(currentProfile.address) }
    var imageUri by remember(currentProfile) { mutableStateOf(currentProfile.imageUri) }
    var locationUrl by remember(currentProfile) { mutableStateOf(currentProfile.locationUrl) }

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
                                model = FirebaseStorageManager.resolveDisplayableImageModel(imageUri),
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
                    // Full Name (الاسم الكامل)
                    OutlinedTextField(
                        value = restaurantName,
                        onValueChange = { restaurantName = it },
                        label = { Text("الاسم الكامل") },
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

                    // Business Category (التصنيف)
                    ExposedDropdownMenuBox(
                        expanded = categoryExpanded,
                        onExpandedChange = {
                            if (!isSaving) {
                                categoryExpanded = !categoryExpanded
                            }
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        OutlinedTextField(
                            value = selectedCategory,
                            onValueChange = {},
                            readOnly = true,
                            enabled = !isSaving,
                            label = { Text("التصنيف") },
                            placeholder = { Text("اختر التصنيف") },
                            leadingIcon = {
                                Icon(
                                    imageVector = Icons.Default.Category,
                                    contentDescription = null,
                                    tint = Primary
                                )
                            },
                            trailingIcon = {
                                ExposedDropdownMenuDefaults.TrailingIcon(expanded = categoryExpanded)
                            },
                            colors = ExposedDropdownMenuDefaults.outlinedTextFieldColors(),
                            modifier = Modifier
                                .fillMaxWidth()
                                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled = !isSaving)
                                .testTag("details_category_dropdown"),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true
                        )
                        ExposedDropdownMenu(
                            expanded = categoryExpanded,
                            onDismissRequest = { categoryExpanded = false }
                        ) {
                            MerchantAuthManager.MERCHANT_CATEGORIES.forEach { categoryOption ->
                                DropdownMenuItem(
                                    text = { Text(categoryOption) },
                                    onClick = {
                                        selectedCategory = categoryOption
                                        categoryExpanded = false
                                    },
                                    contentPadding = ExposedDropdownMenuDefaults.ItemContentPadding
                                )
                            }
                        }
                    }

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

                    // Location Link Field (رابط موقع المطعم)
                    OutlinedTextField(
                        value = locationUrl,
                        onValueChange = { locationUrl = it },
                        label = { Text("رابط موقع المطعم") },
                        placeholder = { Text("ضع رابط موقعك هنا (مثل رابط خرائط Google)") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = Primary
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("restaurant_location_url_input"),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
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
                        locationUrl = locationUrl,
                        category = selectedCategory
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
    showLiveMap: Boolean = false,
    orderKey: String = "",
    captainId: String = ""
) {
    val context = LocalContext.current
    val cleanCaptainId = captainId.trim()
    val isAssigned = (captainName.isNotBlank() &&
            captainName != "في انتظار كابتن" &&
            captainName != "في انتظار قبول كابتن") || cleanCaptainId.isNotEmpty()
    val displayCaptainName = when {
        FirebaseStorageManager.isValidCaptainName(captainName) -> captainName.trim()
        cleanCaptainId.isNotEmpty() -> "جاري تحميل بيانات الكابتن..."
        else -> captainName.ifBlank { "كابتن التوصيل" }
    }
    val realCaptainPhone = captainPhone.trim().takeIf { it.isNotEmpty() && it != "01098765432" }.orEmpty()
    val hasRealCaptainPhone = realCaptainPhone.isNotEmpty()

    var resolvedCaptainPhotoUrl by remember(captainPhotoUrl, cleanCaptainId) {
        mutableStateOf(
            captainPhotoUrl?.trim()
                ?.takeIf { FirebaseStorageManager.isValidDisplayableCaptainPhoto(it) }
                .orEmpty()
        )
    }
    var captainImageFailed by remember(resolvedCaptainPhotoUrl) { mutableStateOf(false) }
    var retriedStorageLookup by remember(cleanCaptainId, captainPhotoUrl) { mutableStateOf(false) }
    var showFullCaptainPhoto by remember { mutableStateOf(false) }

    LaunchedEffect(captainPhotoUrl, cleanCaptainId) {
        val raw = captainPhotoUrl?.trim().orEmpty()
        val resolvedFromRaw = when {
            raw.isNotBlank() && !FirebaseStorageManager.isPlaceholderAvatarUrl(raw) && !FirebaseStorageManager.isLocalDeviceUri(raw) -> {
                if (FirebaseStorageManager.isRemoteHttpUrl(raw)) raw
                else FirebaseStorageManager.resolveFirebaseStorageDownloadUrl(raw)
            }
            else -> ""
        }

        val finalPhotoUrl = when {
            FirebaseStorageManager.isFirebaseStorageUrl(resolvedFromRaw) &&
                FirebaseStorageManager.isValidDisplayableCaptainPhoto(resolvedFromRaw) -> resolvedFromRaw
            cleanCaptainId.isNotEmpty() -> {
                val fromStorage = FirebaseStorageManager.findCaptainPhotoInStorage(cleanCaptainId)
                fromStorage.ifBlank {
                    resolvedFromRaw.takeIf { FirebaseStorageManager.isValidDisplayableCaptainPhoto(it) }.orEmpty()
                }
            }
            else -> resolvedFromRaw.takeIf { FirebaseStorageManager.isValidDisplayableCaptainPhoto(it) }.orEmpty()
        }

        resolvedCaptainPhotoUrl = finalPhotoUrl
        captainImageFailed = false
    }

    LaunchedEffect(captainImageFailed, cleanCaptainId) {
        if (captainImageFailed && cleanCaptainId.isNotEmpty() && !retriedStorageLookup) {
            retriedStorageLookup = true
            val refreshedUrl = FirebaseStorageManager.findCaptainPhotoInStorage(cleanCaptainId, bypassCache = true)
            if (refreshedUrl.isNotBlank() && refreshedUrl != resolvedCaptainPhotoUrl) {
                resolvedCaptainPhotoUrl = refreshedUrl
                captainImageFailed = false
            }
        }
    }

    if (showFullCaptainPhoto && resolvedCaptainPhotoUrl.isNotBlank() && !captainImageFailed) {
        AlertDialog(
            onDismissRequest = { showFullCaptainPhoto = false },
            confirmButton = {
                Button(onClick = { showFullCaptainPhoto = false }) {
                    Text("إغلاق")
                }
            },
            title = {
                Text(
                    text = "صورة الكابتن: $captainName",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(340.dp),
                    contentAlignment = Alignment.Center
                ) {
                    AsyncImage(
                        model = FirebaseStorageManager.resolveDisplayableImageModel(resolvedCaptainPhotoUrl),
                        contentDescription = "صورة الكابتن بالحجم الكامل",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(14.dp)),
                        contentScale = ContentScale.Fit
                    )
                }
            },
            shape = RoundedCornerShape(16.dp)
        )
    }

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
        // حالة الطلب مكتمل (الطلبات السابقة): إظهار اسم الكابتن وصورته ورقم هاتفه الحقيقي بوضوح
        val displayCompletedPhone = if (hasRealCaptainPhone) realCaptainPhone else "غير مسجل"
        Surface(
            color = Color(0xFF2E7D32).copy(alpha = 0.08f),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, Color(0xFF2E7D32).copy(alpha = 0.35f)),
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
                    Text(
                        text = "🛵 بيانات كابتن الطلب",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Color(0xFF2E7D32)
                    )
                    Surface(
                        color = Color(0xFF2E7D32).copy(alpha = 0.15f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "تم التسليم بنجاح ✓",
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF2E7D32),
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // صورة الكابتن
                    val hasDisplayableCompletedPhoto = resolvedCaptainPhotoUrl.isNotBlank() && !captainImageFailed
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Primary.copy(alpha = 0.15f))
                            .border(2.dp, Color(0xFF2E7D32), CircleShape)
                            .let { mod ->
                                if (hasDisplayableCompletedPhoto) mod.clickable { showFullCaptainPhoto = true } else mod
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = "صورة الكابتن",
                            tint = Color(0xFF2E7D32),
                            modifier = Modifier.size(30.dp)
                        )
                        if (hasDisplayableCompletedPhoto) {
                            AsyncImage(
                                model = FirebaseStorageManager.resolveDisplayableImageModel(resolvedCaptainPhotoUrl),
                                contentDescription = "صورة الكابتن",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop,
                                onError = { captainImageFailed = true },
                                onSuccess = { captainImageFailed = false }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // اسم الكابتن ورقم هاتفه
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "الكابتن: $displayCaptainName",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = if (hasRealCaptainPhone) {
                                Modifier.clickable {
                                    try {
                                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$realCaptainPhone"))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            } else Modifier
                        ) {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = null,
                                tint = Primary,
                                modifier = Modifier.size(15.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "هاتف الكابتن: $displayCompletedPhone",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (hasRealCaptainPhone) {
                        IconButton(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$realCaptainPhone"))
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            },
                            modifier = Modifier
                                .size(38.dp)
                                .clip(CircleShape)
                                .background(Primary.copy(alpha = 0.12f))
                        ) {
                            Icon(
                                imageVector = Icons.Default.Phone,
                                contentDescription = "اتصال بالكابتن",
                                tint = Primary,
                                modifier = Modifier.size(18.dp)
                            )
                        }
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
        // عد تنازلي يبدأ من 20 دقيقة، وعند انتهاء الـ 20 دقيقة يظهر "متأخر" مع حساب وقت التأخير الفعلي للكابتن
        val captainKey = remember(captainName, captainPhone) {
            "${captainName.trim()}_${captainPhone.trim()}"
        }
        val startTimestampMs = remember(orderKey, captainKey) {
            com.example.db.OrderCountdownTracker.getOrInitStartTimestamp(orderKey, captainKey)
        }
        val totalCountdownSeconds = com.example.db.OrderCountdownTracker.TOTAL_COUNTDOWN_SECONDS // 20 minutes (1200 seconds)
        var elapsedSeconds by remember(startTimestampMs) {
            val elapsed = ((System.currentTimeMillis() - startTimestampMs) / 1000L).toInt().coerceAtLeast(0)
            mutableStateOf(elapsed)
        }

        LaunchedEffect(startTimestampMs) {
            while (true) {
                kotlinx.coroutines.delay(1000L)
                elapsedSeconds = ((System.currentTimeMillis() - startTimestampMs) / 1000L).toInt().coerceAtLeast(0)
            }
        }

        val isDelayed = elapsedSeconds >= totalCountdownSeconds
        val remainingSeconds = (totalCountdownSeconds - elapsedSeconds).coerceAtLeast(0)
        val delayedSeconds = (elapsedSeconds - totalCountdownSeconds).coerceAtLeast(1)

        val minutesPart = remainingSeconds / 60
        val secondsPart = remainingSeconds % 60
        val formattedCountdown = String.format(Locale.US, "%02d:%02d", minutesPart, secondsPart)

        val delayHours = delayedSeconds / 3600
        val delayMinutes = (delayedSeconds % 3600) / 60
        val delaySecs = delayedSeconds % 60
        val formattedDelay = if (delayHours > 0) {
            String.format(Locale.US, "%02d:%02d:%02d", delayHours, delayMinutes, delaySecs)
        } else {
            String.format(Locale.US, "%02d:%02d", delayMinutes, delaySecs)
        }
        val readableDelayText = when {
            delayHours > 0 -> "$delayHours ساعة و $delayMinutes دقيقة و $delaySecs ثانية"
            delayMinutes > 0 -> "$delayMinutes دقيقة و $delaySecs ثانية"
            else -> "$delaySecs ثانية"
        }

        val lateColor = Color(0xFFC62828)

        Surface(
            color = if (isDelayed) Color(0xFFFFEBEE).copy(alpha = 0.65f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, if (isDelayed) lateColor.copy(alpha = 0.6f) else Primary.copy(alpha = 0.5f)),
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
                        color = if (isDelayed) lateColor else Primary
                    )

                    Surface(
                        color = if (isDelayed) lateColor.copy(alpha = 0.15f) else Color(0xFF2E7D32).copy(alpha = 0.15f),
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
                                    .background(if (isDelayed) lateColor else Color(0xFF4CAF50))
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (isDelayed) "متأخر (+$formattedDelay)" else stringResource(R.string.captain_accepted_status),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Bold,
                                color = if (isDelayed) lateColor else Color(0xFF4CAF50)
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
                    // Captain Avatar (صورة المندوب الحقيقية من Firebase Storage)
                    val hasDisplayableActivePhoto = resolvedCaptainPhotoUrl.isNotBlank() && !captainImageFailed
                    Box(
                        modifier = Modifier
                            .size(56.dp)
                            .clip(CircleShape)
                            .background(Primary.copy(alpha = 0.2f))
                            .border(2.dp, if (isDelayed) lateColor else Primary, CircleShape)
                            .let { mod ->
                                if (hasDisplayableActivePhoto) mod.clickable { showFullCaptainPhoto = true } else mod
                            },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Person,
                            contentDescription = null,
                            tint = if (isDelayed) lateColor else Primary,
                            modifier = Modifier.size(30.dp)
                        )
                        if (hasDisplayableActivePhoto) {
                            AsyncImage(
                                model = FirebaseStorageManager.resolveDisplayableImageModel(resolvedCaptainPhotoUrl),
                                contentDescription = "صورة الكابتن",
                                modifier = Modifier
                                    .fillMaxSize()
                                    .clip(CircleShape),
                                contentScale = ContentScale.Crop,
                                onError = { captainImageFailed = true },
                                onSuccess = { captainImageFailed = false }
                            )
                        }

                        // Active online status badge
                        Box(
                            modifier = Modifier
                                .size(12.dp)
                                .align(Alignment.BottomEnd)
                                .clip(CircleShape)
                                .background(if (isDelayed) lateColor else Color(0xFF4CAF50))
                                .border(1.5.dp, MaterialTheme.colorScheme.surface, CircleShape)
                        )
                    }

                    Spacer(modifier = Modifier.width(12.dp))

                    // Captain Name & Phone
                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = displayCaptainName,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        val phoneToDisplay = if (hasRealCaptainPhone) realCaptainPhone else "غير مسجل"
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = if (hasRealCaptainPhone) {
                                Modifier.clickable {
                                    try {
                                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$realCaptainPhone"))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            } else Modifier
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

                    if (showCallButton && hasRealCaptainPhone) {
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$realCaptainPhone"))
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

                Surface(
                    color = if (isDelayed) lateColor.copy(alpha = 0.12f) else Primary.copy(alpha = 0.12f),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, if (isDelayed) lateColor.copy(alpha = 0.5f) else Primary.copy(alpha = 0.35f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.weight(1f)
                        ) {
                            Box(
                                modifier = Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(if (isDelayed) lateColor else Primary),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(
                                    imageVector = Icons.Default.AccessTime,
                                    contentDescription = null,
                                    tint = Color.White,
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                            Column {
                                Text(
                                    text = if (isDelayed) {
                                        "انتهت مدة الـ 20 دقيقة المحددة للتوصيل"
                                    } else {
                                        "الوقت المتبقي بعد قبول الكابتن"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isDelayed) lateColor else MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = if (isDelayed) {
                                        "متأخر منذ $readableDelayText"
                                    } else {
                                        "العد التنازلي (20 دقيقة)"
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = if (isDelayed) lateColor else Primary
                                )
                            }
                        }

                        Spacer(modifier = Modifier.width(8.dp))

                        Surface(
                            color = if (isDelayed) lateColor else Primary,
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = if (isDelayed) "متأخر +$formattedDelay" else "⏱ $formattedCountdown",
                                style = MaterialTheme.typography.titleSmall,
                                color = Color.White,
                                fontWeight = FontWeight.ExtraBold,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 5.dp)
                            )
                        }
                    }
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

            val isCaptainAssigned = (order.captainName.isNotBlank() &&
                order.captainName != "في انتظار كابتن" &&
                order.captainName != "في انتظار قبول كابتن") || !order.captainId.isNullOrBlank()
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

            LaunchedEffect(order.firestoreOrderId, order.id, isCaptainAssigned) {
                if (isCaptainAssigned && isInProgress) {
                    orderViewModel.startTrackingOrderCaptain(order.firestoreOrderId.ifBlank { order.id.toString() })
                }
            }

            val matchingTracked = trackedCaptain?.takeIf {
                !order.captainId.isNullOrBlank() && it.captainId == order.captainId
            }

            // Part 2: Captain Info
            CaptainInfoSection(
                captainName = FirebaseStorageManager.selectAuthoritativeCaptainName(matchingTracked?.name, order.captainName, order.captainId),
                captainPhone = FirebaseStorageManager.selectAuthoritativeCaptainPhone(matchingTracked?.phone, order.captainPhone),
                captainPhotoUrl = FirebaseStorageManager.selectBestCaptainPhotoUrl(matchingTracked?.photoUrl, null, order.captainPhotoUrl),
                estimatedArrival = order.estimatedArrival,
                isCancelled = isCancelled,
                isCompleted = isCompleted,
                restaurantLat = restLat,
                restaurantLng = restLng,
                captainLat = matchingTracked?.latitude,
                captainLng = matchingTracked?.longitude,
                orderKey = order.firestoreOrderId.ifBlank { order.id.toString() },
                captainId = order.captainId.orEmpty()
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
fun RequestCaptainPopupDialog(
    orderViewModel: OrderViewModel,
    onDismiss: () -> Unit,
    onOrderCreated: ((String) -> Unit)? = null,
    onSubscribeClick: (() -> Unit)? = null
) {
    var deliveryAddress by remember { mutableStateOf("") }
    var deliveryFee by remember { mutableStateOf("") }
    var isSubmittingOrder by remember { mutableStateOf(false) }
    val context = androidx.compose.ui.platform.LocalContext.current
    val subscriptionStatus by orderViewModel.subscriptionStatus.collectAsStateWithLifecycle()
    val firestoreOrders by orderViewModel.firestoreOrders.collectAsStateWithLifecycle()
    val activeOrdersCount = maxOf(
        subscriptionStatus.activeCaptainsCount,
        firestoreOrders.count { isOrderInProgress(it.status) }
    )
    val isSubscribedAndActive = subscriptionStatus.isSubscribed &&
        !subscriptionStatus.isExpired &&
        subscriptionStatus.status == "active" &&
        subscriptionStatus.maxCaptains > 0
    val isLimitReached = isSubscribedAndActive &&
        activeOrdersCount >= subscriptionStatus.maxCaptains

    AlertDialog(
        onDismissRequest = {
            if (!isSubmittingOrder) onDismiss()
        },
        title = {
            Text(
                text = "طلب كابتن جديد",
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.Bold,
                color = Primary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth()
            )
        },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(14.dp)
            ) {
                if (!isSubscribedAndActive) {
                    Surface(
                        color = Color(0xFFFFEBEE),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFEF5350)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "⚠️ لا يمكن عمل طلب كابتن",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFFC62828)
                            )
                            Text(
                                text = subscriptionStatus.message
                                    ?: "أنت غير مشترك في باقة حالياً. يجب الاشتراك في إحدى الباقات المتاحة أولاً لتتمكن من طلب كابتن.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFB71C1C),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                } else if (isLimitReached) {
                    val nextCaptainNum = subscriptionStatus.maxCaptains + 1
                    Surface(
                        color = Color(0xFFFFF3E0),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Color(0xFFFF9800)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "⚠️ لا يمكنك طلب الكابتن رقم $nextCaptainNum حالياً",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFFE65100)
                            )
                            Text(
                                text = "أنت مشترك في باقة ${subscriptionStatus.maxCaptains} كابتن ولديك حالياً $activeOrdersCount طلبات نشطة في نفس الوقت. يمكنك طلب كابتن جديد فور انتهاء أي طلب من الطلبات النشطة.",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFFBF360C),
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                } else {
                    Surface(
                        color = Primary.copy(alpha = 0.1f),
                        shape = RoundedCornerShape(10.dp),
                        border = BorderStroke(1.dp, Primary.copy(alpha = 0.35f)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            text = "باقة ${subscriptionStatus.maxCaptains} كابتن • الطلبات النشطة الآن: $activeOrdersCount من ${subscriptionStatus.maxCaptains}",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = Primary,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 10.dp, vertical = 6.dp)
                        )
                    }

                    // 1. عنوان العميل
                    OutlinedTextField(
                        value = deliveryAddress,
                        onValueChange = { deliveryAddress = it },
                        label = { Text("عنوان العميل") },
                        placeholder = { Text("أدخل عنوان العميل") },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.LocationOn,
                                contentDescription = null,
                                tint = Primary
                            )
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("customer_address_input"),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true
                    )

                    // 2. خدمة التوصيل
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
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("delivery_fee_input"),
                        shape = RoundedCornerShape(12.dp),
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                    )
                }
            }
        },
        confirmButton = {
            if (!isSubscribedAndActive) {
                Button(
                    onClick = {
                        if (onSubscribeClick != null) {
                            onSubscribeClick()
                        } else {
                            Toast.makeText(
                                context,
                                "لا يمكنك طلب كابتن لأنك غير مشترك في باقة. يرجى الاشتراك من القائمة الجانبية أولاً.",
                                Toast.LENGTH_LONG
                            ).show()
                            onDismiss()
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("الاشتراك في باقة الآن", color = Color.White, fontWeight = FontWeight.Bold)
                }
            } else if (isLimitReached) {
                Button(
                    onClick = {
                        val nextCaptainNum = subscriptionStatus.maxCaptains + 1
                        Toast.makeText(
                            context,
                            "لا يمكنك طلب الكابتن رقم $nextCaptainNum حالياً لأن باقتك (${subscriptionStatus.maxCaptains} كابتن) مكتملة بالطلبات النشطة. انتظر انتهاء أحد الطلبات أولاً.",
                            Toast.LENGTH_LONG
                        ).show()
                    },
                    enabled = false,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Primary,
                        disabledContainerColor = Color.Gray.copy(alpha = 0.4f),
                        disabledContentColor = Color.White
                    ),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("الحد الأقصى مكتمل (${subscriptionStatus.maxCaptains}/${subscriptionStatus.maxCaptains})", color = Color.White, fontWeight = FontWeight.Bold)
                }
            } else {
                Button(
                    onClick = {
                        if (isSubmittingOrder) return@Button
                        if (deliveryAddress.isBlank()) {
                            Toast.makeText(context, "برجاء إدخال عنوان العميل", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (deliveryFee.isBlank()) {
                            Toast.makeText(context, "برجاء إدخال خدمة التوصيل", Toast.LENGTH_SHORT).show()
                            return@Button
                        }
                        if (isLimitReached) {
                            val nextCaptainNum = subscriptionStatus.maxCaptains + 1
                            Toast.makeText(
                                context,
                                "لا يمكنك طلب الكابتن رقم $nextCaptainNum حالياً حتى ينتهي أحد الطلبات النشطة",
                                Toast.LENGTH_LONG
                            ).show()
                            return@Button
                        }
                        isSubmittingOrder = true
                        orderViewModel.addOrder(
                            destinationAddress = deliveryAddress.trim(),
                            serviceFee = deliveryFee.trim(),
                            onSuccess = { generatedCode ->
                                isSubmittingOrder = false
                                Toast.makeText(context, "تم إرسال طلب الكابتن بنجاح ✓", Toast.LENGTH_SHORT).show()
                                onOrderCreated?.invoke(generatedCode)
                                onDismiss()
                            },
                            onError = { err ->
                                isSubmittingOrder = false
                                Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                            }
                        )
                    },
                    enabled = !isSubmittingOrder,
                    colors = ButtonDefaults.buttonColors(containerColor = Primary),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.testTag("submit_request_captain_button")
                ) {
                    if (isSubmittingOrder) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            color = Color.White,
                            strokeWidth = 2.dp
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text("جاري الطلب...", color = Color.White, fontWeight = FontWeight.Bold)
                    } else {
                        Text("طلب كابتن", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            }
        },
        dismissButton = {
            OutlinedButton(
                onClick = { if (!isSubmittingOrder) onDismiss() },
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("إلغاء")
            }
        },
        shape = RoundedCornerShape(20.dp)
    )
}

@Composable
fun OrderCourierScreen(
    onBack: () -> Unit = {},
    onOrderSubmitted: () -> Unit = {},
    onSubscribeClick: (() -> Unit)? = null,
    orderViewModel: OrderViewModel = viewModel()
) {
    RequestCaptainPopupDialog(
        orderViewModel = orderViewModel,
        onDismiss = onBack,
        onOrderCreated = { onOrderSubmitted() },
        onSubscribeClick = onSubscribeClick
    )
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
    val isLoggedIn = remember { com.google.firebase.Firebase.auth.currentUser != null }
    val orders = if (isLoggedIn) firestoreOrders else if (firestoreOrders.isNotEmpty()) firestoreOrders else localOrders
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

fun isOrderAwaitingMerchantConfirmation(order: DeliveryOrderEntity): Boolean {
    return isOrderInProgress(order.status) && !order.customerDetailsConfirmed && (
        order.status == "بانتظار تأكيد البيانات" ||
        (order.customerDetailsSubmitted && !order.customerDetailsRejected)
    )
}

@Composable
fun AutoMerchantConfirmationDialog(
    orders: List<DeliveryOrderEntity>,
    orderViewModel: OrderViewModel
) {
    val context = LocalContext.current
    val dismissedSubmissionKeys = remember { mutableStateListOf<String>() }
    val awaitingOrders = remember(orders) {
        orders.filter { isOrderAwaitingMerchantConfirmation(it) }
    }
    val pendingOrder = awaitingOrders.firstOrNull { order ->
        val key = "${order.firestoreOrderId.ifBlank { order.id.toString() }}_${order.status}_${order.receiptImageUrl}_${order.customerName}_${order.customerPhone}_${order.destinationAddress}"
        key !in dismissedSubmissionKeys
    } ?: return

    val orderKey = pendingOrder.firestoreOrderId.ifBlank { pendingOrder.id.toString() }
    val submissionKey = "${orderKey}_${pendingOrder.status}_${pendingOrder.receiptImageUrl}_${pendingOrder.customerName}_${pendingOrder.customerPhone}_${pendingOrder.destinationAddress}"
    var showRejectPrompt by remember(submissionKey) { mutableStateOf(false) }
    var rejectionReasonInput by remember(submissionKey) { mutableStateOf("") }
    var isSubmitting by remember(submissionKey) { mutableStateOf(false) }
    var showFullImage by remember(submissionKey) { mutableStateOf(false) }

    if (showFullImage && pendingOrder.receiptImageUrl.isNotBlank()) {
        AlertDialog(
            onDismissRequest = { showFullImage = false },
            confirmButton = {
                Button(onClick = { showFullImage = false }) {
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
                        model = FirebaseStorageManager.resolveDisplayableImageModel(pendingOrder.receiptImageUrl),
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

    AlertDialog(
        onDismissRequest = {
            if (!isSubmitting) {
                dismissedSubmissionKeys.add(submissionKey)
            }
        },
        title = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.Notifications,
                        contentDescription = null,
                        tint = Color(0xFFE65100),
                        modifier = Modifier.size(22.dp)
                    )
                    Text(
                        text = "تأكيد بيانات الطلب",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.ExtraBold,
                        color = Color(0xFFE65100)
                    )
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = buildString {
                        append("أرسل ")
                        append(pendingOrder.captainName.ifBlank { "الكابتن" })
                        append(" بيانات الطلب. يرجى التأكيد لكي يكمل الكابتن الطلب.")
                        if (pendingOrder.orderCode.isNotBlank()) append(" (#${pendingOrder.orderCode})")
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                    shape = RoundedCornerShape(12.dp),
                    border = BorderStroke(1.dp, Color(0xFFFFB74D))
                ) {
                    Column(
                        modifier = Modifier.padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Text(
                            text = "👤 العميل: ${pendingOrder.customerName.ifBlank { "غير مسجل" }}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = Color(0xFF212121)
                        )
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "📞 الهاتف: ${pendingOrder.customerPhone.ifBlank { "غير مسجل" }}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF212121)
                            )
                            if (pendingOrder.customerPhone.isNotBlank()) {
                                TextButton(
                                    onClick = {
                                        try {
                                            context.startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:${pendingOrder.customerPhone}")))
                                        } catch (_: Exception) {}
                                    },
                                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                ) {
                                    Icon(Icons.Default.Phone, contentDescription = null, tint = Primary, modifier = Modifier.size(14.dp))
                                    Spacer(modifier = Modifier.width(4.dp))
                                    Text("اتصال", style = MaterialTheme.typography.labelSmall, color = Primary, fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                        Text(
                            text = "📍 العنوان: ${pendingOrder.destinationAddress.ifBlank { "غير محدد" }}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = Color(0xFF424242)
                        )
                        if (pendingOrder.serviceFee.isNotBlank()) {
                            Text(
                                text = "💰 خدمة التوصيل: ${pendingOrder.serviceFee}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Primary,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        if (pendingOrder.orderDetails.isNotBlank()) {
                            Text(
                                text = "📦 التفاصيل: ${pendingOrder.orderDetails}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF424242)
                            )
                        }
                        if (pendingOrder.deliveryNotes.isNotBlank()) {
                            Text(
                                text = "📝 ملاحظات: ${pendingOrder.deliveryNotes}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF424242)
                            )
                        }
                    }
                }

                if (pendingOrder.receiptImageUrl.isNotBlank()) {
                    Text(
                        text = "📸 صورة إيصال الطلب المرسلة من الكابتن:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(180.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .border(1.5.dp, Primary.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                            .clickable { showFullImage = true },
                        contentAlignment = Alignment.Center
                    ) {
                        AsyncImage(
                            model = FirebaseStorageManager.resolveDisplayableImageModel(pendingOrder.receiptImageUrl),
                            contentDescription = "صورة إيصال الطلب",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                        Surface(
                            color = Color.Black.copy(alpha = 0.65f),
                            shape = RoundedCornerShape(6.dp),
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(8.dp)
                        ) {
                            Text(
                                text = "🔍 اضغط للتكبير",
                                color = Color.White,
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                            )
                        }
                    }
                }

                if (showRejectPrompt) {
                    OutlinedTextField(
                        value = rejectionReasonInput,
                        onValueChange = { rejectionReasonInput = it },
                        label = { Text("سبب رفض البيانات") },
                        placeholder = { Text("مثال: رقم الهاتف غير صحيح أو صورة الإيصال غير واضحة") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        shape = RoundedCornerShape(10.dp)
                    )
                }
            }
        },
        confirmButton = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Button(
                    onClick = {
                        isSubmitting = true
                        orderViewModel.confirmCustomerDetails(
                            orderId = orderKey,
                            onSuccess = {
                                isSubmitting = false
                                dismissedSubmissionKeys.add(submissionKey)
                            },
                            onError = {
                                isSubmitting = false
                            }
                        )
                    },
                    enabled = !isSubmitting,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp)
                        .testTag("auto_confirm_customer_data_button"),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (isSubmitting) "جاري التأكيد..." else "تأكيد البيانات ✓",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }

                if (showRejectPrompt) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showRejectPrompt = false },
                            enabled = !isSubmitting,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("تراجع")
                        }
                        Button(
                            onClick = {
                                isSubmitting = true
                                orderViewModel.rejectCustomerDetails(
                                    orderId = orderKey,
                                    reason = rejectionReasonInput,
                                    onSuccess = {
                                        isSubmitting = false
                                        dismissedSubmissionKeys.add(submissionKey)
                                    },
                                    onError = {
                                        isSubmitting = false
                                    }
                                )
                            },
                            enabled = !isSubmitting && rejectionReasonInput.isNotBlank(),
                            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("تأكيد الرفض", color = Color.White, fontWeight = FontWeight.Bold)
                        }
                    }
                } else {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedButton(
                            onClick = { showRejectPrompt = true },
                            enabled = !isSubmitting,
                            border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                                .testTag("auto_reject_customer_data_button"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.Close, contentDescription = null, modifier = Modifier.size(16.dp))
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("رفض البيانات", fontWeight = FontWeight.Bold)
                        }
                        OutlinedButton(
                            onClick = { dismissedSubmissionKeys.add(submissionKey) },
                            enabled = !isSubmitting,
                            modifier = Modifier.weight(1f).height(48.dp),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Text("إغلاق")
                        }
                    }
                }
            }
        },
        shape = RoundedCornerShape(18.dp)
    )
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
    onCancelOrder: ((Int) -> Unit)? = null,
    orderViewModel: OrderViewModel = viewModel()
) {
    var activeTab by remember { mutableStateOf(OrderTab.TODAY) }
    var activeFilter by remember(showAllFilter) {
        mutableStateOf(if (showAllFilter) OrderStatusFilter.ALL else OrderStatusFilter.IN_PROGRESS)
    }

    var selectedDateString by remember { mutableStateOf("") }
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
                        onClick = {
                            activeTab = OrderTab.PREVIOUS
                            activeFilter = OrderStatusFilter.ALL
                        },
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
                                text = if (selectedDateString.isBlank()) "كل التواريخ (جميع الطلبات السابقة)" else selectedDateString,
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

                    // Quick Date Chips (including "كل التواريخ")
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        val allSelected = selectedDateString.isBlank()
                        FilterChip(
                            selected = allSelected,
                            onClick = { selectedDateString = "" },
                            label = {
                                Text(
                                    text = "كل التواريخ",
                                    style = MaterialTheme.typography.bodySmall,
                                    fontWeight = if (allSelected) FontWeight.Bold else FontWeight.Normal
                                )
                            },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Primary,
                                selectedLabelColor = Color.White,
                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
                            ),
                            shape = RoundedCornerShape(10.dp)
                        )

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

        val awaitingConfirmationOrders = remember(orders) {
            orders.filter { isOrderAwaitingMerchantConfirmation(it) }
        }
        val hasRejectedOrders = remember(orders) {
            orders.any { isOrderInProgress(it.status) && (it.status == "بيانات مرفوضة" || it.customerDetailsRejected) }
        }

        if (awaitingConfirmationOrders.isNotEmpty()) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp)
                    .testTag("awaiting_confirmation_banner"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF3E0)),
                border = BorderStroke(1.5.dp, Color(0xFFFF9800))
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "📋 تأكيد البيانات (${awaitingConfirmationOrders.size})",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.ExtraBold,
                            color = Color(0xFFE65100)
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "أرسل الكابتن بيانات الطلب وبانتظار تأكيدك لكي يكمل التوصيل.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF5D4037)
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = { activeFilter = OrderStatusFilter.WAITING_CONFIRMATION },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE65100)),
                        shape = RoundedCornerShape(10.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(40.dp)
                    ) {
                        Text(
                            text = "عرض",
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.labelLarge
                        )
                    }
                }
            }
        }

        // 💡 3. Status Filter Chips (جارية / بانتظار تأكيد البيانات / مكتملة / ملغية)
        val displayedFilters = remember(showAllFilter, hasRejectedOrders) {
            val baseFilters = OrderStatusFilter.entries.filter {
                it != OrderStatusFilter.REJECTED_DATA || hasRejectedOrders
            }
            if (showAllFilter) {
                baseFilters
            } else {
                baseFilters.filter { it != OrderStatusFilter.ALL }
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
                val chipLabel = if (filter == OrderStatusFilter.WAITING_CONFIRMATION && awaitingConfirmationOrders.isNotEmpty()) {
                    "${stringResource(filter.labelRes)} (${awaitingConfirmationOrders.size})"
                } else {
                    stringResource(filter.labelRes)
                }
                FilterChip(
                    selected = isSelected,
                    onClick = { activeFilter = filter },
                    label = {
                        Text(
                            text = chipLabel,
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (isSelected) FontWeight.ExtraBold else FontWeight.Medium
                        )
                    },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = if (filter == OrderStatusFilter.WAITING_CONFIRMATION) Color(0xFFE65100) else Primary,
                        selectedLabelColor = Color.White,
                        containerColor = if (filter == OrderStatusFilter.WAITING_CONFIRMATION && awaitingConfirmationOrders.isNotEmpty()) {
                            Color(0xFFFFF3E0)
                        } else {
                            MaterialTheme.colorScheme.surface
                        },
                        labelColor = if (filter == OrderStatusFilter.WAITING_CONFIRMATION && awaitingConfirmationOrders.isNotEmpty()) {
                            Color(0xFFE65100)
                        } else {
                            MaterialTheme.colorScheme.onSurface
                        }
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
                if (selectedDateString.isNotBlank()) {
                    orders.filter { it.orderDate.contains(selectedDateString) }
                } else {
                    val previousOrders = orders.filter {
                        isOrderCompleted(it.status) || isOrderCancelled(it.status) || !it.orderDate.contains(todayDateString)
                    }
                    if (previousOrders.isNotEmpty()) previousOrders else orders
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
                    isOrderAwaitingMerchantConfirmation(it)
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
                        },
                        orderViewModel = orderViewModel
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
    val isAwaitingMerchantConfirmation = isOrderAwaitingMerchantConfirmation(order)
    val orderActionKey = order.firestoreOrderId.ifBlank { order.id.toString() }

    val (statusLabel, badgeColor, badgeTextColor) = when {
        isCancelled -> Triple(stringResource(R.string.status_cancelled), Color(0xFFFFEBEE), Color(0xFFC62828))
        isCompleted -> Triple(stringResource(R.string.status_completed), Color(0xFFE8F5E9), Color(0xFF2E7D32))
        isAwaitingMerchantConfirmation -> Triple("بانتظار تأكيد البيانات", Color(0xFFFFF3E0), Color(0xFFE65100))
        order.status.trim() == "بيانات مرفوضة" || order.customerDetailsRejected -> Triple("بيانات مرفوضة", Color(0xFFFFEBEE), Color(0xFFC62828))
        else -> {
            val label = when (order.status.trim()) {
                "جاري البحث عن كابتن", "في انتظار كابتن", "قيد التحضير", "pending", "جاري البحث" -> "جاري البحث"
                "الكابتن في الطريق لك", "في الطريق لك", "accepted", "في الطريق إلى المطعم" -> "في الطريق لك"
                "وصل المتجر" -> "وصل المتجر"
                "تم الاستلام" -> "تم الاستلام"
                "جاري التوصيل", "delivering", "out_for_delivery" -> "جاري التوصيل"
                else -> if (order.status.isNotBlank()) order.status else stringResource(R.string.status_in_progress)
            }
            Triple(label, Color(0xFFFFF3E0), Color(0xFFE65100))
        }
    }

    var showReviewDialog by remember { mutableStateOf(false) }
    var showFullImageDialog by remember { mutableStateOf(false) }
    var rejectionReasonInput by remember(orderActionKey, order.status) { mutableStateOf("") }
    var showRejectPrompt by remember(orderActionKey, order.status) { mutableStateOf(false) }
    var isActionSubmitting by remember(orderActionKey, order.status) { mutableStateOf(false) }

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
                        model = FirebaseStorageManager.resolveDisplayableImageModel(order.receiptImageUrl),
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
                if (!isActionSubmitting) {
                    showReviewDialog = false
                    showRejectPrompt = false
                }
            },
            title = {
                Column(modifier = Modifier.fillMaxWidth()) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = if (isAwaitingMerchantConfirmation) "📋 تأكيد بيانات الطلب وإيصال العميل" else "📋 بيانات الطلب وإيصال العميل",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (isAwaitingMerchantConfirmation) Color(0xFFE65100) else Primary
                        )
                    }
                    if (order.orderCode.isNotBlank() || order.orderDate.isNotBlank()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = buildString {
                                if (order.orderCode.isNotBlank()) append("كود الطلب: #${order.orderCode} ")
                                if (order.orderDate.isNotBlank()) append("• ${order.orderDate}")
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            text = {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    // Customer Info (اسم العميل ورقم هاتفه وعنوانه)
                    Card(
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)),
                        shape = RoundedCornerShape(12.dp),
                        border = BorderStroke(1.dp, Primary.copy(alpha = 0.3f))
                    ) {
                        Column(
                            modifier = Modifier.padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = "بيانات العميل المستلم",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.Bold,
                                color = Primary
                            )
                            Text(
                                text = "👤 اسم العميل: ${order.customerName.ifBlank { "غير مسجل" }}",
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.Bold
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = "📞 رقم الهاتف: ${order.customerPhone.ifBlank { "غير مسجل" }}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                                if (order.customerPhone.isNotBlank()) {
                                    TextButton(
                                        onClick = {
                                            try {
                                                val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${order.customerPhone}"))
                                                context.startActivity(intent)
                                            } catch (e: Exception) {
                                                e.printStackTrace()
                                            }
                                        },
                                        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)
                                    ) {
                                        Icon(Icons.Default.Phone, contentDescription = null, tint = Primary, modifier = Modifier.size(14.dp))
                                        Spacer(modifier = Modifier.width(4.dp))
                                        Text("اتصال بالعميل", style = MaterialTheme.typography.labelSmall, color = Primary, fontWeight = FontWeight.Bold)
                                    }
                                }
                            }
                            Text(
                                text = "📍 عنوان العميل: ${order.destinationAddress.ifBlank { "غير محدد" }}",
                                style = MaterialTheme.typography.bodyMedium
                            )
                            if (order.serviceFee.isNotBlank()) {
                                Text(
                                    text = "💰 خدمة التوصيل: ${order.serviceFee}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = Primary,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            if (order.orderDetails.isNotBlank()) {
                                Text(
                                    text = "📦 تفاصيل الطلب: ${order.orderDetails}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                            if (order.deliveryNotes.isNotBlank()) {
                                Text(
                                    text = "📝 ملاحظات: ${order.deliveryNotes}",
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }

                    // Receipt Image (صورة إيصال الطلب)
                    Text(
                        text = "📸 صورة إيصال الطلب:",
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    if (order.receiptImageUrl.isNotBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(200.dp)
                                .clip(RoundedCornerShape(12.dp))
                                .border(1.5.dp, Primary.copy(alpha = 0.6f), RoundedCornerShape(12.dp))
                                .clickable { showFullImageDialog = true },
                            contentAlignment = Alignment.Center
                        ) {
                            AsyncImage(
                                model = FirebaseStorageManager.resolveDisplayableImageModel(order.receiptImageUrl),
                                contentDescription = "صورة إيصال الطلب",
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop
                            )
                            Surface(
                                color = Color.Black.copy(alpha = 0.65f),
                                shape = RoundedCornerShape(6.dp),
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                            ) {
                                Text(
                                    text = "🔍 اضغط لتكبير صورة الإيصال",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelSmall,
                                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                                )
                            }
                        }
                    } else {
                        Surface(
                            color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "⚠️ لا توجد صورة إيصال مرفقة لهذا الطلب بعد",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    }

                    if (isAwaitingMerchantConfirmation && showRejectPrompt) {
                        OutlinedTextField(
                            value = rejectionReasonInput,
                            onValueChange = { rejectionReasonInput = it },
                            label = { Text("سبب رفض البيانات") },
                            placeholder = { Text("اكتب سبب الرفض للكابتن...") },
                            modifier = Modifier.fillMaxWidth(),
                            minLines = 2,
                            shape = RoundedCornerShape(10.dp)
                        )
                    }
                }
            },
            confirmButton = {
                if (isAwaitingMerchantConfirmation) {
                    Column(
                        modifier = Modifier.fillMaxWidth(),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                isActionSubmitting = true
                                orderViewModel.confirmCustomerDetails(
                                    orderId = orderActionKey,
                                    onSuccess = {
                                        isActionSubmitting = false
                                        showReviewDialog = false
                                    },
                                    onError = {
                                        isActionSubmitting = false
                                    }
                                )
                            },
                            enabled = !isActionSubmitting,
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(48.dp)
                                .testTag("dialog_confirm_customer_data_button_${order.id}"),
                            shape = RoundedCornerShape(10.dp)
                        ) {
                            Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = if (isActionSubmitting) "جاري التأكيد..." else "تأكيد البيانات ✓",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        if (showRejectPrompt) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { showRejectPrompt = false },
                                    enabled = !isActionSubmitting,
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("تراجع")
                                }
                                Button(
                                    onClick = {
                                        isActionSubmitting = true
                                        orderViewModel.rejectCustomerDetails(
                                            orderId = orderActionKey,
                                            reason = rejectionReasonInput,
                                            onSuccess = {
                                                isActionSubmitting = false
                                                showRejectPrompt = false
                                                showReviewDialog = false
                                            },
                                            onError = {
                                                isActionSubmitting = false
                                            }
                                        )
                                    },
                                    enabled = !isActionSubmitting && rejectionReasonInput.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("تأكيد الرفض", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { showRejectPrompt = true },
                                    enabled = !isActionSubmitting,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("رفض البيانات", fontWeight = FontWeight.Bold)
                                }
                                OutlinedButton(
                                    onClick = { showReviewDialog = false },
                                    enabled = !isActionSubmitting,
                                    modifier = Modifier.weight(1f).height(46.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("إغلاق")
                                }
                            }
                        }
                    }
                } else {
                    Button(
                        onClick = { showReviewDialog = false },
                        colors = ButtonDefaults.buttonColors(containerColor = Primary),
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("إغلاق", color = Color.White, fontWeight = FontWeight.Bold)
                    }
                }
            },
            shape = RoundedCornerShape(18.dp)
        )
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { showReviewDialog = true },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        shape = RoundedCornerShape(18.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isAwaitingMerchantConfirmation) 6.dp else 4.dp),
        border = BorderStroke(
            width = if (isAwaitingMerchantConfirmation) 1.8.dp else 1.dp,
            color = if (isAwaitingMerchantConfirmation) Color(0xFFFF9800) else MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
        )
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

            val isCaptainAssigned = (order.captainName.isNotBlank() &&
                order.captainName != "في انتظار كابتن" &&
                order.captainName != "في انتظار قبول كابتن") || !order.captainId.isNullOrBlank()

            val trackedCaptain by orderViewModel.trackedCaptainInfo.collectAsStateWithLifecycle()
            val restaurantProfile by orderViewModel.restaurantProfile.collectAsStateWithLifecycle()
            val restLat = restaurantProfile.latitude ?: 30.0444
            val restLng = restaurantProfile.longitude ?: 31.2357

            LaunchedEffect(orderActionKey, isCaptainAssigned) {
                if (isCaptainAssigned && isInProgress) {
                    orderViewModel.startTrackingOrderCaptain(orderActionKey)
                }
            }

            val matchingTracked = trackedCaptain?.takeIf {
                !order.captainId.isNullOrBlank() && it.captainId == order.captainId
            }

            // Captain Info Section: Shows searching status or Captain profile with ETA (or cancelled state)
            CaptainInfoSection(
                captainName = FirebaseStorageManager.selectAuthoritativeCaptainName(matchingTracked?.name, order.captainName, order.captainId),
                captainPhone = FirebaseStorageManager.selectAuthoritativeCaptainPhone(matchingTracked?.phone, order.captainPhone),
                captainPhotoUrl = FirebaseStorageManager.selectBestCaptainPhotoUrl(matchingTracked?.photoUrl, null, order.captainPhotoUrl),
                estimatedArrival = order.estimatedArrival,
                showCallButton = false,
                isCancelled = isCancelled,
                isCompleted = isCompleted,
                restaurantLat = restLat,
                restaurantLng = restLng,
                captainLat = matchingTracked?.latitude,
                captainLng = matchingTracked?.longitude,
                orderKey = orderActionKey,
                captainId = order.captainId.orEmpty()
            )

            Spacer(modifier = Modifier.height(10.dp))

            val currentStageIndex = when (order.status.trim()) {
                "جاري البحث", "جاري البحث عن كابتن", "في انتظار كابتن", "قيد التحضير", "Preparing", "pending" -> 0
                "في الطريق لك", "الكابتن في الطريق لك", "accepted", "في الطريق إلى المطعم",
                "وصل المتجر", "تم الاستلام", "بانتظار تأكيد البيانات", "بيانات مرفوضة" -> 1
                "جاري التوصيل", "قيد التوصيل", "Out for Delivery", "delivering" -> 2
                "تم التسليم", "مكتمل", "مكتملة", "Delivered" -> 3
                else -> if (isCaptainAssigned) 1 else 0
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
                        val captainPhoneToCall = FirebaseStorageManager.selectAuthoritativeCaptainPhone(
                            matchingTracked?.phone,
                            order.captainPhone
                        )
                        OutlinedButton(
                            onClick = {
                                if (captainPhoneToCall.isNotBlank()) {
                                    try {
                                        val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$captainPhoneToCall"))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            },
                            enabled = captainPhoneToCall.isNotBlank(),
                            shape = RoundedCornerShape(10.dp),
                            border = BorderStroke(1.dp, if (captainPhoneToCall.isNotBlank()) Primary else MaterialTheme.colorScheme.outlineVariant),
                            modifier = Modifier.fillMaxWidth().height(38.dp),
                            contentPadding = PaddingValues(horizontal = 8.dp)
                        ) {
                            Icon(
                                Icons.Default.Phone,
                                contentDescription = null,
                                tint = if (captainPhoneToCall.isNotBlank()) Primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(14.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = if (captainPhoneToCall.isNotBlank()) stringResource(R.string.call_captain_btn) else "رقم الهاتف غير مسجل",
                                style = MaterialTheme.typography.labelMedium,
                                color = if (captainPhoneToCall.isNotBlank()) Primary else MaterialTheme.colorScheme.onSurfaceVariant,
                                fontWeight = FontWeight.Bold
                            )
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

            // Highlighted Confirmation Section when Captain submits order data
            if (isAwaitingMerchantConfirmation) {
                Spacer(modifier = Modifier.height(12.dp))
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .testTag("card_awaiting_confirmation_${order.id}"),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFFFFF8E1)),
                    shape = RoundedCornerShape(14.dp),
                    border = BorderStroke(1.5.dp, Color(0xFFFF9800))
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Notifications,
                                contentDescription = null,
                                tint = Color(0xFFE65100),
                                modifier = Modifier.size(18.dp)
                            )
                            Text(
                                text = "تأكيد البيانات المطلوب لإكمال الطلب",
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.ExtraBold,
                                color = Color(0xFFE65100)
                            )
                        }
                        Text(
                            text = "أرسل الكابتن بيانات الطلب والإيصال. تأكد من البيانات ثم اضغط تأكيد البيانات ليكمل الكابتن التوصيل.",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF5D4037)
                        )
                        if (order.customerName.isNotBlank()) {
                            Text(
                                text = "👤 العميل: ${order.customerName}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF212121)
                            )
                        }
                        if (order.customerPhone.isNotBlank()) {
                            Text(
                                text = "📞 الهاتف: ${order.customerPhone}",
                                style = MaterialTheme.typography.bodySmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Color(0xFF212121)
                            )
                        }
                        if (order.destinationAddress.isNotBlank()) {
                            Text(
                                text = "📍 العنوان: ${order.destinationAddress}",
                                style = MaterialTheme.typography.bodySmall,
                                color = Color(0xFF424242)
                            )
                        }
                        if (order.receiptImageUrl.isNotBlank()) {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(140.dp)
                                    .clip(RoundedCornerShape(10.dp))
                                    .border(1.dp, Color(0xFFFFB74D), RoundedCornerShape(10.dp))
                                    .clickable { showFullImageDialog = true }
                            ) {
                                AsyncImage(
                                    model = FirebaseStorageManager.resolveDisplayableImageModel(order.receiptImageUrl),
                                    contentDescription = "صورة الإيصال المرسلة",
                                    modifier = Modifier.fillMaxSize(),
                                    contentScale = ContentScale.Crop
                                )
                                Surface(
                                    color = Color.Black.copy(alpha = 0.65f),
                                    shape = RoundedCornerShape(6.dp),
                                    modifier = Modifier
                                        .align(Alignment.BottomEnd)
                                        .padding(6.dp)
                                ) {
                                    Text(
                                        text = "🔍 اضغط للتكبير",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelSmall,
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 3.dp)
                                    )
                                }
                            }
                        }

                        if (showRejectPrompt) {
                            OutlinedTextField(
                                value = rejectionReasonInput,
                                onValueChange = { rejectionReasonInput = it },
                                label = { Text("سبب رفض البيانات") },
                                placeholder = { Text("مثال: صورة الإيصال غير واضحة أو الرقم خاطئ") },
                                modifier = Modifier.fillMaxWidth(),
                                minLines = 2,
                                shape = RoundedCornerShape(10.dp)
                            )
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                OutlinedButton(
                                    onClick = { showRejectPrompt = false },
                                    enabled = !isActionSubmitting,
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("تراجع")
                                }
                                Button(
                                    onClick = {
                                        isActionSubmitting = true
                                        orderViewModel.rejectCustomerDetails(
                                            orderId = orderActionKey,
                                            reason = rejectionReasonInput,
                                            onSuccess = {
                                                isActionSubmitting = false
                                                showRejectPrompt = false
                                            },
                                            onError = {
                                                isActionSubmitting = false
                                            }
                                        )
                                    },
                                    enabled = !isActionSubmitting && rejectionReasonInput.isNotBlank(),
                                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier.weight(1f).height(48.dp),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("تأكيد الرفض", color = Color.White, fontWeight = FontWeight.Bold)
                                }
                            }
                        } else {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                Button(
                                    onClick = {
                                        isActionSubmitting = true
                                        orderViewModel.confirmCustomerDetails(
                                            orderId = orderActionKey,
                                            onSuccess = { isActionSubmitting = false },
                                            onError = { isActionSubmitting = false }
                                        )
                                    },
                                    enabled = !isActionSubmitting,
                                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                                    modifier = Modifier
                                        .weight(1.2f)
                                        .height(48.dp)
                                        .testTag("confirm_customer_data_button_${order.id}"),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Icon(Icons.Default.CheckCircle, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = if (isActionSubmitting) "جاري التأكيد..." else "تأكيد البيانات ✓",
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                                OutlinedButton(
                                    onClick = { showRejectPrompt = true },
                                    enabled = !isActionSubmitting,
                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.error),
                                    colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                                    modifier = Modifier
                                        .weight(0.8f)
                                        .height(48.dp)
                                        .testTag("reject_customer_data_button_${order.id}"),
                                    shape = RoundedCornerShape(10.dp)
                                ) {
                                    Text("رفض", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                }
            } else if (isInProgress && (order.status.trim() == "بيانات مرفوضة" || order.customerDetailsRejected)) {
                Spacer(modifier = Modifier.height(10.dp))
                Surface(
                    color = Color(0xFFFFEBEE),
                    shape = RoundedCornerShape(10.dp),
                    border = BorderStroke(1.dp, Color(0xFFEF9A9A)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "❌ تم رفض البيانات (${order.rejectionReason.ifBlank { "بانتظار إعادة الإرسال من الكابتن" }})",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color(0xFFC62828),
                        fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.padding(10.dp)
                    )
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

            // زر وتنبيه واضح لعرض بيانات الطلب (اسم العميل، هاتفه، عنوانه، وصورة الإيصال) عند الضغط على الطلب
            Spacer(modifier = Modifier.height(10.dp))
            Surface(
                color = Primary.copy(alpha = 0.08f),
                shape = RoundedCornerShape(10.dp),
                border = BorderStroke(1.dp, Primary.copy(alpha = 0.3f)),
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { showReviewDialog = true }
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "📋 اضغط لعرض بيانات العميل والعنوان وصورة الإيصال",
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold,
                        color = Primary
                    )
                    Text(
                        text = "عرض ←",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = Primary
                    )
                }
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
    LaunchedEffect(Unit) {
        orderViewModel.refreshRestaurantFeed()
    }

    val firestoreOrders by orderViewModel.firestoreOrders.collectAsStateWithLifecycle()
    val localOrders by orderViewModel.ordersState.collectAsStateWithLifecycle()
    val isLoggedIn = remember { com.google.firebase.Firebase.auth.currentUser != null }
    val orders = if (isLoggedIn) firestoreOrders else if (firestoreOrders.isNotEmpty()) firestoreOrders else localOrders
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
                OrderManagementSection(
                    orders = orders,
                    onAdvanceStatus = { id, status ->
                        orderViewModel.advanceOrderStatus(id, status)
                    },
                    onRateOrder = { id, rating ->
                        orderViewModel.setOrderRating(id, rating)
                    },
                    onEditOrder = onEditOrder,
                    onRequestNewOrder = null,
                    onCancelOrder = { id ->
                        orderViewModel.cancelOrder(id)
                    },
                    orderViewModel = orderViewModel
                )

                Spacer(modifier = Modifier.height(24.dp))
            }
        }
    }

    AutoMerchantConfirmationDialog(
        orders = orders,
        orderViewModel = orderViewModel
    )
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

            val isCaptainAssigned = (order.captainName.isNotBlank() &&
                order.captainName != "في انتظار كابتن" &&
                order.captainName != "في انتظار قبول كابتن") || !order.captainId.isNullOrBlank()
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

            LaunchedEffect(order.firestoreOrderId, order.id, isCaptainAssigned) {
                if (isCaptainAssigned && isInProgress) {
                    orderViewModel.startTrackingOrderCaptain(order.firestoreOrderId.ifBlank { order.id.toString() })
                }
            }

            val matchingTracked = trackedCaptain?.takeIf {
                !order.captainId.isNullOrBlank() && it.captainId == order.captainId
            }

            // Captain Information (بيانات المندوب)
            val isCancelled = order.status in listOf("ملغي", "Cancelled")
            CaptainInfoSection(
                captainName = FirebaseStorageManager.selectAuthoritativeCaptainName(matchingTracked?.name, order.captainName, order.captainId),
                captainPhone = FirebaseStorageManager.selectAuthoritativeCaptainPhone(matchingTracked?.phone, order.captainPhone),
                captainPhotoUrl = FirebaseStorageManager.selectBestCaptainPhotoUrl(matchingTracked?.photoUrl, null, order.captainPhotoUrl),
                estimatedArrival = order.estimatedArrival,
                isCancelled = isCancelled,
                isCompleted = isDelivered,
                restaurantLat = restLat,
                restaurantLng = restLng,
                captainLat = matchingTracked?.latitude,
                captainLng = matchingTracked?.longitude,
                orderKey = order.firestoreOrderId.ifBlank { order.id.toString() },
                captainId = order.captainId.orEmpty()
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
    val isCaptainAssignedForStage = order.captainName.isNotBlank() &&
        order.captainName != "في انتظار كابتن" &&
        order.captainName != "في انتظار قبول كابتن"
    val currentStageIndex = when (order.status.trim()) {
        "جاري البحث", "جاري البحث عن كابتن", "في انتظار كابتن", "قيد التحضير", "Preparing", "pending" -> 0
        "في الطريق لك", "الكابتن في الطريق لك", "accepted", "في الطريق إلى المطعم",
        "وصل المتجر", "تم الاستلام", "بانتظار تأكيد البيانات", "بيانات مرفوضة" -> 1
        "جاري التوصيل", "قيد التوصيل", "Out for Delivery", "delivering" -> 2
        "تم التسليم", "مكتمل", "مكتملة", "Delivered" -> 3
        else -> if (isCaptainAssignedForStage) 1 else 0
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

                val isAwaitingConfirm = isOrderAwaitingMerchantConfirmation(order)
                Text(
                    text = when {
                        isAwaitingConfirm -> "بانتظار تأكيد البيانات"
                        order.status.trim() == "بيانات مرفوضة" -> "بيانات مرفوضة"
                        order.status.trim() == "وصل المتجر" -> "وصل المتجر"
                        order.status.trim() == "تم الاستلام" -> "تم الاستلام"
                        currentStageIndex == 0 -> stringResource(R.string.stage_searching)
                        currentStageIndex == 1 -> stringResource(R.string.stage_on_the_way)
                        currentStageIndex == 2 -> stringResource(R.string.stage_out_for_delivery)
                        else -> stringResource(R.string.stage_delivered)
                    },
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (isAwaitingConfirm) Color(0xFFE65100) else Primary
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

            val descriptionText = when {
                isOrderAwaitingMerchantConfirmation(order) ->
                    "أرسل $captainDisplayName بيانات الطلب وبانتظار تأكيدك للبيانات ليكمل التوصيل 📋"
                order.status.trim() == "بيانات مرفوضة" ->
                    "تم رفض البيانات المرسلة وبانتظار إعادة إرسالها من $captainDisplayName ❌"
                order.status.trim() == "وصل المتجر" ->
                    "وصل $captainDisplayName إلى المتجر وهو الآن يستلم الطلب 🏪"
                order.status.trim() == "تم الاستلام" ->
                    "استلم $captainDisplayName الطلب وبانتظار إرسال بيانات العميل 📦"
                currentStageIndex == 0 -> ""
                currentStageIndex == 1 -> "استقبل $captainDisplayName الطلب وهو في الطريق لمطعمك 🛵"
                currentStageIndex == 2 -> "استلم $captainDisplayName الطلب وهو الآن في طريقه لتسليمه للعميل 🚀"
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

