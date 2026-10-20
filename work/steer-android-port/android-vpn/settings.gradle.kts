pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "SteerVpnPrototype"
if (providers.gradleProperty("steerP1Candidate").orNull != "true") include(":app")
include(":core")
// Read-only, opt-in comparison against a locally supplied libbox. Never shipped in Steer.
if (providers.gradleProperty("libboxOracleAar").isPresent) include(":oracle")
// Separate primary-core APK candidate. Never added as an app/base dependency.
if (providers.gradleProperty("steerModule").isPresent) include(":module-spi", ":module-apk")
if (providers.gradleProperty("steerP1Candidate").orNull == "true") include(":module-spi", ":primary-adapter")
