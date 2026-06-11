import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jetbrains.kotlin.konan.properties.Properties
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

plugins {
    alias(libs.plugins.android)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.detekt)
    id("org.jetbrains.kotlin.plugin.compose") version libs.versions.kotlin.get()
}

val keystorePropertiesFile: File = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

fun hasSigningVars(): Boolean {
    return providers.environmentVariable("SIGNING_KEY_ALIAS").orNull != null
            && providers.environmentVariable("SIGNING_KEY_PASSWORD").orNull != null
            && providers.environmentVariable("SIGNING_STORE_FILE").orNull != null
            && providers.environmentVariable("SIGNING_STORE_PASSWORD").orNull != null
}

base {
    val versionCode = project.property("VERSION_CODE").toString().toInt()
    archivesName = "phone-$versionCode"
}

android {
    compileSdk = project.libs.versions.app.build.compileSDKVersion.get().toInt()

    defaultConfig {
        applicationId = project.property("APP_ID").toString()
        minSdk = project.libs.versions.app.build.minimumSDK.get().toInt()
        targetSdk = project.libs.versions.app.build.targetSDK.get().toInt()
        versionName = project.property("VERSION_NAME").toString()
        versionCode = project.property("VERSION_CODE").toString().toInt()
    }

    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            register("release") {
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
            }
        } else if (hasSigningVars()) {
            register("release") {
                keyAlias = providers.environmentVariable("SIGNING_KEY_ALIAS").get()
                keyPassword = providers.environmentVariable("SIGNING_KEY_PASSWORD").get()
                storeFile = file(providers.environmentVariable("SIGNING_STORE_FILE").get())
                storePassword = providers.environmentVariable("SIGNING_STORE_PASSWORD").get()
            }
        } else {
            logger.warn("Warning: No signing config found. Build will be unsigned.")
        }
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
        compose = true
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (keystorePropertiesFile.exists() || hasSigningVars()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    flavorDimensions.add("variants")
    productFlavors {
        register("core")
        register("foss")
        register("gplay")
    }

    sourceSets {
        getByName("main").java.directories.add("src/main/kotlin")
    }

    compileOptions {
        val currentJavaVersionFromLibs =
            JavaVersion.valueOf(libs.versions.app.build.javaVersion.get())
        sourceCompatibility = currentJavaVersionFromLibs
        targetCompatibility = currentJavaVersionFromLibs
    }

    dependenciesInfo {
        includeInApk = false
    }

    androidResources {
        @Suppress("UnstableApiUsage")
        generateLocaleConfig = true
    }

    tasks.withType<KotlinCompile> {
        compilerOptions.jvmTarget.set(
            JvmTarget.fromTarget(project.libs.versions.app.build.kotlinJVMTarget.get())
        )
    }

    namespace = project.property("APP_ID").toString()

    lint {
        checkReleaseBuilds = false
        abortOnError = true
        warningsAsErrors = false
        baseline = file("lint-baseline.xml")
        lintConfig = rootProject.file("lint.xml")
    }

    bundle {
        language {
            enableSplit = false
        }
    }
}

detekt {
    baseline = file("detekt-baseline.xml")
    config.setFrom("$rootDir/detekt.yml")
    buildUponDefaultConfig = true
    allRules = false
}

dependencies {
    implementation(libs.fossify.commons)
    implementation(libs.indicator.fast.scroll)
    implementation(libs.autofit.text.view)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.eventbus)
    implementation(libs.libphonenumber)
    implementation(libs.geocoder)
    implementation("com.google.zxing:core:3.5.3")
    detektPlugins(libs.compose.detekt)

    // Jetpack Compose dependencies for custom About page compilation
    implementation(platform("androidx.compose:compose-bom:2024.04.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.runtime:runtime")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.ui:ui-viewbinding")
    implementation("androidx.activity:activity-compose:1.9.0")
}

