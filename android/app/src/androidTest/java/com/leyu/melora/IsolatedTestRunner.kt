package com.leyu.melora

import android.app.Application
import android.app.Activity
import android.content.Intent
import android.content.Context
import androidx.test.runner.AndroidJUnitRunner

/** 组件/本地IO验证无需启动播放器、加载用户源或读取用户库；不触发自动播放。 */
class IsolatedTestRunner : AndroidJUnitRunner() {
    // 非UI组件测试不启动主界面。部分系统在测试期间恢复最近任务，空Application不能承载MainActivity。
    // 只在测试运行器中隔离该入口，禁止为测试修改生产Activity/启动播放器或读取用户配置。
    override fun newActivity(cl: ClassLoader, className: String, intent: Intent): Activity =
        if (className == "com.leyu.melora.MainActivity") Activity() else super.newActivity(cl, className, intent)

    override fun newApplication(cl: ClassLoader, className: String, context: Context): Application =
        super.newApplication(cl, Application::class.java.name, context)
}

/** 缓存夹具先等待异步账本初始化/裁剪退出，再释放实例，防止跨用例迟到回调。 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal suspend fun releaseAudioCacheFixture() {
    val owner = com.leyu.melora.playback.AudioCacheStore
    owner.cancelPrefetch()
    val scope = owner.javaClass.getDeclaredField("scope").apply { isAccessible = true }
        .get(owner) as kotlinx.coroutines.CoroutineScope
    kotlinx.coroutines.withTimeout(5_000) {
        scope.coroutineContext[kotlinx.coroutines.Job]?.children?.toList()?.forEach { it.join() }
    }
    val cache = owner.javaClass.getDeclaredField("cache").apply { isAccessible = true }
    (cache.get(owner) as? androidx.media3.datasource.cache.SimpleCache)?.release()
    cache.set(owner, null)
    owner.javaClass.getDeclaredField("evictor").apply { isAccessible = true }.set(owner, null)
}
