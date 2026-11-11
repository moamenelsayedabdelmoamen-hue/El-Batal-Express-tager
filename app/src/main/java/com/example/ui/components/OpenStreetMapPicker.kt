package com.example.ui.components

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationManager
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GpsFixed
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.Primary
import java.util.Locale

/**
 * OpenStreetMap + Leaflet interactive location picker for Restaurant
 * Supports:
 * 1. Current GPS Location detection
 * 2. Manual pinpoint / drag or tap to select custom coordinates
 * 3. Crimson Red + Dark styling consistent with El-Batal theme
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OpenStreetMapLocationPicker(
    initialLatitude: Double? = null,
    initialLongitude: Double? = null,
    onLocationSelected: (lat: Double, lng: Double) -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val defaultLat = initialLatitude ?: 30.0444 // Cairo default
    val defaultLng = initialLongitude ?: 31.2357

    var currentLat by remember(initialLatitude) { mutableDoubleStateOf(defaultLat) }
    var currentLng by remember(initialLongitude) { mutableDoubleStateOf(defaultLng) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isGpsLocating by remember { mutableStateOf(false) }

    fun updateMarkerInMap(lat: Double, lng: Double) {
        currentLat = lat
        currentLng = lng
        onLocationSelected(lat, lng)
        webViewRef?.post {
            webViewRef?.evaluateJavascript(
                "if (window.setRestaurantLocation) { window.setRestaurantLocation($lat, $lng); }",
                null
            )
        }
    }

    fun requestGpsLocation() {
        isGpsLocating = true
        try {
            val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager
            var bestLocation: Location? = null

            if (locationManager != null) {
                val providers = locationManager.getProviders(true)
                for (provider in providers) {
                    try {
                        val l = locationManager.getLastKnownLocation(provider) ?: continue
                        if (bestLocation == null || l.accuracy < bestLocation.accuracy) {
                            bestLocation = l
                        }
                    } catch (_: SecurityException) {}
                }
            }

            if (bestLocation != null) {
                updateMarkerInMap(bestLocation.latitude, bestLocation.longitude)
            } else {
                // Fallback to Cairo center if GPS is unavailable
                updateMarkerInMap(defaultLat, defaultLng)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        } finally {
            isGpsLocating = false
        }
    }

    val htmlContent = remember {
        """
        <!DOCTYPE html>
        <html>
        <head>
            <meta charset="utf-8" />
            <meta name="viewport" content="width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no" />
            <link rel="stylesheet" href="https://unpkg.com/leaflet@1.9.4/dist/leaflet.css" />
            <script src="https://unpkg.com/leaflet@1.9.4/dist/leaflet.js"></script>
            <style>
                html, body {
                    height: 100%;
                    width: 100%;
                    margin: 0;
                    padding: 0;
                    background: #121212;
                    overflow: hidden;
                    font-family: sans-serif;
                }
                #map {
                    height: 100%;
                    width: 100%;
                }
                .custom-marker {
                    background: #E53935;
                    border: 3px solid #000000;
                    border-radius: 50%;
                    width: 28px !important;
                    height: 28px !important;
                    box-shadow: 0 0 10px rgba(229, 57, 53, 0.8);
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    color: white;
                    font-weight: bold;
                    font-size: 14px;
                }
                .leaflet-popup-content-wrapper {
                    background: #1E1E1E;
                    color: #FFFFFF;
                    border-radius: 8px;
                    border: 1px solid #E53935;
                }
                .leaflet-popup-tip {
                    background: #1E1E1E;
                }
            </style>
        </head>
        <body>
            <div id="map"></div>
            <script>
                var map = L.map('map', {
                    center: [$defaultLat, $defaultLng],
                    zoom: 15,
                    zoomControl: false
                });

                L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                    maxZoom: 19,
                    attribution: '© OpenStreetMap'
                }).addTo(map);

                var markerIcon = L.divIcon({
                    className: 'custom-marker',
                    html: '🏪',
                    iconSize: [28, 28],
                    iconAnchor: [14, 14]
                });

                var marker = L.marker([$defaultLat, $defaultLng], {
                    draggable: true,
                    icon: markerIcon
                }).addTo(map);

                marker.bindPopup("<b>موقع المطعم</b><br>يمكنك سحب العلامة أو الضغط على الخريطة").openPopup();

                marker.on('dragend', function(e) {
                    var pos = marker.getLatLng();
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onLocationPicked(pos.lat, pos.lng);
                    }
                });

                map.on('click', function(e) {
                    marker.setLatLng(e.latlng);
                    if (window.AndroidBridge) {
                        window.AndroidBridge.onLocationPicked(e.latlng.lat, e.latlng.lng);
                    }
                });

                window.setRestaurantLocation = function(lat, lng) {
                    var newLatLng = new L.LatLng(lat, lng);
                    marker.setLatLng(newLatLng);
                    map.panTo(newLatLng);
                };
            </script>
        </body>
        </html>
        """.trimIndent()
    }

    Card(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, Primary.copy(alpha = 0.5f))
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
                    Icon(
                        imageVector = Icons.Default.LocationOn,
                        contentDescription = null,
                        tint = Primary,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "موقع المطعم على الخريطة (OpenStreetMap)",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                FilledTonalButton(
                    onClick = { requestGpsLocation() },
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp),
                    shape = RoundedCornerShape(8.dp),
                    colors = ButtonDefaults.filledTonalButtonColors(
                        containerColor = Primary.copy(alpha = 0.15f),
                        contentColor = Primary
                    )
                ) {
                    if (isGpsLocating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(14.dp),
                            color = Primary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Default.GpsFixed,
                            contentDescription = "GPS",
                            modifier = Modifier.size(14.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = "موقعي الحالي GPS",
                        style = MaterialTheme.typography.labelSmall,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Map Container
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF1E1E1E))
            ) {
                AndroidView(
                    modifier = Modifier.fillMaxSize(),
                    factory = { ctx ->
                        WebView(ctx).apply {
                            webViewRef = this
                            settings.javaScriptEnabled = true
                            settings.domStorageEnabled = true
                            settings.loadWithOverviewMode = true
                            settings.useWideViewPort = true
                            webViewClient = WebViewClient()
                            addJavascriptInterface(object {
                                @JavascriptInterface
                                fun onLocationPicked(lat: Double, lng: Double) {
                                    currentLat = lat
                                    currentLng = lng
                                    onLocationSelected(lat, lng)
                                }
                            }, "AndroidBridge")
                            loadDataWithBaseURL("https://openstreetmap.org", htmlContent, "text/html", "UTF-8", null)
                        }
                    },
                    update = {
                        webViewRef = it
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Coordinates Display & Guidance
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = String.format(Locale.US, "الإحداثيات: %.5f, %.5f", currentLat, currentLng),
                    style = MaterialTheme.typography.labelSmall,
                    color = Primary,
                    fontWeight = FontWeight.SemiBold
                )

                Text(
                    text = "اضغط على الخريطة أو اسحب العلامة لتحديد الموقع",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
