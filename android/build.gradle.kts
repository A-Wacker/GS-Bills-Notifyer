// Intentionally empty.
//
// Plugin versions come from gradle/libs.versions.toml, and each module applies what it
// needs via `alias(...)`. Declaring the Android plugins here with `apply false` would make
// Gradle resolve the AGP artifact even for a `:domain:test` run on a machine with no
// Android SDK and no access to Google's Maven repository — which defeats the point of
// keeping the domain module free of Android.
