plugins { id("com.android.application") }
android {
    namespace = "org.mcrf.engines.steer"
    compileSdk = 34
    defaultConfig {
        applicationId = "org.mcrf.engines.steer"
        minSdk = 29
        targetSdk = 34
        versionCode = 4
        versionName = "0.3.3-module-dev.4"
        buildConfigField("String", "CORE_CAPABILITIES", groovy.json.JsonOutput.toJson(file("../core/src/main/assets/steer-capabilities.json").readText()))
    }
    buildFeatures { buildConfig = true }
    // Main's accepted PathClassLoader uses PackageManager-extracted nativeLibraryDir.
    packaging { jniLibs { useLegacyPackaging = true } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}
dependencies {
    implementation(project(":core"))
    // SPI identity belongs to the host parent loader. Never embed another copy.
    compileOnly(project(":module-spi"))
}
