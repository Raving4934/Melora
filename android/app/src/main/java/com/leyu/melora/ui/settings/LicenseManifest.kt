package com.leyu.melora.ui.settings

import android.content.Context
import org.json.JSONObject

/**
 * The bundled license manifest is the single source for the About page and
 * the lightweight source/asset coverage checks.
 */
internal data class LicenseManifest(
    val entries: List<LicenseEntry>,
    val licenseTexts: Map<String, LicenseText>,
) {
    val aboutEntries: List<LicenseEntry>
        get() = entries.filter(LicenseEntry::showInAbout)

    fun textFor(licenseId: String): LicenseText =
        requireNotNull(licenseTexts[licenseId]) { "Missing license text: $licenseId" }

    companion object {
        const val ASSET_PATH = "licenses/manifest.json"

        fun load(context: Context): LicenseManifest = context.assets.open(ASSET_PATH).bufferedReader().use { reader ->
            parse(reader.readText())
        }

        fun parse(raw: String): LicenseManifest {
            val root = JSONObject(raw)
            val licenseTexts = root.getJSONObject("licenseTexts").let { json ->
                buildMap {
                    val keys = json.keys()
                    while (keys.hasNext()) {
                        val id = keys.next()
                        val value = json.getJSONObject(id)
                        put(
                            id,
                            LicenseText(
                                id = id,
                                name = value.getString("name"),
                                source = value.optString("source").takeIf(String::isNotBlank),
                                text = value.getString("text"),
                            ),
                        )
                    }
                }
            }

            val entries = root.getJSONArray("entries").let { json ->
                buildList {
                    for (index in 0 until json.length()) {
                        val value = json.getJSONObject(index)
                        add(
                            LicenseEntry(
                                id = value.getString("id"),
                                name = value.getString("name"),
                                scope = value.getString("scope"),
                                showInAbout = value.optBoolean("showInAbout", false),
                                licenseIds = value.stringList("licenseIds"),
                                description = value.getString("description"),
                                notices = value.stringList("notices"),
                                source = value.optString("source").takeIf(String::isNotBlank),
                                sourceUrl = value.optString("sourceUrl").takeIf(String::isNotBlank),
                                artifactPatterns = value.stringList("artifactPatterns"),
                                packages = value.stringList("packages"),
                                projectPaths = value.stringList("projectPaths"),
                                sourcePaths = value.stringList("sourcePaths"),
                                assetPaths = value.stringList("assetPaths"),
                            ),
                        )
                    }
                }
            }

            require(entries.map(LicenseEntry::id).distinct().size == entries.size) {
                "Duplicate license entry id"
            }
            entries.forEach { entry ->
                require(entry.licenseIds.isNotEmpty()) { "License entry has no license ids: ${entry.id}" }
                entry.licenseIds.forEach { id -> require(id in licenseTexts) { "Unknown license id: $id" } }
            }
            return LicenseManifest(entries = entries, licenseTexts = licenseTexts)
        }
    }
}

internal data class LicenseText(
    val id: String,
    val name: String,
    val source: String?,
    val text: String,
)

internal data class LicenseEntry(
    val id: String,
    val name: String,
    val scope: String,
    val showInAbout: Boolean,
    val licenseIds: List<String>,
    val description: String,
    val notices: List<String>,
    val source: String?,
    val sourceUrl: String?,
    val artifactPatterns: List<String>,
    val packages: List<String>,
    val projectPaths: List<String>,
    val sourcePaths: List<String>,
    val assetPaths: List<String>,
)

private fun JSONObject.stringList(key: String): List<String> = optJSONArray(key)?.let { array ->
    buildList {
        for (index in 0 until array.length()) add(array.getString(index))
    }
} ?: emptyList()
