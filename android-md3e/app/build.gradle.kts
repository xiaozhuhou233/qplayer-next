plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
// Since Kotlin 1.8 these compatibility artifacts are merged into kotlin-stdlib.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}
// Compile only the old iOS chrome and its vendored glass renderer, never the legacy app.
val stageIosChrome by tasks.registering(Sync::class) {
    from("../../android-shell/app/src/main/java") {
        include("com/kyant/backdrop/**")
        include("dev/t1m3/qplayer/android/ui/IosDesignTheme.kt")
        include("dev/t1m3/qplayer/android/ui/IosDesignNavigation.kt")
        include("dev/t1m3/qplayer/android/ui/IosPlayerDock.kt")
        include("dev/t1m3/qplayer/android/ui/IosPlayerControls.kt")
        include("dev/t1m3/qplayer/android/ui/IosControls.kt")
        include("dev/t1m3/qplayer/android/ui/IosDialogs.kt")
        include("dev/t1m3/qplayer/android/ui/IosLiquidGlass.kt")
        include("dev/t1m3/qplayer/android/ui/IosGlassPalette.kt")
        include("dev/t1m3/qplayer/android/ui/IosGlassInteraction.kt")
        include("dev/t1m3/qplayer/android/ui/IosAdaptiveGlass.kt")
        include("dev/t1m3/qplayer/android/ui/IosLiquidSearchButton.kt")
        include("dev/t1m3/qplayer/android/ui/RestoredIosDialogGlass.kt")
        include("dev/t1m3/qplayer/android/ui/GlassSamplingPolicy.kt")
        include("dev/t1m3/qplayer/android/ui/AndroidGlassColorModel.kt")
        include("dev/t1m3/qplayer/android/ui/BiliPaiGlassParameters.kt")
        include("dev/t1m3/qplayer/android/ui/BiliPaiGlassHighlight.kt")
        include("dev/t1m3/qplayer/android/ui/BiliPaiDeviceTilt.kt")
        include("dev/t1m3/qplayer/android/ui/SystemBarAppearance.kt")
        include("dev/t1m3/qplayer/android/transfer/AccountMigration.kt")
    }
    into(layout.buildDirectory.dir("generated/iosChrome"))
}
tasks.named("preBuild") { dependsOn(stageIosChrome) }
android {
    namespace = "dev.t1m3.qplayer.android.md3eui"
    compileSdk = 35
    buildToolsVersion = "35.0.0"
    defaultConfig {
        applicationId = "dev.t1m3.qplayer.md3e"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0-md3e"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions { jvmTarget = "1.8" }
    buildFeatures { compose = true }
    sourceSets["main"].java.srcDir(layout.buildDirectory.dir("generated/iosChrome"))
    // Release credentials come from the environment, never from this tracked
    // file -- same QPLAYER_* contract android-shell/app/build.gradle.kts uses.
    // Without them the release build stays unsigned rather than silently
    // falling back to the debug key (which would ship a debug-signed APK).
    val releaseKeystore = System.getenv("QPLAYER_KEYSTORE")
    val hasReleaseSigning = releaseKeystore != null && file(releaseKeystore).exists()

    signingConfigs {
        if (hasReleaseSigning) create("release") {
            storeFile = file(releaseKeystore!!)
            storePassword = System.getenv("QPLAYER_STORE_PASSWORD")
            keyAlias = System.getenv("QPLAYER_KEY_ALIAS")
            // PKCS12 stores usually reuse the store password for the key.
            keyPassword = System.getenv("QPLAYER_KEY_PASSWORD")
                ?: System.getenv("QPLAYER_STORE_PASSWORD")
        }
        val existing = rootProject.file("../android-shell/local-debug.keystore")
        if (existing.exists()) create("workspaceDebug") {
            storeFile = existing
            storePassword = "qplayer-debug"
            keyAlias = "qplayer-debug"
            keyPassword = "qplayer-debug"
        }
    }
    buildTypes.named("debug") {
        signingConfigs.findByName("workspaceDebug")?.let { signingConfig = it }
    }
    buildTypes.named("release") {
        isDebuggable = false
        if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
    }
    packaging.resources.excludes += setOf("META-INF/AL2.0", "META-INF/LGPL2.1", "META-INF/*.kotlin_module")
}
dependencies {
    implementation(project(":platform"))
    implementation(platform("androidx.compose:compose-bom:2025.05.00"))
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.graphics:graphics-shapes:1.0.1")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.material3:material3:1.4.0-alpha18")
    implementation("androidx.media:media:1.7.0")
    implementation("sh.calvin.reorderable:reorderable:2.2.0")
}
