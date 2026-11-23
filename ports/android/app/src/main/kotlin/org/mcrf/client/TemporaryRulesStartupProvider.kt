package org.mcrf.client

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri

/** Runs before Application/VPN startup. Failure cannot bypass the RoutingStore runtime gate. */
class TemporaryRulesStartupProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context ?: return false
        reconcileMetadata(app)
        return true
    }
    companion object {
        /** Existing-document housekeeping only, never connection admission or a runtime snapshot.
         * Keep read/expiry/legacy removal/write/scheduling in the same atomic storage scope.
         */
        internal fun reconcileMetadata(app: Context): Boolean {
            // Reconcile only an existing document; do not trigger legacy migration at provider startup.
            return runCatching { synchronized(ProfileStore.storageLock) {
                val disk = ProfileStore(app, "routing.enc")
                val raw = disk.read()
                if (raw.isNotEmpty()) {
                    val root = org.json.JSONObject(raw)
                    val retired=TemporaryRules.removeLegacy(root)
                    TemporaryRules.validate(root)
                    if (TemporaryRules.reconcile(root, TemporaryRulesRuntime.clock(app))||retired)
                        ProfileStore.withoutHistory { disk.write(root.toString()) }
                    TemporaryRulesRuntime.schedule(app, root)
                }
            } }.onFailure { ClientEvents.record(app,"Маршруты","Не удалось проверить временные правила при запуске. Сохранённые данные не изменены.") }.isSuccess
        }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
