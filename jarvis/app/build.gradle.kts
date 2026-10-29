import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "pl.jarvis"
    compileSdk = 36

    defaultConfig {
        applicationId = "pl.jarvis"
        minSdk = 29
        targetSdk = 36
        versionCode = 6
        versionName = "0.6"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            // Biblioteka Claude (Jackson, OkHttp itd.) wnosi zdublowane pliki licencji.
            excludes += setOf(
                "META-INF/DEPENDENCIES",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/INDEX.LIST",
                "META-INF/*.md",
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "/META-INF/{AL2.0,LGPL2.1}",
            )
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.06.00")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("com.anthropic:anthropic-java:3.3.0")
    // Ta sama wersja co kompilator Kotlina (biblioteka Claude ciągnie starszą przez Jacksona).
    implementation("org.jetbrains.kotlin:kotlin-reflect:2.2.20")
    implementation("ai.picovoice:porcupine-android:4.0.2")
}
