plugins { `java-library` }
java { sourceCompatibility = JavaVersion.VERSION_1_8; targetCompatibility = JavaVersion.VERSION_1_8 }
tasks.withType<JavaCompile>().configureEach { options.release.set(8) }
sourceSets.main {
    java.srcDir("../module-contract/src/main/java")
    java.include("org/mcrf/engine/spi/v1/**")
}
