package com.dreef3.weightlossapp.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

enum class NetworkConnectionType {
    Wifi,
    Cellular,
    Other,
    Offline,
}

open class NetworkConnectionMonitor(
    private val context: Context,
) {
    open fun currentConnectionType(): NetworkConnectionType {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
            ?: return NetworkConnectionType.Offline
        val capabilities = connectivityManager.getNetworkCapabilities(connectivityManager.activeNetwork)
            ?: return NetworkConnectionType.Offline
        return when {
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkConnectionType.Wifi
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkConnectionType.Cellular
            else -> NetworkConnectionType.Other
        }
    }
}
