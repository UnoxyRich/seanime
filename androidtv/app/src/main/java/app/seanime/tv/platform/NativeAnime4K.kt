package app.seanime.tv.platform

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import androidx.media3.common.Effect
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.util.GlProgram
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.BaseGlShaderProgram
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram

/** Real Anime4K inference, using the upstream CNN weights bundled with Seanime. No web runtime. */
@UnstableApi
object NativeAnime4K {
    data class Preset(val id: String, val label: String, val supported: Boolean = true)
    val presets = listOf(
        Preset("off", "Off"),
        Preset("mode-a", "Mode A · restore + upscale"),
        Preset("mode-b", "Mode B · soft restore + upscale"),
        Preset("mode-c", "Mode C · denoise + upscale"),
        Preset("mode-aa", "Mode A+A · stronger restore"),
        Preset("mode-bb", "Mode B+B · stronger soft restore"),
        Preset("mode-ca", "Mode C+A · denoise + restore"),
        Preset("cnn-2x-medium", "CNN 2× · Medium"),
        Preset("cnn-2x-very-large", "CNN 2× · Very large"),
        Preset("denoise-cnn-2x-very-large", "Denoise CNN 2× · Very large"),
        Preset("cnn-2x-ultra-large", "CNN 2× · Ultra large"),
        Preset("gan-3x-large", "GAN 3× · Large"),
        Preset("gan-4x-ultra-large", "GAN 4× · Ultra-ultra large"),
    )

    /** onFailure runs on Media3's GL thread; post UI/player changes to the application looper. */
    fun effects(presetId: String, targetWidth: Int, targetHeight: Int, onFailure: (String) -> Unit): List<Effect> {
        if (presetId == "off") return emptyList()
        if (presets.none { it.id == presetId && it.supported }) {
            onFailure("This Anime4K preset has no native shader implementation")
            return emptyList()
        }
        if (targetWidth <= 0 || targetHeight <= 0) return emptyList()
        return listOf(Anime4KEffect(presetId, targetWidth, targetHeight, onFailure))
    }
}

@UnstableApi
private class Anime4KEffect(private val preset: String, private val width: Int, private val height: Int,
    private val failure: (String) -> Unit) : GlEffect {
    override fun toGlShaderProgram(context: Context, useHdr: Boolean): GlShaderProgram =
        NativeAnime4KShaderProgram(context, preset, width, height, useHdr, failure)
}

