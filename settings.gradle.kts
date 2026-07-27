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
    }
}

rootProject.name = "NovaVPN"

include(":app")
include(":core:common")
include(":core:domain")
include(":core:data")
include(":core:ui")
include(":engine:api")
include(":engine:xray")
include(":engine:singbox")
include(":storage:room")
include(":storage:datastore")
include(":network")
include(":statistics")
include(":logging")
include(":subscription")
include(":feature:home")
include(":feature:subscriptions")
include(":feature:servers")
include(":feature:statistics")
include(":feature:settings")
include(":feature:logs")
