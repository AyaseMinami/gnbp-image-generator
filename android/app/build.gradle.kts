import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

val pinnedBuildToolsVersion = "36.0.0"

android {
    namespace = "io.github.ayaseminami.gnbp"
    compileSdk = 37
    buildToolsVersion = pinnedBuildToolsVersion

    defaultConfig {
        applicationId = "io.github.ayaseminami.gnbp"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }

    sourceSets {
        getByName("test").resources.directories.add("../../contracts")
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

room {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    implementation(libs.androidx.datastore.preferences)

    ksp(libs.androidx.room.compiler)

    debugImplementation(libs.androidx.compose.ui.tooling)
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.rules)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
}

val verifyNetworkChokepoint by tasks.registering {
    group = "verification"
    description = "Rejects production network clients outside the provider transport module."

    val sourceRoot = layout.projectDirectory.dir("src/main/java")
    inputs.dir(sourceRoot)

    doLast {
        val constructionPatterns = listOf(
            "OkHttpClient.Builder(",
            "OkHttpClient(",
            "SSLSocketFactory",
            "SSLContext",
            "TrustManager",
            "HttpURLConnection",
            ".openConnection(",
            "CronetEngine.Builder(",
            "HttpEngine.Builder(",
            "WebView(",
            "Socket(",
        )
        val allowedPath = "/provider/transport/"
        val violations = fileTree(sourceRoot).matching {
            include("**/*.kt", "**/*.java")
        }.flatMap { file ->
            val normalizedPath = file.invariantSeparatorsPath
            if (allowedPath in normalizedPath) {
                emptyList()
            } else {
                file.readLines().mapIndexedNotNull { index, line ->
                    val pattern = constructionPatterns.firstOrNull(line::contains)
                    pattern?.let { "$normalizedPath:${index + 1} constructs $it" }
                }
            }
        }
        check(violations.isEmpty()) {
            "Network construction must stay inside provider/transport:\n${violations.joinToString("\n")}"
        }
    }
}

val debugApk = layout.buildDirectory.file("outputs/apk/debug/app-debug.apk")

val verifyDebugApkPageAlignment by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies 16 KB page alignment for native libraries in the debug APK."
    dependsOn("assembleDebug")

    val zipalign = androidComponents.sdkComponents.sdkDirectory.map { sdkDirectory ->
        val executable = if (System.getProperty("os.name").startsWith("Windows")) {
            "zipalign.exe"
        } else {
            "zipalign"
        }
        sdkDirectory.file("build-tools/$pinnedBuildToolsVersion/$executable").asFile
    }

    inputs.file(debugApk)

    doFirst {
        val zipalignFile = zipalign.get()
        val apkFile = debugApk.get().asFile
        check(zipalignFile.isFile) {
            "zipalign not found at ${zipalignFile.absolutePath}"
        }
        check(apkFile.isFile) {
            "Debug APK not found at ${apkFile.absolutePath}"
        }
        commandLine(
            zipalignFile.absolutePath,
            "-c",
            "-P",
            "16",
            "-v",
            "4",
            apkFile.absolutePath,
        )
    }
}

val verifyDebugArm64ElfPageAlignment by tasks.registering {
    group = "verification"
    description = "Rejects arm64 native libraries whose ELF LOAD alignment is below 16 KB."
    dependsOn("assembleDebug")
    inputs.file(debugApk)

    doLast {
        val apkFile = debugApk.get().asFile
        check(apkFile.isFile) { "Debug APK not found at ${apkFile.absolutePath}" }
        ZipFile(apkFile).use { apk ->
            val arm64Libraries = apk.entries().asSequence()
                .filter { entry ->
                    !entry.isDirectory &&
                        entry.name.startsWith("lib/arm64-v8a/") &&
                        entry.name.endsWith(".so")
                }
                .toList()
            arm64Libraries.forEach { entry ->
                val bytes = apk.getInputStream(entry).use { input -> input.readBytes() }
                val loadAlignments = bytes.elf64LoadAlignments(entry.name)
                check(loadAlignments.isNotEmpty()) {
                    "${entry.name} contains no ELF LOAD segments"
                }
                val invalid = loadAlignments.filter { alignment -> alignment < 0x4000L }
                check(invalid.isEmpty()) {
                    "${entry.name} has ELF LOAD p_align below 0x4000: " +
                        invalid.joinToString { alignment -> "0x${alignment.toString(16)}" }
                }
            }
        }
    }
}

