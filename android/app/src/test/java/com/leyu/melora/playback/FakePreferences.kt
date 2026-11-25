package com.leyu.melora.playback

import android.content.SharedPreferences
import java.lang.reflect.Proxy

internal class FakePreferences(initialValues: Map<String, Any?> = emptyMap()) {
    val values = initialValues.toMutableMap()
    var editCount = 0
        private set
    var applyCount = 0
        private set

    private val editor: SharedPreferences.Editor

    val sharedPreferences: SharedPreferences = Proxy.newProxyInstance(
        SharedPreferences::class.java.classLoader,
        arrayOf(SharedPreferences::class.java),
    ) { _, method, args ->
        when (method.name) {
            "getString" -> values[args!![0] as String] as? String ?: args[1] as String?
            "getBoolean" -> values[args!![0] as String] as? Boolean ?: args[1] as Boolean
            "contains" -> values.containsKey(args!![0] as String)
            "getAll" -> values.toMap()
            "edit" -> {
                editCount++
                editor
            }
            "toString" -> "FakePreferences"
            "hashCode" -> System.identityHashCode(this)
            "equals" -> args?.firstOrNull() === this
            else -> error("Unsupported SharedPreferences method: ${method.name}")
        }
    } as SharedPreferences

    init {
        editor = Proxy.newProxyInstance(
            SharedPreferences.Editor::class.java.classLoader,
            arrayOf(SharedPreferences.Editor::class.java),
        ) { _, method, args ->
            when (method.name) {
                "putString" -> {
                    values[args!![0] as String] = args[1] as String?
                    editor
                }
                "remove" -> {
                    values.remove(args!![0] as String)
                    editor
                }
                "apply" -> {
                    applyCount++
                    null
                }
                "commit" -> {
                    applyCount++
                    true
                }
                "toString" -> "FakePreferences.Editor"
                "hashCode" -> System.identityHashCode(this)
                "equals" -> args?.firstOrNull() === this
                else -> error("Unsupported SharedPreferences.Editor method: ${method.name}")
            }
        } as SharedPreferences.Editor
    }
}
