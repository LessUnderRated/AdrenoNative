pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        // Google's official mirror of Maven Central. Central itself rate-limits (HTTP 429)
        // shared CI/cloud hosts; misses fall through to mavenCentral() below.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        // Google's official mirror of Maven Central. Central itself rate-limits (HTTP 429)
        // shared CI/cloud hosts; misses fall through to mavenCentral() below.
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        mavenCentral()
        maven { url = uri("https://central.sonatype.com/repository/maven-snapshots/") } // JavaSteam
    }
}

rootProject.name = "gamenative"
include(":app")
include(":ubuntufs")
