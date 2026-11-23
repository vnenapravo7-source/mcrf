package org.mcrf.client

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import org.junit.rules.TemporaryFolder
import java.io.File
import javax.crypto.Cipher
import javax.crypto.spec.SecretKeySpec

/** SOURCE ONLY: next common gate; no separate build/device/provider run. */
class BsScreenOffBatteryTest {
    @get:Rule val temporary=TemporaryFolder()
    @Test fun absentOptInPreservesEveryOldModeAndAutoRegardlessOfBattery() {
        for(mode in BsScreenOffSettings.MODES)for(saver in listOf(false,true))
            for(screen in listOf(false,true))for(alive in listOf(false,true))
                for(percent in listOf(null,0,20,100))for(charging in listOf(null,false,true)) {
                    val value=BsScreenOffSettings(mode)
                    val expected=alive&&!screen&&(mode=="stable"||mode=="auto"&&!saver)
                    assertEquals(expected,value.holdCpu(screen,alive,saver,BsScreenOffBattery(percent,charging)))
                    assertFalse(value.watchesBattery)
                    assertFalse(value.batterySaving(BsScreenOffBattery(percent,charging)))
                    assertEquals(1,value.json().length())
                    assertFalse(value.json().has(BsScreenOffSettings.THRESHOLD))
                    assertFalse(value.json().has(BsScreenOffSettings.CHARGING))
                }
    }

    @Test fun optionalThresholdRoundTripsAndReadingNeverInsertsDefaultsOrMutatesInput() {
        val absent=JSONObject().put("unrelated","keep")
        val original=absent.toString()
        val old=BsScreenOffSettings.fromPreferences(absent)
        assertNull(old.batteryThresholdPercent);assertFalse(old.batteryWhileCharging)
        assertEquals(original,absent.toString())
        for(mode in BsScreenOffSettings.MODES)for(percent in listOf(1,20,100))for(charging in listOf(false,true)) {
            val value=BsScreenOffSettings(mode,percent,charging)
            val root=JSONObject().put("unrelated",42).put(BsScreenOffSettings.KEY,value.json())
            val before=root.toString()
            assertEquals(value,BsScreenOffSettings.fromPreferences(root))
            assertEquals(before,root.toString())
            assertEquals(value,BsScreenOffSettings.fromJson(value.json()))
        }
    }

    @Test fun backupSchemaRejectsWrongTypesOutOfRangeNullAndOrphanChargerOptions() {
        for(bad in listOf<Any>(-1,0,101,"20",20L,20.0,20.5,true,JSONObject.NULL,JSONObject()))
            assertThrows(IllegalArgumentException::class.java) {
                BsScreenOffSettings.fromJson(JSONObject().put("mode","auto").put(BsScreenOffSettings.THRESHOLD,bad))
            }
        for(bad in listOf<Any>("false",1,JSONObject.NULL,JSONObject()))
            assertThrows(IllegalArgumentException::class.java) {
                BsScreenOffSettings.fromJson(JSONObject().put("mode","auto").put(BsScreenOffSettings.THRESHOLD,20)
                    .put(BsScreenOffSettings.CHARGING,bad))
            }
        for(flag in listOf(false,true))assertThrows(IllegalArgumentException::class.java) {
            BsScreenOffSettings.fromJson(JSONObject().put("mode","auto").put(BsScreenOffSettings.CHARGING,flag))
        }
        for(percent in listOf(0,101))assertThrows(IllegalArgumentException::class.java) {BsScreenOffSettings("auto",percent)}
        assertThrows(IllegalArgumentException::class.java) {BsScreenOffSettings("auto",null,true)}
        assertThrows(IllegalArgumentException::class.java) {BsScreenOffSettings.fromJson(JSONObject().put("threshold",20))}
        val thresholdOnly=BsScreenOffSettings.fromJson(JSONObject().put("mode","auto").put(BsScreenOffSettings.THRESHOLD,20))
        assertEquals(20,thresholdOnly.batteryThresholdPercent);assertFalse(thresholdOnly.batteryWhileCharging)
    }

    @Test fun inclusiveThresholdReleasesAndRecoversOnlyForTheSameEligibleScreenOffSession() {
        val auto=BsScreenOffSettings("auto",20)
        assertTrue(auto.needsLease);assertTrue(auto.watchesBattery)
        for(percent in listOf(0,1,19,20)) {
            val battery=BsScreenOffBattery(percent,false)
            assertTrue(auto.batterySaving(battery));assertFalse(auto.holdCpu(false,true,false,battery))
        }
        val recovered=BsScreenOffBattery(21,false)
        assertTrue(auto.holdCpu(false,true,false,recovered))
        assertFalse(auto.holdCpu(true,true,false,recovered))
        assertFalse(auto.holdCpu(false,false,false,recovered))
        assertFalse(auto.holdCpu(false,true,true,recovered))
        assertFalse(auto.holdCpu(false,true,false,BsScreenOffBattery(20,false)))
        assertTrue(auto.holdCpu(false,true,false,recovered))
    }

