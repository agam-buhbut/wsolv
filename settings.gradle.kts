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
    }
}

rootProject.name = "wsolv"

// :core is pure Kotlin/JVM and builds anywhere (no Android SDK needed).
include(":core")

// :app needs the Android SDK. Include it only when an SDK is configured, so that
// `./gradlew :core:test` works on machines without the SDK (e.g. CI, this dev box).
val sdkDir: String? =
    System.getenv("ANDROID_HOME")
        ?: System.getenv("ANDROID_SDK_ROOT")
        ?: file("local.properties").takeIf { it.exists() }?.let { lp ->
            java.util.Properties().apply { lp.inputStream().use { load(it) } }.getProperty("sdk.dir")
        }

if (sdkDir != null && file(sdkDir).exists()) {
    include(":app")
} else {
    gradle.rootProject {
        logger.lifecycle(
            "[wsolv] Android SDK not found — :app is skipped. " +
                "Set ANDROID_HOME or local.properties 'sdk.dir' to build the APK.",
        )
    }
}
