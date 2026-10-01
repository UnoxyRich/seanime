package app.seanime.tv.data

import java.io.File
import java.net.URI
import java.security.MessageDigest

/** Identity and containment for a chapter issued by the built-in local-manga provider. */
class LocalMangaPdfSource private constructor(
    val path: String,
    val usesSaf: Boolean,
    val cacheIdentity: String,
) {
    override fun toString(): String = "LocalMangaPdfSource($cacheIdentity)"

    companion object {
        fun resolve(serverOrigin: String, sourceRoot: String, mediaId: Long, chapter: MangaChapter): LocalMangaPdfSource {
            val origin = runCatching { URI(serverOrigin) }.getOrNull()
            require(origin != null && origin.scheme in setOf("http", "https") && origin.port in 1..65535 &&
                origin.rawAuthority in setOf("127.0.0.1:${origin.port}", "[::1]:${origin.port}") &&
                origin.rawUserInfo == null && origin.rawQuery == null && origin.rawFragment == null && origin.rawPath in setOf("", "/")) {
                "Local PDF reading requires this TV's embedded loopback server"
            }
            require(chapter.provider == "local-manga" && chapter.raw.optBoolean("localIsPDF") && chapter.id.endsWith(".pdf", ignoreCase = true)) {
                "This chapter is not a local PDF"
            }
            require(sourceRoot.startsWith('/') && !sourceRoot.contains('\u0000') && !sourceRoot.contains('\\')) { "Choose a local manga source folder in Settings" }
            val parts = chapter.id.split('/')
            require(parts.isNotEmpty() && parts.none { it.isBlank() || it == "." || it == ".." || it.contains('\\') || it.contains('\u0000') || it.contains(':') }) {
                "The PDF chapter has an invalid relative path"
            }
            val root = File(sourceRoot).canonicalFile
            val file = File(root, chapter.id).canonicalFile
            require(file.path.startsWith(root.path.trimEnd('/') + File.separator)) { "The PDF chapter is outside the selected manga folder" }
            val usesSaf = sourceRoot.startsWith("/androidtv/")
            if (usesSaf) {
                val rootParts = sourceRoot.removePrefix("/androidtv/").trimEnd('/').split('/')
                require(rootParts.firstOrNull()?.matches(Regex("[a-f0-9]{16}")) == true && rootParts.none { it.isBlank() || it == "." || it == ".." }) {
                    "The saved manga storage folder is invalid"
                }
            }
            val identity = listOf(origin.scheme.lowercase(), origin.host.lowercase(), origin.port.toString(), root.path, mediaId.toString(), chapter.provider, chapter.id)
                .joinToString("\u0000")
            val digest = MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
            return LocalMangaPdfSource(file.path, usesSaf, digest)
        }
    }
}
