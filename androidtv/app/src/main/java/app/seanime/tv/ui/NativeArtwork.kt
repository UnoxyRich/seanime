package app.seanime.tv.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import app.seanime.tv.data.NativeArtworkUrl
import app.seanime.tv.data.MediaCard
import app.seanime.tv.data.MediaArtworkOrigin
import app.seanime.tv.data.providerArtwork
import app.seanime.tv.data.ProviderUrlPolicy
import app.seanime.tv.data.NativeImageTransport
import app.seanime.tv.data.NativeNetworkFailure
import app.seanime.tv.data.SeanimeApiClient
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.memory.MemoryCache
import coil.request.ImageRequest
import java.security.MessageDigest

private class ArtworkOwner(val api: SeanimeApiClient, val loader: ImageLoader)
private val LocalArtwork = staticCompositionLocalOf<ArtworkOwner> { error("NativeArtwork requires NativeArtworkProvider") }
private enum class ArtworkPhase(val label: String) {
    LOADING("Loading image"), ABSENT("No artwork"), FAILED("Image unavailable"), LOADED("")
}

/** One bounded artwork loader is shared by the native shell, including dialogs and lazy grids. */
@Composable
internal fun NativeArtworkProvider(api: SeanimeApiClient, content: @Composable () -> Unit) =
    NativeArtworkProvider(api, { NativeImageTransport(it) }, content)

@Composable
internal fun NativeArtworkProvider(api: SeanimeApiClient, transportFactory: (SeanimeApiClient) -> NativeImageTransport, content: @Composable () -> Unit) {
    val context = LocalContext.current.applicationContext
    val transport = remember(api) { transportFactory(api) }
    val owner = remember(context, api, transport) {
        ArtworkOwner(api, ImageLoader.Builder(context).callFactory(transport)
            .memoryCache { MemoryCache.Builder(context).maxSizePercent(0.06).weakReferencesEnabled(false).build() }
            .diskCache(null).bitmapFactoryMaxParallelism(2).build())
    }
    DisposableEffect(owner, transport) {
        onDispose {
            // Retire first; transport-owned calls also dispatch Coil's synchronous cancellation to IO.
            transport.close()
            owner.loader.shutdown()
        }
    }
    CompositionLocalProvider(LocalArtwork provides owner, content = content)
}

@Composable
internal fun NativeArtwork(
    media: MediaCard,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    url: String? = media.imageUrl,
) = NativeArtwork(url, contentDescription, modifier, contentScale, providerResult = media.providerArtwork(url),
    offlineAssetMediaId = media.id.takeIf { media.artworkOrigin == MediaArtworkOrigin.LOCAL_ASSET && !media.providerArtwork(url) })

@Composable
internal fun NativeArtwork(
    url: String?,
    contentDescription: String?,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Fit,
    headers: Map<String, String> = emptyMap(),
    providerResult: Boolean = false,
    offlineAssetMediaId: Long? = null,
) {
    val owner = LocalArtwork.current
    val context = LocalContext.current
    val resolved = remember(owner.api.baseUrl, url, providerResult) {
        if (providerResult) url?.let { runCatching { ProviderUrlPolicy.requirePublicUrl(it).toString() }.getOrNull() }
        else NativeArtworkUrl.resolve(owner.api.baseUrl, url)
    }
    // A provider can serve different covers at one URL depending on its headers. Include that
    // authority in memory identity; secrets stay in a hash, never a readable cache key or URL.
    val cacheHeaders = if (offlineAssetMediaId != null) emptyMap() else if (!providerResult && resolved != null && owner.api.isServerUrl(resolved)) headers + owner.api.requestHeaders() else headers
    val request = remember(context, resolved, headers, cacheHeaders, providerResult, offlineAssetMediaId) {
        ImageRequest.Builder(context).data(resolved).crossfade(false)
            .tag(NativeImageTransport.SourceHeaders::class.java, NativeImageTransport.SourceHeaders(headers, providerResult, offlineAssetMediaId))
            .apply { if (resolved != null) memoryCacheKey(artworkCacheKey(resolved, cacheHeaders, providerResult, offlineAssetMediaId)) }.build()
    }
    var phase by remember(request) { mutableStateOf(if (resolved == null) { if (providerResult && !url.isNullOrBlank()) ArtworkPhase.FAILED else ArtworkPhase.ABSENT } else ArtworkPhase.LOADING) }
    Box(modifier = modifier.clipToBounds().semantics(mergeDescendants = true) {
        this.contentDescription = contentDescription ?: "Artwork"
        role = Role.Image
        if (phase != ArtworkPhase.LOADED) stateDescription = phase.label
    }, propagateMinConstraints = true) {
        if (resolved != null) AsyncImage(model = request, imageLoader = owner.loader, contentDescription = null,
            modifier = Modifier.fillMaxSize(), contentScale = contentScale,
            onLoading = { phase = ArtworkPhase.LOADING }, onError = { result ->
                phase = ArtworkPhase.FAILED
                NativeNetworkFailure.logDebug(context, NativeNetworkFailure.Surface.ARTWORK, result.result.throwable)
            },
            onSuccess = { phase = ArtworkPhase.LOADED })
        if (phase != ArtworkPhase.LOADED) ArtworkPlaceholder(phase.label)
    }
}

/** Static, nonfocusable artwork state. Grids do not run dozens of progress animations. */
@Composable
private fun BoxScope.ArtworkPlaceholder(label: String) {
    val foreground = MaterialTheme.colorScheme.onSurfaceVariant
    BoxWithConstraints(Modifier.matchParentSize().background(MaterialTheme.colorScheme.surfaceVariant).padding(8.dp),
        contentAlignment = Alignment.Center) {
        val iconSize = minOf(30.dp, maxWidth, maxHeight)
        val showLabel = maxWidth >= 72.dp && maxHeight >= 70.dp
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Canvas(Modifier.size(iconSize).padding(1.dp)) {
            val line = 1.5.dp.toPx()
            drawRoundRect(foreground.copy(alpha = 0.65f), style = Stroke(line))
            drawCircle(foreground.copy(alpha = 0.65f), size.width * 0.08f, Offset(size.width * 0.7f, size.height * 0.28f))
            drawPath(Path().apply {
                moveTo(size.width * 0.14f, size.height * 0.8f)
                lineTo(size.width * 0.4f, size.height * 0.43f)
                lineTo(size.width * 0.58f, size.height * 0.65f)
                lineTo(size.width * 0.7f, size.height * 0.52f)
                lineTo(size.width * 0.86f, size.height * 0.8f)
            }, foreground.copy(alpha = 0.65f), style = Stroke(line))
        }
        if (showLabel) {
            Spacer(Modifier.height(8.dp))
            Text(label, color = foreground, fontSize = 11.sp, lineHeight = 14.sp, textAlign = TextAlign.Center, maxLines = 2)
        }
        }
    }
}

/** Header-sensitive memory identity prevents a recycled provider card from displaying stale pixels. */
private fun artworkCacheKey(url: String, headers: Map<String, String>, providerResult: Boolean, offlineAssetMediaId: Long?): String {
    val identity = buildString {
        append(if (offlineAssetMediaId != null) "local-asset:$offlineAssetMediaId:" else if (providerResult) "provider:" else "trusted:")
        append(url.length).append(':').append(url)
        headers.entries.sortedBy { it.key.lowercase() }.forEach { (name, value) ->
            val normalized = name.lowercase()
            append(normalized.length).append(':').append(normalized).append(value.length).append(':').append(value)
        }
    }
    return "native-artwork:" + MessageDigest.getInstance("SHA-256").digest(identity.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }
}
