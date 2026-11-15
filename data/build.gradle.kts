plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.ksp)
    alias(libs.plugins.room)
}

android {
    namespace = "dev.lusalhi.lumynoid.data"
    compileSdk = 37

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

room3 {
    schemaDirectory("$projectDir/schemas")
}

dependencies {
    api(project(":core"))
    implementation(libs.room.runtime)
    implementation(libs.sqlite.framework)
    ksp(libs.room.compiler)

    // Host-JVM persistence tests run Room on the bundled SQLite; never part of the app.
    testImplementation(kotlin("test"))
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.sqlite.bundled.jvm)
}
