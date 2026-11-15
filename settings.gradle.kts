pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositories {
        maven {
            url = uri(providers.environmentVariable("GOOGLE_HOME_SDK_REPO").orElse("home-sdk"))
            content {
                includeModule("com.google.android.gms", "play-services-home")
                includeModule("com.google.android.gms", "play-services-home-types")
            }
        }
        google()
        mavenCentral()
    }
}
rootProject.name = "PainelGoogleHome"
include(":app")
