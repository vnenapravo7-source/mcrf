plugins { id("com.android.library") }
require(providers.gradleProperty("steerP1Candidate").orNull == "true")
val candidateJson = rootProject.extra["p1CandidateCapabilities"] as String
android {
    namespace = "org.mcrf.engines.steer"
    compileSdk = 34
    defaultConfig {
        minSdk = 29
        buildConfigField("String", "CORE_CAPABILITIES", groovy.json.JsonOutput.toJson(candidateJson))
        consumerProguardFiles("consumer-rules.pro")
    }
    buildFeatures { buildConfig = true }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    sourceSets.getByName("main").java.srcDir("../module-apk/src/main/java")
}
dependencies {
    compileOnly(project(":module-spi"))
    compileOnly(project(":core"))
}
