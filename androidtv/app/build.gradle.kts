import java.io.File
import java.security.MessageDigest
import java.util.zip.GZIPInputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.android.compose.screenshot")
}

val repoRoot = rootProject.projectDir.parentFile
val ffmpegSourceScript = repoRoot.resolve("scripts/build-android-ffmpeg.sh")
val ffmpegJniLibs = projectDir.resolve("src/main/jniLibs")
val ffmpegMetadata = projectDir.resolve("src/main/assets/ffmpeg")
val generatedAndroidRuntimeJniLibs = layout.buildDirectory.dir("generated/androidRuntimeJniLibs")
val compressedAnime4KShader = layout.projectDirectory.file(
    "src/main/compressedAssets/anime4k/Anime4K_Upscale_GAN_x4_UUL.glsl.gz",
)
val generatedAnime4KAssets = layout.buildDirectory.dir("generated/anime4kAssets")
val generatedAnime4KShader = generatedAnime4KAssets.map {
    it.file("anime4k/Anime4K_Upscale_GAN_x4_UUL.glsl")
}
val ffmpegBuildAbis = providers.environmentVariable("SEANIME_ANDROID_ABIS").orElse("arm64-v8a x86_64")
val ffmpegBuildConfigChecksum = providers.provider {
    providers.exec {
        commandLine("cksum", ffmpegSourceScript.absolutePath)
    }.standardOutput.asText.get().trim().substringBefore(" ")
}
val generatedAar = layout.buildDirectory.file("generated/gomobile/seanime-mobile.aar")
val androidGoCompatibilityScript = repoRoot.resolve("scripts/prepare-android-go-compat.py")
val generatedGoCompatibility = layout.buildDirectory.dir("generated/go-android-compat")
val androidGoDriver = generatedGoCompatibility.map { it.dir("driver") }
val goBuildParallelism = providers.environmentVariable("SEANIME_GO_BUILD_PARALLELISM")
    .orElse("2").get().toIntOrNull()?.takeIf { it > 0 }
    ?: throw GradleException("SEANIME_GO_BUILD_PARALLELISM must be a positive integer")
val goBuildFlags = (System.getenv("GOFLAGS").orEmpty() + " -p=$goBuildParallelism").trim()
val pinnedNdkPath = android.sdkDirectory.resolve("ndk/27.2.12479018")
val gomobileNdkPath = providers.environmentVariable("ANDROID_NDK_HOME")
    .orElse(pinnedNdkPath.absolutePath)
val androidNdkRuntimeLibDirectory = providers.provider {
    val hostPrefix = when {
        System.getProperty("os.name").startsWith("Mac", ignoreCase = true) -> "darwin-"
        System.getProperty("os.name").startsWith("Linux", ignoreCase = true) -> "linux-"
        System.getProperty("os.name").startsWith("Windows", ignoreCase = true) -> "windows-"
        else -> throw GradleException("Unsupported host OS for Android NDK C++ runtime")
    }
    val prebuiltRoot = File(gomobileNdkPath.get(), "toolchains/llvm/prebuilt")
    val prebuiltDirectory = prebuiltRoot.listFiles()
        ?.firstOrNull { it.isDirectory && it.name.startsWith(hostPrefix) }
        ?: throw GradleException("Android NDK host toolchain not found under $prebuiltRoot")
    prebuiltDirectory.resolve("sysroot/usr/lib")
}
val androidVersionName = providers.environmentVariable("SEANIME_ANDROID_VERSION_NAME")
    .orElse("3.10.3")
    .get()
    .removePrefix("v")
val androidVersionParts = Regex("^(\\d+)\\.(\\d+)\\.(\\d+)(?:[-+].*)?$")
    .matchEntire(androidVersionName)
    ?: throw GradleException("SEANIME_ANDROID_VERSION_NAME must be a semantic version")
