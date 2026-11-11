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
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.example.db.OrderViewModel
import com.example.network.NetworkHelper
import com.example.ui.theme.ElBatalExpressTheme
import com.example.ui.theme.ThemeManager
import com.google.firebase.Firebase
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
  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
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
        val initialRoute = androidx.compose.runtime.remember {
          val isGoogleUser = currentUser?.providerData?.any { it.providerId == "google.com" } == true
          if (currentUser != null && (currentUser.isEmailVerified || isGoogleUser)) {
            RestaurantDashboardRoute
          } else {
            LoginRoute
          }
        }
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
                  onLoginSuccess = { navController.navigate(SubscriptionRoute) },
                  onRegisterClick = { navController.navigate(RegisterRoute) }
                )
              }
              composable<RegisterRoute> {
                RegisterScreen(
                  onRegisterSuccess = {
                    navController.navigate(LoginRoute) {
                      popUpTo(LoginRoute) { inclusive = true }
                    }
                  }
                )
              }
              composable<SubscriptionRoute> {
                SubscriptionPlansScreen(
                  onSkip = { navController.navigate(RestaurantDashboardRoute) }
                )
              }
              composable<RestaurantDashboardRoute> {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                  if (Firebase.auth.currentUser == null) {
                    navController.navigate(LoginRoute) {
                      popUpTo(0) { inclusive = true }
                    }
                  }
                }
                if (Firebase.auth.currentUser != null) {
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
                  if (Firebase.auth.currentUser == null) {
                    navController.navigate(LoginRoute) {
                      popUpTo(0) { inclusive = true }
                    }
                  }
                }
                if (Firebase.auth.currentUser != null) {
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
                  if (Firebase.auth.currentUser == null) {
                    navController.navigate(LoginRoute) {
                      popUpTo(0) { inclusive = true }
                    }
                  }
                }
                if (Firebase.auth.currentUser != null) {
                  OrderCourierScreen(
                    onBack = { navController.popBackStack() },
                    onOrderSubmitted = { navController.navigate(OrderHistoryRoute) },
                    orderViewModel = orderViewModel
                  )
                }
              }
              composable<OrderHistoryRoute> {
                androidx.compose.runtime.LaunchedEffect(Unit) {
                  if (Firebase.auth.currentUser == null) {
                    navController.navigate(LoginRoute) {
                      popUpTo(0) { inclusive = true }
                    }
                  }
                }
                if (Firebase.auth.currentUser != null) {
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
                  if (Firebase.auth.currentUser == null) {
                    navController.navigate(LoginRoute) {
                      popUpTo(0) { inclusive = true }
                    }
                  }
                }
                if (Firebase.auth.currentUser != null) {
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

          SimulatedPushNotificationOverlay(
            notification = pushNotification,
            onDismiss = { orderViewModel.dismissPushNotification() }
          )

          if (currentUser != null && subscriptionStatus.isExpired) {
            SubscriptionExpiredScreen(
              message = subscriptionStatus.message ?: "انتهت فترة اشتراكك (30 يوماً)، يرجى التواصل مع الإدارة للتجديد",
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
                kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Main).launch {
                  com.example.auth.MerchantAuthManager.signOutMerchant(context)
                  orderViewModel.clearListeners()
                  navController.navigate(LoginRoute) {
                    popUpTo(0) { inclusive = true }
                  }
                }
              }
            )
          }
        }
      }
    }
  }
}
