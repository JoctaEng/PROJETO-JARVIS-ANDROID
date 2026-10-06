import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.library")
}

android {
    namespace = "com.joctaeng.jarvis.mind.local"
    compileSdk = 36

    defaultConfig {
        minSdk = 31
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
        // Mesmo ajuste do app oficial AI Edge Gallery: o AAR do LiteRT-LM pode
        // ser compilado com uma versão de Kotlin mais nova que a do projeto.
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}

dependencies {
    api(project(":core:contracts"))
    implementation(libs.litertlm.android)
    implementation(libs.kotlinx.coroutines.android)
}