val versionMajor = androidVersionParts.groupValues[1].toInt()
val versionMinor = androidVersionParts.groupValues[2].toInt()
val versionPatch = androidVersionParts.groupValues[3].toInt()
if (versionMinor >= 1000 || versionPatch >= 1000) {
    throw GradleException("Android version minor and patch components must be below 1000")
}
val androidVersionCode = versionMajor * 1_000_000 + versionMinor * 1_000 + versionPatch
val releaseKeystoreFile = providers.environmentVariable("SEANIME_ANDROID_KEYSTORE_FILE").orNull
val releaseKeystorePassword = providers.environmentVariable("SEANIME_ANDROID_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("SEANIME_ANDROID_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("SEANIME_ANDROID_KEY_PASSWORD").orNull
val releaseSigningValues = listOf(releaseKeystoreFile, releaseKeystorePassword, releaseKeyAlias, releaseKeyPassword)
val hasReleaseSigning = releaseSigningValues.all { !it.isNullOrBlank() }
if (releaseSigningValues.any { !it.isNullOrBlank() } && !hasReleaseSigning) {
    throw GradleException("All four SEANIME_ANDROID_KEYSTORE_* signing values are required together")
}
val goToolPath = providers.provider {
    val configuredGoBin = providers.exec {
        commandLine("go", "env", "GOBIN")
    }.standardOutput.asText.get().trim()
    val goPath = providers.exec {
        commandLine("go", "env", "GOPATH")
    }.standardOutput.asText.get().trim()
    val goBins = buildList {
        if (configuredGoBin.isNotEmpty()) add(configuredGoBin)
        if (goPath.isNotEmpty()) {
            goPath.split(File.pathSeparator)
                .filter(String::isNotBlank)
                .mapTo(this) { File(it, "bin").absolutePath }
        }
    }.distinct()
    (goBins + System.getenv("PATH").orEmpty())
        .filter(String::isNotBlank)
        .joinToString(File.pathSeparator)
}

android {
    namespace = "app.seanime.tv"
    compileSdk = 36
    buildToolsVersion = "36.0.0"

    defaultConfig {
        applicationId = "app.seanime.tv"
        minSdk = 23
        targetSdk = 34
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = androidVersionCode
        versionName = androidVersionName
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    sourceSets.getByName("main").jniLibs.srcDir(generatedAndroidRuntimeJniLibs)
    sourceSets.getByName("main").assets.srcDir(generatedAnime4KAssets)
    // One test-only color oracle serves both host JVM and device assertions.
    sourceSets.getByName("test").java.srcDir("src/sharedTest/java")
    sourceSets.getByName("androidTest").java.srcDir("src/sharedTest/java")

    signingConfigs {
        create("release") {
            if (hasReleaseSigning) {
                storeFile = file(requireNotNull(releaseKeystoreFile))
                storePassword = requireNotNull(releaseKeystorePassword)
                keyAlias = requireNotNull(releaseKeyAlias)
                keyPassword = requireNotNull(releaseKeyPassword)
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    packaging {
        jniLibs {
            // These are static FFmpeg command-line executables packaged under
            // Android's ABI-specific native-library directory for extraction.
            useLegacyPackaging = true
            // libass and the Android host both supply libc++; acceptance checks
            // verify the packaged runtime against the pinned NDK and renderer.
            pickFirsts += "**/libc++_shared.so"
            keepDebugSymbols += setOf("**/libffmpeg.so", "**/libffprobe.so")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures { compose = true }

    experimentalProperties["android.experimental.enableScreenshotTest"] = true

    testOptions {
        unitTests {
            isReturnDefaultValues = true
            isIncludeAndroidResources = true
            all {
                it.jvmArgs(
                    "--add-opens=java.base/java.lang=ALL-UNNAMED",
                    "--add-opens=java.base/java.util=ALL-UNNAMED",
                    "--add-opens=java.base/java.io=ALL-UNNAMED",
                    "--add-opens=java.base/java.net=ALL-UNNAMED",
                    "--add-opens=java.base/java.security=ALL-UNNAMED",
                    "--add-opens=java.base/java.text=ALL-UNNAMED",
                    "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED",
                    "--add-opens=java.desktop/java.awt.font=ALL-UNNAMED",
                    "--add-opens=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
                )
            }
        }
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

val generateAnime4KAssets by tasks.registering {
    val expectedBytes = 1_091_539
    val expectedSha256 = "f4740658e3b8a15f3eb2e34d73a7b05d130bb2af11f3e71685636e4809e917ee"
    inputs.file(compressedAnime4KShader)
    inputs.property("expandedBytes", expectedBytes)
    inputs.property("expandedSha256", expectedSha256)
    outputs.file(generatedAnime4KShader)
    doLast {
        // Bound expansion and validate the complete original source before writing.
        val bytes = GZIPInputStream(compressedAnime4KShader.asFile.inputStream()).use {
            it.readNBytes(expectedBytes + 1)
        }
        check(bytes.size == expectedBytes) { "Anime4K GAN 4x source length changed" }
        val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        check(sha256 == expectedSha256) { "Pinned Anime4K GAN 4x source checksum changed" }
        val output = generatedAnime4KShader.get().asFile
        output.parentFile.mkdirs()
        output.writeBytes(bytes)
    }
}

val buildAndroidFfmpeg by tasks.registering(Exec::class) {
    inputs.file(ffmpegSourceScript)
    inputs.property("abis", ffmpegBuildAbis)
    outputs.dir(ffmpegJniLibs)
    outputs.dir(ffmpegMetadata)
    onlyIf("Android FFmpeg binaries are missing or built from a different toolchain script") {
        val expectedChecksum = ffmpegBuildConfigChecksum.get()
        ffmpegBuildAbis.get().split(Regex("\\s+")).filter(String::isNotBlank).any { abi ->
            val binariesExist = listOf("libffmpeg.so", "libffprobe.so").all { filename ->
                ffmpegJniLibs.resolve("$abi/$filename").isFile
            }
            val versionFile = ffmpegMetadata.resolve("$abi/version")
            !binariesExist || !versionFile.isFile ||
                !versionFile.readText().contains("Build config $expectedChecksum")
        }
    }
    workingDir = repoRoot
    environment("ANDROID_NDK_HOME", gomobileNdkPath.get())
    commandLine("bash", ffmpegSourceScript.absolutePath)
}

val stageAndroidNdkCppRuntime by tasks.registering(Copy::class) {
    from(androidNdkRuntimeLibDirectory.map { it.resolve("aarch64-linux-android/libc++_shared.so") }) {
        into("arm64-v8a")
    }
    from(androidNdkRuntimeLibDirectory.map { it.resolve("x86_64-linux-android/libc++_shared.so") }) {
        into("x86_64")
    }
    into(generatedAndroidRuntimeJniLibs)
}

val initGoMobile by tasks.registering(Exec::class) {
    workingDir = repoRoot
    environment("PATH", goToolPath.get())
    environment("ANDROID_NDK_HOME", gomobileNdkPath.get())
    environment("GOFLAGS", goBuildFlags)
    commandLine("go", "run", "golang.org/x/mobile/cmd/gomobile", "init")
}

val prepareAndroidGoCompatibility by tasks.registering(Exec::class) {
    inputs.file(androidGoCompatibilityScript)
    inputs.file(repoRoot.resolve("go.mod"))
    inputs.file(repoRoot.resolve("go.sum"))
    outputs.dir(generatedGoCompatibility)
    // Revalidate the immutable dependency checksum each time. The generator
    // leaves identical outputs untouched, preserving the expensive AAR cache.
    outputs.upToDateWhen { false }
    workingDir = repoRoot
    environment("PATH", goToolPath.get())
    environment("GOFLAGS", goBuildFlags)
    environment("GOWORK", "off")
    commandLine("python3", androidGoCompatibilityScript.absolutePath,
        "--output-dir", generatedGoCompatibility.get().asFile.absolutePath)
}

val bindGoMobile by tasks.registering(Exec::class) {
    dependsOn(initGoMobile, prepareAndroidGoCompatibility)
    inputs.file(androidGoCompatibilityScript)
    inputs.dir(generatedGoCompatibility)
    inputs.files(fileTree(repoRoot.resolve("mobile")) { exclude("**/*_test.go") })
    inputs.files(fileTree(repoRoot.resolve("internal")) { exclude("**/*_test.go") })
    inputs.file(repoRoot.resolve("go.mod"))
    inputs.file(repoRoot.resolve("go.sum"))
    inputs.property("versionName", androidVersionName)
    inputs.property("ndkPath", gomobileNdkPath)
    inputs.property("goBuildFlags", goBuildFlags)
    outputs.file(generatedAar)
    doFirst {
        generatedAar.get().asFile.parentFile.mkdirs()
    }
    workingDir = androidGoDriver.get().asFile
    environment("PATH", goToolPath.get())
    environment("ANDROID_NDK_HOME", gomobileNdkPath.get())
    if (Regex("""(?:^|\s)-(?:overlay|modfile)(?:=|\s)""").containsMatchIn(goBuildFlags)) {
        throw GradleException("Android's verified dependency driver cannot use a separate GOFLAGS overlay or modfile")
    }
    environment("GOFLAGS", goBuildFlags)
    environment("GOWORK", "off")
    commandLine(
        "go", "run", "golang.org/x/mobile/cmd/gomobile", "bind",
        "-target=android/arm64,android/amd64",
        "-androidapi=23",
        "-javapkg=app.seanime.tv.gomobile",
        // anet uses a supported Android fix through net.zoneCache via go:linkname.
        "-ldflags=-s -w -checklinkname=0 -X=seanime/internal/constants.Version=$androidVersionName " +
            "-extldflags=-Wl,-z,max-page-size=16384,-z,common-page-size=16384",
        "-o", generatedAar.get().asFile.absolutePath,
        "seanime/mobile",
    )
}

tasks.named("preBuild").configure {
    dependsOn(bindGoMobile, buildAndroidFfmpeg, stageAndroidNdkCppRuntime, generateAnime4KAssets)
}

dependencies {
    implementation(files(generatedAar))
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.tv:tv-material:1.0.0")
    implementation("io.coil-kt:coil-compose:2.7.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    screenshotTestImplementation(platform("androidx.compose:compose-bom:2025.05.00"))
    screenshotTestImplementation("com.android.tools.screenshot:screenshot-validation-api:0.0.1-alpha16")
    screenshotTestImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.15.1")
    testImplementation("androidx.compose.ui:ui-test-junit4:1.8.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("org.json:json:20240303")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.10.2")
    androidTestImplementation(platform("androidx.compose:compose-bom:2025.05.00"))
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    androidTestImplementation("io.github.peerless2012:ass-kt:0.5.1")
    androidTestImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.11.1")
    implementation("androidx.media3:media3-effect:1.11.1")
    implementation("io.github.peerless2012:ass-media:0.5.1")
    implementation("io.github.peerless2012:ass-kt:0.5.1")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation(kotlin("stdlib"))
}
