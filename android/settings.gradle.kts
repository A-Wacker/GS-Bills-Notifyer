pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "GS-Bills-Notifyer"

// :domain is pure Kotlin/JVM — it has no Android dependencies and its tests run
// anywhere a JDK exists. All of the schedule math lives there deliberately.
include(":domain")

// :app needs the Android SDK. The Android Gradle Plugin resolves the SDK during
// *configuration*, so merely having :app in the build makes every task — including
// `:domain:test` — fail on a machine without one. Including it conditionally keeps the
// domain tests runnable in CI and in sandboxes that can't fetch the SDK.
//
// Point it at an SDK the usual way (either is enough):
//   - export ANDROID_HOME=/path/to/Android/sdk
//   - echo "sdk.dir=/path/to/Android/sdk" >> android/local.properties
val androidSdkDir: String? = sequenceOf(
    System.getenv("ANDROID_HOME"),
    System.getenv("ANDROID_SDK_ROOT"),
    file("local.properties")
        .takeIf { it.isFile }
        ?.let { file -> java.util.Properties().apply { file.inputStream().use(::load) } }
        ?.getProperty("sdk.dir"),
).filterNotNull().firstOrNull { it.isNotBlank() && file(it).isDirectory }

if (androidSdkDir != null) {
    include(":app")
} else if (file("app").isDirectory) {
    logger.lifecycle(
        """
        |
        |  NOTE: skipping the :app module — no Android SDK found.
        |        Set ANDROID_HOME or add sdk.dir to android/local.properties to build the app.
        |        The :domain module and its tests are unaffected.
        |
        """.trimMargin(),
    )
}
