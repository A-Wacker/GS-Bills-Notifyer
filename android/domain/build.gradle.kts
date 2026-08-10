import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.jvm)
}

// Pure Kotlin/JVM: no Android dependencies, so these tests run on any JDK 17+ without
// the Android SDK. Bytecode is pinned to 17 (rather than pinned via a toolchain) so the
// :app module can consume this directly while still building on a newer local JDK.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(17)
}

dependencies {
    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

tasks.test {
    useJUnitPlatform()
    // Lets SheetSchemaContractTest find appsscript/Common.gs regardless of working directory.
    systemProperty("repoRoot", rootProject.projectDir.parentFile.absolutePath)
    testLogging {
        events("passed", "failed", "skipped")
    }
}
