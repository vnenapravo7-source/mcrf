import java.security.MessageDigest
import java.nio.file.Files
plugins { id("com.android.application") }
val gateFile=file(requireNotNull(providers.gradleProperty("p1SourceReadyReceipt").orNull))
val expectedSha=requireNotNull(providers.gradleProperty("p1SourceReadySha256").orNull)
require(expectedSha.matches(Regex("[0-9a-f]{64}"))&&gateFile.isAbsolute&&gateFile.absoluteFile.normalize()==gateFile.canonicalFile&&
    gateFile.canonicalFile.parentFile==rootProject.file("coordination").canonicalFile&&
    gateFile.name.matches(Regex("p1-progress-[0-9]{4}-[0-9]{2}-[0-9]{2}\\.json"))&&!Files.isSymbolicLink(gateFile.toPath())&&gateFile.isFile&&gateFile.length() in 1L..262144L)
val bytes=gateFile.readBytes()
require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt()and 255)}==expectedSha)
val gate=groovy.json.JsonSlurper().parse(bytes) as Map<*,*>
require(gate["priority"].toString()=="1"&&gate["sourceReady"]==true&&gate["readyForBuild"]==true&&gate["allowCommonVerification"]==true&&gate["allowApkBuild"]==true)
android {
    namespace="org.mcrf.p1owner.peer"
    compileSdk=34
    defaultConfig {applicationId="org.mcrf.p1owner.peer";minSdk=29;targetSdk=34;versionCode=1;versionName="p1-shared-uid-fixture-1"}
    buildFeatures {buildConfig=false}
}
androidComponents {beforeVariants(selector().withBuildType("release")){it.enable=false}}
