package org.mcrf.client

import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.junit.Assert.*
import org.junit.Test

class EngineInstallerHardeningTest {
    private fun rejected(key:String,action:()->Unit) {
        val error=try {action();null} catch(e:IllegalArgumentException) {e}
        assertNotNull("Must reject $key",error)
        assertTrue(error!!.message.orEmpty(),error.message.orEmpty().contains(key))
    }
    private fun elf(clazz:Int,machine:Int)=ByteArray(if(clazz==1)52 else 64).apply {
        this[0]=0x7f;this[1]=69;this[2]=76;this[3]=70
        this[4]=clazz.toByte();this[5]=1;this[6]=1
        this[18]=machine.toByte();this[19]=(machine ushr 8).toByte()
    }
    private fun archive(entries:Map<String,ByteArray>,check:(ZipFile)->Unit) {
        val file=Files.createTempFile("engine-elf-",".zip").toFile()
        try {
            ZipOutputStream(file.outputStream()).use {zip->entries.forEach {(name,data)->
                zip.putNextEntry(ZipEntry(name));zip.write(data);zip.closeEntry()
            }}
            ZipFile(file).use(check)
        } finally {file.delete()}
    }

    @Test fun oemResumeWithoutActivityResultConsumesOnlyAfterPauseAndOnlyOnce() {
        val gate=EngineInstallPermission.ReturnGate()
        gate.launched()
        assertFalse(gate.consume(true)) // Same foreground before Settings opens.
        gate.paused()
        assertTrue(gate.consume(true))
        assertEquals("download-ready",EngineInstallPermission.resumePhase(true,false))
        assertFalse(gate.consume(false)) // Late result cannot repeat download/install.
        assertFalse(gate.consume(true))
    }
    @Test fun callbackFirstAndRotationCannotConsumePermissionTwice() {
        val gate=EngineInstallPermission.ReturnGate()
        gate.launched();gate.paused()
        assertTrue(gate.consume(false))
        gate.paused()
        assertFalse(gate.consume(true))
        assertFalse(gate.consume(false))
    }
    @Test fun denialDoesNotRearmUntilExplicitRetry() {
        val gate=EngineInstallPermission.ReturnGate()
        gate.launched();gate.paused();assertTrue(gate.consume(true))
        val denied=EngineInstallPermission.resumePhase(false,false)
        repeat(3) {
            gate.paused();assertFalse(gate.consume(true))
            assertEquals("permission-denied",EngineInstallPermission.beforeOfferPhase(denied,false))
        }
        gate.launched();gate.paused();assertTrue(gate.consume(true))
        assertEquals("install-ready",EngineInstallPermission.resumePhase(true,true))
    }
    @Test fun metadataKindRejectsNonStringWithoutCoercion() {
        for(value in listOf<Any>(1,1L,true,listOf("sing-box"))) {
            rejected("org.mcrf.engines.kind") {EngineMetadata {value}.requiredString("org.mcrf.engines.kind")}
        }
    }
    @Test fun metadataInterfaceAndPrimaryIntegerKeysRejectWrongTypes() {
        val keys=listOf("org.mcrf.engines.interface","org.mcrf.engines.primary-core",
            "org.mcrf.engines.core-api","org.mcrf.engine.core.api","org.mcrf.engine.spi.version")
        for(key in keys) for(value in listOf<Any>("1",1L,true,1.0)) {
            rejected(key) {EngineMetadata {value}.requiredInt(key)}
        }
    }
    @Test fun primaryStringKeysRejectWrongTypesAndMissingValues() {
        val keys=listOf("org.mcrf.engines.entry","org.mcrf.engine.entry","org.mcrf.engine.abis",
            "org.mcrf.engines.source.repository","org.mcrf.engines.source.version","org.mcrf.engines.source.sha256")
        for(key in keys) {
            rejected(key) {EngineMetadata {7}.requiredString(key)}
            rejected(key) {EngineMetadata {null}.requiredString(key)}
        }
        rejected("org.mcrf.engines.interface") {EngineMetadata {null}.requiredInt("org.mcrf.engines.interface")}
    }
    @Test fun correctlyTypedMetadataAndOptionalAbsenceRemainCompatible() {
        val data=mapOf("kind" to "sing-box","interface" to 1)
        val m=EngineMetadata {data[it]}
        assertEquals("sing-box",m.requiredString("kind"));assertEquals(1,m.requiredInt("interface"))
        assertNull(m.string("absent"));assertEquals(0,m.int("absent"))
    }
    @Test fun catalogVersionMatchesExactlyUsingAll64BitsAndAbsentPinIsAllowed() {
        val version=(3L shl 32)+10
        EngineArchivePolicy.requireCatalogVersion(version,version)
        EngineArchivePolicy.requireCatalogVersion(version,null)
        rejected("каталогом") {EngineArchivePolicy.requireCatalogVersion(10,version)}
        rejected("каталогом") {EngineArchivePolicy.requireCatalogVersion(version,10)}
        rejected("каталогом") {EngineArchivePolicy.requireCatalogVersion(11,10)}
        rejected("каталогом") {EngineArchivePolicy.requireCatalogVersion(9,10)}
    }
    @Test fun elfAcceptsTheThreeSupportedArchitectures() {
        EngineArchivePolicy.requireElf("arm64-v8a",elf(2,183))
        EngineArchivePolicy.requireElf("armeabi-v7a",elf(1,40))
        EngineArchivePolicy.requireElf("x86_64",elf(2,62))
    }
    @Test fun elfRejectsMislabeledClassMachineEndianAndTruncation() {
        for((abi,correct) in listOf("arm64-v8a" to elf(2,183),"armeabi-v7a" to elf(1,40),"x86_64" to elf(2,62))) {
            for(header in listOf(correct.copyOf().apply {this[4]=if(this[4]==1.toByte())2 else 1},
                correct.copyOf().apply {this[18]=0},correct.copyOf().apply {this[5]=2},
                correct.copyOf().apply {this[6]=0},correct.copyOf(correct.size-1))) {
                rejected(abi) {EngineArchivePolicy.requireElf(abi,header)}
            }
        }
        rejected("ELF") {EngineArchivePolicy.requireElf("arm64-v8a",ByteArray(64))}
        rejected("ELF") {EngineArchivePolicy.requireElf("arm64-v8a",byteArrayOf(127,69,76,70))}
        rejected("ABI") {EngineArchivePolicy.requireElf("mips",elf(1,40))}
    }
    @Test fun archiveChecksEveryNativeEntryIncludingNonSelectedAbiAndSecondaryLibrary() {
        val entries=linkedMapOf("lib/arm64-v8a/libbox.so" to elf(2,183),
            "lib/x86_64/libbox.so" to elf(2,62),"lib/armeabi-v7a/libbox.so" to elf(1,40))
        archive(entries) {EngineArchivePolicy.requireElfLibraries(it)}
        entries["lib/x86_64/libbox.so"]=elf(2,183)
        archive(entries) {zip->rejected("lib/x86_64/libbox.so") {EngineArchivePolicy.requireElfLibraries(zip)}}
        archive(mapOf("lib/arm64-v8a/libsteer_vpn.so" to elf(2,183),"lib/arm64-v8a/libmcrf_utls49.so" to elf(1,40))) {
            zip->rejected("libmcrf_utls49.so") {EngineArchivePolicy.requireElfLibraries(zip)}
        }
        archive(mapOf("lib/arm64-v8a/../libbox.so" to elf(2,183))) {
            zip->rejected("путь") {EngineArchivePolicy.requireElfLibraries(zip)}
        }
    }
    @Test fun certificateIdentityUsesStableBytesAndExactSignerSets() {
        val bytes="abc".toByteArray(Charsets.UTF_8)
        val id=EngineSignerPolicy.certificateIdentity(bytes)
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",id)
        assertEquals(id,EngineSignerPolicy.certificateIdentity(bytes.copyOf()))
        val other=EngineSignerPolicy.certificateIdentity("abcd".toByteArray())
        EngineSignerPolicy.requireSame(setOf(id,other),setOf(other,id))
        rejected("не совпадает") {EngineSignerPolicy.requireSame(setOf(id),setOf(id,other))}
        rejected("не совпадает") {EngineSignerPolicy.requireSame(setOf(other),setOf(id))}
        rejected("сертификат") {EngineSignerPolicy.certificateIdentity(byteArrayOf())}
    }
}
