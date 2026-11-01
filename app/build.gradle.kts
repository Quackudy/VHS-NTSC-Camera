plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "dev.vhs.proof"
    compileSdk = 35
    ndkVersion = "28.2.13676358"
    defaultConfig {
        applicationId = "dev.vhs.proof"
        minSdk = 29
        targetSdk = 35
        versionCode = 14
        versionName = "0.6.5-frame-edges"
        testInstrumentationRunner = "dev.vhs.proof.ProofInstrumentation"
        ndk { abiFilters += "arm64-v8a" }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

val buildNative by tasks.registering(Exec::class) {
    workingDir(rootDir)
    commandLine("bash", "scripts/build-native.sh")
    // Always run Cargo: it performs its own dependency/freshness checks.
}
tasks.named("preBuild") { dependsOn(buildNative) }
