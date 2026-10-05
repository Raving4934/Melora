package com.leyu.melora.playback

import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.Response
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

/** 回调内完成响应读取；协程取消会同时取消 HTTP 请求（包括响应体读取）。不向外转交裸 Response。 */
internal suspend fun <T> Call.readResponse(read: (Response) -> T): T = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, e: IOException) {
            continuation.resumeWith(Result.failure(e))
        }

        override fun onResponse(call: Call, response: Response) {
            val result = runCatching {
                response.use {
                    if (!continuation.isActive) throw CancellationException("网络请求已取消")
                    read(it)
                }
            }
            continuation.resumeWith(result)
        }
    })
}
