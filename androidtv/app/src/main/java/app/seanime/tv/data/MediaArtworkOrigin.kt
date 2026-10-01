package app.seanime.tv.data

/** Matches Go customsource.IsExtensionId for the supported positive, exact Long wire range. */
fun isCustomSourceMediaId(id: Long): Boolean = id >= (1L shl 31)

/** Authority comes from the route, never from provider-controlled JSON flags or URL markers. */
enum class MediaArtworkOrigin {
    SERVER, PROVIDER, LOCAL_ASSET;

    fun providerResult(url: String?, mediaId: Long): Boolean = when (this) {
        SERVER -> false
        PROVIDER -> true
        // Explicit local-library contexts can request one image in this title's static asset
        // directory. This never grants API credentials, redirects, or other local URLs.
        LOCAL_ASSET -> !isSnapshotAsset(url, mediaId)
    }

    companion object {
        fun forMedia(id: Long): MediaArtworkOrigin = if (isCustomSourceMediaId(id)) PROVIDER else SERVER
        internal fun isSnapshotAsset(url: String?, mediaId: Long): Boolean {
            val path = url?.removePrefix("{{LOCAL_ASSETS}}/$mediaId/") ?: return false
            return mediaId in 1L..MAX_NATIVE_MEDIA_ID && path != url && isAssetFilename(path)
        }
        internal fun isAssetFilename(value: String): Boolean = value.length <= 255 &&
            Regex("[A-Za-z0-9][A-Za-z0-9._-]*\\.(png|jpg|jpeg|gif|webp|avif|bmp|ico)", RegexOption.IGNORE_CASE).matches(value)
    }
}

fun MediaCard.providerArtwork(url: String? = imageUrl): Boolean = artworkOrigin.providerResult(url, id)

/** Only Offline/downloaded views or verified active offline media may grant this static-file capability. */
internal fun MediaCard.withLocalArtwork(): MediaCard =
    if (artworkOrigin == MediaArtworkOrigin.PROVIDER) copy(artworkOrigin = MediaArtworkOrigin.LOCAL_ASSET) else this
