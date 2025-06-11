// settings.gradle.kts
pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // Google API用のリポジトリを追加
        maven { url = uri("https://maven.google.com") }
        maven { url = uri("https://googleapis.dev/java/google-api-services-gmail/latest/") }
        // Maven Centralのミラー
        maven { url = uri("https://repo1.maven.org/maven2/") }
    }
}

rootProject.name = "ShareFileBC"
include(":app")