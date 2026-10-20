package org.mcrf.client

/** Every Android process validates the same portable encrypted settings before any restore/export. */
object ClientSettingsRegistry {
    @Synchronized fun register() {
        AndroidBackup.setRuntimeValidator { context, config ->
            val value=org.json.JSONObject(config)
            val inbounds=value.optJSONArray("inbounds")
            val vpn=(0 until (inbounds?.length()?:0)).any {inbounds!!.getJSONObject(it).optString("type")=="tun"}
            ClientPrimarySelection.validateNative(context,config,vpn)
        }
        AndroidBackup.registerSection(ClientTunSettings.KEY) { ClientTunSettings.fromJson(it); Unit }
        AndroidBackup.registerSection(CoreTuningSettings.KEY) { CoreTuningSettings.fromJson(it); Unit }
        AndroidBackup.registerSection(SubscriptionOptions.SECTION) { SubscriptionOptions.validate(it); Unit }
        AndroidBackup.registerSection(ClientBehaviorSettings.KEY,BackupDefaults.Group.GENERAL) { ClientBehaviorSettings.fromJson(it); Unit }
        AndroidBackup.registerSection(AndroidDeployBackupSettings.SECTION,BackupDefaults.Group.PRESERVE) { AndroidDeployBackupSettings.validate(it); Unit }
        AndroidBackup.registerSection(WarpSettings.SECTION) { WarpSettings.validate(it); Unit }
        AndroidBackup.registerSection(WarpRegistrationStore.SECTION,BackupDefaults.Group.PRESERVE) { WarpRegistrationStore.validate(it); Unit }
        AndroidBackup.registerSection(OptionalCoreSettings.KEY) { OptionalCoreSettings.fromJson(it); Unit }
        AndroidBackup.registerSection(AutoVpnReturn.KEY) { AutoVpnReturn.validate(it) }
        AndroidBackup.registerPreference(BsDetectionMonitor.KEY,false)
        AndroidBackup.registerPreference("logsTerminal",false)
        AndroidBackup.registerPreference(ConnectionAppearance.MERGED,false)
        AndroidBackup.registerPreference(ConnectionAppearance.STYLE,ConnectionAppearance.DEFAULT)
        AndroidBackup.registerPreference(ConnectionAppearance.EXIT,ConnectionAppearance.DOOR)
        AndroidBackup.registerPreference(EngineUpdates.AUTOMATIC_PREFERENCE,true)
        AndroidBackup.registerPreference(ClientPrimarySelection.KEY,"")
        AndroidBackup.registerPreference(ClientPrimarySelection.DUAL_CONFIRMED,false)
        AndroidBackup.registerPreference(PrimaryFallbackPolicy.KEY,false)
        AndroidBackup.registerPreference("steerIpv4DnsAccepted",false)
        AndroidBackup.registerPreference(ClientPrimarySelection.DNS_MODEL_V2_ACCEPTED,false)
        AndroidBackup.registerPreference(UsageFeatures.KEY,UsageFeatures.encode(UsageFeatures.all))
        AndroidBackup.registerPreference(UsageFeatures.ASKED,false)
        UserThemeColors.keys.forEach {AndroidBackup.registerPreference(it,"")}
        AndroidBackup.registerPreference(PatternWallpaper.KEY,"none")
    }
}
