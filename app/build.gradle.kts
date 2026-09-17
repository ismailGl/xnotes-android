import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Release signing is read from a gitignored keystore.properties when present.
// F-Droid builds without that file, producing an unsigned APK it verifies against
// the published (signed) binary for reproducible builds.
val keystorePropertiesFile = rootProject.file("keystore.properties")
val hasReleaseSigning = keystorePropertiesFile.exists()
val keystoreProperties = Properties().apply {
    if (hasReleaseSigning) keystorePropertiesFile.inputStream().use { load(it) }
}

// Optional local development verifier. Release builds never receive credentials.
val verifierLocal = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun verifierSetting(name: String, fallback: String = ""): String =
    providers.gradleProperty(name).orNull ?: providers.environmentVariable(name).orNull
        ?: verifierLocal.getProperty(name) ?: fallback
fun javaLiteral(value: String): String = "\"" + value.flatMap { c ->
    when (c) {
        '\\' -> "\\\\".toList()
        '"' -> "\\\"".toList()
        else -> if (c.code < 32 || c.code > 126) "\\u%04x".format(c.code).toList() else listOf(c)
    }
}.joinToString("") + "\""

android {
    namespace = "com.xnotes"
    compileSdk = 36
    // Pinned toolchain for the vendored tree-sitter build (F-Droid reproducibility).
    ndkVersion = "27.0.12077973"

    defaultConfig {
        applicationId = "com.xnotes"
        buildConfigField("String", "GEMINI_API_KEY", "\"\"")
        buildConfigField("String", "GEMINI_MODEL", javaLiteral(verifierSetting("GEMINI_MODEL", "gemini-2.5-flash")))
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        minSdk = 26
        targetSdk = 36
        versionCode = 54
        versionName = "0.8.17"
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    packaging {
        jniLibs {
            // The tree-sitter parser tables gzip ~6x: compressed packaging keeps the
            // APK at ~12MB instead of ~30MB, at the cost of extract-on-install.
            useLegacyPackaging = true
        }
    }

    // F-Droid rejects the AGP dependency-metadata block in the APK signing block.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "GEMINI_API_KEY", javaLiteral(verifierSetting("GEMINI_API_KEY").trim()))
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            if (hasReleaseSigning) signingConfig = signingConfigs.getByName("release")
            // R8 shrinks/optimises release builds (see proguard-rules.pro for the few keeps). R8
            // output is reproducible (pinned AGP fixes the R8 version + mapping); the one
            // non-reproducible artefact, the baseline profile, is dropped below (see ArtProfile).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// F-Droid runs verified builds: its from-source APK must byte-match the released one. AGP's
// auto-merged baseline profile (assets/dexopt/baseline.prof, built from the Compose/AndroidX
// library profiles) is not reproducible across build hosts, so omit it from release builds.
// Tradeoff: slightly slower first-run startup (no ART pre-warm profile).
tasks.configureEach {
    if (name.contains("ArtProfile")) enabled = false
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.pdfbox.android)
    // Bundled Latin model: available on first launch, including offline Turkish OCR.
    implementation("com.google.mlkit:text-recognition:16.0.1")
    implementation(libs.androidsvg)
    debugImplementation(libs.androidx.ui.tooling)

    androidTestImplementation("androidx.test:runner:1.6.2")
    androidTestImplementation(libs.junit)
    testImplementation(libs.junit)
    testImplementation(libs.json)
}
