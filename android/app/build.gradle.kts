plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

/**
 * Reads a value from local.properties, falling back to an environment variable.
 *
 * The web app URL and shared secret live there (gitignored) rather than in source. Worth
 * being clear-eyed about what this does and doesn't buy: anyone holding the APK can read
 * both out of it. It keeps them out of version control, nothing more. That is an
 * acceptable trade for a private two-person app whose sheet holds bill amounts rather than
 * credentials — see docs/SETUP.md.
 */
fun localProperty(name: String, fallback: String = ""): String {
    val properties = java.util.Properties()
    val file = rootProject.file("local.properties")
    if (file.exists()) file.inputStream().use(properties::load)
    return properties.getProperty(name) ?: System.getenv(name) ?: fallback
}

android {
    namespace = "com.awacker.billsnotifier"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.awacker.billsnotifier"
        minSdk = 26 // java.time without desugaring
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "WEBAPP_URL", "\"${localProperty("WEBAPP_URL")}\"")
        buildConfigField("String", "SHARED_SECRET", "\"${localProperty("SHARED_SECRET")}\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

// Lets Room's schema export land somewhere useful for future migrations.
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons.extended)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.work.runtime.ktx)
    implementation(libs.datastore.preferences)
    implementation(libs.okhttp)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.coroutines.test)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.room.testing)
    androidTestImplementation(libs.work.testing)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
