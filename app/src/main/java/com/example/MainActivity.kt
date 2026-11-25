package com.example

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.db.OrderViewModel
import com.example.network.NetworkHelper
import com.example.notification.MerchantMessagingService
import com.example.ui.subscription.MerchantPaymentsScreen
import com.example.ui.subscription.VodafoneCashPaymentScreen
import com.example.ui.theme.ElBatalExpressTheme
import com.example.ui.theme.ThemeManager
import com.google.firebase.Firebase
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.auth.auth
import com.google.firebase.firestore.FirebaseFirestoreSettings
import com.google.firebase.firestore.firestore
import com.google.firebase.firestore.firestoreSettings
import com.google.firebase.firestore.persistentCacheSettings
import kotlinx.serialization.Serializable
import kotlinx.coroutines.launch
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers

@Serializable
object LoginRoute

@Serializable
object HomeRoute

@Serializable
object RegisterRoute

@Serializable
object RegistrationSuccessRoute

@Serializable
object SubscriptionRoute

@Serializable
object PaymentsRoute

@Serializable
data class VodafoneCashPaymentRoute(val planId: String)

@Serializable
object RestaurantDashboardRoute

@Serializable
object CaptainDashboardRoute

@Serializable
object RestaurantDetailsRoute

@Serializable
object OrderCourierRoute

@Serializable
object OrderHistoryRoute

@Serializable
data class EditOrderRoute(val orderId: Int)

class MainActivity : ComponentActivity() {

  companion object {
    const val EXTRA_START_DESTINATION = "extra_start_destination"
    const val START_DESTINATION_DASHBOARD = "restaurant_dashboard"
    const val START_DESTINATION_LOGIN = "login"
  }

