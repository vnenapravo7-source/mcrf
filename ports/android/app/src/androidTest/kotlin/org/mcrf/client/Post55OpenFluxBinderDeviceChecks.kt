package org.mcrf.client

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Parcel
import java.io.File
import java.util.UUID
import java.util.function.Consumer

/** Read-only declarations + actual local Android Binder rejection, not cross-process/cookie proof.
 * Explicit isolated final-batch mode. No Activity/WebView/provider/account/VPN is opened. */
object Post55OpenFluxBinderDeviceChecks {
    @JvmStatic fun run(base: Context, temporary: File, passed: Consumer<String>) {
        check(base.packageName == "org.mcrf.p3test")
        check(base.packageManager.getPackageInfo(base.packageName, 0).longVersionCode >= 10056)
        check(!RemoteServerOperationGate.busy())
        check(temporary.canonicalFile.parentFile == base.cacheDir.canonicalFile && !temporary.exists() && temporary.mkdir())
        fun verify(ok: Boolean, message: String) { check(ok) { message }; passed.accept(message) }
        val activity = base.packageManager.getActivityInfo(ComponentName(base, OpenFluxVerificationActivity::class.java), 0)
        verify(!activity.exported && activity.processName == "${base.packageName}:openfluxVerification", "OpenFlux browser is private and assigned to its own process")
        val serviceInfo = base.packageManager.getServiceInfo(ComponentName(base, OpenFluxVerificationService::class.java), 0)
        val receiver = base.packageManager.getReceiverInfo(ComponentName(base, OpenFluxVerificationCancelReceiver::class.java), 0)
        verify(!serviceInfo.exported && serviceInfo.processName == base.applicationInfo.processName && !receiver.exported && receiver.processName == base.applicationInfo.processName,
            "Private registry service/cancel receiver stay in the main process")
        verify(Application.getProcessName() == base.applicationInfo.processName && !OpenFluxVerificationWebProfile.ready,
            "Main-process initialization has not selected the OpenFlux browser profile")
        val service = OpenFluxVerificationService()
        val id = UUID.randomUUID().toString()
        val intent = Intent(base, OpenFluxVerificationService::class.java)
            .setData(Uri.parse("mcrf-openflux-bind://request/$id/${UUID.randomUUID()}"))
            .putExtra("request", id)
        try {
            val endpoint = requireNotNull(service.onBind(intent))
            val input = Parcel.obtain(); val output = Parcel.obtain()
            try {
                input.writeInt(12345) // Deliberately no valid interface token or payload.
                val accepted = endpoint.transact(1, input, output, 0)
                verify(!accepted && output.dataSize() == 0, "Actual local Binder endpoint rejects same-PID malformed attach before payload decoding")
            } finally { input.recycle(); output.recycle() }
            verify(service.onBind(Intent(intent).putExtra("request", "not-a-request-id")) == null,
                "Malformed binding ID cannot create a private request endpoint")
            val fixtureChild = UUID.randomUUID().toString().replace("-", "")
            var nativeCalls = 0
            val registry = OpenFluxVerificationRequests.registry
            val request = requireNotNull(registry.register(fixtureChild, UUID.randomUUID().toString().replace("-", ""),
                requireNotNull(OpenFluxVerificationPolicy.externalPage("https://captcha.invalid/", "", false, "")), { true }, { nativeCalls++; true }))
            try {
                OpenFluxVerificationCancelReceiver().onReceive(base,
                    Intent().setAction("synthetic-unknown").putExtra("request", request.id))
                verify(registry.get(request.id) === request && nativeCalls == 0,
                    "Actual Android receiver ignores an unknown action without cancelling its owned fixture or posting UI")
            } finally { registry.closeChild(fixtureChild) }
        } finally { service.onUnbind(intent); service.onDestroy() }
    }
}
