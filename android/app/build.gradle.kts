import java.util.zip.ZipFile

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.androidx.room)
}

val pinnedBuildToolsVersion = "36.0.0"
val applicationIdValue = "io.github.ayaseminami.gnbp"
val releaseVersionCodeValue = 1
val releaseVersionNameValue = "0.1.0"

android {
    namespace = "io.github.ayaseminami.gnbp"
    compileSdk = 37
    buildToolsVersion = pinnedBuildToolsVersion

    defaultConfig {
        applicationId = applicationIdValue
        minSdk = 26
        targetSdk = 37
        versionCode = releaseVersionCodeValue
        versionName = releaseVersionNameValue

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
val unsignedReleaseApk = layout.buildDirectory.file("outputs/apk/release/app-release-unsigned.apk")
val releaseCandidateApkPath = providers.gradleProperty("gnbp.releaseCandidateApk")
val releaseCertificateSha256 = providers.gradleProperty("gnbp.releaseCertificateSha256")
val releaseCandidateApk = releaseCandidateApkPath.map(rootProject::file)
val sdkDirectory = androidComponents.sdkComponents.sdkDirectory
val zipalignExecutable = sdkDirectory.map { directory ->
    val executable = if (System.getProperty("os.name").startsWith("Windows")) {
        "zipalign.exe"
    } else {
        "zipalign"
    }
    directory.file("build-tools/$pinnedBuildToolsVersion/$executable").asFile
}
val apksignerExecutable = sdkDirectory.map { directory ->
    val executable = if (System.getProperty("os.name").startsWith("Windows")) {
        "apksigner.bat"
    } else {
        "apksigner"
    }
    directory.file("build-tools/$pinnedBuildToolsVersion/$executable").asFile
}
val aapt2Executable = sdkDirectory.map { directory ->
    val executable = if (System.getProperty("os.name").startsWith("Windows")) {
        "aapt2.exe"
    } else {
        "aapt2"
    }
    directory.file("build-tools/$pinnedBuildToolsVersion/$executable").asFile
}

val verifyDebugApkPageAlignment by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies 16 KB page alignment for native libraries in the debug APK."
    dependsOn("assembleDebug")

    inputs.file(debugApk)

    doFirst {
        val zipalignFile = zipalignExecutable.get()
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
        verifyArm64ElfPageAlignment(apkFile)
    }
}

verifyDebugApkPageAlignment.configure {
    dependsOn(verifyDebugArm64ElfPageAlignment)
}

val verifyReleaseApkPageAlignment by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies 16 KB page alignment for native libraries in the unsigned release APK."
    dependsOn("assembleRelease")
    inputs.file(unsignedReleaseApk)

    doFirst {
        val zipalignFile = zipalignExecutable.get()
        val apkFile = unsignedReleaseApk.get().asFile
        check(zipalignFile.isFile) { "zipalign not found at ${zipalignFile.absolutePath}" }
        check(apkFile.isFile) { "Unsigned release APK not found at ${apkFile.absolutePath}" }
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

val verifyReleaseArm64ElfPageAlignment by tasks.registering {
    group = "verification"
    description = "Rejects arm64 libraries below 16 KB ELF alignment in the unsigned release APK."
    dependsOn("assembleRelease")
    inputs.file(unsignedReleaseApk)

    doLast {
        val apkFile = unsignedReleaseApk.get().asFile
        check(apkFile.isFile) { "Unsigned release APK not found at ${apkFile.absolutePath}" }
        verifyArm64ElfPageAlignment(apkFile)
    }
}

verifyReleaseApkPageAlignment.configure {
    dependsOn(verifyReleaseArm64ElfPageAlignment)
}

val verifyReleaseCandidateZipAlignment by tasks.registering(Exec::class) {
    group = "verification"
    description = "Verifies ZIP 16 KB alignment on the caller-supplied signed APK."
    inputs.property("releaseCandidateApk", releaseCandidateApkPath.orElse(""))
    outputs.upToDateWhen { false }

    doFirst {
        val apkFile = releaseCandidateApk.orNull
            ?: error("Set -Pgnbp.releaseCandidateApk to the exact signed APK")
        val zipalignFile = zipalignExecutable.get()
        check(zipalignFile.isFile) { "zipalign not found at ${zipalignFile.absolutePath}" }
        check(apkFile.isFile) { "Signed APK not found at ${apkFile.absolutePath}" }
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

val verifyReleaseCandidateElfAlignment by tasks.registering {
    group = "verification"
    description = "Verifies arm64 ELF LOAD alignment on the caller-supplied signed APK."
    inputs.property("releaseCandidateApk", releaseCandidateApkPath.orElse(""))
    outputs.upToDateWhen { false }

    doLast {
        val apkFile = releaseCandidateApk.orNull
            ?: error("Set -Pgnbp.releaseCandidateApk to the exact signed APK")
        check(apkFile.isFile) { "Signed APK not found at ${apkFile.absolutePath}" }
        verifyArm64ElfPageAlignment(apkFile)
    }
}

val verifyReleaseCandidateSignature by tasks.registering {
    group = "verification"
    description = "Verifies the signed APK and its expected public certificate SHA-256 digest."
    inputs.property("releaseCandidateApk", releaseCandidateApkPath.orElse(""))
    inputs.property("releaseCertificateSha256", releaseCertificateSha256.orElse(""))
    outputs.upToDateWhen { false }

    doLast {
        val apkFile = releaseCandidateApk.orNull
            ?: error("Set -Pgnbp.releaseCandidateApk to the exact signed APK")
        val expectedDigest = releaseCertificateSha256.orNull
            ?.normalizeSha256Digest()
            ?: error("Set -Pgnbp.releaseCertificateSha256 to the release certificate digest")
        val apksignerFile = apksignerExecutable.get()
        check(apksignerFile.isFile) { "apksigner not found at ${apksignerFile.absolutePath}" }
        check(apkFile.isFile) { "Signed APK not found at ${apkFile.absolutePath}" }

        val apksignerCommand = listOf(
            apksignerFile.absolutePath,
            "verify",
            "--verbose",
            "--print-certs",
            apkFile.absolutePath,
        )
        val processCommand = if (System.getProperty("os.name").startsWith("Windows")) {
            listOf("cmd", "/c") + apksignerCommand
        } else {
            apksignerCommand
        }
        val process = ProcessBuilder(processCommand).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { reader -> reader.readText() }
        val exitCode = process.waitFor()
        check(exitCode == 0) { "apksigner rejected the supplied APK (exit $exitCode)" }
        val actualDigests = Regex(
            "Signer #\\d+ certificate SHA-256 digest:\\s*([0-9a-fA-F:]+)",
        ).findAll(output).map { match ->
            match.groupValues[1].normalizeSha256Digest()
        }.toSet()
        check(actualDigests == setOf(expectedDigest)) {
            "The signed APK certificate set does not match the expected SHA-256 digest"
        }
        println("Verified release certificate SHA-256: $expectedDigest")
    }
}

val verifyReleaseCandidateManifest by tasks.registering {
    group = "verification"
    description = "Verifies the signed APK package, version, and non-debuggable release manifest."
    inputs.property("releaseCandidateApk", releaseCandidateApkPath.orElse(""))
    outputs.upToDateWhen { false }

    doLast {
        val apkFile = releaseCandidateApk.orNull
            ?: error("Set -Pgnbp.releaseCandidateApk to the exact signed APK")
        val aapt2File = aapt2Executable.get()
        check(aapt2File.isFile) { "aapt2 not found at ${aapt2File.absolutePath}" }
        check(apkFile.isFile) { "Signed APK not found at ${apkFile.absolutePath}" }

        val process = ProcessBuilder(
            aapt2File.absolutePath,
            "dump",
            "badging",
            apkFile.absolutePath,
        ).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { reader -> reader.readText() }
        val exitCode = process.waitFor()
        check(exitCode == 0) { "aapt2 rejected the supplied APK (exit $exitCode)" }
        val packageMatch = Regex(
            "package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'",
        ).find(output) ?: error("aapt2 output did not contain a package declaration")
        check(packageMatch.groupValues[1] == applicationIdValue) {
            "The candidate package does not match $applicationIdValue"
        }
        check(packageMatch.groupValues[2] == releaseVersionCodeValue.toString()) {
            "The candidate versionCode is not $releaseVersionCodeValue"
        }
        check(packageMatch.groupValues[3] == releaseVersionNameValue) {
            "The candidate versionName is not $releaseVersionNameValue"
        }
        check(output.lineSequence().none { line -> line.trim() == "application-debuggable" }) {
            "The release candidate must not be debuggable"
        }
    }
}

val verifyReleaseBuild by tasks.registering {
    group = "verification"
    description = "Runs CI-safe offline checks and validates the unsigned release APK."
    dependsOn(
        "check",
        "lintRelease",
        verifyReleaseApkPageAlignment,
        verifyReleaseArm64ElfPageAlignment,
    )
}

val verifyReleaseCandidate by tasks.registering {
    group = "verification"
    description = "Validates the exact signed Android release candidate without signing secrets."
    dependsOn(
        verifyReleaseCandidateZipAlignment,
        verifyReleaseCandidateElfAlignment,
        verifyReleaseCandidateSignature,
        verifyReleaseCandidateManifest,
    )
}

val releaseCandidateConflictingTaskNames = setOf(
    "assembleRelease",
    "bundleRelease",
    "packageRelease",
    "verifyReleaseBuild",
)

gradle.taskGraph.whenReady {
    if (hasTask(verifyReleaseCandidate.get())) {
        val conflictingTasks = allTasks.filter { task ->
            task.name in releaseCandidateConflictingTaskNames
        }
        check(conflictingTasks.isEmpty()) {
            "verifyReleaseCandidate must run by itself after signing; " +
                "the same invocation contains release-producing tasks: " +
                conflictingTasks.joinToString { task -> task.path }
        }
    }
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

fun verifyArm64ElfPageAlignment(apkFile: File) {
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

fun String.normalizeSha256Digest(): String {
    val normalized = trim().replace(":", "").replace(" ", "").lowercase()
    check(normalized.matches(Regex("[0-9a-f]{64}"))) {
        "Certificate SHA-256 digest must contain exactly 64 hexadecimal digits"
    }
    return normalized
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
