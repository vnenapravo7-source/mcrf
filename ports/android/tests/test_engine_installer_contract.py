"""Source integration gates runnable without Gradle, APKs, or native compilation."""
from pathlib import Path
import unittest

ROOT = Path(__file__).resolve().parents[1] / "app/src/main/kotlin/org/mcrf/client"


def source(name):
    return (ROOT / f"{name}.kt").read_text(encoding="utf-8")


class EngineInstallerContractTest(unittest.TestCase):
    def test_telegram_and_packet_reuse_oem_safe_package_readers(self):
        telegram = source("EngineUpdateTelegram")
        self.assertIn('EngineSignatures.installed(pm,EngineModules.packageName("tgws"))', telegram)
        self.assertIn("EngineSignerPolicy.requireSame(actual.signers,EngineSignatures.installed(pm,context.packageName).signers)", telegram)
        self.assertNotIn(".signingInfo", telegram)
        self.assertNotIn(".getInt(", telegram)
        self.assertIn("EngineArchivePolicy.requireElfLibraries(zip)", telegram)
        self.assertIn("EngineSignatures.archive(context.packageManager,file.absolutePath)", source("EngineUpdatePacket"))

    def test_archive_and_installed_packages_query_both_signer_apis(self):
        code = source("EngineSignatures")
        self.assertIn("Build.VERSION.SDK_INT>=28", code)
        self.assertIn("query(PackageManager.GET_SIGNING_CERTIFICATES)", code)
        self.assertIn("query(PackageManager.GET_SIGNATURES)", code)
        self.assertIn("it.signingInfo?.apkContentsSigners", code)
        self.assertIn("it.signatures?.map", code)
        self.assertNotIn("signingCertificateHistory", code)
        self.assertIn("pm.getPackageArchiveInfo(path,flags or PackageManager.GET_META_DATA)", code)
        self.assertIn("pm.getPackageInfo(packageName,flags or PackageManager.GET_META_DATA)", code)

    def test_primary_installation_reuses_archive_signer_policy(self):
        code = source("EngineUpdatePrimary").split("fun installation(", 1)[1].split("fun verifyArchive(", 1)[0]
        self.assertIn("EngineSignatures.installed(pm,contract(kind).packageName)", code)
        self.assertIn("EngineSignerPolicy.requireSame(inspected.signers,EngineSignatures.installed(pm,context.packageName).signers)", code)
        self.assertIn("metadata(kind,app,abi)", code)
        self.assertIn("verifyNative(File(app.sourceDir),kind,abi)", code)

    def test_archive_rejections_are_specific_and_security_checks_remain(self):
        code = source("EnginePackages")
        for text in ("Неверное имя пакета движка", "Тип движка в метаданных", "Несовместимая версия интерфейса"):
            self.assertIn(text, code)
        self.assertNotIn("Подпись или формат APK", code)
        self.assertIn("EngineSignatures.archive(pm,file.absolutePath)", code)
        self.assertIn("EngineSignerPolicy.requireSame(inspected.signers,EngineSignatures.installed(pm,context.packageName).signers)", code)
        for guard in ("EnginePackage.copyVerified", "apk.longVersionCode >=", "EngineModules.validNativeEntries", "EngineUpdatePrimary.verifyArchive", "EngineUpdatePacket.verifyArchive"):
            self.assertIn(guard, code)

    def test_permission_gate_precedes_the_only_apk_download(self):
        code = source("EngineImportUi")
        download = code.split("fun download()", 1)[1].split("fun install()", 1)[0]
        gate = "if(!packageManager.canRequestPackageInstalls()){requestInstallPermission();return}"
        self.assertLess(download.index(gate), download.index("EnginePackages.download("))
        self.assertEqual(1, code.count("EnginePackages.download("))
        self.assertIn('"download-ready" -> download()', code)
        self.assertIn("Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES", code)
        self.assertIn('Uri.parse("package:$packageName")', code)

    def test_grant_and_retained_dialog_resume_through_file_aware_policy(self):
        code = source("EngineImportUi")
        self.assertIn("EngineInstallPermission.resumePhase(allowed,pending.file?.isFile==true)", code)
        self.assertNotIn('if (pending.phase == "permission") permissionReturned', code)
        returned = code.split("fun returned(", 1)[1]
        self.assertIn('request == 9 && pending.phase=="permission" && pending.permissionReturn.consume(false)', returned)
        self.assertNotIn("pending.downloadApproved=allowed", returned)
        self.assertIn("permissionReturned(pending,allowed)", returned)
        self.assertIn('phase == "download-ready"&&pending.downloadApproved', code)
        self.assertIn('phase == "install-ready"&&pending.automaticInstall&&!active()', code)

    def test_permission_transition_precedes_offer_on_ui_thread_and_callback_is_wired(self):
        code = source("EngineImportUi")
        render = code.split("handler.post(object : Runnable", 1)[1]
        self.assertLess(render.index("EngineInstallPermission.beforeOfferPhase"), render.index("val phase = pending.phase"))
        self.assertLess(render.index('if(pending.phase=="permission-required")requestInstallPermission()'), render.index("button.isEnabled"))
        request = code.split("fun requestInstallPermission()", 1)[1].split("fun download()", 1)[0]
        self.assertNotIn("pending.downloadApproved=true", request)
        self.assertIn("pending.permissionDownloadApproved=pending.downloadApproved", request)
        retry = code.split('"permission-denied" -> {', 1)[1].split('"install-ready" ->', 1)[0]
        self.assertIn("pending.downloadApproved=pending.permissionDownloadApproved", retry)
        self.assertNotIn("pending.downloadApproved=true", retry)
        self.assertLess(request.index('pending.phase="permission"'), request.index("startActivityForResult"))
        callback = source("MainActivity").split("override fun onActivityResult", 1)[1].split("override fun onSaveInstanceState", 1)[0]
        self.assertIn("request == 9 || request == 10 || EngineUpdateGate.isInstallerRequest(request)", callback)
        self.assertIn("engineImport.returned(request,result)", callback)

    def test_secondary_archive_reads_keep_vendor_fallback(self):
        self.assertIn("EngineSignatures.archive(context.packageManager,file.absolutePath)", source("EngineUpdatePrimary"))
        requirements = source("EnginePackages").split("fun verifyRequirements", 1)[1]
        self.assertIn("EngineSignatures.archive(context.packageManager,file.absolutePath)", requirements)

    def test_denial_is_retryable_without_automatic_settings_loop(self):
        code = source("EngineImportUi")
        self.assertIn("if(!allowed)pending.downloadApproved=false", code)
        self.assertIn("pending.automaticInstall=allowed&&pending.downloadApproved", code)
        self.assertIn('"permission-denied" -> {', code)
        self.assertIn('"error", "permission-denied" ->"Повторить"', code)
        enabled = code.split("button.isEnabled =", 1)[1].splitlines()[0]
        self.assertIn('"permission-denied"', enabled)
        self.assertNotIn('"permission"', enabled)
        self.assertIn("Разрешение на установку не выдано", code)

    def test_installer_rechecks_permission_and_surfaces_verification_errors(self):
        code = source("EngineImportUi")
        install = code.split("fun install()", 1)[1].split("fun installFailed(", 1)[0]
        self.assertLess(install.index("canRequestPackageInstalls()"), install.index("requireNotNull(pending.file)"))
        self.assertLess(install.index("EnginePackages.verifyArchive"), install.index("Intent.ACTION_INSTALL_PACKAGE"))
        self.assertLess(install.index("EnginePackages.verifyRequirements"), install.index("Intent.ACTION_INSTALL_PACKAGE"))
        self.assertIn("if(error is IllegalArgumentException)error.message.orEmpty()", code)
        self.assertEqual(2, code.count("installFailed(error)"))

    def test_oem_resume_is_wired_and_rechecks_actual_permission(self):
        ui = source("EngineImportUi")
        activity = source("MainActivity")
        self.assertIn("engineImport.resumed()", activity.split("override fun onResume()", 1)[1].split("override fun onPause()", 1)[0])
        self.assertIn("engineImport.paused()", activity.split("override fun onPause()", 1)[1].splitlines()[0])
        resumed = ui.split("fun resumed()", 1)[1].split("fun returned(", 1)[0]
        self.assertIn('pending.phase=="permission"&&pending.permissionReturn.consume(true)', resumed)
        self.assertIn("permissionReturned(pending,activity.packageManager.canRequestPackageInstalls())", resumed)
        self.assertIn("pending.permissionReturn.launched()", ui)
        self.assertIn("ownerResumed=true", resumed)
        self.assertIn("ownerResumed=false;state?.permissionReturn?.paused()", ui)
        attach = ui.split("fun attach(pending: State)", 1)[1].split("val input =", 1)[0]
        self.assertIn("if(ownerResumed)resumed()", attach)

    def test_metadata_readers_use_raw_typed_values(self):
        for name in ("EnginePackages", "EngineUpdatePrimary"):
            code = source(name)
            self.assertIn("EngineMetadata", code)
            self.assertNotIn(".getInt(", code)
            self.assertNotIn(".getString(", code)
        primary = source("EngineUpdatePrimary")
        for key in ("primary-core", "kind", "core-api", "entry", "source.repository", "source.version", "source.sha256"):
            self.assertIn(f'"org.mcrf.engines.{key}"', primary)
        self.assertIn('m.requiredString("org.mcrf.engines.kind")', source("EnginePackages"))
        self.assertIn('m.requiredInt("org.mcrf.engines.interface")', source("EnginePackages"))

    def test_catalog_version_checked_after_download_and_before_installer(self):
        packages = source("EnginePackages")
        self.assertIn("verifyArchive(context, file,expected.engine,expected.versionCode)", packages)
        self.assertIn("EngineArchivePolicy.requireCatalogVersion(apk.longVersionCode,expectedVersionCode)", packages)
        install = source("EngineImportUi").split("fun install()", 1)[1].split("fun installFailed(", 1)[0]
        self.assertIn("EnginePackages.verifyArchive(this@with,file,pending.asset?.engine,pending.asset?.versionCode)", install)

    def test_all_native_paths_keep_name_guards_then_check_elf(self):
        for name, guard in (("EnginePackages", "EngineModules.validNativeEntries"),
                            ("EngineUpdatePrimary", "require(validNativeEntries"),
                            ("EngineUpdatePacket", 'require(entries==listOf')):
            code = source(name)
            self.assertLess(code.index(guard), code.index("EngineArchivePolicy.requireElfLibraries"))

    def test_signatures_use_certificate_bytes_without_formatting_or_history(self):
        code = source("EngineSignatures")
        self.assertEqual(2, code.count("EngineSignerPolicy.certificateIdentity(s.toByteArray())"))
        self.assertNotIn("toCharsString", code)
        self.assertNotIn("signingCertificateHistory", code)


if __name__ == "__main__":
    unittest.main()
