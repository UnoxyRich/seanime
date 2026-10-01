package app.seanime.tv.platform

import kotlin.math.ceil
import kotlin.math.min

/** Parses only our bundled, attributed Anime4K mpv shaders. This is not an arbitrary shader loader. */
internal object NativeAnime4KPlan {
    data class Dimensions(val width: Int, val height: Int) {
        init { require(width > 0 && height > 0) }
        val bytes: Long get() = width.toLong() * height * 8 // RGBA16F, including signed activations.
    }
    data class Pass(val name: String, val source: String, val bindings: Map<String, Int>, val size: Dimensions)
    data class Slot(val size: Dimensions, var lastUse: Int)
    data class Plan(val passes: List<Pass>, val output: Int, val slots: List<Dimensions>, val passSlots: List<Int>) {
        val size: Dimensions get() = passes[output].size
        // Include Media3's single output texture (RGBA8 SDR) when enforcing the GPU allocation limit.
        val bytes: Long get() = slots.sumOf { it.bytes } + size.width.toLong() * size.height * 4
    }
    data class ShaderPass(val name: String, val bindings: List<String>, val save: String?,
        val width: String?, val height: String?, val source: String)

    fun parse(source: String): List<ShaderPass> = source.split("//!DESC ").drop(1).map { chunk ->
        val lines = chunk.lines()
        fun value(key: String) = lines.firstOrNull { it.startsWith("//!$key ") }?.substringAfter(" ")?.trim()
        val commands = lines.filter { it.startsWith("//!") }.map { it.substring(3).substringBefore(" ") }
        require(commands.all { it in setOf("HOOK", "BIND", "SAVE", "WIDTH", "HEIGHT", "WHEN", "COMPONENTS") })
        require(value("HOOK") in setOf("MAIN", "PREKERNEL"))
        // Preset logic gates the complete network; applying WHEN again per pass can skip
        // a suffix after MAIN is replaced. Direct CNN presets intentionally always run 2x.
        // Fail closed if a future bundled model introduces a different mpv condition.
        require(value("WHEN") == null || value("WHEN") ==
            "OUTPUT.w MAIN.w / 1.200 > OUTPUT.h MAIN.h / 1.200 > *")
        ShaderPass(lines.first(), lines.filter { it.startsWith("//!BIND ") }.map { it.substringAfter(" ").trim() },
            value("SAVE"), value("WIDTH"), value("HEIGHT"), lines.drop(1).filterNot { it.startsWith("//!") }.joinToString("\n"))
    }.also { require(it.isNotEmpty()) }

    /** Restricted postfix dimensions used by the bundled CNN sources, not evaluated code. */
    fun dimension(expression: String?, fallback: Int, sizes: Map<String, Dimensions>): Int {
        if (expression == null) return fallback
        val stack = mutableListOf<Double>()
        for (token in expression.split(Regex("\\s+"))) {
            if (token in setOf("*", "/")) {
                require(stack.size >= 2)
                val right = stack.removeAt(stack.lastIndex); val left = stack.removeAt(stack.lastIndex)
                stack += if (token == "*") left * right else left / right
            } else {
                stack += token.toDoubleOrNull() ?: run {
                    val size = sizes[token.substringBeforeLast('.')] ?: error("Unknown shader size $token")
                    when (token.substringAfterLast('.')) { "w" -> size.width.toDouble(); "h" -> size.height.toDouble(); else -> error("Invalid dimension") }
                }
            }
        }
        require(stack.size == 1 && stack[0].isFinite() && stack[0] > 0 && stack[0] <= 16_384)
        return ceil(stack[0]).toInt()
    }

