package com.xd.network

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.Closeable
import java.util.concurrent.atomic.AtomicBoolean

enum class NetworkTransport {
    WIFI,
    CELLULAR,
    ETHERNET,
    VPN,
    BLUETOOTH,
    OTHER
}

data class NetworkStatus(
    val isAvailable: Boolean,
    val isValidated: Boolean,
    val isMetered: Boolean,
    val transports: Set<NetworkTransport>,
    val downstreamKbps: Int,
    val upstreamKbps: Int
) {
    companion object {
        @JvmField
        val Unavailable = NetworkStatus(
            isAvailable = false,
            isValidated = false,
            isMetered = true,
            transports = emptySet(),
            downstreamKbps = 0,
            upstreamKbps = 0
        )
    }
}

/**
 * Observes the app's default Android network without polling. Call [close] when
 * the observer is no longer needed so the platform callback is unregistered.
 */
class NetworkMonitor @JvmOverloads constructor(
    context: Context,
    autoStart: Boolean = true
) : Closeable {
    private val connectivityManager = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val started = AtomicBoolean(false)
    private val mutableStatus = MutableStateFlow(NetworkStatus.Unavailable)
    private val availabilityLock = Object()

    val status: StateFlow<NetworkStatus> = mutableStatus.asStateFlow()
    val currentStatus: NetworkStatus get() = mutableStatus.value

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            updateStatus(
                NetworkStatus(
                    isAvailable = true,
                    isValidated = false,
                    isMetered = true,
                    transports = emptySet(),
                    downstreamKbps = 0,
                    upstreamKbps = 0
                )
            )
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            updateStatus(capabilities.toStatus())
        }

        override fun onLost(network: Network) {
            updateStatus(NetworkStatus.Unavailable)
        }
    }

    init {
        if (autoStart) start()
    }

    @SuppressLint("MissingPermission")
    fun start() {
        if (!started.compareAndSet(false, true)) return
        try {
            val activeNetwork = connectivityManager.activeNetwork
            updateStatus(
                activeNetwork
                    ?.let(connectivityManager::getNetworkCapabilities)
                    ?.toStatus()
                    ?: NetworkStatus.Unavailable
            )
            connectivityManager.registerDefaultNetworkCallback(callback)
        } catch (error: RuntimeException) {
            started.set(false)
            throw error
        }
    }

    fun stop() {
        if (!started.compareAndSet(true, false)) return
        runCatching { connectivityManager.unregisterNetworkCallback(callback) }
        updateStatus(NetworkStatus.Unavailable)
    }

    override fun close() = stop()

    internal fun awaitAvailable(timeoutMillis: Long, checkCall: () -> Unit): Boolean {
        val deadline = System.nanoTime() + timeoutMillis * 1_000_000L
        synchronized(availabilityLock) {
            while (!mutableStatus.value.isAvailable) {
                checkCall()
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) return false
                val waitMillis = minOf(remainingNanos / 1_000_000L, 100L).coerceAtLeast(1L)
                try {
                    availabilityLock.wait(waitMillis)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                    throw NetworkException.Cancelled()
                }
            }
            return true
        }
    }

    private fun updateStatus(value: NetworkStatus) {
        mutableStatus.value = value
        synchronized(availabilityLock) { availabilityLock.notifyAll() }
    }

    private fun NetworkCapabilities.toStatus(): NetworkStatus {
        val transports = buildSet {
            if (hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add(NetworkTransport.WIFI)
            if (hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add(NetworkTransport.CELLULAR)
            if (hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add(NetworkTransport.ETHERNET)
            if (hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add(NetworkTransport.VPN)
            if (hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add(NetworkTransport.BLUETOOTH)
            if (isEmpty()) add(NetworkTransport.OTHER)
        }
        return NetworkStatus(
            isAvailable = hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
            isValidated = hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
            isMetered = !hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            transports = transports,
            downstreamKbps = linkDownstreamBandwidthKbps,
            upstreamKbps = linkUpstreamBandwidthKbps
        )
    }
}
