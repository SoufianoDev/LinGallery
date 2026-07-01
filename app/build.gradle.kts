plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

val compileRust = tasks.register("compileRust") {
    val jniLibsDir = layout.projectDirectory.dir("src/main/jniLibs")
    val rustDir = layout.projectDirectory.dir("src/main/rust")
    val cargoTargetDir = layout.projectDirectory.dir("src/main/rust/target")
    val nativeLibFile = jniLibsDir.file("liblingallery_native.so")

    inputs.dir(rustDir.dir("src"))
    inputs.file(rustDir.file("Cargo.toml"))
    inputs.file(rustDir.file("Cargo.lock"))
    outputs.file(nativeLibFile)

    doLast {
        val cargoVersionOutput = try {
            val process = ProcessBuilder("cargo", "--version")
                .redirectErrorStream(true)
                .start()
            val exitCode = process.waitFor()
            if (exitCode != 0) null
            else process.inputStream.bufferedReader().readText().trim()
        } catch (_: Exception) { null }

        if (cargoVersionOutput == null) {
            throw GradleException(
                """
                Rust toolchain (cargo) not found — required to build the native library.

                Install Rust:
                  curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh

                Or via package manager:
                  Debian/Ubuntu : sudo apt install cargo
                  Fedora        : sudo dnf install cargo
                  Arch Linux    : sudo pacman -S rust

                After installing, restart your terminal and run the build again.
                """.trimIndent()
            )
        }

        val versionMatch = Regex("""cargo (\d+)\.(\d+)""").find(cargoVersionOutput)
        if (versionMatch == null) {
            throw GradleException(
                """
                Cannot parse Rust version from: $cargoVersionOutput
                Expected format: cargo X.Y.Z
                Update: rustup update stable
                """.trimIndent()
            )
        }

        val major = versionMatch.groupValues[1].toInt()
        val minor = versionMatch.groupValues[2].toInt()
        val versionDouble = major + minor / 100.0

        if (versionDouble < 1.79) {
            throw GradleException(
                """
                Rust version $versionDouble is too old (requires >= 1.79).
                Current: $cargoVersionOutput
                Update: rustup update stable
                """.trimIndent()
            )
        }

        val r = rustDir.asFile
        val j = jniLibsDir.asFile
        val t = cargoTargetDir.asFile

        logger.lifecycle("Rust toolchain: $cargoVersionOutput (>= 1.79.0)")
        logger.lifecycle("Building native library...")

        j.mkdirs()

        val process = ProcessBuilder("cargo", "build", "--release")
            .directory(r)
            .inheritIO()
            .start()

        val exitCode = process.waitFor()
        if (exitCode != 0)
            throw GradleException("Cargo build failed with exit code $exitCode")

        val builtLib = File(t, "release/liblingallery_native.so")
        if (!builtLib.exists())
            throw GradleException("Native library not found at: ${builtLib.absolutePath}")

        builtLib.copyTo(File(j, "liblingallery_native.so"), overwrite = true)

        logger.lifecycle("Native library built: ${j}/liblingallery_native.so")
    }
}

tasks.named("classes") {
    dependsOn(compileRust)
}

tasks.named("clean") {
    delete("src/main/jniLibs", "src/main/rust/target")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(compose.components.resources)
    implementation(libs.compose.uiToolingPreview)

    implementation(libs.kotlinx.coroutinesSwing)

    implementation(libs.sketch.compose)
    implementation(libs.sketch.svg)
    implementation(libs.sketch.gif)

    implementation(libs.metadata.extractor)

    implementation(libs.scrimage.core)
    implementation(libs.scrimage.webp)
    implementation(libs.scrimage.extra)

    runtimeOnly("com.twelvemonkeys.imageio:imageio-jpeg:3.13.1")
    runtimeOnly("com.twelvemonkeys.imageio:imageio-bmp:3.13.1")
    runtimeOnly("com.twelvemonkeys.imageio:imageio-tiff:3.13.1")
    runtimeOnly("com.twelvemonkeys.imageio:imageio-webp:3.13.1")
    runtimeOnly("com.github.usefulness:webp-imageio:0.10.0")

    implementation(libs.sqlite.jdbc)

    implementation(libs.filekit.core)
    implementation(libs.filekit.dialogs)

    implementation(libs.json)
}

configurations.all {
    resolutionStrategy {
        force("org.jetbrains.skiko:skiko:0.144.6")
    }
}

compose.desktop {
    application {
        mainClass = "com.soufianodev.lingallery.app.MainKt"

        nativeDistributions {
            targetFormats(
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Deb,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.Rpm,
                org.jetbrains.compose.desktop.application.dsl.TargetFormat.AppImage
            )
            packageName = "com.soufianodev.lingallery"
            packageVersion = "1.0.0"
            linux {
                modules("jdk.security.auth")
            }
        }

        jvmArgs += listOf(
            "-Djava.library.path=${layout.projectDirectory.dir("src/main/jniLibs").asFile.absolutePath}",
            "-Dskiko.renderApi=OPENGL",
            "-Dskiko.gpu.resourceCacheLimit=128M",
            "-Xss512k",
            "-Xmx2g",
            "-XX:NativeMemoryTracking=summary"
        )
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21)
        freeCompilerArgs.add("-Xjvm-default=all")
    }
}

tasks.withType<JavaCompile> {
    sourceCompatibility = "21"
    targetCompatibility = "21"
}
