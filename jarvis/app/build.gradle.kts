import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

/**
 * sherpa-onnx (synteza mowy Piper) nie jest w repozytorium Maven – AAR pobieramy z GitHuba
 * przy pierwszym budowaniu do app/libs. Wycinamy z niego libonnxruntime.so, bo aplikacja
 * ma już pełny ONNX Runtime z com.microsoft.onnxruntime. Wersje muszą do siebie pasować:
 * sherpa-onnx 1.13.0 wymaga symboli VERS_1.24.3, a onnxruntime-android 1.24.3 dokładnie je udostępnia.
 */
fun sherpaOnnxAar(): File {
    val version = "1.13.0"
    val aar = file("libs/sherpa-onnx-$version-bez-onnxruntime.aar")
    if (aar.exists()) return aar
    aar.parentFile.mkdirs()
    val url = uri("https://github.com/k2-fsa/sherpa-onnx/releases/download/v$version/sherpa-onnx-$version.aar").toURL()
    val tmp = File(aar.path + ".tmp")
    ZipInputStream(url.openStream().buffered()).use { input ->
        ZipOutputStream(tmp.outputStream().buffered()).use { output ->
            while (true) {
                val entry = input.nextEntry ?: break
                if (entry.name.endsWith("libonnxruntime.so")) continue
                output.putNextEntry(ZipEntry(entry.name))
                input.copyTo(output)
                output.closeEntry()
            }
        }
    }
    check(tmp.renameTo(aar)) { "Nie udało się zapisać ${aar.path}" }
    return aar
}

android {
    namespace = "pl.jarvis"
    compileSdk = 36

    defaultConfig {
        applicationId = "pl.jarvis"
        minSdk = 26
        targetSdk = 36
        versionCode = 39
        versionName = "0.39"
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
    // Wykrywanie „Hey Jarvis” (openWakeWord) – modele ONNX w assets/wakeword.
    // Wersja 1.24.3 – dokładnie ta, z którą zbudowano sherpa-onnx 1.13.0 (głos Piper).
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.24.3")
    // Głos Piper (sherpa-onnx) i rozpakowywanie pobranego głosu.
    implementation(files(sherpaOnnxAar()))
    implementation("org.apache.commons:commons-compress:1.27.1")
    // Zapytania do API zgodnych z OpenAI (np. Kimi).
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
