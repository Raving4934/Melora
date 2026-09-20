package com.leyu.melora.playback

import android.content.SharedPreferences
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.lang.reflect.Proxy

class ThemeModeTest {
    @Test
    fun eachThemeModeResolvesAgainstBothSystemAppearances() {
        assertFalse(ThemeMode.Auto.isDark(systemDark = false))
        assertTrue(ThemeMode.Auto.isDark(systemDark = true))
        assertFalse(ThemeMode.Light.isDark(systemDark = false))
        assertFalse(ThemeMode.Light.isDark(systemDark = true))
        assertTrue(ThemeMode.Dark.isDark(systemDark = false))
        assertTrue(ThemeMode.Dark.isDark(systemDark = true))
    }

    @Test
    fun restoreDefaultsUnknownValuesAndPrioritizesTheNewKey() {
        assertEquals(ThemeMode.Auto, ThemeMode.restore(value = null, legacyFollowSystem = null))
        assertEquals(ThemeMode.Auto, ThemeMode.restore(value = "unknown", legacyFollowSystem = null))
        assertEquals(ThemeMode.Dark, ThemeMode.restore(value = "dark", legacyFollowSystem = false))
        assertEquals(ThemeMode.Light, ThemeMode.restore(value = "unknown", legacyFollowSystem = false))
    }

    @Test
    fun legacyBooleanMigratesTrueAndFalseAndSecondReadIsIdempotent() {
        listOf(
            true to ThemeMode.Auto,
            false to ThemeMode.Light,
        ).forEach { (legacyValue, expected) ->
            val preferences = FakePreferences(
                mapOf("basic.followSystemTheme" to legacyValue),
            )

            assertEquals(expected, loadThemeMode(preferences.sharedPreferences))
            assertEquals(expected.storageValue, preferences.values[THEME_MODE_PREFERENCE])
            assertFalse(preferences.values.containsKey("basic.followSystemTheme"))
            assertEquals(1, preferences.editCount)
            assertEquals(1, preferences.applyCount)

            assertEquals(expected, loadThemeMode(preferences.sharedPreferences))
            assertEquals(1, preferences.editCount)
            assertEquals(1, preferences.applyCount)
        }
    }

    @Test
    fun defaultAndUnknownStoredValuesAreNormalized() {
        val empty = FakePreferences()
        assertEquals(ThemeMode.Auto, loadThemeMode(empty.sharedPreferences))
        assertEquals("auto", empty.values[THEME_MODE_PREFERENCE])
        assertEquals(1, empty.applyCount)

        val unknown = FakePreferences(mapOf(THEME_MODE_PREFERENCE to "sepia"))
        assertEquals(ThemeMode.Auto, loadThemeMode(unknown.sharedPreferences))
        assertEquals("auto", unknown.values[THEME_MODE_PREFERENCE])
        assertEquals(1, unknown.applyCount)
    }

    @Test
    fun alreadyMigratedValueDoesNotWriteAgain() {
        val preferences = FakePreferences(mapOf(THEME_MODE_PREFERENCE to "dark"))

        assertEquals(ThemeMode.Dark, loadThemeMode(preferences.sharedPreferences))
        assertEquals(0, preferences.editCount)
        assertEquals(0, preferences.applyCount)
    }

    @Test
    fun importPrefersLegalNewValueOverLegacyBoolean() = withSettings {
        MeloraSettings.themeMode.value = ThemeMode.Light

        BackupSettings.apply(BackupSettings.prepare(
            JSONObject()
                .put("themeMode", "dark")
                .put("followSystemTheme", false), emptySet(), emptySet()).first)

        assertEquals(ThemeMode.Dark, MeloraSettings.themeMode.value)
    }

    @Test
    fun importMigratesLegacyTrueAndFalseIncludingFalse() = withSettings {
        listOf(
            true to ThemeMode.Auto,
            false to ThemeMode.Light,
        ).forEach { (legacyValue, expected) ->
            MeloraSettings.themeMode.value = ThemeMode.Dark

            BackupSettings.apply(BackupSettings.prepare(JSONObject().put("followSystemTheme", legacyValue), emptySet(), emptySet()).first)

            assertEquals(expected, MeloraSettings.themeMode.value)
        }
    }

    @Test
    fun importMigratesLegacyBooleanStringsIgnoringCase() = withSettings {
        listOf(
            "TRUE" to ThemeMode.Auto,
            "FaLsE" to ThemeMode.Light,
        ).forEach { (legacyValue, expected) ->
            MeloraSettings.themeMode.value = ThemeMode.Dark

            BackupSettings.apply(BackupSettings.prepare(JSONObject().put("followSystemTheme", legacyValue), emptySet(), emptySet()).first)

            assertEquals(expected, MeloraSettings.themeMode.value)
        }
    }

    @Test
    fun importKeepsCurrentValueForMissingInvalidOrJsonNullFields() = withSettings {
        val current = ThemeMode.Dark
        MeloraSettings.themeMode.value = current
        val invalidBackups = listOf(
            JSONObject(),
            JSONObject().put("themeMode", "sepia"),
            JSONObject().put("themeMode", JSONObject.NULL),
            JSONObject().put("followSystemTheme", "not-a-boolean"),
            JSONObject().put("followSystemTheme", JSONObject.NULL),
            JSONObject()
                .put("themeMode", "sepia")
                .put("followSystemTheme", JSONObject.NULL),
        )

        invalidBackups.forEach { backup ->
            BackupSettings.apply(BackupSettings.prepare(backup, emptySet(), emptySet()).first)
            assertEquals(current, MeloraSettings.themeMode.value)
        }
    }

    @Test
    fun nullNewValueStillAcceptsAValidLegacyFalse() = withSettings {
        MeloraSettings.themeMode.value = ThemeMode.Dark

        BackupSettings.apply(BackupSettings.prepare(
            JSONObject()
                .put("themeMode", JSONObject.NULL)
                .put("followSystemTheme", false), emptySet(), emptySet()).first)

        assertEquals(ThemeMode.Light, MeloraSettings.themeMode.value)
    }

    @Test
    fun exportUsesOnlyTheNewThemeKeyAndPreservesUnrelatedSettings() = withSettings {
        MeloraSettings.themeMode.value = ThemeMode.Dark
        MeloraSettings.sourceAliasEnabled.value = false

        val settings = BackupSettings.collect()

        assertEquals("dark", settings.getString("themeMode"))
        assertFalse(settings.has("followSystemTheme"))
        assertFalse(settings.getBoolean("sourceAliasEnabled"))
    }

    private inline fun withSettings(block: () -> Unit) {
        val originalThemeMode = MeloraSettings.themeMode.value
        val originalSourceAlias = MeloraSettings.sourceAliasEnabled.value
        try {
            block()
        } finally {
            MeloraSettings.themeMode.value = originalThemeMode
            MeloraSettings.sourceAliasEnabled.value = originalSourceAlias
        }
    }

    private class FakePreferences(initialValues: Map<String, Any?> = emptyMap()) {
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
}
