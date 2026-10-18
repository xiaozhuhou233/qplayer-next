plugins { id("com.android.library") }
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk7")
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib-jdk8")
}

// AGP source-directory discovery does not preserve SourceDirectorySet include filters.
// Stage byte-identical selected files under build/, never maintain a second source fork.
val stagePlatform by tasks.registering(Sync::class) {
    from("../../android-shell/app/src/main/java") {
        include("dev/t1m3/qplayer/android/app/QPlayerApplication.java")
        include("dev/t1m3/qplayer/android/security/AndroidKeystoreKeyProtector.java")
        include("dev/t1m3/qplayer/android/settings/PrefsSettingsStore.java")
        include("dev/t1m3/qplayer/android/library/AndroidMetadataReader.java")
        include("dev/t1m3/qplayer/android/library/AndroidLibraryScanner.java")
        include("dev/t1m3/qplayer/android/graphics/AndroidColorExtractor.java")
        include("dev/t1m3/qplayer/android/playback/AndroidAudioBackend.java")
        include("dev/t1m3/qplayer/android/playback/AndroidAutoMixDecoder.java")
        include("dev/t1m3/qplayer/android/playback/PlaybackService.java")
        include("dev/t1m3/qplayer/android/playback/PlaybackArtworkLoader.java")
    }
    into(layout.buildDirectory.dir("generated/platformJava"))
}
android {
    namespace = "dev.t1m3.qplayer.android.md3eui.platform"
    compileSdk = 35
    defaultConfig { minSdk = 26 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    sourceSets["main"].java.setSrcDirs(listOf(layout.buildDirectory.dir("generated/platformJava")))
}
tasks.named("preBuild") { dependsOn(stagePlatform) }
dependencies {
    api(project(":core"))
    implementation("androidx.core:core:1.13.1")
    implementation("androidx.media:media:1.7.0")
    // Keep platform compile dependencies aligned with the Compose app's versions.
    implementation("androidx.collection:collection:1.5.0")
    implementation("androidx.annotation:annotation:1.9.1")
    implementation("androidx.profileinstaller:profileinstaller:1.4.0")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
}
