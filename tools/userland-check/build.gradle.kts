plugins {
    alias(libs.plugins.android.application)
}

val userland = project(":userland")
val runtimeBuild = userland.layout.buildDirectory.dir("userland").get()

android {
    namespace = "dev.lusalhi.lumynoid.userland.checks"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.lusalhi.lumynoid.userland.checks"
        minSdk = 29
        targetSdk = 37
        versionCode = 3
        versionName = "2.7.1"
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs.getByName("debug") {
        storeFile = rootProject.file("app/debug.keystore")
        storePassword = "android"
        keyAlias = "androiddebugkey"
        keyPassword = "android"
    }

    sourceSets["main"].apply {
        kotlin.directories += userland.file("src/main/kotlin").absolutePath
        kotlin.directories += userland.file("src/checks/kotlin").absolutePath
        assets.directories += userland.file("src/main/assets").absolutePath
        assets.directories += runtimeBuild.dir("test-assets").asFile.absolutePath
        jniLibs.directories += runtimeBuild.dir("native/jni").asFile.absolutePath
    }

    packaging.jniLibs {
        useLegacyPackaging = true
        keepDebugSymbols += setOf("**/libproot.so", "**/libproot_loader.so", "**/libuserland_supervisor.so")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

androidComponents.beforeVariants { variant ->
    if (variant.buildType != "debug") variant.enable = false
}

tasks.matching { it.name == "mergeDebugAssets" }.configureEach { dependsOn(":userland:stageUserlandTestAssets") }
tasks.matching { it.name == "mergeDebugJniLibFolders" }.configureEach { dependsOn(":userland:buildUserlandNative") }

kotlin { jvmToolchain(17) }

dependencies {
    implementation(project(":core"))
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.ktor.client.core)
    implementation(libs.junit)
}
