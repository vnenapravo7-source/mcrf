package org.mcrf.client

import org.json.JSONObject

/** Respect Connections -> storageLock ordering; stale dialogs never overwrite newer edits. */
internal object ServerEditorCommit {
    fun requireUnchanged(current:ServerImport.Server?,expected:JSONObject):ServerImport.Server {
        requireNotNull(current){"Подключение уже удалено. Обнови список."}
        require(RoutingDefaultsEdits.equivalent(ConnectionEdits.snapshot(current),expected)) {
            "Сервер изменён в другом окне. Закрой редактор и открой его заново."
        }
        return current
    }
    fun save(activity:MainActivity,id:String,expected:JSONObject,options:JSONObject?) = synchronized(activity.catalogue) {
        synchronized(ProfileStore.storageLock) {
            val document=ProfileStore(activity,"connections.enc").read()
            val latest=ConnectionCatalogue(document).entries().firstOrNull {it.id==id}
            requireUnchanged(latest,expected)
            activity.catalogue.edit(id,options)
        }
    }
    internal fun prepareMove(document:JSONObject,expected:ServerImport.Server,whitelists:Boolean):JSONObject {
        val latest=requireUnchanged(ConnectionCatalogue(document.toString()).entries().firstOrNull {it.id==expected.id},ConnectionEdits.snapshot(expected))
        require(latest.whitelists==expected.whitelists){"Тип сервера изменён в другом окне. Обнови список."}
        return ServerSectionChoice.move(document,expected.id,whitelists)
    }
    fun moveToSection(activity:MainActivity,expected:ServerImport.Server,whitelists:Boolean) = synchronized(activity.catalogue) {
        synchronized(ProfileStore.storageLock) {
            val store=ProfileStore(activity,"connections.enc")
            val original=JSONObject(store.read())
            val prepared=prepareMove(original,expected,whitelists)
            if(!RoutingDefaultsEdits.equivalent(original,prepared))store.write(prepared.toString())
            activity.catalogue.load()
        }
    }
}
