plugins {
    alias(libs.plugins.android).apply(true) // Apply the Android plugin
    alias(libs.plugins.kotlinSerialization).apply(false)
    alias(libs.plugins.detekt).apply(false)
}

android {
    compileSdk = 34 // Specify the compileSdk version

    defaultConfig {
        applicationId = project.property("APP_ID").toString()
    }

    kotlin {
        namespace = "com.novadial.phone"
    }

    sourceSets {
        main {
            manifest.srcFile("src/main/AndroidManifest.xml")
        }
    }
}
