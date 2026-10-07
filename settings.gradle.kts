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
    ":mind:persona",
    ":mind:memory",
    ":mind:provider-cloud",
    ":action:gateway",
    ":action:mcp",
    ":system:resources",
    ":presence:placement",
    ":presence:expression",
)

// Módulos Android: dependem do Android Gradle Plugin (Google Maven).
val jvmOnly = providers.gradleProperty("jarvis.jvmOnly").orNull == "true"
if (!jvmOnly) {
    include(
        ":mind:provider-local",
        ":mind:provider-llama",
        ":app",
    )
}
