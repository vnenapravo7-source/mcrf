import java.security.MessageDigest

plugins { `java-library` }

val contract = groovy.json.JsonSlurper().parse(file("contract-lock.json")) as Map<*, *>
require(contract["schema"].toString()=="1" && contract["spiVersion"].toString()=="1")
val expectedRoot = "work/steer-android-port/android-vpn/module-contract/src/main/java"
require(contract["sourceRoot"]==expectedRoot)
val sourceRoot = rootProject.file("../../$expectedRoot")
val rows = (contract["sources"] as List<*>).map { it as Map<*, *> }
val sourceNames = setOf("org/mcrf/engine/spi/v1/Engine.java", "org/mcrf/engine/spi/v1/Platform.java")
require(rows.size==2 && rows.map { it["path"] }.toSet()==sourceNames)
rows.forEach { row ->
    val source=sourceRoot.resolve(row["path"].toString())
    require(source.isFile) { "Missing canonical primary SPI source" }
    val sha=MessageDigest.getInstance("SHA-256").digest(source.readBytes()).joinToString("") { "%02x".format(it.toInt() and 255) }
    require(sha==row["sha256"]) { "Canonical primary SPI changed: coordinator must review and pin the new contract" }
}
java {
    sourceCompatibility=JavaVersion.VERSION_1_8
    targetCompatibility=JavaVersion.VERSION_1_8
}
sourceSets.named("main") {
    java.setSrcDirs(listOf(sourceRoot, file("src/main/java")))
    java.setIncludes(sourceNames + setOf("org/mcrf/engine/protocol/v1/PacketBackend.java", "org/mcrf/engine/protocol/v1/OwnedPacketBackend.java", "org/mcrf/engine/protocol/dns/v1/ManagedDnsPacketBackend.java", "org/mcrf/engine/protocol/pipe/v1/PacketPipeBackend.java"))
}
tasks.jar {
    manifest.attributes("MCRF-Primary-SPI-Version" to "1")
}
