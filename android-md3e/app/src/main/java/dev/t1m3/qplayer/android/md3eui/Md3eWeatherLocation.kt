package dev.t1m3.qplayer.android.md3eui

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Geocoder
import android.location.Address
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Build
import android.os.Looper
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import dev.t1m3.qplayer.ai.WeatherFacts
import dev.t1m3.qplayer.ai.ChinaWeatherFacts
import java.time.ZonedDateTime
import java.util.Locale
import kotlin.coroutines.resume

/** City-level foreground lookup only. No coordinates are persisted or sent to search. */
internal object Md3eWeatherLocation {
    fun allowed(context: Context) = context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

    @Suppress("DEPRECATION", "MissingPermission")
    private suspend fun location(context: Context): Location? {
        if (!allowed(context)) return null
        val manager = context.getSystemService(Context.LOCATION_SERVICE) as? LocationManager ?: return null
        val providers = listOf("fused", LocationManager.NETWORK_PROVIDER).filter {
            runCatching { manager.isProviderEnabled(it) }.getOrDefault(false)
        }
        val recent = (providers + LocationManager.PASSIVE_PROVIDER).distinct().mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time in 0..10_800_000L }.maxByOrNull { it.time }
        return recent ?: withTimeoutOrNull(6000) {
            if (providers.isEmpty()) return@withTimeoutOrNull null
            suspendCancellableCoroutine<Location?> { continuation ->
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) {
                        manager.removeUpdates(this)
                        if (continuation.isActive) continuation.resume(location)
                    }
                    override fun onProviderDisabled(provider: String) {}
                    override fun onProviderEnabled(provider: String) {}
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
                }
                continuation.invokeOnCancellation { runCatching { manager.removeUpdates(listener) } }
                // Some ROMs expose fused but never deliver its result. Race the available coarse providers.
                var registered = false
                providers.forEach { provider ->
                    if (runCatching { manager.requestSingleUpdate(provider, listener, Looper.getMainLooper()) }.isSuccess)
                        registered = true
                }
                if (!registered && continuation.isActive) continuation.resume(null)
            }
        }
    }

    @Suppress("DEPRECATION")
    private suspend fun address(context: Context, location: Location): Address? {
        if (!Geocoder.isPresent()) return null
        // Weather catalogs use Chinese names even when the phone's UI language is English.
        val geocoder = Geocoder(context, Locale.SIMPLIFIED_CHINESE)
        return if (Build.VERSION.SDK_INT >= 33) withTimeoutOrNull(5000) {
            suspendCancellableCoroutine { continuation ->
                try {
                    geocoder.getFromLocation(location.latitude, location.longitude, 1, object : Geocoder.GeocodeListener {
                        override fun onGeocode(addresses: MutableList<Address>) {
                            if (continuation.isActive) continuation.resume(addresses.firstOrNull())
                        }
                        override fun onError(errorMessage: String?) {
                            if (continuation.isActive) continuation.resume(null)
                        }
                    })
                } catch (_: Exception) { if (continuation.isActive) continuation.resume(null) }
            }
        } else runCatching { geocoder.getFromLocation(location.latitude, location.longitude, 1)?.firstOrNull() }.getOrNull()
    }

    @Suppress("DEPRECATION")
    suspend fun weather(context: Context, now: ZonedDateTime, fallbackCity: String = ""): String = withContext(Dispatchers.IO) {
        suspend fun fallback(reason: String): String {
            val parts = fallbackCity.trim().split('/', '／').map { it.trim() }
            return if (parts.size == 2 && parts.all { it.isNotBlank() })
                ChinaWeatherFacts.lookup(parts[0], parts[1], "", now, 10_000)
            else WeatherFacts.unavailable(reason, now)
        }
        if (!allowed(context)) return@withContext fallback("未授权大致位置")
        val location = location(context) ?: return@withContext fallback("系统尚未返回大致位置，可重试或设置备用天气城市")
        val address = address(context, location)
            ?: return@withContext fallback("系统无法将位置转为省市，可设置备用天气城市")
        val province = address.adminArea.orEmpty()
        val city = listOf(address.locality, address.subAdminArea, address.adminArea).firstOrNull { !it.isNullOrBlank() }.orEmpty()
        if (province.isBlank() || city.isBlank()) return@withContext fallback("系统返回的位置缺少省市，可设置备用天气城市")
        if (address.countryCode.isNullOrBlank() || address.countryCode in listOf("CN", "HK", "MO", "TW"))
            if (!province.any { it in '\u4e00'..'\u9fff' } || !city.any { it in '\u4e00'..'\u9fff' })
                fallback("系统未返回中文省市，可设置备用天气城市")
            else ChinaWeatherFacts.lookup(province, city, address.subLocality.orEmpty(), now, 10_000)
        else WeatherFacts.lookup(location.latitude, location.longitude, "$province $city".trim(), now, 5000)
    }
}

@Composable
internal fun Md3eWeatherPermissionHost(runtime: Md3eRuntime) {
    val context = LocalContext.current
    val prefs = context.getSharedPreferences("md3e_weather_location", Context.MODE_PRIVATE)
    val revision = runtime.settingsRevision
    val configured = runtime.settings.str("aiApiKey").isNotBlank()
    val enabled = runtime.settings.bool("aiDailyContextEnabled")
    val requested = runtime.weatherLocationRequested
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        runtime.weatherLocationRequested = false
        runtime.notice = if (granted) "已授权大致位置，天气会在下次生成歌单时更新" else "未授权位置，仍可根据音乐事件推荐"
    }
    LaunchedEffect(configured, enabled, requested, revision) {
        if ((configured && enabled && !prefs.getBoolean("asked", false)) || requested) {
            prefs.edit().putBoolean("asked", true).apply()
            if (!Md3eWeatherLocation.allowed(context)) launcher.launch(Manifest.permission.ACCESS_COARSE_LOCATION)
            else {
                runtime.weatherLocationRequested = false
                if (requested) runtime.notice = "已获得大致位置权限"
            }
        }
    }
}
