pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}
rootProject.name = "machine-controller"
includeFlat("exchange")
includeFlat("repository-spring")
includeFlat("master-spring")
includeFlat("machine-model")