    @Test fun chargerDefaultAndExplicitOverrideAreIndependentOfAndroidSaver() {
        val auto=BsScreenOffSettings("auto",20)
        assertFalse(auto.holdCpu(false,true,false,BsScreenOffBattery(10,false)))
        for(charging in listOf(true,null)) {
            assertFalse(auto.batterySaving(BsScreenOffBattery(10,charging)))
            assertTrue(auto.holdCpu(false,true,false,BsScreenOffBattery(10,charging)))
            assertFalse(auto.holdCpu(false,true,true,BsScreenOffBattery(10,charging)))
        }
        val onCharger=auto.copy(batteryWhileCharging=true)
        for(charging in listOf(false,true,null)) {
            assertTrue(onCharger.batterySaving(BsScreenOffBattery(10,charging)))
            assertFalse(onCharger.holdCpu(false,true,false,BsScreenOffBattery(10,charging)))
            assertTrue(onCharger.holdCpu(false,true,false,BsScreenOffBattery(21,charging)))
        }
    }

    @Test fun missingBatteryNeverBecomesZeroPercentOrOverridesOldSystemSaverRule() {
        for(charging in listOf(false,true,null))for(onCharger in listOf(false,true)) {
            val auto=BsScreenOffSettings("auto",20,onCharger)
            val battery=BsScreenOffBattery(null,charging)
            assertFalse(auto.batterySaving(battery))
            assertTrue(auto.holdCpu(false,true,false,battery))
            assertFalse(auto.holdCpu(false,true,true,battery))
        }
    }

    @Test fun rawBatteryValidationHandlesScaleOverflowAndUnknownChargerWithoutGuessing() {
        assertEquals(BsScreenOffBattery(20,false),BsScreenOffBattery.fromRaw(2,10,0))
        assertEquals(BsScreenOffBattery(100,true),BsScreenOffBattery.fromRaw(Int.MAX_VALUE,Int.MAX_VALUE,4))
        assertEquals(BsScreenOffBattery(0,false),BsScreenOffBattery.fromRaw(0,100,0))
        assertEquals(BsScreenOffBattery(33,null),BsScreenOffBattery.fromRaw(1,3,null))
        assertEquals(BsScreenOffBattery(10,null),BsScreenOffBattery.fromRaw(10,100,-1))
        for((level,scale) in listOf(null to 100,10 to null,10 to 0,10 to -1,-1 to 100,101 to 100)) {
            val battery=BsScreenOffBattery.fromRaw(level,scale,0)
            assertNull(battery.percent);assertEquals(false,battery.charging)
        }
        for(invalid in listOf(-1,101))assertThrows(IllegalArgumentException::class.java) {BsScreenOffBattery(invalid,false)}
    }

    @Test fun optOutAndModeSwitchRetainExplicitChoicesButRemoveBatteryObserverEligibility() {
        val active=BsScreenOffSettings("auto",25,true)
        val dormant=active.copy(mode="stable")
        assertFalse(dormant.watchesBattery)
        assertTrue(dormant.holdCpu(false,true,true,BsScreenOffBattery(0,false)))
        assertEquals(active,dormant.copy(mode="auto"))
        val balanced=active.copy(mode="balanced")
        assertFalse(balanced.needsLease);assertFalse(balanced.watchesBattery)
        assertFalse(balanced.holdCpu(false,true,false,BsScreenOffBattery(100,false)))
        val disabled=active.copy(batteryThresholdPercent=null,batteryWhileCharging=false)
        assertFalse(disabled.watchesBattery)
        assertTrue(disabled.needsLease)
        assertTrue(disabled.holdCpu(false,true,false,BsScreenOffBattery(0,false)))
        assertEquals("{\"mode\":\"auto\"}",disabled.json().toString())
    }

    @Test fun endpointThresholdsAndPresetsStayWithinValidatedRange() {
        val low=BsScreenOffSettings("auto",1)
        assertTrue(low.batterySaving(BsScreenOffBattery(1,false)))
        assertFalse(low.batterySaving(BsScreenOffBattery(2,false)))
        assertTrue(BsScreenOffSettings("auto",100).batterySaving(BsScreenOffBattery(100,false)))
        assertTrue(BsScreenOffSettings.THRESHOLD_PRESETS.all {it in 1..100})
        assertEquals(BsScreenOffSettings.THRESHOLD_PRESETS.distinct(),BsScreenOffSettings.THRESHOLD_PRESETS)
    }

