plugins {
    alias(libs.plugins.android.library)
}

abstract class BuildUserlandNative : Exec() {
    @get:OutputDirectory
    abstract val jniDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val sourceDirectory: DirectoryProperty
}

val tools = rootProject.layout.projectDirectory.dir("tools/userland")
val nativeOutput = layout.buildDirectory.dir("userland/native")
val rootfsOutput = layout.buildDirectory.dir("userland/rootfs")
val testAssets = layout.buildDirectory.dir("userland/test-assets")
val metadata = layout.projectDirectory.file("src/main/assets/lumynoid-userland.properties")
val sdk = androidComponents.sdkComponents.sdkDirectory

val buildUserlandNative = tasks.register<BuildUserlandNative>("buildUserlandNative") {
    inputs.files(
        tools.file("build-native.sh"), tools.file("proot-exitkill.patch"), tools.file("supervisor.c"),
        rootProject.layout.projectDirectory.file("tools/userland-spike/talloc-cross-answers.txt"),
    )
    jniDirectory.set(nativeOutput.map { it.dir("jni") })
    sourceDirectory.set(nativeOutput.map { it.dir("sources") })
    commandLine("bash", tools.file("build-native.sh").asFile, nativeOutput.get().asFile, sdk.get().asFile)
}

val buildUserlandRootfs = tasks.register<Exec>("buildUserlandRootfs") {
    inputs.files(
        tools.file("build-rootfs.sh"), tools.file("alpine-inputs.lock"),
        tools.file("alpine-source-provenance.tsv"), metadata,
        tools.file("alpine-inputs.tar"),
    )
    outputs.dir(rootfsOutput)
    commandLine("bash", tools.file("build-rootfs.sh").asFile, rootfsOutput.get().asFile)
}

val stageUserlandTestAssets = tasks.register<Sync>("stageUserlandTestAssets") {
    val fixtureSuffix = ".fixture"
    inputs.property("fixtureSuffix", fixtureSuffix)
    dependsOn(buildUserlandRootfs)
    from(rootfsOutput) {
        include("*.tar.gz")
        // AAPT unpacks assets ending in .gz. Keep the original bytes for digest verification.
        rename { "$it$fixtureSuffix" }
    }
    into(testAssets)
}

android {
    namespace = "dev.lusalhi.lumynoid.userland"
    compileSdk = 37
    ndkVersion = "28.2.13676358"

    defaultConfig {
        minSdk = 29
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    testOptions {
        targetSdk = 37
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging.jniLibs {
        useLegacyPackaging = true
        keepDebugSymbols += setOf("**/libproot.so", "**/libproot_loader.so", "**/libuserland_supervisor.so")
    }

    sourceSets["androidTest"].assets.srcDir(testAssets.get().asFile)
    sourceSets["androidTest"].kotlin.directories += "src/checks/kotlin"
}

tasks.matching { it.name == "mergeDebugAndroidTestAssets" }.configureEach { dependsOn(stageUserlandTestAssets) }

androidComponents.onVariants { variant ->
    variant.sources.jniLibs?.addGeneratedSourceDirectory(buildUserlandNative, BuildUserlandNative::jniDirectory)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(libs.ktor.client.core)
    testImplementation(libs.junit)
    androidTestImplementation(libs.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
