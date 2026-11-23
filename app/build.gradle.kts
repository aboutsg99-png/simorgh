import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val signingPropertiesFile = providers.gradleProperty("signingProperties").orNull
    ?.let(::file)
    ?: providers.environmentVariable("SIGNING_PROPERTIES").orNull?.let(::file)
    ?: rootProject.file("signing.properties").let { if (it.isFile) it else rootProject.file("../signing.properties") }
val signingProperties = Properties().apply {
    if (signingPropertiesFile.isFile) {
        signingPropertiesFile.inputStream().use { load(it) }
    }
}
val releaseSigningAvailable = signingPropertiesFile.isFile &&
    signingProperties.getProperty("storeFile").orEmpty().isNotBlank()

gradle.taskGraph.whenReady {
    if (allTasks.any { it.name == "assembleRelease" } && !releaseSigningAvailable) {
        throw GradleException("Release signing is not configured. Set the SIGNING_* secrets.")
    }
}

val gpsMockTest = providers.gradleProperty("uacGpsMockTest").orNull == "true"
if (gpsMockTest) {
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("gps-mock-test/app"))
}

android {
    namespace = "com.uacspoofer.mobile"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    ndkVersion = "26.3.11579264"

    defaultConfig {
        applicationId = "app.simorgh.vpn"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0.0"

        buildConfigField("boolean", "TV_MODE", "false")
        buildConfigField(
            "String",
            "SIGNING_CERT_SHA256",
            "\"" + (providers.gradleProperty("signingCertSha256").orNull
                ?: "98:4C:1B:C8:23:41:8B:65:FE:9A:1D:8B:57:46:90:02:11:41:D4:52:6E:00:06:A8:75:6F:9C:22:ED:C8:3D:32") + "\"",
        )
        buildConfigField("boolean", "GPS_MOCK_TEST", "false")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseSigningAvailable) {
            create("release") {
                storeFile = signingPropertiesFile.parentFile.resolve(signingProperties.getProperty("storeFile"))
                storePassword = signingProperties.getProperty("storePassword")
                keyAlias = signingProperties.getProperty("keyAlias")
                keyPassword = signingProperties.getProperty("keyPassword")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    buildTypes {
        debug {
            if (gpsMockTest) {
                buildConfigField("boolean", "GPS_MOCK_TEST", "true")
            }
            if (releaseSigningAvailable) {
                signingConfig = signingConfigs.getByName("release")
            }
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("release")
            ndk {
                abiFilters += listOf("arm64-v8a")
            }
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
        // Release optimization with isolated render fixtures; never shipped as release.
        create("benchmark") {
            initWith(getByName("release"))
            matchingFallbacks += listOf("release")
        }
        create("tv") {
            initWith(getByName("debug"))
            buildConfigField("boolean", "TV_MODE", "true")
            ndk {
                abiFilters.clear()
                abiFilters += "armeabi-v7a"
            }
            matchingFallbacks += listOf("debug")
        }
    }

    sourceSets.getByName("debug").java.srcDir("src/renderBenchmark/java")
    if (gpsMockTest) {
        sourceSets.getByName("debug").manifest.srcFile("src/gpsMockTest/AndroidManifest.xml")
    }
    sourceSets.getByName("benchmark") {
        java.srcDirs("src/renderBenchmark/java", "src/release/java")
        manifest.srcFile("src/debug/AndroidManifest.xml")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }


    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/versions/**/OSGI-INF/MANIFEST.MF"
        }
    }
}

android.applicationVariants.configureEach {
    val version = versionName
    outputs.configureEach {
        val apkOutput = this as com.android.build.gradle.internal.api.ApkVariantOutputImpl
        val abi = apkOutput.getFilter("ABI") ?: "arm64-v8a"
        if (gpsMockTest && buildType.name == "debug") {
            apkOutput.outputFileName = "Simorgh-${version}-gps-mock-test-${abi}.apk"
        } else if (buildType.name == "release") {
            apkOutput.outputFileName = "Simorgh-${version}-${abi}.apk"
        }
    }
}

tasks.register("copyTvApkNextToDebug") {
    dependsOn("assembleTv")
    doLast {
        copy {
            from(layout.buildDirectory.dir("outputs/apk/tv")) {
                include("*.apk")
                rename { "app-tv-armeabi-v7a.apk" }
            }
            into(layout.buildDirectory.dir("outputs/apk/debug"))
        }
    }
}

val generatedPowAars = layout.buildDirectory.dir("generated/pow-aars")

val isolatePsiphonAar = tasks.register<Exec>("isolatePsiphonAar") {
    val out = generatedPowAars.map { it.file("psiphontunnel-isolated.aar") }
    inputs.file(file("libs/psiphontunnel-2.0.39.aar"))
    inputs.file(rootProject.file("scripts/isolate_psiphon_aar.py"))
    outputs.file(out)
    commandLine("python", rootProject.file("scripts/isolate_psiphon_aar.py").absolutePath)
    environment("POW_PSIPHON_SRC", file("libs/psiphontunnel-2.0.39.aar").absolutePath)
    environment("POW_PSIPHON_DST", out.get().asFile.absolutePath)
}

val patchV2raySeqAar = tasks.register<Exec>("patchV2raySeqAar") {
    val out = generatedPowAars.map { it.file("libv2ray-seqpatched.aar") }
    inputs.file(file("libs/libv2ray-native-tun.aar"))
    inputs.file(rootProject.file("scripts/patch_v2ray_seq.py"))
    outputs.file(out)
    commandLine("python", rootProject.file("scripts/patch_v2ray_seq.py").absolutePath)
    environment("POW_V2RAY_SRC", file("libs/libv2ray-native-tun.aar").absolutePath)
    environment("POW_V2RAY_DST", out.get().asFile.absolutePath)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation(isolatePsiphonAar.map { it.outputs.files })
    implementation(patchV2raySeqAar.map { it.outputs.files })
    implementation("com.facebook.fresco:fresco:3.6.0")
    implementation("com.facebook.fresco:animated-webp:3.6.0")
    implementation("com.facebook.fresco:webpsupport:3.6.0")

    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    debugImplementation("androidx.compose.ui:ui-tooling")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}

val nativeCoreAbis = listOf("arm64-v8a", "armeabi-v7a", "x86_64")
val coreLibraries = mapOf("Pow" to "libaether.so", "Direct" to "libDirect.so")
val nativeNoticeAssets = layout.buildDirectory.dir("generated/nativeCoreNotices/assets")
val copyNativeCoreNotices = tasks.register<Sync>("copyNativeCoreNotices") {
    from(rootProject.file("THIRD_PARTY_NOTICES.md"))
    from(rootProject.file("NOTICE.md"))
    from(rootProject.file("core/direct/LICENSE")) { rename { "AGPL-3.0.txt" } }
    from(rootProject.file("core/aether/TRADEMARK.md")) { rename { "AETHER-TRADEMARK.md" } }
    into(nativeNoticeAssets.map { it.dir("licenses") })
}
android.sourceSets.getByName("main").assets.srcDir(nativeNoticeAssets)
tasks.named("preBuild").configure { dependsOn(copyNativeCoreNotices) }

for ((core, library) in coreLibraries) {
    for (abi in nativeCoreAbis) {
        val output = file("src/main/jniLibs/$abi/$library")
        if (output.isFile) continue
        val suffix = abi.split('-').joinToString("") { it.replaceFirstChar(Char::uppercase) }
        val task = tasks.register<Exec>("buildUac${core}Core$suffix") {
            group = "build"
            description = "Build the $core native core for $abi"
            val windows = System.getProperty("os.name").startsWith("Windows", ignoreCase = true)
            val script = rootProject.file(if (windows) "core/build-android.ps1" else "core/build-android.sh")
            val command = if (windows) {
                listOf("powershell.exe", "-ExecutionPolicy", "Bypass", "-File", script.absolutePath)
            } else {
                listOf("bash", script.absolutePath)
            }
            commandLine(command + listOf("-Abi", abi, "-Core", core))
            environment("ANDROID_HOME", android.sdkDirectory.absolutePath)
            inputs.dir(rootProject.file("core/aether/src"))
            inputs.files(rootProject.file("core/aether/Cargo.toml"), rootProject.file("core/aether/Cargo.lock"), script)
            if (core == "Direct") {
                inputs.dir(rootProject.file("core/direct/src"))
                inputs.files(rootProject.file("core/direct/Cargo.toml"), rootProject.file("core/direct/Cargo.lock"))
            }
            outputs.file(output)
        }
        tasks.named("preBuild").configure { dependsOn(task) }
    }
}

tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doLast {
        for ((core, library) in coreLibraries) {
            for (abi in nativeCoreAbis) {
                val binary = file("src/main/jniLibs/$abi/$library")
                val contents = if (binary.isFile) binary.readText(Charsets.ISO_8859_1) else ""
                val correctCore = if (core == "Direct") {
                    contents.contains("direct_start") && contents.contains("Direct IP sweep ") && !contents.contains("aether_start_json")
                } else {
                    contents.contains("aether_start_json") && !contents.contains("Direct IP sweep ")
                }
                check(correctCore) {
                    "Rebuild $library for $abi with core/build-android.ps1 -Abi $abi -Core $core."
                }
            }
        }
    }
}