    @Test fun existingBackupSectionValidatorAcceptsNewOptInAndRejectsMalformedValuesWithoutMutation() {
        // Same registration as ClientSettingsRegistry, not a second schema/key.
        AndroidBackup.registerSection(BsScreenOffSettings.KEY,BackupDefaults.Group.GENERAL) {BsScreenOffSettings.fromJson(it);Unit}
        val root=JSONObject().put(BsScreenOffSettings.KEY,BsScreenOffSettings("auto",17,true).json())
        val before=root.toString()
        AndroidBackup.validatePreferences(root)
        assertEquals(before,root.toString())
        val old=JSONObject().put(BsScreenOffSettings.KEY,JSONObject().put("mode","auto"))
        AndroidBackup.validatePreferences(old)
        assertFalse(old.getJSONObject(BsScreenOffSettings.KEY).has(BsScreenOffSettings.THRESHOLD))
        val bad=JSONObject().put(BsScreenOffSettings.KEY,JSONObject().put("mode","auto").put(BsScreenOffSettings.THRESHOLD,101))
        val badBefore=bad.toString()
        assertThrows(IllegalArgumentException::class.java) {AndroidBackup.validatePreferences(bad)}
        assertEquals(badBefore,bad.toString())
    }

    @Test fun readOnlyAbsentPreferencesNeverCreatesFilesDirectoriesOrRequestsAKey() {
        val absent=File(temporary.root,"not-created")
        val before=temporary.root.list()!!.toSet()
        val loaded=BsScreenOffSettings.readOnly(absent){error("MUST_NOT_REQUEST_KEY")}
        assertEquals(BsScreenOffSettings(),loaded)
        assertFalse(absent.exists());assertEquals(before,temporary.root.list()!!.toSet())
    }

    @Test fun readOnlyExistingGenerationUsesSameEncryptedBytesAndNeverRepairsSidecars() {
        val files=temporary.newFolder("files")
        val generation=File(files,"mcrf-store-00000000-0000-0000-0000-000000000001").apply {assertTrue(mkdir())}
        File(files,"active-profile.txt").writeText(generation.name,Charsets.US_ASCII)
        val key=SecretKeySpec(ByteArray(32){it.toByte()},"AES")
        val value=BsScreenOffSettings("auto",37,true)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key)}
        val ciphertext=cipher.doFinal(JSONObject().put(BsScreenOffSettings.KEY,value.json()).toString().toByteArray())
        val encrypted=java.nio.ByteBuffer.allocate(4).putInt(0x4d435246).array()+cipher.iv+ciphertext
        val preferences=File(generation,"preferences.enc").apply {writeBytes(encrypted)}
        fun hashes()=files.walkTopDown().filter(File::isFile).associate {it.relativeTo(files).path to it.readBytes().toList()}
        val before=hashes();var keyReads=0
        assertEquals(value,BsScreenOffSettings.readOnly(files){keyReads++;key})
        assertEquals(1,keyReads);assertEquals(before,hashes())
        for(suffix in listOf(".bak",".new")) {
            val sidecar=File(preferences.path+suffix).apply {writeBytes(byteArrayOf(1,2,3))}
            val pending=hashes()
            assertThrows(IllegalArgumentException::class.java) {BsScreenOffSettings.readOnly(files){error("MUST_NOT_REQUEST_KEY")}}
            assertEquals(pending,hashes())
            assertTrue(sidecar.delete()) // Only test-owned temporary data.
        }
        assertEquals(before,hashes())
        assertThrows(IllegalStateException::class.java) {BsScreenOffSettings.readOnly(files){error("EXISTING_KEY_UNAVAILABLE")}}
        assertEquals(before,hashes())
        // Model another process restoring a generation while this process has
        // already read ciphertext. Reject stale policy, never roll back the owner.
        val pointer=File(files,"active-profile.txt")
        val replacement=File(files,"mcrf-store-00000000-0000-0000-0000-000000000002").apply {assertTrue(mkdir())}
        assertThrows(IllegalArgumentException::class.java) {
            BsScreenOffSettings.readOnly(files){pointer.writeText(replacement.name,Charsets.US_ASCII);key}
        }
        assertEquals(replacement.name,pointer.readText(Charsets.US_ASCII))
        assertArrayEquals(encrypted,preferences.readBytes())
        pointer.writeText(generation.name,Charsets.US_ASCII) // Restore only this test's temporary pointer.
        assertThrows(IllegalArgumentException::class.java) {
            BsScreenOffSettings.readOnly(files){
                val next=File(generation,"replacement.enc").apply {writeBytes(encrypted)}
                java.nio.file.Files.move(next.toPath(),preferences.toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING)
                key
            }
        }
        assertArrayEquals(encrypted,preferences.readBytes())
        assertEquals(generation.name,pointer.readText(Charsets.US_ASCII))
        assertEquals(value,BsScreenOffSettings.readOnly(files){key})
    }
}
