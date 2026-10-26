package org.mcrf.client

import org.json.JSONObject

/** Owned by one ordered writer. Failed saves never advance the acknowledged draft. */
internal class RoutingEditBaseline(initial:JSONObject) {
    private var committed=JSONObject(initial.toString())

    fun commit(edited:JSONObject,save:(JSONObject,JSONObject)->Unit) {
        val captured=JSONObject(edited.toString())
        val acknowledged=JSONObject(captured.toString())
        save(JSONObject(committed.toString()),captured)
        committed=acknowledged
    }
}
