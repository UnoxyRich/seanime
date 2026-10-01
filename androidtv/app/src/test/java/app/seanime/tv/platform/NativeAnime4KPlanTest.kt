package app.seanime.tv.platform

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.util.zip.GZIPInputStream

class NativeAnime4KPlanTest {
    private val input = NativeAnime4KPlan.Dimensions(32, 18)
    private fun assetBytes(name: String): ByteArray {
        val plain = File("src/main/assets/anime4k/$name")
        val compressed = File("src/main/compressedAssets/anime4k/$name.gz")
        check(plain.isFile != compressed.isFile) { "Expected exactly one source for $name" }
        return if (plain.isFile) plain.readBytes()
        else GZIPInputStream(compressed.inputStream()).use { it.readBytes() }
    }
    private fun asset(name: String) = assetBytes(name).toString(Charsets.UTF_8)
    private fun plan(id: String, target: NativeAnime4KPlan.Dimensions = NativeAnime4KPlan.Dimensions(64, 36)) =
        NativeAnime4KPlan.build(id, input, target, ::asset)

    @Test fun mediumIsTheOriginalNinePassCnnWithRealDepthToSpace() {
        val graph = plan("cnn-2x-medium")
        assertEquals(10, graph.passes.size) // Color encoding plus 9 original network passes.
        assertEquals(NativeAnime4KPlan.Dimensions(64, 36), graph.size)
        assertTrue(graph.passes.last().name.endsWith("Depth-to-Space"))
        assertTrue(graph.passes[1].source.contains("-0.010995803"))
        assertTrue(graph.passes[1].source.contains("mat4("))
        // All seven skip-connected activations remain live through the dense pass;
        // the 2x output cannot share their smaller storage. Reuse starts across mode stages.
        assertEquals(graph.passes.size, graph.slots.size)
        assertTrue(plan("mode-a").slots.size < plan("mode-a").passes.size)
    }

    @Test fun allNativePresetsBuildAndKeepEveryLiveInputOutOfTheWriteTexture() {
        for (id in listOf("mode-a", "mode-b", "mode-c", "mode-aa", "mode-bb", "mode-ca",
            "cnn-2x-medium", "cnn-2x-very-large", "cnn-2x-ultra-large", "denoise-cnn-2x-very-large", "gan-3x-large", "gan-4x-ultra-large")) {
            val graph = plan(id)
            graph.passes.forEachIndexed { index, pass ->
                pass.bindings.values.filter { it >= 0 }.forEach { source ->
                    assertTrue("$id has a forward/self dependency", source < index)
                    assertNotEquals("$id reads its current output texture", graph.passSlots[index], graph.passSlots[source])
                    // No intervening pass may have overwritten a still-live source.
                    for (other in source + 1 until index) assertNotEquals(graph.passSlots[source], graph.passSlots[other])
                }
            }
            assertTrue(graph.bytes > 0)
        }
    }

    @Test fun modeResolutionGatesAvoidNeedlessDoubleUpscaleAndFitBlackBars() {
        val fit = NativeAnime4KPlan.fit(NativeAnime4KPlan.Dimensions(640, 480), NativeAnime4KPlan.Dimensions(1920, 1080))
        assertEquals(NativeAnime4KPlan.Dimensions(1440, 1080), fit)
        assertEquals(input, plan("mode-a", input).size)
        assertEquals(NativeAnime4KPlan.Dimensions(48, 27), plan("mode-a", NativeAnime4KPlan.Dimensions(48, 27)).size)
        assertEquals(NativeAnime4KPlan.Dimensions(96, 54), plan("mode-a", NativeAnime4KPlan.Dimensions(96, 54)).size)
        // Standalone CNN means exactly 2x even on a smaller display.
        assertEquals(NativeAnime4KPlan.Dimensions(64, 36), plan("cnn-2x-medium", input).size)
    }


