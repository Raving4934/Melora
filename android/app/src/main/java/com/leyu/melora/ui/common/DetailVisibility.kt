package com.leyu.melora.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.RememberObserver
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember

/** 主顶栏是否应被真实详情状态隐藏。 */
object DetailVisibility {
    private val activeStates = mutableIntStateOf(0)

    val visible: Boolean
        get() = activeStates.intValue > 0

    internal fun enter() {
        activeStates.intValue += 1
    }

    internal fun leave() {
        activeStates.intValue = (activeStates.intValue - 1).coerceAtLeast(0)
    }
}

/**
 * 详情目标本身就是唯一状态源：值从 null 变为非 null 时隐藏主顶栏，清空或离开页面时自动恢复。
 */
@Composable
fun <T : Any> rememberDetailState(): MutableState<T?> = remember { DetailState<T>() }

private class DetailState<T : Any> : MutableState<T?>, RememberObserver {
    private val backing = mutableStateOf<T?>(null)
    private var registered = false

    override var value: T?
        get() = backing.value
        set(newValue) {
            val wasOpen = backing.value != null
            backing.value = newValue
            val isOpen = newValue != null
            if (registered && wasOpen != isOpen) {
                if (isOpen) DetailVisibility.enter() else DetailVisibility.leave()
            }
        }

    override fun component1(): T? = value

    override fun component2(): (T?) -> Unit = { value = it }

    override fun onRemembered() {
        if (registered) return
        registered = true
        if (value != null) DetailVisibility.enter()
    }

    override fun onForgotten() = unregister()

    override fun onAbandoned() = unregister()

    private fun unregister() {
        if (!registered) return
        if (value != null) DetailVisibility.leave()
        registered = false
    }
}
