pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement { repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS); repositories { google(); mavenCentral() } }
rootProject.name = "MCRF"
include(":app")
include(":engines")
include(":primary-spi")
include(":primary-adapter")
if(providers.gradleProperty("p2DispatcherStage").isPresent){
    require(providers.gradleProperty("p2DispatcherCandidate").orNull=="true")
    include(":p2-route-dispatcher")
}
if (providers.gradleProperty("wgBackendStage").isPresent) include(":wg-module")
// Common P1 acceptance only, never a default app/release dependency.
if (providers.gradleProperty("p1OwnerFixture").orNull == "true") include(":p1-owner-probe", ":p1-owner-peer")
