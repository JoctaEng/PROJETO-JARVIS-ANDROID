import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.joctaeng.jarvis"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.joctaeng.jarvis"
        minSdk = 31
        targetSdk = 36
        versionCode = 1
        versionName = "0.0.1-fase0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // APK pessoal (sideload): assinado com a chave de debug até existir uma chave própria.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    implementation(project(":core:contracts"))
    implementation(project(":mind:orchestrator"))
    implementation(project(":mind:provider-local"))
    implementation(project(":action:gateway"))
    implementation(project(":system:resources"))
    implementation(project(":presence:placement"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.savedstate.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.camerax.core)
    implementation(libs.camerax.camera2)
    implementation(libs.camerax.lifecycle)

    testImplementation(libs.kotlin.test.junit)
    testImplementation(libs.junit)
}
