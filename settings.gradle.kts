rootProject.name = "secuhub"

pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
    }
}

include(
    "securance-common",
    "securance-protocol",
    "securance-domain",
    "securance-server",
    "securance-scheduler",
    "securance-web",
    "securance-app",
)
