plugins {
    id("com.android.application") version "9.0.1" apply false
    id("com.android.library") version "9.0.1" apply false
}
if (providers.gradleProperty("steerP1Candidate").orNull == "true") apply(from = "p1-candidate.gradle.kts")
