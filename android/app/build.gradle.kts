plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.compose.compiler)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "io.github.ayaseminami.gnbp"
    compileSdk = 37

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
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.okhttp)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.okhttp.tls)
}

val verifyNetworkChokepoint by tasks.registering {
    group = "verification"
    description = "Rejects production network clients outside the provider transport module."

    val sourceRoot = layout.projectDirectory.dir("src/main/java")
    inputs.dir(sourceRoot)

    doLast {
        val constructionPatterns = listOf(
            "OkHttpClient.Builder(",
            "SSLSocketFactory",
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

tasks.named("preBuild").configure {
    dependsOn(verifyNetworkChokepoint)
}