// ---------------------------------------------------------------------------
// Patch fossify-commons: strip LineColorPickerDialog duplicate classes.
// Scans both the source AAR and all jetified transform JARs in the Gradle
// caches directory. Runs before compile/minify so R8 never sees duplicates.
// ---------------------------------------------------------------------------
fun stripLineColorPickerClasses(inputJar: File, outputJar: File) {
    ZipFile(inputJar).use { zip ->
        ZipOutputStream(FileOutputStream(outputJar)).use { zos ->
            zip.entries().asSequence().forEach { entry ->
                val skip = entry.name.startsWith(
                    "org/fossify/commons/dialogs/LineColorPickerDialog"
                ) || entry.name.contains("ComposableSingletons\$LineColorPickerDialog")
                if (!skip) {
                    zos.putNextEntry(ZipEntry(entry.name))
                    zip.getInputStream(entry).use { it.copyTo(zos) }
                    zos.closeEntry()
                }
            }
        }
    }
}

fun patchJar(file: File) {
    val tmp = File.createTempFile("strip-commons", ".jar")
    try {
        stripLineColorPickerClasses(file, tmp)
        tmp.copyTo(file, overwrite = true)
        println("[PatchCommons] Patched: ${file.name}")
    } finally {
        tmp.delete()
    }
}

fun patchAar(file: File) {
    // Unpack AAR → patch classes.jar → repack AAR
    val tempDir = File(file.parentFile, "tmp-aar-${file.nameWithoutExtension}")
    tempDir.deleteRecursively(); tempDir.mkdirs()
    try {
        ZipFile(file).use { zip ->
            zip.entries().asSequence().forEach { entry ->
                val out = File(tempDir, entry.name)
                if (entry.isDirectory) out.mkdirs()
                else { out.parentFile.mkdirs(); zip.getInputStream(entry).use { inp -> out.outputStream().use { inp.copyTo(it) } } }
            }
        }
        val classesJar = File(tempDir, "classes.jar")
        if (classesJar.exists()) {
            val tmp = File.createTempFile("strip-classes", ".jar")
            try {
                stripLineColorPickerClasses(classesJar, tmp)
                tmp.copyTo(classesJar, overwrite = true)
            } finally { tmp.delete() }
        }
        val tmpAar = File(file.parentFile, "tmp-${file.name}")
        ZipOutputStream(FileOutputStream(tmpAar)).use { zos ->
            tempDir.walkTopDown().filter { it.isFile }.forEach { f ->
                zos.putNextEntry(ZipEntry(f.relativeTo(tempDir).path.replace('\\', '/')))
                f.inputStream().use { it.copyTo(zos) }; zos.closeEntry()
            }
        }
        tmpAar.copyTo(file, overwrite = true); tmpAar.delete()
        println("[PatchCommons] Patched AAR: ${file.name}")
    } finally {
        tempDir.deleteRecursively()
    }
}

tasks.register("patchFossifyCommonsJar") {
    doLast {
        val targets = mutableListOf<File>()
        // 1. Source AAR in modules-2 cache
        configurations.toList().forEach { cfg ->
            if (cfg.isCanBeResolved) {
                runCatching {
                    cfg.resolvedConfiguration.resolvedArtifacts.forEach { a ->
                        if (a.file.name.contains("commons") && a.file.name.contains("6.1.6"))
                            targets += a.file
                    }
                }
            }
        }
        // 2. Jetified transforms in Gradle version cache
        File(gradle.gradleUserHomeDir, "caches").walkTopDown().maxDepth(7).forEach { f ->
            if (f.isFile && f.name.contains("commons-6.1.6") &&
                (f.name.endsWith(".jar") || f.name.endsWith(".aar"))) targets += f
        }
        targets.distinct().forEach { file ->
            runCatching {
                if (file.name.endsWith(".aar")) patchAar(file) else patchJar(file)
            }.onFailure { println("[PatchCommons] Skipped ${file.name}: ${it.message}") }
        }
    }
}

tasks.configureEach {
    if (name.contains("Release") &&
        (name.startsWith("compile") || name.startsWith("minify") ||
         name.startsWith("merge") || name.startsWith("dex"))) {
        dependsOn("patchFossifyCommonsJar")
    }
}
