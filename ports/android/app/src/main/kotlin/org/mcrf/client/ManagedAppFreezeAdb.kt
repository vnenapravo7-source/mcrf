package org.mcrf.client

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.Parcel
import android.os.SystemClock
import java.util.UUID

internal object ManagedAppFreezeAdb {
    const val PACKAGE = "org.mcrf.client"
    const val UI_CLONE_PACKAGE = "$PACKAGE.ui20261120"
    const val SHELL_UID = 2000
    const val ATTACH = "attach"
    const val GET = "get"
    const val BINDER = "binder"
    const val REJECTED = "rejected"
    const val DESCRIPTOR = "$PACKAGE.ManagedAppFreezeAdb"
    const val CHANGE = IBinder.FIRST_CALL_TRANSACTION
    const val QUERY = CHANGE + 1
    const val STOP = CHANGE + 2

    private val gate = FreezeAdbActivation()
    private val lifetime = Binder()
    @Volatile private var session: IBinder? = null
    private var sessionNonce: String? = null

    val enabled: Boolean
        get() = session?.isBinderAlive == true

    fun ownerPackageForBuild(applicationId: String, debug: Boolean): String? = when {
        applicationId == PACKAGE -> PACKAGE
        applicationId == UI_CLONE_PACKAGE && debug -> UI_CLONE_PACKAGE
        else -> null
    }

    fun ownerPackageForBuild() = ownerPackageForBuild(BuildConfig.APPLICATION_ID, BuildConfig.DEBUG)

    fun authority(ownerPackage: String) = "$ownerPackage.freeze-adb"

    fun supports(context: Context) = context.packageName == ownerPackageForBuild() &&
        FreezeAdbActivation.isOwnerUid(context.applicationInfo.uid)

    fun supportsDesktop(context: Context) = context.packageName == PACKAGE && supports(context)

    @Synchronized
    fun activationCommand(context: Context): String {
        check(supportsDesktop(context))
        return "adb shell '${helperCommand(gate.issue(SystemClock.elapsedRealtime()))}'"
    }

    internal class LocalActivation internal constructor(internal val nonce: String, private val ownerPackage: String = PACKAGE) {
        val shellCommand: String get() = helperCommand(nonce, ownerPackage, detached = true)
    }

    /** Call after an explicitly paired local transport has connected. */
    @Synchronized
    fun beginLocalActivation(context: Context): LocalActivation {
        check(Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && supports(context) && !enabled)
        return LocalActivation(gate.issue(SystemClock.elapsedRealtime(), local = true), context.packageName)
    }

    @Synchronized
    fun localActivationBinder(activation: LocalActivation): IBinder? =
        session?.takeIf { sessionNonce == activation.nonce && it.isBinderAlive }

    /** Return the Binder to stop only if this attempt attached it. */
    @Synchronized
    fun cancelLocalActivation(activation: LocalActivation): IBinder? {
        gate.cancel(activation.nonce)
        if (sessionNonce != activation.nonce) return null
        sessionNonce = null
        return session.also { session = null }
    }

    private fun helperCommand(nonce: String, ownerPackage: String = PACKAGE, detached: Boolean = false): String {
        check(ownerPackage == ownerPackageForBuild())
        check(runCatching { UUID.fromString(nonce).toString() == nonce }.getOrDefault(false))
        val command = "CLASSPATH=\$(pm path --user 0 $ownerPackage | head -n 1 | cut -d: -f2) " +
            "${if (detached) "/system/bin/setsid " else ""}app_process /system/bin ${ManagedAppFreezeAdbDaemon::class.java.name} $nonce"
        return if (detached) "$command </dev/null >/dev/null 2>&1 &" else command
    }

