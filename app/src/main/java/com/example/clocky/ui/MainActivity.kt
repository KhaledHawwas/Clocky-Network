package com.example.clocky.ui

import android.Manifest
import android.app.ActivityManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Bundle
import android.os.IBinder
import android.telephony.TelephonyManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.*
import androidx.core.app.ActivityCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.example.clocky.ui.components.DottedCircularProgressBackground
import com.example.clocky.ui.components.rememberDottedCircularProgressBackgroundState
import com.example.clocky.ui.stopwatch.Stopwatch
import com.example.clocky.stopwatchservice.StopwatchService
import com.example.clocky.ui.theme.ClockyTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var serviceConnection: ServiceConnection? = null
    private var stopwatchService: StopwatchService? = null
    private val elapsedTimeStringStream = MutableStateFlow("00:00:00:00")
    private val stopwatchStateStream = MutableStateFlow<StopwatchService.StopwatchState?>(null)
    companion object {
        private const val REQ_PERMISSIONS = 1001
    }

    private val isPlaying = mutableStateOf(false)


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (!isStopwatchServiceRunning()) startStopwatchService()
        bindStopwatchService()
        setContent { ClockyTheme(content = { ClockyApp() }) }
    }

    @Composable
    private fun ClockyApp() {
        val elapsedTimeString by elapsedTimeStringStream.collectAsStateWithLifecycle()
        val stopwatchState by stopwatchStateStream.collectAsStateWithLifecycle()
        val isInitiallyRunning = remember {
            stopwatchState == StopwatchService.StopwatchState.RUNNING
        }
        val dottedProgressBackgroundState = rememberDottedCircularProgressBackgroundState(
            isInitiallyRunning = isInitiallyRunning
        )
        val isStopButtonEnabled = remember(stopwatchState) {
            stopwatchState != null && stopwatchState != StopwatchService.StopwatchState.RESET
        }
        val isStopwatchRunning = remember(stopwatchState) {
            stopwatchState == StopwatchService.StopwatchState.RUNNING
        }
        DottedCircularProgressBackground(state = dottedProgressBackgroundState) {
            Stopwatch(
                elapsedTimeText = { elapsedTimeString },
                onPlayButtonClick = {
                    stopwatchService?.startStopwatch()
                    dottedProgressBackgroundState.start()
                },
                onPauseButtonClick = {
                    stopwatchService?.pauseStopwatch()
                    dottedProgressBackgroundState.pause()
                },
                onStopButtonClick = {
                    stopwatchService?.stopAndResetStopwatch()
                    dottedProgressBackgroundState.stopAndReset()
                },
                isStopButtonEnabled = isStopButtonEnabled,
                isStopwatchRunning = isStopwatchRunning
            )
        }
    }

    override fun onStop() {
        super.onStop()
        if (
            isStopwatchServiceRunning() &&
            stopwatchStateStream.value == StopwatchService.StopwatchState.RESET
        ) stopStopwatchService()
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceConnection?.let(::unbindService)
        if (
            isStopwatchServiceRunning() &&
            stopwatchStateStream.value == StopwatchService.StopwatchState.RESET
        ) stopStopwatchService()
    }

    /**
     * Checks if the [StopwatchService] is running.
     *
     * @return true if the StopwatchService is running, false otherwise.
     */
    @Suppress("DEPRECATION")
    private fun isStopwatchServiceRunning(): Boolean {
        return (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager)
            .getRunningServices(Int.MAX_VALUE)
            .any { it.service.className == StopwatchService::class.java.name }
    }

    /**
     * Used to start the [StopwatchService].
     */
    private fun startStopwatchService() {
        val intent = Intent(this, StopwatchService::class.java)
        ActivityCompat.startForegroundService(this, intent)
    }

    /**
     * Used to stop the [StopwatchService].
     */
    private fun stopStopwatchService() {
        val intent = Intent(this, StopwatchService::class.java)
        stopService(intent)
    }

    /**
     * Used to bind the activity to the [StopwatchService].
     */
    private fun bindStopwatchService() {
        val intent = Intent(this, StopwatchService::class.java)
        serviceConnection = createServiceConnection()
        bindService(
            intent,
            serviceConnection!!,
            Context.BIND_IMPORTANT
        )
    }

    /**
     * Creates a [ServiceConnection] to connect to the [StopwatchService].
     */
    private fun createServiceConnection() = object : ServiceConnection {
        override fun onServiceConnected(componentName: ComponentName, binder: IBinder) {
            val stopwatchServiceBinder = binder as StopwatchService.StopwatchServiceBinder
            stopwatchService = stopwatchServiceBinder.service
            repeatOnLifecycleInLifecycleScope {
                stopwatchService!!
                    .formattedElapsedMillisStream
                    .collect { elapsedTimeStringStream.value = it }
            }
            repeatOnLifecycleInLifecycleScope {
                stopwatchService!!
                    .stopwatchState
                    .collect { stopwatchStateStream.value = it }
            }
        }

        override fun onServiceDisconnected(componentName: ComponentName) {
            serviceConnection = null
        }
    }
    @Deprecated("Overrides deprecated onRequestPermissionsResult; consider Activity Result API")
    @Suppress("DEPRECATION")
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_PERMISSIONS) {
            // If any relevant permission granted, refresh info shown in the UI
            var updated = false
            permissions.forEachIndexed { idx, perm ->
                if (perm == Manifest.permission.ACCESS_NETWORK_STATE && grantResults.getOrNull(idx) == PackageManager.PERMISSION_GRANTED) {
                    networkInfo.value = collectNetworkInfo()
                    updated = true
                }
                if (perm == Manifest.permission.READ_PHONE_STATE && grantResults.getOrNull(idx) == PackageManager.PERMISSION_GRANTED) {
                    // READ_PHONE_STATE may provide more detailed cellular info
                    networkInfo.value = collectNetworkInfo()
                    updated = true
                }
            }

        }
    }

}
    /**
     * A utility function that is a shortcut for the following code snippet.
     * ```
     * lifecycleScope.launch {
     *       repeatOnLifecycle(...){...}
     * }
     * ```
     */
    private fun ComponentActivity.repeatOnLifecycleInLifecycleScope(
        state: Lifecycle.State = Lifecycle.State.STARTED,
        block: suspend CoroutineScope.() -> Unit
    ): Job = lifecycleScope.launch { repeatOnLifecycle(state, block) }
 val networkInfo = mutableStateOf("Unknown network")