  private fun safeCurrentFirebaseUser(): com.google.firebase.auth.FirebaseUser? = try {
    Firebase.auth.currentUser
  } catch (e: Exception) {
    android.util.Log.w("MainActivity", "FirebaseAuth unavailable: ${e.message}")
    null
  }

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    com.example.splash.SplashSessionState.markCompleted()
    runCatching { NetworkHelper.startMonitoring(this) }
      .onFailure { android.util.Log.w("MainActivity", "NetworkHelper init warning: ${it.message}") }
    try {
      val settings = firestoreSettings {
        setLocalCacheSettings(persistentCacheSettings {})
      }
      Firebase.firestore.firestoreSettings = settings
    } catch (_: Exception) {}
    runCatching { ThemeManager.initialize(this) }
      .onFailure { android.util.Log.w("MainActivity", "ThemeManager init warning: ${it.message}") }
    enableEdgeToEdge()
    setContent {
      val isDarkMode by ThemeManager.isDarkModeState.collectAsStateWithLifecycle()
      ElBatalExpressTheme(darkTheme = isDarkMode, dynamicColor = false) {
        val navController = rememberNavController()
        val orderViewModel: OrderViewModel = viewModel()
        val pushNotification by orderViewModel.pushNotificationState.collectAsStateWithLifecycle()
        val subscriptionStatus by orderViewModel.subscriptionStatus.collectAsStateWithLifecycle()
        val isOnline by NetworkHelper.isOnline.collectAsStateWithLifecycle()
        val currentUser = safeCurrentFirebaseUser()
        var verifiedMerchantUid by remember {
          mutableStateOf(currentUser?.takeIf { it.isEmailVerified }?.uid)
        }
        DisposableEffect(Unit) {
          val listener = FirebaseAuth.AuthStateListener { auth ->
            val u = auth.currentUser
            verifiedMerchantUid = if (u != null && u.isEmailVerified) u.uid else null
          }
          runCatching { Firebase.auth.addAuthStateListener(listener) }
            .onFailure { android.util.Log.w("MainActivity", "AuthStateListener add warning: ${it.message}") }
          onDispose {
            runCatching { Firebase.auth.removeAuthStateListener(listener) }
          }
        }
        LaunchedEffect(verifiedMerchantUid) {
          val uid = verifiedMerchantUid
          if (uid != null) {
            runCatching { MerchantMessagingService.syncCurrentFcmTokenForMerchant(uid) }
              .onFailure { android.util.Log.w("MainActivity", "FCM sync warning: ${it.message}") }
          }
        }
        val lifecycleOwner = LocalLifecycleOwner.current
        DisposableEffect(lifecycleOwner, verifiedMerchantUid) {
          val observer = LifecycleEventObserver { _, event ->
            val uid = verifiedMerchantUid
            if (event == Lifecycle.Event.ON_RESUME && uid != null) {
              orderViewModel.refreshSubscriptionFromServer(uid)
            }
          }
          lifecycleOwner.lifecycle.addObserver(observer)
          onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
          }
        }
        val requestedStart = intent?.getStringExtra(EXTRA_START_DESTINATION)
        val initialRoute = remember {
          // Re-evaluate the live Firebase Auth session in MainActivity before forcing LoginRoute
          // so a transient splash lookup error never hijacks a valid verified merchant session.
          val liveUser = safeCurrentFirebaseUser() ?: currentUser
          val isVerifiedSession = liveUser != null && liveUser.isEmailVerified
          when {
            isVerifiedSession -> RestaurantDashboardRoute
            requestedStart == START_DESTINATION_LOGIN -> LoginRoute
            else -> LoginRoute
          }
        }
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRouteString = navBackStackEntry?.destination?.route.orEmpty()
        val isOnSubscriptionRoute = currentRouteString.contains("SubscriptionRoute") ||
          currentRouteString.contains("VodafoneCashPaymentRoute") ||
          currentRouteString.contains("PaymentsRoute")
        val context = LocalContext.current

        Box(modifier = Modifier.fillMaxSize()) {
          Scaffold(
            modifier = Modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets.safeDrawing,
            topBar = {
              AnimatedVisibility(visible = !isOnline) {
                Surface(
                  color = Color(0xFFD32F2F),
                  modifier = Modifier.fillMaxWidth()
                ) {
                  Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center
                  ) {
                    Icon(
                      imageVector = Icons.Default.WifiOff,
                      contentDescription = "غير متصل بالإنترنت",
                      tint = Color.White,
                      modifier = Modifier.size(16.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                      text = "⚠️ أنت غير متصل بالإنترنت حالياً",
                      color = Color.White,
                      style = MaterialTheme.typography.labelMedium,
                      fontWeight = FontWeight.Bold
                    )
                  }
                }
              }
            }
          ) { innerPadding ->
            NavHost(
              navController = navController,
              startDestination = initialRoute,
              modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .consumeWindowInsets(innerPadding)
            ) {
              composable<LoginRoute> {
                LoginScreen(
                  onLoginSuccess = { merchant ->
                    orderViewModel.applyAuthenticatedMerchant(merchant)
                    orderViewModel.listenToFirestore()
                    navController.navigate(RestaurantDashboardRoute) {
                      popUpTo(LoginRoute) { inclusive = true }
                    }
                  },
                  onRegisterClick = { navController.navigate(RegisterRoute) }
                )
              }
              composable<RegisterRoute> {
                RegisterScreen(
                  onRegisterSuccess = {
                    navController.navigate(LoginRoute) {
                      popUpTo(0) { inclusive = true }
                    }
                  },
                  onEmailVerified = { merchant ->
                    orderViewModel.applyAuthenticatedMerchant(merchant)
                    orderViewModel.listenToFirestore()
                    navController.navigate(RestaurantDashboardRoute) {
                      popUpTo(0) { inclusive = true }
                    }
                  }
                )
              }
              composable<SubscriptionRoute> {
                SubscriptionPlansScreen(
                  onSkip = { navController.popBackStack() },
                  onProceedToVodafoneCash = { planId ->
                    navController.navigate(VodafoneCashPaymentRoute(planId))
                  },
                  onOpenPayments = { navController.navigate(PaymentsRoute) },
                  orderViewModel = orderViewModel
                )
              }
              composable<PaymentsRoute> {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                  val authUser = safeCurrentFirebaseUser()
                  if (authUser == null || !authUser.isEmailVerified) {
                    navController.navigate(LoginRoute) {
                      popUpTo(0) { inclusive = true }
                    }
                  }
                }
                if (safeCurrentFirebaseUser()?.isEmailVerified == true) {
                  MerchantPaymentsScreen(
                    onBack = { navController.popBackStack() },
                    orderViewModel = orderViewModel
                  )
                }
              }
              composable<VodafoneCashPaymentRoute> { backStackEntry ->
                androidx.compose.runtime.LaunchedEffect(Unit) {
                  val authUser = safeCurrentFirebaseUser()
                  if (authUser == null || !authUser.isEmailVerified) {
                    navController.navigate(LoginRoute) {
                      popUpTo(0) { inclusive = true }
                    }
                  }
                }
                if (safeCurrentFirebaseUser()?.isEmailVerified == true) {
                  val route: VodafoneCashPaymentRoute = backStackEntry.toRoute()
                  VodafoneCashPaymentScreen(
                    planId = route.planId,
                    onBack = { navController.popBackStack() },
                    onSubmitted = { navController.popBackStack() },
                    orderViewModel = orderViewModel,
                    onViewPaymentsClick = { navController.navigate(PaymentsRoute) }
                  )
                }
              }
              composable<RestaurantDashboardRoute> {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                  val authUser = safeCurrentFirebaseUser()
                  if (authUser == null) {
                    navController.navigate(LoginRoute) {
                      popUpTo(0) { inclusive = true }
                    }
                  } else {
                    val sessionResult = try {
                      com.example.auth.MerchantAuthManager.checkExistingMerchantSessionResult(context)
                    } catch (ce: kotlinx.coroutines.CancellationException) {
                      throw ce
                    } catch (e: Exception) {
                      android.util.Log.e("MainActivity", "Failed checking existing merchant session: ${e.message}", e)
                      com.example.auth.MerchantSignInResult.Error(
                        e.localizedMessage ?: "تعذر التحقق من الجلسة حالياً."
                      )
                    }
                    when (sessionResult) {
                      is com.example.auth.MerchantSignInResult.Success -> {
                        orderViewModel.applyAuthenticatedMerchant(sessionResult.merchant)
                        orderViewModel.listenToFirestore()
                      }
                      is com.example.auth.MerchantSignInResult.EmailNotVerified,
                      is com.example.auth.MerchantSignInResult.NotRegistered -> {
                        android.widget.Toast.makeText(
                          context,
                          "لا يمكن الدخول إلا بحساب تاجر مسجل ومؤكد البريد الإلكتروني.",
                          android.widget.Toast.LENGTH_LONG
                        ).show()
                        orderViewModel.clearListeners()
                        navController.navigate(LoginRoute) {
                          popUpTo(0) { inclusive = true }
                        }
                      }
                      is com.example.auth.MerchantSignInResult.Error -> {
                        // Surface non-transient verification errors honestly (e.g., PERMISSION_DENIED)
                        // without falsely signing out or converting the error into fake success.
                        android.widget.Toast.makeText(
                          context,
                          sessionResult.message,
                          android.widget.Toast.LENGTH_LONG
                        ).show()
                        orderViewModel.listenToFirestore()
                      }
                      com.example.auth.MerchantSignInResult.Cancelled -> Unit
                    }
                  }
                }
                if (safeCurrentFirebaseUser()?.isEmailVerified == true) {
                  RestaurantDashboardScreen(
                    onRequestCaptain = { navController.navigate(OrderCourierRoute) },
                    onViewAllOrders = { navController.navigate(OrderHistoryRoute) },
                    onRestaurantClick = { navController.navigate(RestaurantDetailsRoute) },
                    onPlansClick = { navController.navigate(SubscriptionRoute) },
                    onPaymentsClick = { navController.navigate(PaymentsRoute) },
                    onCaptainDashboardClick = {
                      android.widget.Toast.makeText(context, "لوحة الكابتن مخصصة لتطبيق الكباتن فقط", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onLogout = {
                      kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        com.example.auth.MerchantAuthManager.signOutMerchant(context)
                        orderViewModel.clearListeners()
                        navController.navigate(LoginRoute) {
                          popUpTo(0) { inclusive = true }
                        }
                      }
                    },
                    onEditOrder = { id -> navController.navigate(EditOrderRoute(id)) },
                    orderViewModel = orderViewModel
                  )
                }
              }
              composable<CaptainDashboardRoute> {
                // Restricted boundary for merchant app
                androidx.compose.runtime.LaunchedEffect(Unit) {
                  android.widget.Toast.makeText(context, "هذا التطبيق مخصص للتاجر فقط", android.widget.Toast.LENGTH_SHORT).show()
                  navController.popBackStack()
                }
              }
              composable<RestaurantDetailsRoute> {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                  val authUser = safeCurrentFirebaseUser()
                  if (authUser == null || !authUser.isEmailVerified) {
                    navController.navigate(LoginRoute) {
                      popUpTo(0) { inclusive = true }
                    }
                  }
                }
                if (safeCurrentFirebaseUser()?.isEmailVerified == true) {
                  RestaurantDetailsScreen(
                    onBack = { navController.popBackStack() },
                    orderViewModel = orderViewModel
                  )
                }
              }
              composable<RegistrationSuccessRoute> {
                RegistrationSuccessScreen()
              }
              composable<HomeRoute> {
                HomeScreen(
                  onRequestCourier = { navController.navigate(OrderCourierRoute) },
                  onViewOrdersHistory = { navController.navigate(OrderHistoryRoute) }
                )
              }
              composable<OrderCourierRoute> {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                  val authUser = safeCurrentFirebaseUser()
                  if (authUser == null || !authUser.isEmailVerified) {
                    navController.navigate(LoginRoute) {
                      popUpTo(0) { inclusive = true }
                    }
                  }
                }
                if (safeCurrentFirebaseUser()?.isEmailVerified == true) {
                  OrderCourierScreen(
                    onBack = { navController.popBackStack() },
                    onOrderSubmitted = { navController.navigate(OrderHistoryRoute) },
                    onSubscribeClick = { navController.navigate(SubscriptionRoute) },
                    orderViewModel = orderViewModel
                  )
                }
              }
              composable<OrderHistoryRoute> {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                  val authUser = safeCurrentFirebaseUser()
                  if (authUser == null || !authUser.isEmailVerified) {
                    navController.navigate(LoginRoute) {
                      popUpTo(0) { inclusive = true }
                    }
                  }
                }
                if (safeCurrentFirebaseUser()?.isEmailVerified == true) {
                  OrderHistoryScreen(
                    onBack = { navController.popBackStack() },
                    onRequestNewOrder = { navController.navigate(OrderCourierRoute) },
                    onEditOrder = { id -> navController.navigate(EditOrderRoute(id)) },
                    orderViewModel = orderViewModel
                  )
                }
              }
              composable<EditOrderRoute> { backStackEntry ->
                androidx.compose.runtime.LaunchedEffect(Unit) {
                  val authUser = safeCurrentFirebaseUser()
                  if (authUser == null || !authUser.isEmailVerified) {
                    navController.navigate(LoginRoute) {
                      popUpTo(0) { inclusive = true }
                    }
                  }
                }
                if (safeCurrentFirebaseUser()?.isEmailVerified == true) {
                  val route: EditOrderRoute = backStackEntry.toRoute()
                  EditOrderScreen(
                    orderId = route.orderId,
                    onBack = { navController.popBackStack() },
                    orderViewModel = orderViewModel
                  )
                }
              }
            }
          }

          if (verifiedMerchantUid != null &&
            !subscriptionStatus.isChecking &&
            (subscriptionStatus.isSuspended || subscriptionStatus.isCancelled || subscriptionStatus.isExpired) &&
            !isOnSubscriptionRoute
          ) {
            val fallbackMessage = when {
              subscriptionStatus.status == "inactive" ->
                "اشتراكك غير نشط حالياً. يرجى التواصل مع الإدارة أو تجديد الاشتراك لتفعيل الخدمات."
              subscriptionStatus.isSuspended ->
                "تم إيقاف اشتراكك مؤقتاً من قبل الإدارة. يرجى التواصل مع الإدارة أو تجديد الاشتراك."
              subscriptionStatus.isCancelled ->
                "تم إلغاء اشتراكك من قبل الإدارة. يرجى التواصل مع الإدارة أو الاشتراك في باقة جديدة."
              else ->
                "انتهت فترة اشتراكك، يرجى التواصل مع الإدارة أو تجديد الاشتراك."
            }
            SubscriptionExpiredScreen(
              status = subscriptionStatus.status,
              message = subscriptionStatus.message ?: fallbackMessage,
              suspensionReason = subscriptionStatus.suspensionReason,
              onViewPlans = { navController.navigate(SubscriptionRoute) },
              onRecheck = { orderViewModel.recheckSubscription() },
              onContactAdmin = {
                try {
                  val intent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:01000000000"))
                  context.startActivity(intent)
                } catch (e: Exception) {
                  e.printStackTrace()
                }
              },
              onLogout = {
                CoroutineScope(Dispatchers.Main).launch {
                  com.example.auth.MerchantAuthManager.signOutMerchant(context)
                  orderViewModel.clearListeners()
                  navController.navigate(LoginRoute) {
                    popUpTo(0) { inclusive = true }
                  }
                }
              }
            )
          }

          SimulatedPushNotificationOverlay(
            notification = pushNotification,
            onDismiss = { orderViewModel.dismissPushNotification() }
          )
        }
      }
    }
  }
}
