package app.seanime.tv.ui

import org.json.JSONArray
import org.json.JSONObject
import app.seanime.tv.data.ExtensionItem
import app.seanime.tv.data.parseNativeMediaId

internal data class SettingChoice(val value: String, val label: String)

internal fun settingProviderType(section: String, field: String): String? = when ("$section.$field") {
    "library.torrentProvider", "autoDownloader.provider" -> "anime-torrent-provider"
    "manga.defaultMangaProvider" -> "manga-provider"
    else -> null
}

internal fun settingProviderChoices(current: String, extensions: List<ExtensionItem>): List<SettingChoice> {
    val choices = listOf(SettingChoice("", "Use server default")) + extensions.filterNot { it.disabled }
        .distinctBy { it.id }.map { SettingChoice(it.id, it.name) }
    return if (choices.none { it.value == current }) choices + SettingChoice(current, "Current provider: $current") else choices
}

internal fun settingPlaybackSourceChoices(current: String, tabs: List<JSONObject>): List<SettingChoice> {
    val choices = settingChoices("library", "defaultPlaybackSource", "") + tabs
        .filter { it.text("id").isNotBlank() }.distinctBy { it.text("id") }.map { tab ->
            SettingChoice("ext:${tab.text("id")}", tab.text("tabName").takeIf(String::isNotBlank)
                ?.let { "$it (${tab.text("name")})" } ?: tab.text("name", "Plugin source"))
        }.sortedBy { it.label }
    return if (choices.none { it.value == current }) choices + SettingChoice(current, "Current source: $current") else choices
}

/** Values match existing server settings, without desktop-only defaults on Android. */
internal fun settingChoices(section: String, field: String, current: String): List<SettingChoice> {
    fun choices(vararg values: Pair<String, String>) = values.map { SettingChoice(it.first, it.second) }
    val options = when ("$section.$field") {
        "library.updateChannel" -> choices("androidtv" to "Seanime TV releases")
        "library.scannerMatchingAlgorithm" -> choices("" to "Automatic matching", "sorensen-dice" to "Sorensen–Dice", "jaccard" to "Jaccard")
        "library.defaultPlaybackSource" -> choices("" to "Automatic", "library" to "Local library", "onlinestream" to "Online streaming", "torrentstream" to "Torrent streaming", "debridstream" to "Debrid streaming")
        "torrent.defaultTorrentClient" -> choices("qbittorrent" to "qBittorrent", "transmission" to "Transmission", "seanime" to "Built-in Seanime")
        "mediaPlayer.defaultPlayer" -> choices("vlc" to "VLC", "mpc-hc" to "MPC-HC", "mpv" to "mpv", "iina" to "IINA")
        "torrentstream.preferredResolution", "debrid.streamPreferredResolution" -> choices("" to "Default (1080p)", "480" to "480p", "720" to "720p", "1080" to "1080p", "2160" to "2160p")
        "debrid.provider" -> choices("" to "None", "torbox" to "TorBox", "realdebrid" to "Real-Debrid", "alldebrid" to "AllDebrid", "premiumize" to "Premiumize")
        "mediastream.transcodeHwAccel" -> choices("auto" to "Auto · device hardware, CPU fallback", "cpu" to "CPU", "mediacodec" to "Android MediaCodec", "custom" to "Custom encoder settings")
        "mediastream.transcodePreset" -> choices("ultrafast" to "Ultrafast", "superfast" to "Superfast", "veryfast" to "Veryfast", "fast" to "Fast", "medium" to "Medium")
        else -> emptyList()
    }
    // Preserve a server/plugin value unknown to this APK instead of coercing it.
    return if (options.isNotEmpty() && options.none { it.value == current }) options + SettingChoice(current, "Current: ${current.ifBlank { "Not set" }}") else options
}

internal fun settingListValue(field: String, entries: List<String>): JSONArray {
    require(entries.size <= 1000) { "Keep this list under 1,000 entries" }
    val values: List<Any> = when (field) {
        "hostUnsharedAnimeIds" -> entries.map { value ->
            parseNativeMediaId(value.trim()) ?: error("Enter a valid positive media ID")
        }
        "libraryPaths" -> entries.map { value ->
            value.trim().also {
                require(it.isNotBlank() && !it.contains('\u0000')) { "Enter a valid folder path" }
                require(!it.contains(',')) { "The existing library-path setting cannot store a comma in a folder name" }
            }
        }
        else -> error("This setting does not have a native list editor")
    }
    return JSONArray(values.distinct())
}

internal fun validateSettingNumber(field: String, value: String, integral: Boolean): Number {
    val number: Number = if (integral) value.toLongOrNull() ?: error("Enter a whole number")
        else value.toDoubleOrNull()?.takeIf { it.isFinite() } ?: error("Enter a valid number")
    val double = number.toDouble()
    when {
        field.endsWith("Port", true) -> require(double in 0.0..65535.0) { "Use a port from 0 to 65535" }
        field == "scannerMatchingThreshold" -> require(double in 0.0..1.0) { "Use a matching threshold from 0 to 1" }
        else -> require(double >= 0) { "Enter a nonnegative number" }
    }
    return number
}
