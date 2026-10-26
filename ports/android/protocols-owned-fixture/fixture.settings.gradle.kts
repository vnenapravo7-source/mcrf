pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name="MCRFProtocolOwnedFixture"
// Only the new emulator helper: no historical app tests, engine variants or
// production native-stage admission before the owned cases have executed.
include(":protocols-owned-fixture")
