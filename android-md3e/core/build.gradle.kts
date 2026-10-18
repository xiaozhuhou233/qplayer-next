plugins { `java-library` }
java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}
// Compile the workspace's actual core, without replacing its Maven snapshot or outputs.
sourceSets {
    main {
        java.setSrcDirs(listOf("../../player-core/src/main/java"))
        resources.setSrcDirs(listOf("../../player-core/src/main/resources"))
    }
    test { java.setSrcDirs(listOf("../../player-core/src/test/java")) }
}
tasks.withType<JavaCompile>().configureEach { options.encoding = "UTF-8" }
dependencies {
    api("io.github.timer-err:qml4j-core:0.2.31")
    api("io.github.humbleui:skija-shared:0.143.17")
    api("com.google.code.gson:gson:2.10.1")
    api("org.tukaani:xz:1.12")
    api("com.google.zxing:core:3.5.3")
    testImplementation("junit:junit:4.13.2")
}