fun Context.collectNetworkInfo(): String {
    try {
        // Check for ACCESS_NETWORK_STATE before calling Connectivity APIs (lint + runtime).
        if (checkSelfPermission(Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {
            Log.w("WatchButtons", "ACCESS_NETWORK_STATE missing - cannot read network state")
            return "unknown (ACCESS_NETWORK_STATE missing)"
        }

        val cm = getSystemService(ConnectivityManager::class.java)
        val active = cm?.activeNetwork
        val caps = if (active != null) cm.getNetworkCapabilities(active) else null

        val transports = mutableListOf<String>()
        if (caps != null) {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) transports.add("WiFi")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) transports.add("Cellular")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) transports.add("Bluetooth")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) transports.add("Ethernet")
        }

        var result = if (transports.isNotEmpty()) transports.joinToString("+") else "No network"

        // If cellular, try to get a more specific network type (LTE/5G/etc.) only if permission is granted
        if (transports.contains("Cellular")) {
            if (checkSelfPermission(Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED) {
                val tm = getSystemService(TelephonyManager::class.java)
                val typeInt = try {
                    // dataNetworkType is preferred and available on modern devices
                    tm?.dataNetworkType ?: -1
                } catch (se: SecurityException) {
                    Log.w("WatchButtons", "Telephony access denied", se)
                    -1
                }

                val typeName = when (typeInt) {
                    TelephonyManager.NETWORK_TYPE_LTE -> "LTE"
                    TelephonyManager.NETWORK_TYPE_NR -> "5G-NR"
                    TelephonyManager.NETWORK_TYPE_HSPAP -> "HSPA+"
                    TelephonyManager.NETWORK_TYPE_HSDPA -> "HSDPA"
                    TelephonyManager.NETWORK_TYPE_HSPA -> "HSPA"
                    TelephonyManager.NETWORK_TYPE_EDGE -> "EDGE"
                    TelephonyManager.NETWORK_TYPE_GPRS -> "GPRS"
                    TelephonyManager.NETWORK_TYPE_UMTS -> "UMTS"
                    -1 -> "unknown (security)"
                    else -> "UNKNOWN(${typeInt})"
                }

                result = "$result ($typeName)"
            } else {
                result = "$result (unknown: READ_PHONE_STATE missing)"
            }
        }

        Log.d("WatchButtons", "Collected network info: $result")
        networkInfo .value = result
        return result
    } catch (e: Exception) {
        Log.w("WatchButtons", "Failed to collect network info", e)
        networkInfo .value = "Error collecting network info"
        return "Error collecting network info"
    }
}