    @Test fun everyHqModeKeepsCanonicalNetworkOrderAcrossResolutionTransitions() {
        val original = NativeAnime4KPlan.Dimensions(100, 60)
        val targets = listOf(original, NativeAnime4KPlan.Dimensions(130, 78),
            NativeAnime4KPlan.Dimensions(200, 120), NativeAnime4KPlan.Dimensions(300, 180),
            NativeAnime4KPlan.Dimensions(400, 240))
        fun network(name: String): String = when {
            "Upscale-Denoise-CNN" in name -> "denoise-vl"
            "Upscale-CNN" in name -> if ("(VL)" in name) "upscale-vl" else "upscale-m"
            "Restore-CNN-Soft" in name -> if ("(VL)" in name) "soft-vl" else "soft-m"
            "Restore-CNN" in name -> if ("(VL)" in name) "restore-vl" else "restore-m"
            else -> error("Unrecognized original network: $name")
        }
        for (id in listOf("mode-a", "mode-b", "mode-c", "mode-aa", "mode-bb", "mode-ca")) {
            for ((scaleIndex, target) in targets.withIndex()) {
                val graph = NativeAnime4KPlan.build(id, original, target, ::asset)
                assertEquals("$id output at $target", target, graph.size)
                val order = graph.passes.filter { "-Conv-" in it.name }.map { network(it.name) }
                    .fold(mutableListOf<String>()) { result, stage ->
                        if (result.lastOrNull() != stage) result.add(stage)
                        result
                    }
                val expected = buildList {
                    if (id in setOf("mode-a", "mode-aa")) add("restore-vl")
                    if (id in setOf("mode-b", "mode-bb")) add("soft-vl")
                    if (scaleIndex > 0) add(if (id in setOf("mode-c", "mode-ca")) "denoise-vl" else "upscale-vl")
                    if (id in setOf("mode-aa", "mode-ca")) add("restore-m")
                    if (id == "mode-bb") add("soft-m")
                    if (scaleIndex >= 3) add("upscale-m")
                }
                assertEquals("$id network order at $target", expected, order)
                val downscale = graph.passes.indexOfFirst { it.name.startsWith("AutoDownscalePre") }
                assertEquals("$id auto-downscale gate at $target", scaleIndex in setOf(1, 3), downscale >= 0)
                if (downscale >= 0 && id in setOf("mode-aa", "mode-bb", "mode-ca")) {
                    val secondRestore = graph.passes.indexOfFirst { "Restore-CNN" in it.name && "(M)" in it.name }
                    assertEquals("$id second restore relative to downscale", id == "mode-aa", secondRestore < downscale)
                }
            }
        }
    }

    @Test fun bundledWeightsAndLicensesAreUnmodifiedCopies() {
        val upstream = File("../../seanime-denshi/assets/shaders")
        val names = File("src/main/assets/anime4k").listFiles()!!
            .filter { it.extension == "glsl" }.map { it.name } +
            File("src/main/compressedAssets/anime4k").listFiles()!!
                .filter { it.name.endsWith(".glsl.gz") }.map { it.name.removeSuffix(".gz") }
        assertEquals(11, names.size)
        assertEquals(11, names.toSet().size)
        names.forEach { name ->
            val bytes = assetBytes(name)
            val existingSource = File(upstream, name)
            if (existingSource.exists()) assertArrayEquals(name, existingSource.readBytes(), bytes)
            else {
                val expected = mapOf(
                    "Anime4K_Upscale_GAN_x3_L.glsl" to "fecde271daf90df63d03f9999da57080b268d085cce1b60c0fbfe4589688da21",
                    "Anime4K_Upscale_GAN_x4_UUL.glsl" to "f4740658e3b8a15f3eb2e34d73a7b05d130bb2af11f3e71685636e4809e917ee",
                ).getValue(name)
                val actual = java.security.MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) }
                assertEquals("Pinned official GAN asset changed: $name", expected, actual)
            }
            assertTrue(bytes.toString(Charsets.UTF_8).startsWith("// MIT License"))
        }
    }

    @Test fun pinnedGanGraphsKeepAllStagesAndFractionalUpsampleConvolutions() {
        for ((id, factor, stages) in listOf(Triple("gan-3x-large", 3, 30), Triple("gan-4x-ultra-large", 4, 84))) {
            val graph = plan(id, input)
            assertEquals(stages + 1, graph.passes.size) // Complete network plus transfer conversion.
            assertEquals(NativeAnime4KPlan.Dimensions(input.width * factor, input.height * factor), graph.size)
            assertTrue(graph.passes.last().source.contains("return result + MAIN_tex(MAIN_pos)"))
            val upsamples = graph.passes.filter { it.size == graph.size }
            assertEquals(if (factor == 3) 3 else 7, upsamples.size)
            assertTrue(upsamples.first().source.contains(if (factor == 3) "* 0.6666666666666666" else "* 0.5"))
            assertEquals(if (factor == 3) 9 else 16, graph.passes.maxOf { it.bindings.size })
        }
    }

    @Test fun malformedSizesAndUnknownPresetsFailClosed() {
        for (expression in listOf("MAIN.w 0 /", "MAIN.w -1 *", "MAIN.w 2", "OTHER.w", "MAIN.w +")) {
            assertThrows(IllegalArgumentException::class.java) {
                try { NativeAnime4KPlan.dimension(expression, 32, mapOf("MAIN" to input)) }
                catch (error: IllegalStateException) { throw IllegalArgumentException(error) }
            }
        }
        assertThrows(IllegalStateException::class.java) { plan("unknown-network") }
    }
}
