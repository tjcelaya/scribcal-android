// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.navigation.safeargs.kotlin) apply false
    // Applied by :app only when scribcal.appfunctions=true (see app/build.gradle.kts).
    alias(libs.plugins.ksp) apply false
}