verifyDebugApkPageAlignment.configure {
    dependsOn(verifyDebugArm64ElfPageAlignment)
}

tasks.named("preBuild").configure {
    dependsOn(verifyNetworkChokepoint)
}

tasks.named("check").configure {
    dependsOn(verifyDebugApkPageAlignment, verifyDebugArm64ElfPageAlignment)
}

val providerSmokeTestClass = "**/ProviderSmokeTest.class"
tasks.withType<org.gradle.api.tasks.testing.Test>().configureEach {
    if (name == "testDebugUnitTest") {
        exclude(providerSmokeTestClass)
    }
}

val providerSmokeTest by tasks.registering(org.gradle.api.tasks.testing.Test::class) {
    group = "verification"
    description = "Runs one manually authorized real-provider image request outside CI."
    val debugUnitTests = tasks.named<org.gradle.api.tasks.testing.Test>("testDebugUnitTest")
    dependsOn(debugUnitTests)

    testClassesDirs = debugUnitTests.get().testClassesDirs
    classpath = debugUnitTests.get().classpath
    include(providerSmokeTestClass)
    useJUnit()

    val smokeConfig = rootProject.layout.projectDirectory.file("../test_api.txt").asFile
    systemProperty("gnbp.providerSmoke.config", smokeConfig.absolutePath)
    outputs.upToDateWhen { false }
    doFirst {
        check(smokeConfig.isFile) {
            "Provider smoke config is missing from the repository root"
        }
    }
}

fun ByteArray.elf64LoadAlignments(entryName: String): List<Long> {
    check(size >= 64) { "$entryName is too small to be an ELF64 library" }
    check(
        this[0] == 0x7f.toByte() &&
            this[1] == 'E'.code.toByte() &&
            this[2] == 'L'.code.toByte() &&
            this[3] == 'F'.code.toByte(),
    ) { "$entryName does not have an ELF header" }
    check(this[4].toInt() == 2) { "$entryName is not ELF64" }
    check(this[5].toInt() == 1) { "$entryName is not little-endian ELF" }

    val programHeaderOffset = readUnsignedLongLittleEndian(32)
    val programHeaderEntrySize = readUnsignedShortLittleEndian(54)
    val programHeaderCount = readUnsignedShortLittleEndian(56)
    check(programHeaderEntrySize >= 56) { "$entryName has an invalid ELF program-header size" }
    check(programHeaderOffset <= Int.MAX_VALUE.toLong()) {
        "$entryName has an unsupported ELF program-header offset"
    }

    return buildList {
        repeat(programHeaderCount) { index ->
            val offset = programHeaderOffset.toInt() + index * programHeaderEntrySize
            check(offset >= 0 && offset + programHeaderEntrySize <= this@elf64LoadAlignments.size) {
                "$entryName has a truncated ELF program-header table"
            }
            val type = readUnsignedIntLittleEndian(offset)
            if (type == 1L) {
                add(readUnsignedLongLittleEndian(offset + 48))
            }
        }
    }
}

fun ByteArray.readUnsignedShortLittleEndian(offset: Int): Int {
    check(offset >= 0 && offset + 2 <= size) { "ELF read exceeds input" }
    return (this[offset].toInt() and 0xff) or
        ((this[offset + 1].toInt() and 0xff) shl 8)
}

fun ByteArray.readUnsignedIntLittleEndian(offset: Int): Long {
    check(offset >= 0 && offset + 4 <= size) { "ELF read exceeds input" }
    return (0 until 4).fold(0L) { value, index ->
        value or ((this[offset + index].toLong() and 0xffL) shl (index * 8))
    }
}

fun ByteArray.readUnsignedLongLittleEndian(offset: Int): Long {
    check(offset >= 0 && offset + 8 <= size) { "ELF read exceeds input" }
    return (0 until 8).fold(0L) { value, index ->
        value or ((this[offset + index].toLong() and 0xffL) shl (index * 8))
    }
}
