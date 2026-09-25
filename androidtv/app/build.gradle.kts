import org.gradle.api.tasks.Sync

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val repoRoot = rootProject.projectDir.parentFile
val webOutput = repoRoot.resolve("seanime-web/out-androidtv")
val embeddedWeb = repoRoot.resolve("mobile/web")
val generatedAar = layout.buildDirectory.file("generated/gomobile/seanime-mobile.aar")

android {
    namespace = "app.seanime.tv"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.seanime.tv"
        minSdk = 23
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
    }

    splits {
        abi {
            isEnable = true
            reset()
            include("armeabi-v7a", "arm64-v8a", "x86_64")
            isUniversalApk = false
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
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

val initGoMobile by tasks.registering(Exec::class) {
    workingDir = repoRoot
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
    commandLine(
        "go", "run", "golang.org/x/mobile/cmd/gomobile", "bind",
        "-target=android/arm,android/arm64,android/amd64",
        "-androidapi=23",
        "-javapkg=app.seanime.tv.gomobile",
        "-o", generatedAar.get().asFile.absolutePath,
        "./mobile",
    )
}

tasks.named("preBuild").configure {
    dependsOn(bindGoMobile)
}

dependencies {
    implementation(files(generatedAar))
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.media3:media3-exoplayer:1.11.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.11.1")
    implementation("androidx.media3:media3-ui:1.11.1")
}
