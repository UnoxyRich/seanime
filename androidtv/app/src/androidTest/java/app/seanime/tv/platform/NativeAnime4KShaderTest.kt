package app.seanime.tv.platform

import android.opengl.GLES20
import androidx.media3.common.util.GlUtil
import androidx.media3.common.util.Size
import androidx.media3.common.util.UnstableApi
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.nio.ByteBuffer
import kotlin.math.abs

/** Executes the production GLES graph, including the original trained weights, on the device GPU. */
@androidx.annotation.OptIn(markerClass = [UnstableApi::class])
@RunWith(AndroidJUnit4::class)
class NativeAnime4KShaderTest {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private fun fixture(name: String) = instrumentation.context.assets.open("anime4k/$name").use { it.readBytes() }

    @Test fun originalMediumCnnMatchesIndependentCpuReferenceAndReconfigures() = withEgl {
        val failures = mutableListOf<String>()
        val shader = NativeAnime4KShaderProgram(context, "cnn-2x-medium", 16, 16, false, failures::add)
        try {
            val input = fixture("cnn-medium-input-8x8.rgba")
            val expected = fixture("cnn-medium-reference-16x16.rgba")
            val first = render(shader, input, 8, 8)
            assertEquals("GPU fallback: $failures", emptyList<String>(), failures)
            assertEquals(Size(16, 16), first.first)
            // Differences of a few code values accommodate half-float rounding and FMA differences.
            val errors = first.second.indices.map { abs((first.second[it].toInt() and 255) - (expected[it].toInt() and 255)) }
            assertTrue("CNN differs from independent reference: max=${errors.maxOrNull()}, mean=${errors.average()}",
                errors.maxOrNull()!! <= 5 && errors.average() < 1.0)
            val small = render(shader, input.copyOf(4 * 4 * 4), 4, 4)
            assertEquals(Size(8, 8), small.first)
            val again = render(shader, input, 8, 8)
            assertArrayEquals("Resize/reconfigure must not retain stale activation textures", first.second, again.second)
            assertTrue(failures.isEmpty())
        } finally { shader.release() }
    }

    @Test fun everySupportedPresetCompilesAndRendersRealPixels() = withEgl {
        val input = fixture("cnn-medium-input-8x8.rgba")
        NativeAnime4K.presets.filter { it.supported && it.id != "off" }.forEach { preset ->
            val failures = mutableListOf<String>()
            val shader = NativeAnime4KShaderProgram(context, preset.id, 16, 16, false, failures::add)
            try {
                val (size, output) = render(shader, input, 8, 8)
                assertTrue("${preset.id} silently fell back: $failures", failures.isEmpty())
                val factor = when (preset.id) { "gan-3x-large" -> 3; "gan-4x-ultra-large" -> 4; else -> 2 }
                assertEquals(preset.id, Size(8 * factor, 8 * factor), size)
                if (factor > 2) {
                    val expected = fixture("gan-${factor}x-reference-${8 * factor}x${8 * factor}.rgba")
                    val errors = output.indices.map { abs((output[it].toInt() and 255) - (expected[it].toInt() and 255)) }
                    assertTrue("${preset.id} differs from CPU reference: max=${errors.maxOrNull()}, mean=${errors.average()}",
                        errors.maxOrNull()!! <= 8 && errors.average() < 1.5)
                }
                assertTrue("${preset.id} produced an empty frame", output.indices.filter { it % 4 != 3 }.any { output[it].toInt() != 0 })
                assertTrue("${preset.id} must output opaque video", output.indices.filter { it % 4 == 3 }.all { (output[it].toInt() and 255) == 255 })
            } finally { shader.release() }
        }
    }

    @Test fun memoryLimitAndHdrBypassKeepOriginalPixelsAndReportOnce() = withEgl {
        val input = fixture("cnn-medium-input-8x8.rgba")
        for (hdr in listOf(false, true)) {
            val failures = mutableListOf<String>()
            val shader = NativeAnime4KShaderProgram(context, "cnn-2x-medium", 16, 16, hdr, failures::add,
                memoryBudgetBytes = 1)
            try {
                repeat(2) {
                    val (size, output) = render(shader, input, 8, 8)
                    assertEquals(Size(8, 8), size)
                    assertArrayEquals("Fallback must preserve source colors", input, output)
                }
                assertEquals(1, failures.size)
                assertTrue(failures.single().contains(if (hdr) "HDR" else "memory budget"))
            } finally { shader.release() }
        }
    }

    private fun render(shader: NativeAnime4KShaderProgram, pixels: ByteArray, width: Int, height: Int): Pair<Size, ByteArray> {
        val input = GlUtil.createTexture(width, height, false)
        var output = 0
        var fbo = 0
        try {
            GlUtil.bindTexture(GLES20.GL_TEXTURE_2D, input, GLES20.GL_LINEAR)
            GLES20.glTexSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, width, height, GLES20.GL_RGBA,
                GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(pixels.size).put(pixels).apply { position(0) })
            val size = shader.configure(width, height)
            output = GlUtil.createTexture(size.width, size.height, false)
            fbo = GlUtil.createFboForTexture(output)
            GlUtil.focusFramebufferUsingCurrentContext(fbo, size.width, size.height)
            shader.drawFrame(input, 0)
            val buffer = ByteBuffer.allocateDirect(size.width * size.height * 4)
            GLES20.glReadPixels(0, 0, size.width, size.height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buffer)
            GlUtil.checkGlError()
            buffer.position(0)
            return Pair(size, ByteArray(buffer.capacity()).also { buffer.get(it) })
        } finally {
            if (fbo != 0) GlUtil.deleteFbo(fbo)
            if (output != 0) GlUtil.deleteTexture(output)
            GlUtil.deleteTexture(input)
        }
    }

    private fun withEgl(block: () -> Unit) {
        // Match Media3's SDR ES2 context, rather than hiding compatibility bugs with a forced ES3 context.
        val display = GlUtil.getDefaultEglDisplay()
        val eglContext = GlUtil.createEglContext(display)
        val surface = GlUtil.createFocusedPlaceholderEglSurface(eglContext, display)
        try { block() } finally {
            GlUtil.destroyEglSurface(display, surface)
            GlUtil.destroyEglContext(display, eglContext)
            GlUtil.terminate(display)
        }
    }
}
