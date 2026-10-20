import java.util.zip.ZipFile
import java.util.zip.ZipInputStream

plugins { id("com.android.library") }

// Build only the reviewed sing-box adapter. This project is NOT a dependency of
// :app. Its AAR is delivered to a separately installed, gated primary module.
android {
    namespace="org.mcrf.engines.singbox"
    compileSdk=34
    defaultConfig { minSdk=29; consumerProguardFiles("../primary-cores/singbox/consumer-rules.pro") }
    compileOptions {
        sourceCompatibility=JavaVersion.VERSION_17
        targetCompatibility=JavaVersion.VERSION_17
    }
    sourceSets.getByName("main").kotlin.srcDir("../primary-cores/singbox/src/main/kotlin")
}
tasks.register("verifyReleaseAdapter") {
    dependsOn("assembleRelease")
    doLast {
        val aar=layout.buildDirectory.file("outputs/aar/primary-adapter-release.aar").get().asFile
        require(aar.isFile) { "Primary adapter AAR is missing" }
        ZipFile(aar).use { zip ->
            require(zip.entries().asSequence().none { it.name.startsWith("jni/") }) { "Adapter must not embed native runtime" }
            val classes=requireNotNull(zip.getEntry("classes.jar")) { "Adapter has no classes.jar" }
            val names=zip.getInputStream(classes).use { input -> ZipInputStream(input).use { jar ->
                buildSet<String> { while(true) { val entry=jar.nextEntry?:break;add(entry.name) } }
            } }
            require("org/mcrf/engines/singbox/SingBoxEngine.class" in names) { "Adapter entry was not compiled" }
            require(names.filter { it.endsWith(".class") }.all { it.startsWith("org/mcrf/engines/singbox/") }) {
                "Adapter contains foreign runtime, tests or duplicate parent SPI"
            }
        }
    }
}
dependencies {
    compileOnly(project(":primary-spi"))
    compileOnly(files("../app/libs/libbox.aar"))
}
