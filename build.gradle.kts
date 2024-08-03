buildscript {
    dependencies {
        classpath(libs.google.services)

    }
}
// Top-level build file where you can add configuration options common to all sub-projects/modules.
plugins {
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.jetbrainsKotlinAndroid) apply false
    id("com.google.gms.google-services") version "4.3.15" apply false
    // Add the Google services Gradle plugin

}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "com.google.api-client") {
            useVersion("1.31.1")
        }
    }
}