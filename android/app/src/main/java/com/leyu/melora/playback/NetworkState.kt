package com.leyu.melora.playback

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** 网络类型感知：按当前链路（WiFi/移动网络）自动选择播放音质。 */
object NetworkState {
    fun isCellular(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) &&
            !caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun isConnected(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    /** 后台整曲补齐仅允许系统明确标记为非计费的网络。 */
    fun isUnmetered(context: Context): Boolean {
        val manager = context.getSystemService(ConnectivityManager::class.java) ?: return false
        val network = manager.activeNetwork ?: return false
        val caps = manager.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
    }

    /** 当前应使用的播放音质：移动数据用移动档，其余（WiFi/以太网/VPN）用 WiFi 档。 */
    fun playQuality(context: Context): String =
        if (isCellular(context)) MeloraSettings.playQualityMobile.value else MeloraSettings.playQualityWifi.value
}
