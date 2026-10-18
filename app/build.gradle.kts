import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "kiwi.lorenz.pdfreader"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "kiwi.lorenz.pdfreader"
        minSdk = 35
        targetSdk = 37
        // A higher versionCode pushed to main triggers a release; see the README
        val versionProperties = Properties().apply {
            rootProject.file("version.properties").inputStream().use { load(it) }
        }
        versionCode = versionProperties.getProperty("versionCode").toInt()
        versionName = versionProperties.getProperty("versionName")
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    signingConfigs {
        if (keystorePropertiesFile.exists()) {
            val keystoreProperties = Properties().apply {
                keystorePropertiesFile.inputStream().use { load(it) }
            }
            create("release") {
                storeFile = rootProject.file(keystoreProperties.getProperty("storeFile"))
                storePassword = keystoreProperties.getProperty("storePassword")
                keyAlias = keystoreProperties.getProperty("keyAlias")
                keyPassword = keystoreProperties.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release")
            optimization {
                enable = true
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.pdf.compose)
    implementation(libs.androidx.pdf.document.service)
    // Only for the XML theme: the PDF viewer opens Material dialogs (see themes.xml)
    implementation(libs.material)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    debugImplementation(libs.androidx.compose.ui.tooling)
}