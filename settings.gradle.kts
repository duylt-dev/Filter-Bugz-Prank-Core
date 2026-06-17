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
        google()
        mavenCentral()
        // GitHub Packages: để :app tải :core (com.piontech.bugfilter:core) đã publish.
        // Credential lấy từ gradle.properties (gpr.user/gpr.key) — token cần scope read:packages.
        maven {
            name = "GitHubPackages"
            url = uri("https://maven.pkg.github.com/duylt-dev/Filter-Bugz-Prank-Core")
            credentials {
                username = providers.gradleProperty("gpr.user").orNull
                password = providers.gradleProperty("gpr.key").orNull
            }
        }
    }
}

rootProject.name = "Filter Prank Purple"
include(":app")
include(":core")
 