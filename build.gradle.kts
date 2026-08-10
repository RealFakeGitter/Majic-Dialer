plugins {
    alias(libs.plugins.android).apply(true) // Apply the Android plugin
    alias(libs.plugins.kotlinSerialization).apply(false)
    alias(libs.plugins.detekt).apply(false)
}

android {
    // Other Android configurations...

    defaultConfig {
        // Other default configurations...

        // Set the application ID
        applicationId = project.property("APP_ID").toString()
    }

    // Force the namespace to stay the old one
    kotlin {
        namespace = "com.novadial.phone"
    }
}
