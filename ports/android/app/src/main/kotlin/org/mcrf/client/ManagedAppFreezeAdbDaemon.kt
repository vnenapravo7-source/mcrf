package org.mcrf.client

import android.content.AttributionSource
import android.content.Context
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.Process
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.TimeUnit
import kotlin.system.exitProcess

/** Entry point of the explicitly launched ADB process from the installed MCRF APK. */
object ManagedAppFreezeAdbDaemon {
    private const val REJECTED = "MCRF_ADB_AUTH_REJECTED"
    private const val START_FAILED = "MCRF_ADB_START_FAILED"

    @JvmStatic
    fun main(args: Array<String>) {
        var stage = "caller"
        try {
            require(Process.myUid() == ManagedAppFreezeAdb.SHELL_UID)
            val ownerPackage = checkNotNull(ManagedAppFreezeAdb.ownerPackageForBuild())
            val nonce = args.single()
            require(nonce.matches(Regex("[a-f0-9-]{36}")))
            stage = "context"
            @Suppress("DEPRECATION")
            if (Looper.getMainLooper() == null) Looper.prepareMainLooper()
            val thread = Class.forName("android.app.ActivityThread")
            val system = thread.getMethod("getSystemContext")
                .invoke(thread.getMethod("systemMain").invoke(null)) as Context
            val shell = system.createPackageContext("com.android.shell", 0)
            stage = "owner"
            val appUid = shell.packageManager.getApplicationInfo(ownerPackage, 0).uid
            require(FreezeAdbActivation.isOwnerUid(appUid))
            val authority = ManagedAppFreezeAdb.authority(ownerPackage)
            val provider = checkNotNull(shell.packageManager.resolveContentProvider(authority, 0))
            require(provider.packageName == ownerPackage && provider.applicationInfo.uid == appUid &&
                provider.authority == authority && provider.name == ManagedAppFreezeAdbProvider::class.java.name)
            stage = "handoff"
            val lifetime = handoff(authority, nonce, FreezeService(shell, ownerPackage, appUid))
            if (lifetime == null) {
                System.err.println(REJECTED)
                exitProcess(2)
            }
            stage = "lifetime"
            lifetime.linkToDeath({ stop() }, 0)
        } catch (error: Throwable) {
            if (error !is Exception && error !is LinkageError) throw error
            val cause = (error as? InvocationTargetException)?.targetException ?: error
            System.err.println("$START_FAILED:$stage:${cause.javaClass.simpleName}")
            exitProcess(1)
        }
        println("MCRF ADB connected. Keep this terminal open.")
        Looper.loop()
    }

    private fun stop() = Process.killProcess(Process.myPid())

    // ADB shell has ACCESS_CONTENT_PROVIDERS_EXTERNALLY. Android 10, 11,
    // and 12+ use different framework call signatures for the same operation.
    private fun handoff(authority: String, nonce: String, service: IBinder): IBinder? {
        val activityManager = Class.forName("android.app.ActivityManager").getMethod("getService").invoke(null)
        val managerApi = Class.forName("android.app.IActivityManager")
        val get = managerApi.getMethod("getContentProviderExternal", String::class.java,
            Int::class.javaPrimitiveType, IBinder::class.java, String::class.java)
        val release = managerApi.getMethod("removeContentProviderExternalAsUser",
            String::class.java, IBinder::class.java, Int::class.javaPrimitiveType)
        val token = Binder()
        val holder = checkNotNull(get.invoke(activityManager, authority, 0, token, authority))
        try {
            val provider = checkNotNull(holder.javaClass.getField("provider").get(holder))
            val api = Class.forName("android.content.IContentProvider")
            val string = String::class.java
            val bundle = Bundle::class.java
            val shell = "com.android.shell"
            val extras = Bundle().apply { putBinder(ManagedAppFreezeAdb.BINDER, service) }
            val method = ManagedAppFreezeAdb.ATTACH
            val response = when {
                Build.VERSION.SDK_INT >= 31 -> {
                    val source = AttributionSource.Builder(Process.myUid()).setPackageName(shell).build()
                    api.getMethod("call", AttributionSource::class.java, string, string, string, bundle)
                        .invoke(provider, source, authority, method, nonce, extras)
                }
                Build.VERSION.SDK_INT == 30 ->
                    api.getMethod("call", string, string, string, string, string, bundle)
                        .invoke(provider, shell, null, authority, method, nonce, extras)
                else ->
                    api.getMethod("call", string, string, string, string, bundle)
                        .invoke(provider, shell, authority, method, nonce, extras)
            } as? Bundle
            checkNotNull(response)
            if (response.getBoolean(ManagedAppFreezeAdb.REJECTED)) return null
            return checkNotNull(response.getBinder(ManagedAppFreezeAdb.BINDER))
        } finally {
            release.invoke(activityManager, authority, token, 0)
        }
    }

    private class FreezeService(private val shell: Context, private val ownerPackage: String, private val ownerUid: Int) : Binder() {
        private val mutationLock = Any()

        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            require(FreezeAdbActivation.canUse(ownerUid, getCallingUid())) { "Invalid ADB freeze caller" }
            require(code in ManagedAppFreezeAdb.CHANGE..ManagedAppFreezeAdb.STOP)
            data.enforceInterface(ManagedAppFreezeAdb.DESCRIPTOR)
            if (code == ManagedAppFreezeAdb.STOP) {
                require(data.dataAvail() == 0)
                stop()
                return true
            }
            val id = requireNotNull(data.readString())
            ManagedAppFreezeAdb.validateTarget(id, ownerPackage)
            val response = requireNotNull(reply)
            val identity = clearCallingIdentity()
            try {
                if (code == ManagedAppFreezeAdb.QUERY) {
                    require(data.dataAvail() == 0)
                    val state = shell.packageManager.getApplicationEnabledSetting(id)
                    response.writeNoException()
                    response.writeInt(state)
                } else {
                    val state = data.readInt()
                    require(data.dataAvail() == 0)
                    synchronized(mutationLock) {
                        // Recheck package-manager guards here. Settings-backed role checks
                        // run in the registered app process before the Binder call.
                        val action = ManagedAppFreezeCommand.action(shell, id, state)
                        changeState(action, id)
                    }
                    response.writeNoException()
                }
            } finally {
                restoreCallingIdentity(identity)
            }
            return true
        }

        private fun changeState(action: String, id: String) {
            val discard = File("/dev/null")
            val child = ProcessBuilder("/system/bin/pm", action, "--user", "0", id)
                .redirectInput(discard).redirectOutput(discard).redirectError(discard).start()
            try {
                check(child.waitFor(8, TimeUnit.SECONDS) && child.exitValue() == 0) {
                    "Android rejected the application state change"
                }
            } finally {
                if (child.isAlive) child.destroyForcibly()
            }
        }
    }
}
