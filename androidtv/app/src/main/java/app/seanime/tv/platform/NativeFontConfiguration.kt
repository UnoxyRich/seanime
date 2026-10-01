package app.seanime.tv.platform

import android.content.Context
import android.system.Os
import java.io.File

/** Supplies fontconfig with Android font directories and an app-writable cache. */
object NativeFontConfiguration {
    @Synchronized
    fun prepare(context: Context) {
        val root = File(context.cacheDir, "native-fontconfig")
        val cache = File(root, "cache")
        check(cache.isDirectory || cache.mkdirs()) { "Could not create the native font cache" }
        val config = File(root, "fonts.conf")
        val directories = listOf("/system/fonts", "/product/fonts", "/vendor/fonts")
            .map(::File).filter { it.isDirectory }
        val xml = buildString {
            append("<?xml version=\"1.0\"?>\n<fontconfig>\n")
            directories.forEach { append("<dir>${escape(it.absolutePath)}</dir>\n") }
            append("<cachedir>${escape(cache.absolutePath)}</cachedir>\n")
            append("<alias><family>sans-serif</family><prefer><family>Roboto</family><family>Noto Sans</family></prefer></alias>\n")
            append("<alias><family>Arial</family><prefer><family>Roboto</family><family>Noto Sans</family></prefer></alias>\n")
            append("<alias><family>serif</family><prefer><family>Noto Serif</family></prefer></alias>\n")
            append("<alias><family>monospace</family><prefer><family>Droid Sans Mono</family><family>Noto Sans Mono</family></prefer></alias>\n")
            append("</fontconfig>\n")
        }
        if (!config.isFile || config.readText() != xml) config.writeText(xml)
        // Must happen before libass's first font-provider initialization. The upstream
        // AAR otherwise falls back to unwritable build-machine and root cache paths.
        Os.setenv("FONTCONFIG_FILE", config.absolutePath, true)
        Os.setenv("FONTCONFIG_PATH", root.absolutePath, true)
    }

    private fun escape(value: String) = value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