    fun build(id: String, input: Dimensions, target: Dimensions, asset: (String) -> String): Plan {
        val passes = mutableListOf(Pass("BT.709 encode", ENCODE, mapOf("INPUT" to -1), input))
        var main = 0
        fun addShader(name: String) {
            val names = mutableMapOf("MAIN" to main)
            for (part in parse(asset("Anime4K_$name.glsl"))) {
                names["MAIN"] = main; names["HOOKED"] = main
                val bindings = part.bindings.associateWith { names[it] ?: error("Missing Anime4K texture $it") }.toMutableMap()
                // The upstream Clamp_Highlights shader binds HOOKED but uses the MAIN alias.
                if ("HOOKED" in bindings) bindings["MAIN"] = main
                val dimensions = names.mapValues { passes[it.value].size }
                val current = passes[main].size
                val size = Dimensions(dimension(part.width, current.width, dimensions), dimension(part.height, current.height, dimensions))
                passes += Pass(part.name, part.source, bindings, size)
                val output = passes.lastIndex
                if (part.save == null || part.save == "MAIN") main = output else names[part.save] = output
            }
        }
        fun upscale(name: String, conditional: Boolean = true) {
            val current = passes[main].size
            if (!conditional || target.width > current.width * 1.2 && target.height > current.height * 1.2) addShader(name)
        }
        fun downscale() {
            val factor = when {
                target.width > input.width * 1.2 && target.height > input.height * 1.2 &&
                    target.width < input.width * 2 && target.height < input.height * 2 -> 1
                target.width > input.width * 2.4 && target.height > input.height * 2.4 &&
                    target.width < input.width * 4 && target.height < input.height * 4 -> 2
                else -> return
            }
            passes += Pass("AutoDownscalePre x${factor * 2}", COPY, mapOf("INPUT" to main),
                Dimensions((target.width + factor - 1) / factor, (target.height + factor - 1) / factor))
            main = passes.lastIndex
        }
        when (id) {
            "gan-3x-large" -> addShader("Upscale_GAN_x3_L")
            "gan-4x-ultra-large" -> addShader("Upscale_GAN_x4_UUL")
            "cnn-2x-medium" -> upscale("Upscale_CNN_x2_M", false)
            "cnn-2x-very-large" -> upscale("Upscale_CNN_x2_VL", false)
            "cnn-2x-ultra-large" -> upscale("Upscale_CNN_x2_UL", false)
            "denoise-cnn-2x-very-large" -> upscale("Upscale_Denoise_CNN_x2_VL", false)
            "mode-a", "mode-b", "mode-c", "mode-aa", "mode-bb", "mode-ca" -> {
                // Same network selection/order and resolution gates as anime4k-webgpu's HQ modes.
                addShader("Clamp_Highlights")
                if (id in setOf("mode-a", "mode-aa")) addShader("Restore_CNN_VL")
                if (id in setOf("mode-b", "mode-bb")) addShader("Restore_CNN_Soft_VL")
                upscale(if (id in setOf("mode-c", "mode-ca")) "Upscale_Denoise_CNN_x2_VL" else "Upscale_CNN_x2_VL")
                if (id == "mode-aa") addShader("Restore_CNN_M")
                downscale()
                if (id == "mode-bb") addShader("Restore_CNN_Soft_M")
                if (id == "mode-ca") addShader("Restore_CNN_M")
                upscale("Upscale_CNN_x2_M")
            }
            else -> error("Unsupported native Anime4K preset: $id")
        }
        // Reuse a texture only after its final consumer. Never overwrite a texture sampled by a pass.
        val lastUse = IntArray(passes.size) { it }
        passes.forEachIndexed { index, pass -> pass.bindings.values.filter { it >= 0 }.forEach { lastUse[it] = index } }
        lastUse[main] = passes.size
        val slots = mutableListOf<Slot>()
        val assignments = passes.mapIndexed { index, pass ->
            val reusable = slots.indexOfFirst { it.size == pass.size && it.lastUse < index }
            if (reusable >= 0) { slots[reusable].lastUse = lastUse[index]; reusable }
            else { slots += Slot(pass.size, lastUse[index]); slots.lastIndex }
        }
        return Plan(passes, main, slots.map { it.size }, assignments)
    }

    /** Fit display bounds without changing source aspect ratio or upscaling against black bars. */
    fun fit(input: Dimensions, bounds: Dimensions): Dimensions {
        val scale = min(bounds.width.toDouble() / input.width, bounds.height.toDouble() / input.height)
        return Dimensions((input.width * scale).toInt().coerceAtLeast(1), (input.height * scale).toInt().coerceAtLeast(1))
    }

    const val COPY = "vec4 hook() { return INPUT_tex(INPUT_pos); }"
    // Media3 GlEffect textures are linear RGB BT.709; original Anime4K weights expect gamma-encoded SDR.
    const val ENCODE = """
        vec4 hook() {
            vec3 c = max(INPUT_tex(INPUT_pos).rgb, vec3(0.0));
            vec3 v = mix(4.5 * c, 1.099 * pow(c, vec3(0.45)) - 0.099, step(vec3(0.018), c));
            return vec4(v, 1.0);
        }
    """
    const val DECODE = """
        vec4 hook() {
            vec3 c = clamp(INPUT_tex(INPUT_pos).rgb, 0.0, 1.0);
            vec3 v = mix(c / 4.5, pow((c + 0.099) / 1.099, vec3(1.0 / 0.45)), step(vec3(0.0812), c));
            return vec4(v, 1.0);
        }
    """
}
