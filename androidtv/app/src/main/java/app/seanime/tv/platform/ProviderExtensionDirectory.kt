package app.seanime.tv.platform

import java.io.File
import org.json.JSONObject

/**
 * Reads the ordinary TOML forms used by Go/Viper without rewriting config.toml.
 * Unsupported/ambiguous TOML fails closed: startup reports it and seeds nothing.
 * This intentionally is not a second general-purpose TOML configuration parser.
 */
internal object ProviderExtensionDirectory {
    fun resolve(dataDir: File, environment: (String) -> String?): File {
        val config = File(dataDir, "config.toml")
        var path: String? = null
        var table = emptyList<String>()
        if (config.exists()) {
            config.readLines().forEachIndexed { index, source ->
                val line = stripComment(source).trim()
                if (line.isEmpty()) return@forEachIndexed
                check(!line.contains("\"\"\"") && !line.contains("'''")) {
                    "Cannot safely resolve extensions.dir from multiline TOML (line ${index + 1})"
                }
                if (line.startsWith("[")) {
                    check(line.endsWith("]") && !line.startsWith("[[")) { "Unsupported TOML table while resolving extensions.dir" }
                    table = keys(line.substring(1, line.length - 1))
                    check(table.take(2) != listOf("extensions", "dir")) { "extensions.dir must be a path string" }
                } else {
                    val separator = outsideQuotes(line, '=')
                    check(separator > 0) { "Cannot safely resolve extensions.dir from TOML line ${index + 1}" }
                    val key = table + keys(line.substring(0, separator))
                    if (key == listOf("extensions", "dir")) {
                        check(path == null) { "Ambiguous duplicate extensions.dir configuration" }
                        path = stringValue(line.substring(separator + 1).trim())
                    } else {
                        check(key != listOf("extensions") && key.take(2) != listOf("extensions", "dir")) {
                            "Unsupported extensions.dir configuration; no bundled providers were written"
                        }
                    }
                }
            }
        }
        val configured = path ?: "\$SEANIME_DATA_DIR/extensions"
        val expanded = Regex("\\$(?:\\{([A-Za-z_][A-Za-z0-9_]*)\\}|([A-Za-z_][A-Za-z0-9_]*))").replace(configured) { match ->
            val name = match.groupValues[1].ifEmpty { match.groupValues[2] }
            when (name) {
                "SEANIME_DATA_DIR", "SEANIME_WORKING_DIR" -> dataDir.absolutePath
                else -> environment(name) ?: error("Cannot resolve extensions.dir environment variable $name")
            }
        }
        check('$' !in expanded && '\u0000' !in expanded && File(expanded).isAbsolute) {
            "extensions.dir must resolve to an absolute filesystem path"
        }
        return File(expanded).canonicalFile
    }

    private fun keys(source: String): List<String> {
        val result = mutableListOf<String>()
        var remainder = source.trim()
        while (remainder.isNotEmpty()) {
            val separator = outsideQuotes(remainder, '.')
            val token = (if (separator < 0) remainder else remainder.substring(0, separator)).trim()
            val key = if (token.startsWith('"') || token.startsWith('\'')) stringValue(token) else {
                check(token.matches(Regex("[A-Za-z0-9_-]+"))) { "Unsupported TOML key while resolving extensions.dir" }
                token
            }
            check(key.isNotEmpty() && '.' !in key) { "Ambiguous TOML key while resolving extensions.dir" }
            result += key.lowercase(java.util.Locale.ROOT)
            if (separator < 0) break
            remainder = remainder.substring(separator + 1).trim()
            check(remainder.isNotEmpty()) { "Invalid dotted TOML key" }
        }
        check(result.isNotEmpty()) { "Empty TOML key" }
        return result
    }

    private fun stringValue(source: String): String {
        check(source.length >= 2) { "extensions.dir must be a quoted TOML string" }
        if (source.first() == '\'' && source.last() == '\'') {
            check('\'' !in source.substring(1, source.length - 1)) { "Unsupported TOML literal string" }
            return source.substring(1, source.length - 1)
        }
        check(source.first() == '"' && source.last() == '"' && !source.contains("\\U")) {
            "Unsupported TOML string while resolving extensions.dir"
        }
        // The supported basic-string escapes overlap JSON; reject trailing tokens.
        check(outsideQuotes(source, '=') < 0) { "Invalid TOML path string" }
        return JSONObject("{\"path\":$source}").getString("path")
    }

    private fun stripComment(source: String): String {
        val comment = outsideQuotes(source, '#')
        return if (comment < 0) source else source.substring(0, comment)
    }

    private fun outsideQuotes(source: String, delimiter: Char): Int {
        var quote: Char? = null
        var escaped = false
        source.forEachIndexed { index, char ->
            if (escaped) { escaped = false; return@forEachIndexed }
            if (quote == '"' && char == '\\') { escaped = true; return@forEachIndexed }
            if (quote != null) { if (char == quote) quote = null }
            else if (char == delimiter) return index
            else if (char == '\'' || char == '"') quote = char
        }
        check(quote == null && !escaped) { "Unsupported multiline or unterminated TOML string" }
        return -1
    }
}
