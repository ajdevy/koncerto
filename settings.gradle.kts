pluginManagement {
    val useLocalPluginRepo = gradle.startParameter.projectProperties["koncerto.useLocalPluginRepo"] == "true"
    val useAliyunMirrors = gradle.startParameter.projectProperties["koncerto.useAliyunMirrors"] == "true"
    repositories {
        if (useLocalPluginRepo) {
            maven(url = rootDir.resolve("gradle/local-plugin-repo").toURI())
        }
        if (useAliyunMirrors) {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/public")
        }
        gradlePluginPortal()
        mavenCentral()
    }
}

rootProject.name = "koncerto"

include(
    "koncerto-core",
    "koncerto-logging",
    "koncerto-workflow",
    "koncerto-workspace",
    "koncerto-linear",
    "koncerto-agent",
    "koncerto-orchestrator",
    "koncerto-dashboard",
    "koncerto-metrics",
    "koncerto-app",
    "koncerto-e2e",
    "koncerto-notifications",
    "koncerto-demo",
    "koncerto-deploy"
)
