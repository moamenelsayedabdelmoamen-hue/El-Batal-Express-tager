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
data class VodafoneCashPaymentRoute(val planId: Int)

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

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    com.example.splash.SplashSessionState.markCompleted()
    NetworkHelper.startMonitoring(this)
    try {
      val settings = firestoreSettings {
        setLocalCacheSettings(persistentCacheSettings {})
      }
      Firebase.firestore.firestoreSettings = settings
    } catch (_: Exception) {}
    ThemeManager.initialize(this)
    enableEdgeToEdge()
    setContent {
      val isDarkMode by ThemeManager.isDarkModeState.collectAsStateWithLifecycle()
      ElBatalExpressTheme(darkTheme = isDarkMode, dynamicColor = false) {
        val navController = rememberNavController()
        val orderViewModel: OrderViewModel = viewModel()
        val pushNotification by orderViewModel.pushNotificationState.collectAsStateWithLifecycle()
        val subscriptionStatus by orderViewModel.subscriptionStatus.collectAsStateWithLifecycle()
        val isOnline by NetworkHelper.isOnline.collectAsStateWithLifecycle()
        val currentUser = Firebase.auth.currentUser
        var verifiedMerchantUid by remember {
          mutableStateOf(currentUser?.takeIf { it.isEmailVerified }?.uid)
        }
        DisposableEffect(Unit) {
          val listener = FirebaseAuth.AuthStateListener { auth ->
            val u = auth.currentUser
            verifiedMerchantUid = if (u != null && u.isEmailVerified) u.uid else null
          }
          Firebase.auth.addAuthStateListener(listener)
          onDispose {
            Firebase.auth.removeAuthStateListener(listener)
          }
        }
        LaunchedEffect(verifiedMerchantUid) {
          val uid = verifiedMerchantUid
          if (uid != null) {
            MerchantMessagingService.syncCurrentFcmTokenForMerchant(uid)
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
          val isVerifiedSession = currentUser != null && currentUser.isEmailVerified
          when {
            requestedStart == START_DESTINATION_DASHBOARD && isVerifiedSession -> RestaurantDashboardRoute
            requestedStart == START_DESTINATION_LOGIN -> LoginRoute
            isVerifiedSession -> RestaurantDashboardRoute
            else -> LoginRoute
          }
        }
        val navBackStackEntry by navController.currentBackStackEntryAsState()
        val currentRouteString = navBackStackEntry?.destination?.route.orEmpty()
        val isOnSubscriptionRoute = currentRouteString.contains("SubscriptionRoute") ||
          currentRouteString.contains("VodafoneCashPaymentRoute")
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
                  orderViewModel = orderViewModel
                )
              }
              composable<VodafoneCashPaymentRoute> { backStackEntry ->
                androidx.compose.runtime.LaunchedEffect(Unit) {
                  val authUser = Firebase.auth.currentUser
                  if (authUser == null || !authUser.isEmailVerified) {
                    navController.navigate(LoginRoute) {
                      popUpTo(0) { inclusive = true }
                    }
                  }
                }
                if (Firebase.auth.currentUser?.isEmailVerified == true) {
                  val route: VodafoneCashPaymentRoute = backStackEntry.toRoute()
                  VodafoneCashPaymentScreen(
                    planId = route.planId,
                    onBack = { navController.popBackStack() },
                    onSubmitted = { navController.popBackStack() },
                    orderViewModel = orderViewModel
                  )
                }
              }
              composable<RestaurantDashboardRoute> {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                  val authUser = Firebase.auth.currentUser
                  if (authUser == null) {
                    navController.navigate(LoginRoute) {
                      popUpTo(0) { inclusive = true }
                    }
                  } else {
                    val sessionMerchant = com.example.auth.MerchantAuthManager.checkExistingMerchantSession(context)
                    if (sessionMerchant == null) {
                      android.widget.Toast.makeText(
                        context,
                        "لا يمكن الدخول إلا بحساب تاجر مسجل ومؤكد البريد الإلكتروني.",
                        android.widget.Toast.LENGTH_LONG
                      ).show()
                      orderViewModel.clearListeners()
                      navController.navigate(LoginRoute) {
                        popUpTo(0) { inclusive = true }
                      }
                    } else {
                      orderViewModel.applyAuthenticatedMerchant(sessionMerchant)
                      orderViewModel.listenToFirestore()
                    }
                  }
                }
                if (Firebase.auth.currentUser != null && Firebase.auth.currentUser?.isEmailVerified == true) {
                  RestaurantDashboardScreen(
                    onRequestCaptain = { navController.navigate(OrderCourierRoute) },
                    onViewAllOrders = { navController.navigate(OrderHistoryRoute) },
                    onRestaurantClick = { navController.navigate(RestaurantDetailsRoute) },
                    onPlansClick = { navController.navigate(SubscriptionRoute) },
                    onCaptainDashboardClick = {
                      android.widget.Toast.makeText(context, "لوحة الكابتن مخصصة لتطبيق الكباتن فقط", android.widget.Toast.LENGTH_SHORT).show()
                    },
                    onLogout = {
                      kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                        val merchantUid = Firebase.auth.currentUser?.uid.orEmpty()
                        com.example.notification.MerchantMessagingService.clearCurrentFcmTokenForMerchant(merchantUid)
                        orderViewModel.clearListeners()
                        try {
                          kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                            com.example.db.AppDatabase.getInstance(context).clearAllTables()
                          }
                        } catch (e: Exception) {
                          android.util.Log.w("MerchantLogout", "Failed to clear local merchant database: ${e.message}")
                        }
                        com.example.auth.MerchantAuthManager.signOutMerchant(context)
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
                  val authUser = Firebase.auth.currentUser
                  if (authUser == null || !authUser.isEmailVerified) {
                    navController.navigate(LoginRoute) {
                      popUpTo(0) { inclusive = true }
                    }
                  }
                }
                if (Firebase.auth.currentUser?.isEmailVerified == true) {
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
                  val authUser = Firebase.auth.currentUser
                  if (authUser == null || !authUser.isEmailVerified) {
                    navController.navigate(LoginRoute) {
                      popUpTo(0) { inclusive = true }
                    }
                  }
                }
                if (Firebase.auth.currentUser?.isEmailVerified == true) {
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
                  val authUser = Firebase.auth.currentUser
                  if (authUser == null || !authUser.isEmailVerified) {
                    navController.navigate(LoginRoute) {
                      popUpTo(0) { inclusive = true }
                    }
                  }
                }
                if (Firebase.auth.currentUser?.isEmailVerified == true) {
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
                  val authUser = Firebase.auth.currentUser
                  if (authUser == null || !authUser.isEmailVerified) {
                    navController.navigate(LoginRoute) {
                      popUpTo(0) { inclusive = true }
                    }
                  }
                }
                if (Firebase.auth.currentUser?.isEmailVerified == true) {
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
                  val merchantUid = Firebase.auth.currentUser?.uid.orEmpty()
                  MerchantMessagingService.clearCurrentFcmTokenForMerchant(merchantUid)
                  orderViewModel.clearListeners()
                  try {
                    kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                      com.example.db.AppDatabase.getInstance(context).clearAllTables()
                    }
                  } catch (e: Exception) {
                    android.util.Log.w("MerchantLogout", "Failed to clear local merchant database: ${e.message}")
                  }
                  com.example.auth.MerchantAuthManager.signOutMerchant(context)
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
