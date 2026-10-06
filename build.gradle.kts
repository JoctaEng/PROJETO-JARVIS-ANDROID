// Plugins entram no classpath da raiz para que todos os módulos compartilhem
// o mesmo classloader (exigência do AGP + Kotlin). Os plugins Android só são
// carregados quando o build não está em modo "jvmOnly".
buildscript {
    val jvmOnly = providers.gradleProperty("jarvis.jvmOnly").orNull == "true"
    repositories {
        google {
                content {
                    includeGroupByRegex("com\\.android.*")
                    includeGroupByRegex("com\\.google.*")
                    includeGroupByRegex("androidx.*")
                }
            }
        mavenCentral()
        gradlePluginPortal()
    }
    dependencies {
        classpath(libs.plugin.kotlin)
        if (!jvmOnly) {
            classpath(libs.plugin.android)
            classpath(libs.plugin.compose.compiler)
        }
    }
}
