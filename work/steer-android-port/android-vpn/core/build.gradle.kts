import java.security.MessageDigest
plugins { id("com.android.library") }
android {
    namespace = "org.steer.core"
    compileSdk = 34
    ndkVersion = "27.2.12479018"
    defaultConfig {
        minSdk = 29
        ndk { abiFilters += listOf("arm64-v8a", "x86_64") }
        externalNativeBuild { cmake { arguments += "-DANDROID_SUPPORT_FLEXIBLE_PAGE_SIZES=ON" } }
        consumerProguardFiles("consumer-rules.pro")
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    // Compile only the shared pure boundary guard, not host projection or SPI.
    sourceSets.getByName("main").java.srcDir("../integration-helper/src/main/java/org/mcrf/steerprep/json")
    externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt"); version = "3.22.1" } }
}
dependencies { api("com.squareup.okhttp3:okhttp:4.12.0") }
if (providers.gradleProperty("steerP1Candidate").orNull == "true") {
    val nativeScratch = file(requireNotNull(providers.gradleProperty("p1NativeScratch").orNull))
    require(nativeScratch.isAbsolute && nativeScratch.absoluteFile.normalize() == nativeScratch.canonicalFile &&
        nativeScratch.name == "cxx" && nativeScratch.parentFile.name.matches(Regex("p1s-[0-9a-f]{12}")) &&
        nativeScratch.parentFile.parentFile.canonicalFile == file("D:/MCRF/Builds/Android").canonicalFile)
    android {
        // Keep FetchContent/Ninja generated paths below Windows MAX_PATH.
        // This is a unique output directory, never a canonical source input.
        externalNativeBuild { cmake { buildStagingDirectory = nativeScratch } }
        defaultConfig { externalNativeBuild { cmake {
            arguments += "-DCMAKE_OBJECT_PATH_MAX=180"
            targets += listOf("steer_vpn", "ip6_packet_test", "host_routing_test", "host_dualstack_flow_test", "host_dualstack_wire_test")
        } } }
    }
    val generated = layout.buildDirectory.dir("generated/p1-candidate-assets")
    val capabilityJson = rootProject.extra["p1CandidateCapabilities"] as String
    val prepareAssets = tasks.register("prepareP1CandidateAssets") {
        inputs.dir("src/main/assets"); inputs.property("capabilities", capabilityJson); outputs.dir(generated)
        doLast {
            val destination = generated.get().asFile
            destination.mkdirs()
            project.copy { from("src/main/assets"); exclude("steer-capabilities.json"); into(destination) }
            destination.resolve("steer-capabilities.json").writeText(capabilityJson, Charsets.UTF_8)
        }
    }
    android.sourceSets.getByName("main").assets.setSrcDirs(listOf(generated))
    tasks.named("preBuild").configure { dependsOn(prepareAssets) }
    tasks.register("writeP1RuntimeClasspath") {
        val runtime = configurations.named("releaseRuntimeClasspath")
        inputs.files(runtime)
        val destination = layout.buildDirectory.file("p1-runtime-classpath.json")
        outputs.file(destination)
        doLast {
            val rows = runtime.get().resolvedConfiguration.resolvedArtifacts.map { artifact ->
                val id = artifact.moduleVersion.id
                val value = artifact.file
                require(value.extension == "jar") { "P1 fat runtime only merges pinned JAR dependencies" }
                mapOf("coordinate" to "${id.group}:${id.name}:${id.version}", "file" to value.canonicalPath,
                    "sha256" to MessageDigest.getInstance("SHA-256").digest(value.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) })
            }
            destination.get().asFile.writeText(groovy.json.JsonOutput.toJson(rows), Charsets.UTF_8)
        }
    }
}
