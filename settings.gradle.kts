pluginManagement {
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
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
    }
}

rootProject.name = "jarvis"

// Módulos Kotlin puros: lógica testável sem Android (contratos, regras, políticas).
include(
    ":core:model",
    ":core:contracts",
    ":mind:orchestrator",
    ":action:gateway",
    ":system:resources",
    ":presence:placement",
)

// Módulos Android: dependem do Android Gradle Plugin (Google Maven).
val jvmOnly = providers.gradleProperty("jarvis.jvmOnly").orNull == "true"
if (!jvmOnly) {
    include(
        ":mind:provider-local",
        ":app",
    )
}
