import java.util.Properties
import java.io.FileInputStream

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Function to read properties from local.properties
fun getApiKey(propertyKey: String): String {
    val properties = Properties()
    // Ensure rootProject points to the correct directory containing local.properties
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        try {
            properties.load(FileInputStream(localPropertiesFile))
            return properties.getProperty(propertyKey) ?: ""
        } catch (e: Exception) {
            println("Warning: Could not load local.properties file: ${e.message}")
        }
    }
    return "" // Return empty string if file or key not found
}

android {
    namespace = "com.example.timelinter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.timelinter"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true // Keep true if other buildConfig fields exist or might be added
    }

    lint {
        baseline = file("lint-baseline.xml")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.google.ai.client.generativeai)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.material.icons.extended)
    
    // Handlebars for proper templating
    implementation("com.github.jknack:handlebars:4.4.0")
    
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.ui.test.junit4)
    debugImplementation(libs.androidx.ui.tooling)
    debugImplementation(libs.androidx.ui.test.manifest)

    // AndroidX Security for EncryptedSharedPreferences
    implementation(libs.androidx.security.crypto)
}