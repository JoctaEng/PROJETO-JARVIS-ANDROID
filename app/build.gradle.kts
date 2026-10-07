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
        // versionCode cresce a cada build do CI, para o APK novo sempre atualizar o anterior.
        versionCode = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 1
        versionName = "${providers.gradleProperty("euno.version").get()} (Fase ${providers.gradleProperty("euno.phase").get()})"
        // Só arm64 (Redmi Note 13 Pro+ e celulares atuais): llama.cpp e sherpa-onnx são pesados em 4 arquiteturas.
        ndk { abiFilters += "arm64-v8a" }
    }

    // Chave fixa (ADR 0010): o CI gera a keystore a partir do segredo EUNO_SIGNING_SEED. Sem ela (build local),
    // usa a chave de debug da máquina, e o APK não atualiza por cima do instalado.
    val eunoKeystore = System.getenv("EUNO_KEYSTORE_FILE")?.let(::file)?.takeIf { it.isFile }
    signingConfigs {
        if (eunoKeystore != null) {
            create("euno") {
                storeFile = eunoKeystore
                storeType = "PKCS12"
                storePassword = System.getenv("EUNO_KEYSTORE_PASSWORD")
                keyAlias = "euno"
                keyPassword = System.getenv("EUNO_KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        val signing = if (eunoKeystore != null) signingConfigs.getByName("euno") else signingConfigs.getByName("debug")
        debug { signingConfig = signing }
        release {
            isMinifyEnabled = false
            signingConfig = signing
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        aidl = true
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
    implementation(project(":mind:provider-llama"))
    implementation(project(":mind:provider-cloud"))
    implementation(project(":mind:persona"))
    implementation(project(":mind:memory"))
    implementation(project(":action:gateway"))
    implementation(project(":action:mcp"))
    // Voz offline (Kokoro): AAR oficial do sherpa-onnx v1.13.8, baixado pelo CI dos releases do GitHub (não está no Maven).
    implementation(files("libs/sherpa-onnx-1.13.8.aar"))
    implementation(libs.commons.compress)
    implementation(project(":system:resources"))
    implementation(project(":presence:placement"))
    implementation(project(":presence:expression"))

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
