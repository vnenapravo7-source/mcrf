plugins { id("com.android.application") }
android {
    namespace = "org.mcrf.anubis"
    compileSdk = 37
    buildTypes.named("release") {
        isMinifyEnabled = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
    }
    defaultConfig {
        applicationId = "org.mcrf.anubis"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }
}

// The standalone service is Java-only.
configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}
