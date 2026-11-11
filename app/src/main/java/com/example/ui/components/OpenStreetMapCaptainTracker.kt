package com.example.ui.components

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.DirectionsBike
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.example.ui.theme.Primary
import java.util.Locale

/**
 * OpenStreetMap + Leaflet interactive view for tracking the assigned Captain on a specific trip.
 * Constraints:
 * - Restaurant only sees the assigned captain for this trip.
 * - Shows restaurant marker and captain marker with a connecting line.
 * - Live coordinate updates via JavaScript evaluation.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun OpenStreetMapCaptainTracker(
    restaurantLat: Double,
    restaurantLng: Double,
    captainLat: Double?,
    captainLng: Double?,
    captainName: String,
    estimatedArrival: String = "",
    modifier: Modifier = Modifier
) {
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    LaunchedEffect(captainLat, captainLng) {
        if (captainLat != null && captainLng != null) {
            webViewRef?.evaluateJavascript(
                "if (window.updateCaptainLocation) { window.updateCaptainLocation($captainLat, $captainLng); }",
                null
            )
        }
    }

    val htmlContent = remember(restaurantLat, restaurantLng) {
        val initialCapLat = captainLat ?: (restaurantLat + 0.007)
        val initialCapLng = captainLng ?: (restaurantLng + 0.005)
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
                .restaurant-marker {
                    background: #E53935;
                    border: 3px solid #000000;
                    border-radius: 50%;
                    width: 28px !important;
                    height: 28px !important;
                    box-shadow: 0 0 10px rgba(229, 57, 53, 0.8);
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    font-size: 14px;
                }
                .captain-marker {
                    background: #00E676;
                    border: 3px solid #000000;
                    border-radius: 50%;
                    width: 32px !important;
                    height: 32px !important;
                    box-shadow: 0 0 12px rgba(0, 230, 118, 0.8);
                    display: flex;
                    align-items: center;
                    justify-content: center;
                    font-size: 16px;
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
                    center: [$restaurantLat, $restaurantLng],
                    zoom: 14,
                    zoomControl: false
                });

                L.tileLayer('https://{s}.tile.openstreetmap.org/{z}/{x}/{y}.png', {
                    maxZoom: 19,
                    attribution: '© OpenStreetMap'
                }).addTo(map);

                var restIcon = L.divIcon({
                    className: 'restaurant-marker',
                    html: '🏪',
                    iconSize: [28, 28],
                    iconAnchor: [14, 14]
                });

                var capIcon = L.divIcon({
                    className: 'captain-marker',
                    html: '🛵',
                    iconSize: [32, 32],
                    iconAnchor: [16, 16]
                });

                var restMarker = L.marker([$restaurantLat, $restaurantLng], { icon: restIcon })
                    .addTo(map)
                    .bindPopup("<b>موقع مطعمك</b>");

                var capMarker = null;
                var routeLine = null;

                function setCaptain(lat, lng) {
                    if (!capMarker) {
                        capMarker = L.marker([lat, lng], { icon: capIcon }).addTo(map);
                        capMarker.bindPopup("<b>الكابتن: $captainName</b><br>في الطريق لمطعمك");
                    } else {
                        capMarker.setLatLng([lat, lng]);
                    }

                    if (!routeLine) {
                        routeLine = L.polyline([[$restaurantLat, $restaurantLng], [lat, lng]], {
                            color: '#E53935',
                            weight: 4,
                            dashArray: '8, 8',
                            opacity: 0.8
                        }).addTo(map);
                    } else {
                        routeLine.setLatLngs([[$restaurantLat, $restaurantLng], [lat, lng]]);
                    }

                    var group = new L.featureGroup([restMarker, capMarker]);
                    map.fitBounds(group.getBounds().pad(0.2));
                }

                if (${captainLat != null && captainLng != null}) {
                    setCaptain($initialCapLat, $initialCapLng);
                }

                window.updateCaptainLocation = function(lat, lng) {
                    setCaptain(lat, lng);
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
                        imageVector = Icons.Default.DirectionsBike,
                        contentDescription = null,
                        tint = Color(0xFF00E676),
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "متابعة مسار الكابتن مباشرة",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                }

                if (estimatedArrival.isNotBlank()) {
                    Surface(
                        color = Primary.copy(alpha = 0.15f),
                        shape = RoundedCornerShape(8.dp),
                        border = BorderStroke(1.dp, Primary.copy(alpha = 0.4f))
                    ) {
                        Text(
                            text = "وصول: $estimatedArrival",
                            style = MaterialTheme.typography.labelSmall,
                            color = Primary,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(220.dp)
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
                            loadDataWithBaseURL("https://openstreetmap.org", htmlContent, "text/html", "UTF-8", null)
                        }
                    },
                    update = {
                        webViewRef = it
                    }
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .clip(RoundedCornerShape(50))
                            .background(Color(0xFF00E676))
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = if (captainLat != null && captainLng != null) {
                            String.format(Locale.US, "موقع الكابتن المباشر: %.4f, %.4f", captainLat, captainLng)
                        } else {
                            "جاري تحديث إحداثيات الكابتن..."
                        },
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}
