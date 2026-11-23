package org.mcrf.client

/** Every Android process validates the same portable encrypted settings before any restore/export. */
object ClientSettingsRegistry {
    @Synchronized fun register() {
        InviteCodeSync.registerBackup()
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
        AndroidBackup.registerSection(BsWifiSettings.KEY,BackupDefaults.Group.GENERAL) { BsWifiSettings.fromJson(it); Unit }
        AndroidBackup.registerSection(CarrierStreamDefaults.SECTION,BackupDefaults.Group.GENERAL) { CarrierStreamDefaults.validate(it); Unit }
        AndroidBackup.registerSection(BsScreenOffSettings.KEY,BackupDefaults.Group.GENERAL) { BsScreenOffSettings.fromJson(it); Unit }
        AndroidBackup.registerSection(WdttMasqueStore.SECTION,BackupDefaults.Group.PRESERVE) { WdttMasqueStore.validateSection(it) }
        AndroidBackup.registerSection(AndroidDeployBackupSettings.SECTION,BackupDefaults.Group.PRESERVE) { AndroidDeployBackupSettings.validate(it); Unit }
        AndroidBackup.registerSection(WarpSettings.SECTION) { WarpSettings.validate(it); Unit }
        AndroidBackup.registerSection(WarpRegistrationStore.SECTION,BackupDefaults.Group.PRESERVE) { WarpRegistrationStore.validate(it); Unit }
        AndroidBackup.registerSection(OptionalCoreSettings.KEY) { OptionalCoreSettings.fromJson(it); Unit }
        AndroidBackup.registerSection(AutoVpnReturn.KEY) { AutoVpnReturn.validate(it) }
        AndroidBackup.registerSection(ConditionProfiles.KEY,BackupDefaults.Group.PRESERVE) {ConditionProfiles.fromJson(it);Unit}
        AndroidBackup.registerPreference(ExperienceModeStore.KEY,ExperienceMode.SIMPLE.id)
        AndroidBackup.registerPreference(RandomAppearance.KEY,false)
        AndroidBackup.registerPreference(ColdLaunchSwarm.KEY,true)
        AndroidBackup.registerPreference(VpnOwnerIsolation.KEY,VpnOwnerIsolation.DEFAULT_ENABLED)
        AndroidBackup.registerPreference(RuAppTunnelNotice.ENABLED,true)
        AndroidBackup.registerPreference(AppearanceMotion.SLIDER,AppearanceMotion.DEFAULT_SLIDER)
        listOf(AppearanceMotion.MATH,AppearanceMotion.MATH_BODY)
            .forEach {AndroidBackup.registerPreference(it,true)}
        listOf(AppearanceMotion.GRID,AppearanceMotion.EYE)
            .forEach {AndroidBackup.registerPreference(it,false)}
        AndroidBackup.registerPreference(SafeModeStore.KEY,false)
        AndroidBackup.registerPreference(BsDetectionMonitor.KEY,false)
        AndroidBackup.registerPreference(BsDetectionInterval.KEY,BsDetectionInterval.DEFAULT)
        AndroidBackup.registerPreference(BsAutoSelectionPolicy.KEY,BsAutoSelectionPolicy.FASTEST)
        AndroidBackup.registerPreference(AutoSelectionDeviation.FAILURE_ONLY,false)
        AndroidBackup.registerPreference(BsAutoSelectionPolicy.LAST_VPN,"")
        AndroidBackup.registerPreference(BsAutoSelectionPolicy.LAST_BS,"")
        AndroidBackup.registerPreference("logsTerminal",false)
        AndroidBackup.registerPreference(LogRetention.KEY,LogRetention.DEFAULT)
        AndroidBackup.registerPreference(ConnectionAppearance.MERGED,true)
        AndroidBackup.registerPreference(ConnectionAppearance.MERGED_DEFAULT_APPLIED,false)
        AndroidBackup.registerPreference(ConnectionAppearance.STYLE,ConnectionAppearance.SIDE_ONLY)
        AndroidBackup.registerPreference(ConnectionAppearance.TOGGLE_MAIN_ONLY,false)
        AndroidBackup.registerPreference(ConnectionAppearance.COMPACT,true)
        AndroidBackup.registerPreference(ConnectionAppearance.SERVER_ORBIT,false)
        AndroidBackup.registerPreference(ConnectionIndicator.KEY,ConnectionIndicator.ORB_CONNECTING.key)
        AndroidBackup.registerPreference(AppTypography.ENABLED,false)
        AndroidBackup.registerPreference(AppTypography.DISPLAY,AppTypography.DEFAULT)
        AndroidBackup.registerPreference(AppTypography.INTERFACE,AppTypography.DEFAULT)
        AndroidBackup.registerPreference(HomeUiTheme.GRADIENT_KEY,false)
        AndroidBackup.registerPreference(HomeUiTheme.MODE_KEY,"")
        AndroidBackup.registerPreference(HomeUiTheme.V2_DEFAULTS_APPLIED,false)
        AndroidBackup.registerPreference(EdgePanelAppearance.ORBIT_COLLAPSED,false)
        AndroidBackup.registerPreference(AppLaunchGroupsUi.KEEP_VPN_AFTER_EXIT,false)
        AppLaunchGroupsUi.Mode.entries.forEach {AndroidBackup.registerPreference(it.key,"[]")}
        AndroidBackup.registerPreference(UiConstructor.KEY,"{}")
        AndroidBackup.registerPreference("resourceMonitorQuickStart",false)
        AndroidBackup.registerPreference(ConnectionControlsDock.HIDDEN,false)
        AndroidBackup.registerPreference(ConnectionAppearance.EXIT,ConnectionAppearance.DOOR)
        EdgePanelAppearance.items.keys.forEach {AndroidBackup.registerPreference(it,true)}
        AndroidBackup.registerPreference(EdgePanelAppearance.HIDDEN,false)
        AndroidBackup.registerPreference(EdgePanelAppearance.PINNED,false)
        AndroidBackup.registerPreference(BsAutoSelectionPolicy.LAST_MODE,BsAutoSelectionPolicy.FASTEST)
        AndroidBackup.registerPreference(NotificationTrafficSettings.ENABLED,true)
        AndroidBackup.registerPreference(NotificationTrafficSettings.FOREGROUND,3)
        AndroidBackup.registerPreference(NotificationTrafficSettings.BACKGROUND,10)
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
