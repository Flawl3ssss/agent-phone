import java.time.Duration
import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "dev.kimiterminal"
    compileSdk = 35
    // В SDK стоит только 35.0.0; AGP 8.7 по умолчанию просит 34.0.0.
    buildToolsVersion = "35.0.0"

    defaultConfig {
        applicationId = "dev.kimiterminal.app"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0-acp"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }

    flavorDimensions += "distribution"
    productFlavors {
        create("sideload") {
            dimension = "distribution"
            versionNameSuffix = "-sideload"
        }
        create("play") {
            dimension = "distribution"
            versionNameSuffix = "-play"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    packaging { resources.excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    testOptions { unitTests { isIncludeAndroidResources = false } }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3:1.3.1")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.navigation:navigation-compose:2.8.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}

/*
 * Диагностика зависаний. Первый же прогон CI съел 30 минут и не напечатал НИ ОДНОЙ
 * строки результата: JUnit выдаёт итоги только когда тест закончился, а «неотвеченный»
 * JSON-RPC запрос жрал по requestTimeoutMs (120 с) × ~9 вызовов × 2 сценария.
 * runner просто упирался в timeout-minutes и завершался как cancelled.
 *
 * events("started") — видно, КАКОЙ тест встал, ещё до его конца.
 * exceptionFormat FULL — текст расхождения, а не только имя класса исключения.
 * showStandardStreams — stderr мокагента («[mock-kimi] ...») попадает в лог CI.
 * timeout — жёсткий потолок таска, чтобы висячий тест давал FAILED+стек,
 * а не молчаливое cancelled через полчаса.
 */
tasks.withType<Test>().configureEach {
    timeout = Duration.ofMinutes(10)
    testLogging {
        events("started", "passed", "failed", "skipped")
        exceptionFormat = TestExceptionFormat.FULL
        showStandardStreams = true
    }
}

