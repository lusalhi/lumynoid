plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val releaseSigning = listOf(
    "LUMYNOID_KEYSTORE_PATH",
    "LUMYNOID_KEYSTORE_PASSWORD",
    "LUMYNOID_KEY_ALIAS",
    "LUMYNOID_KEY_PASSWORD",
).associateWith { System.getenv(it) }

android {
    namespace = "dev.lusalhi.lumynoid.app"
    compileSdk = 37

    defaultConfig {
        // The only place the literal application id appears (AD-25).
        applicationId = "dev.lusalhi.lumynoid"
        minSdk = 29
        targetSdk = 37
        versionCode = 2
        versionName = "v0.2.0"
    }

    signingConfigs {
        // Shared, committed debug key: debug APKs from CI and from every dev machine can update each other.
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
        // Release key (AD-25): only present when the release workflow (or its owner) provides all four values.
        // Without them the release build stays unsigned, which is what local and CI builds want.
        if (releaseSigning.values.all { !it.isNullOrBlank() }) {
            create("release") {
                storeFile = file(releaseSigning.getValue("LUMYNOID_KEYSTORE_PATH")!!)
                storePassword = releaseSigning["LUMYNOID_KEYSTORE_PASSWORD"]
                keyAlias = releaseSigning["LUMYNOID_KEY_ALIAS"]
                keyPassword = releaseSigning["LUMYNOID_KEY_PASSWORD"]
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            signingConfigs.findByName("release")?.let { signingConfig = it }
            // AD-25: the release APK is arm64-v8a only; debug keeps every ABI for emulators.
            ndk {
                abiFilters += "arm64-v8a"
            }
        }
    }

    dependenciesInfo {
        includeInApk = false
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":llm"))
    implementation(project(":data"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.core)
    implementation(libs.ktor.client.okhttp)

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.foundation)
    implementation(libs.compose.material3)
    implementation(libs.markdown.renderer)
    implementation(libs.markdown.renderer.m3)

    testImplementation(libs.junit)
}

// AD-17: the release classpath gate.
apply(from = rootProject.file("gradle/dependency-policy.gradle.kts"))