/** Owns its private multipass graph. Media3 continues to own the decoder and final output texture. */
@UnstableApi
internal class NativeAnime4KShaderProgram(
    private val context: Context,
    private val preset: String,
    private val targetWidth: Int,
    private val targetHeight: Int,
    private val hdr: Boolean,
    private val failure: (String) -> Unit,
    private val memoryBudgetBytes: Long = 256L * 1024 * 1024,
) : BaseGlShaderProgram(hdr, 1) {
    private data class Texture(val id: Int, val fbo: Int)
    private val copy = program(NativeAnime4KPlan.COPY, listOf("INPUT"))
    private var decode: GlProgram? = null
    private var plan: NativeAnime4KPlan.Plan? = null
    private val programs = mutableListOf<GlProgram>()
    private val textures = mutableListOf<Texture>()
    private var inputSize = NativeAnime4KPlan.Dimensions(1, 1)
    private var reportedFailure = false

    override fun configure(inputWidth: Int, inputHeight: Int): Size {
        releaseGraph()
        inputSize = NativeAnime4KPlan.Dimensions(inputWidth, inputHeight)
        if (hdr) {
            report("Anime4K is disabled for HDR; the bundled networks are trained for SDR")
            return Size(inputWidth, inputHeight)
        }
        try {
            val target = NativeAnime4KPlan.fit(inputSize, NativeAnime4KPlan.Dimensions(targetWidth, targetHeight))
            val candidate = NativeAnime4KPlan.build(preset, inputSize, target) { filename ->
                context.assets.open("anime4k/$filename").bufferedReader().use { it.readText() }
            }
            val maxTexture = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
            require(candidate.slots.all { it.width <= minOf(maxTexture, 4096) && it.height <= minOf(maxTexture, 4096) }) {
                "The selected Anime4K preset exceeds this GPU's texture size limit"
            }
            require(candidate.bytes <= memoryBudgetBytes) { "The selected Anime4K preset exceeds the safe GPU memory budget" }
            val samplers = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_IMAGE_UNITS, it, 0) }[0]
            require(candidate.passes.all { it.bindings.size <= samplers }) { "This GPU has too few texture units for the selected Anime4K network" }
            candidate.passes.forEach { programs += program(it.source, it.bindings.keys) }
            candidate.slots.forEach { textures += texture(it.width, it.height) }
            decode = program(NativeAnime4KPlan.DECODE, listOf("INPUT"))
            plan = candidate
            return Size(candidate.size.width, candidate.size.height)
        } catch (error: Exception) {
            releaseGraph()
            clearErrors()
            report("Anime4K disabled: ${error.message ?: error.javaClass.simpleName}")
            return Size(inputWidth, inputHeight)
        }
    }

    override fun drawFrame(inputTexId: Int, presentationTimeUs: Long) {
        val graph = plan
        if (graph == null) {
            draw(copy, mapOf("INPUT" to Pair(inputTexId, inputSize)))
            return
        }
        val framebuffer = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING, it, 0) }[0]
        val viewport = IntArray(4).also { GLES20.glGetIntegerv(GLES20.GL_VIEWPORT, it, 0) }
        try {
            graph.passes.forEachIndexed { index, pass ->
                val target = textures[graph.passSlots[index]]
                GlUtil.focusFramebufferUsingCurrentContext(target.fbo, pass.size.width, pass.size.height)
                val bindings = pass.bindings.mapValues { (_, source) ->
                    if (source < 0) Pair(inputTexId, inputSize)
                    else Pair(textures[graph.passSlots[source]].id, graph.passes[source].size)
                }
                draw(programs[index], bindings)
            }
            restoreFramebuffer(framebuffer, viewport)
            draw(requireNotNull(decode), mapOf("INPUT" to Pair(textures[graph.passSlots[graph.output]].id, graph.size)))
        } catch (error: Exception) {
            releaseGraph()
            clearErrors()
            restoreFramebuffer(framebuffer, viewport)
            report("Anime4K disabled after GPU error: ${error.message ?: error.javaClass.simpleName}")
            draw(copy, mapOf("INPUT" to Pair(inputTexId, inputSize)))
        }
    }

    override fun release() {
        releaseGraph()
        try { copy.delete() } finally { super.release() }
    }

    private fun report(message: String) {
        if (!reportedFailure) { reportedFailure = true; runCatching { failure(message.replace(Regex("\\s+"), " ").take(500)) } }
    }

    private fun releaseGraph() {
        plan = null
        programs.forEach { runCatching { it.delete() } }; programs.clear()
        textures.forEach { runCatching { GlUtil.deleteFbo(it.fbo) }; runCatching { GlUtil.deleteTexture(it.id) } }; textures.clear()
        decode?.let { runCatching { it.delete() } }; decode = null
    }

    private fun texture(width: Int, height: Int): Texture {
        val id = GlUtil.generateTexture()
        var fbo = 0
        try {
            GlUtil.bindTexture(GLES20.GL_TEXTURE_2D, id, GLES20.GL_LINEAR)
            // SDR Media3 may create ES2. Half-float storage must preserve negative CNN activations;
            // RGBA8 cannot be used as a fallback without silently changing the network.
            if (GlUtil.getContextMajorVersion() >= 3) {
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES30.GL_RGBA16F, width, height, 0,
                    GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, null)
            } else {
                val extensions = GLES20.glGetString(GLES20.GL_EXTENSIONS).orEmpty()
                require("GL_OES_texture_half_float" in extensions && "GL_OES_texture_half_float_linear" in extensions) {
                    "This GPU does not support filtered signed half-float Anime4K textures"
                }
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, width, height, 0,
                    GLES20.GL_RGBA, 0x8D61 /* GL_HALF_FLOAT_OES */, null)
            }
            GlUtil.checkGlError()
            fbo = GlUtil.createFboForTexture(id)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
            require(GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER) == GLES20.GL_FRAMEBUFFER_COMPLETE) {
                "This GPU cannot render the signed floating-point Anime4K network"
            }
            return Texture(id, fbo)
        } catch (error: Exception) {
            if (fbo != 0) runCatching { GlUtil.deleteFbo(fbo) }
            runCatching { GlUtil.deleteTexture(id) }
            throw error
        }
    }

    private fun draw(program: GlProgram, bindings: Map<String, Pair<Int, NativeAnime4KPlan.Dimensions>>) {
        GLES20.glDisable(GLES20.GL_BLEND)
        program.use()
        bindings.entries.forEachIndexed { unit, (name, texture) ->
            if (program.getUniformLocation("u_$name") >= 0) program.setSamplerTexIdUniform("u_$name", texture.first, unit)
            program.setFloatsUniformIfPresent("${name}_size", floatArrayOf(texture.second.width.toFloat(), texture.second.height.toFloat()))
        }
        program.bindAttributesAndUniforms()
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GlUtil.checkGlError()
    }

    private fun restoreFramebuffer(framebuffer: Int, viewport: IntArray) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, framebuffer)
        GLES20.glViewport(viewport[0], viewport[1], viewport[2], viewport[3])
    }

    private fun clearErrors() { repeat(16) { if (GLES20.glGetError() == GLES20.GL_NO_ERROR) return } }

    companion object {
        private const val VERTEX = """
            attribute vec4 aPosition;
            varying highp vec2 vTexCoord;
            void main() { gl_Position = aPosition; vTexCoord = aPosition.xy * 0.5 + 0.5; }
        """
        internal fun fragment(source: String, bindings: Collection<String>): String = buildString {
            append("precision highp float;\nprecision highp int;\nvarying highp vec2 vTexCoord;\n")
            bindings.forEach { name ->
                append("uniform highp sampler2D u_$name;\nuniform vec2 ${name}_size;\n")
                append("#define ${name}_pos vTexCoord\n#define ${name}_pt (1.0 / ${name}_size)\n")
                append("#define ${name}_tex(p) texture2D(u_$name, (p))\n")
                append("#define ${name}_texOff(p) texture2D(u_$name, vTexCoord + (p) / ${name}_size)\n")
            }
            append(source)
            append("\nvoid main() { gl_FragColor = hook(); }\n")
        }
        private fun program(source: String, bindings: Collection<String>): GlProgram =
            GlProgram(VERTEX, fragment(source, bindings)).apply {
                setBufferAttribute("aPosition", GlUtil.getNormalizedCoordinateBounds(), 4)
            }
    }
}
