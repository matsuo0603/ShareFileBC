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

        // Google API用のリポジトリ
        maven { url = uri("https://maven.google.com") }
        maven { url = uri("https://googleapis.dev/java/google-api-services-gmail/latest/") }

        // Maven Centralのミラー
        maven { url = uri("https://repo1.maven.org/maven2/") }

        // 🔽 Tapyrus Wallet の GitHub Packages
        maven {
            url = uri("https://maven.pkg.github.com/chaintope/rust-tapyrus-wallet-ffi")
            credentials {
                username = System.getenv("GITHUB_USER") ?: ""
                password = System.getenv("GITHUB_TOKEN") ?: ""
            }
        }
    }
}

rootProject.name = "ShareFileBC"
include(":app")