    @Synchronized
    fun attach(context: Context, caller: Int, nonce: String?, binder: IBinder?): Bundle {
        check(supports(context))
        if (enabled || binder == null || !gate.consume(caller, nonce, SystemClock.elapsedRealtime()))
            return Bundle().apply { putBoolean(REJECTED, true) }
        binder.linkToDeath({
            synchronized(this) {
                if (session === binder) {
                    session = null
                    sessionNonce = null
                }
            }
            ManagedAppFreezeUi.adbAvailabilityChanged(context)
        }, 0)
        session = binder
        sessionNonce = nonce
        return Bundle().apply { putBinder(BINDER, lifetime) }
    }

    @Synchronized
    fun forOwner(ownerUid: Int, caller: Int): Bundle {
        require(FreezeAdbActivation.canUse(ownerUid, caller))
        return Bundle().apply { putBinder(BINDER, session) }
    }

    @Synchronized
    fun detach(): IBinder? {
        gate.cancel()
        sessionNonce = null
        return session.also { session = null }
    }

    fun stop(binder: IBinder) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            binder.transact(STOP, data, null, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
        }
    }

    fun change(context: Context, id: String, state: Int) {
        check(supports(context))
        validateTarget(id, context.packageName)
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
            require(ManagedAppFreezePolicy.blockReason(context, id) == null)
        val binder = checkNotNull(session)
        request(binder, CHANGE, id, state)
        check(request(binder, QUERY, id, null) == state) { "Android did not retain the requested application state" }
    }

    fun validateTarget(id: String, ownerPackage: String) {
        ManagedAppFreezeCommand.validateTarget(id, ownerPackage)
        require(ownerPackage != UI_CLONE_PACKAGE || id != PACKAGE)
    }

    private fun request(binder: IBinder, code: Int, id: String, state: Int?): Int? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(DESCRIPTOR)
            data.writeString(id)
            if (state != null) data.writeInt(state)
            check(binder.transact(code, data, reply, 0))
            reply.readException()
            return if (code == QUERY) reply.readInt() else null
        } finally {
            data.recycle()
            reply.recycle()
        }
    }
}

internal class FreezeAdbActivation {
    private var nonce: String? = null
    private var validUntil = 0L
    private var shellOnly = false

    fun issue(now: Long, local: Boolean = false): String = UUID.randomUUID().toString().also {
        nonce = it
        validUntil = now + if (local) 15_000 else 300_000
        shellOnly = local
    }

    fun consume(caller: Int, submitted: String?, now: Long): Boolean {
        if (!canAttach(caller) || (shellOnly && caller != ManagedAppFreezeAdb.SHELL_UID) ||
            nonce == null || submitted != nonce || now >= validUntil) return false
        cancel()
        return true
    }

    fun cancel() {
        nonce = null
    }

    fun cancel(issuedNonce: String) {
        if (nonce == issuedNonce) cancel()
    }

    companion object {
        fun canAttach(uid: Int) = uid == 0 || uid == ManagedAppFreezeAdb.SHELL_UID
        fun isOwnerUid(uid: Int) = uid in 10_000 until 100_000
        fun canUse(ownerUid: Int, caller: Int) = isOwnerUid(ownerUid) && caller == ownerUid
    }
}

class ManagedAppFreezeAdbProvider : ContentProvider() {
    override fun onCreate() = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val app = checkNotNull(context).applicationContext
        val caller = Binder.getCallingUid()
        require(ManagedAppFreezeAdb.supports(app))
        return when (method) {
            ManagedAppFreezeAdb.ATTACH -> {
                require(FreezeAdbActivation.canAttach(caller))
                ManagedAppFreezeAdb.attach(app, caller, arg, extras?.getBinder(ManagedAppFreezeAdb.BINDER))
                    .also { ManagedAppFreezeUi.adbAvailabilityChanged(app) }
            }
            ManagedAppFreezeAdb.GET -> ManagedAppFreezeAdb.forOwner(app.applicationInfo.uid, caller)
            else -> throw UnsupportedOperationException()
        }
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
                       selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?) = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?,
                        selectionArgs: Array<out String>?) = 0
}
