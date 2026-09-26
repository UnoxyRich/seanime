import org.gradle.api.tasks.Sync
import java.io.File

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val repoRoot = rootProject.projectDir.parentFile
val webOutput = repoRoot.resolve("seanime-web/out-androidtv")
val embeddedWeb = repoRoot.resolve("mobile/web")
val ffmpegSourceScript = repoRoot.resolve("scripts/build-android-ffmpeg.sh")
val ffmpegJniLibs = projectDir.resolve("src/main/jniLibs")
val ffmpegMetadata = projectDir.resolve("src/main/assets/ffmpeg")
val generatedAndroidRuntimeJniLibs = layout.buildDirectory.dir("generated/androidRuntimeJniLibs")
val ffmpegBuildAbis = providers.environmentVariable("SEANIME_ANDROID_ABIS").orElse("arm64-v8a x86_64")
val ffmpegBuildConfigChecksum = providers.provider {
    providers.exec {
        commandLine("cksum", ffmpegSourceScript.absolutePath)
    }.standardOutput.asText.get().trim().substringBefore(" ")
}
val generatedAar = layout.buildDirectory.file("generated/gomobile/seanime-mobile.aar")
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
            keepDebugSymbols += setOf("**/libffmpeg.so", "**/libffprobe.so")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

val buildAndroidWeb by tasks.registering(Exec::class) {
    workingDir = repoRoot.resolve("seanime-web")
    commandLine("npm", "run", "build:androidtv")
}

val embedAndroidWeb by tasks.registering(Sync::class) {
    dependsOn(buildAndroidWeb)
    from(webOutput)
    into(embeddedWeb)
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
    commandLine("go", "run", "golang.org/x/mobile/cmd/gomobile", "init")
}

val bindGoMobile by tasks.registering(Exec::class) {
    dependsOn(embedAndroidWeb, initGoMobile)
    inputs.dir(repoRoot.resolve("mobile"))
    inputs.dir(repoRoot.resolve("internal"))
    inputs.file(repoRoot.resolve("go.mod"))
    inputs.file(repoRoot.resolve("go.sum"))
    outputs.file(generatedAar)
    doFirst {
        generatedAar.get().asFile.parentFile.mkdirs()
    }
    workingDir = repoRoot
    environment("PATH", goToolPath.get())
    environment("ANDROID_NDK_HOME", gomobileNdkPath.get())
    commandLine(
        "go", "run", "golang.org/x/mobile/cmd/gomobile", "bind",
        "-target=android/arm64,android/amd64",
        "-androidapi=23",
        "-javapkg=app.seanime.tv.gomobile",
        // anet uses a supported Android fix through net.zoneCache via go:linkname.
        "-ldflags=-s -w -checklinkname=0 -X=seanime/internal/constants.Version=$androidVersionName",
        "-o", generatedAar.get().asFile.absolutePath,
        "./mobile",
    )
}

tasks.named("preBuild").configure {
    dependsOn(bindGoMobile, buildAndroidFfmpeg, stageAndroidNdkCppRuntime)
}

dependencies {
    implementation(files(generatedAar))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.webkit:webkit:1.15.0")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
}
