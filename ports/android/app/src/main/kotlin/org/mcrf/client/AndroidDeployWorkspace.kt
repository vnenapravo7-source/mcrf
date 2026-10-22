package org.mcrf.client

import org.json.JSONObject
import java.util.UUID

/** Named independent drafts. A recipe is a type/filter, never a record key. */
object AndroidDeployWorkspace {
    const val SECTION="deployWorkspace"
    private val uuid=Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
    fun empty()=JSONObject().put("schema",1).put("drafts",JSONObject()).put("selected",JSONObject())
    fun migrate(current:JSONObject,legacy:JSONObject):JSONObject {
        val root=if(current.length()==0)empty()else JSONObject(current.toString()).also(::validate)
        // Only initial import: subsequently a fresh draft must remain fresh.
        if(current.length()==0)for(recipe in legacy.keys()){
            val id=UUID.nameUUIDFromBytes(("mcrf.deploy.draft.$recipe").toByteArray(Charsets.UTF_8)).toString()
            root.getJSONObject("drafts").put(id,record(recipe,recipe,legacy.getJSONObject(recipe)))
            root.getJSONObject("selected").put(recipe,id)
        }
        validate(root);return root
    }
    fun record(recipe:String,name:String,values:JSONObject,panelResultKey:String="")=JSONObject()
        .put("recipe",recipe).put("name",AndroidDeployInventory.name(name)).put("values",JSONObject(values.toString())).put("panelResultKey",panelResultKey)
    fun validate(root:JSONObject){
        require(root.keys().asSequence().toSet()==setOf("schema","drafts","selected")&&root.get("schema")==1)
        val drafts=root.getJSONObject("drafts");val selected=root.getJSONObject("selected")
        require(drafts.length()<=128&&selected.length()<=AndroidDeployRecipe.all.size)
        for(id in drafts.keys()){
            require(uuid.matches(id));val item=drafts.getJSONObject(id)
            require(item.keys().asSequence().toSet()==setOf("recipe","name","values","panelResultKey"))
            require(item.get("recipe") is String&&AndroidDeployRecipe.all.any {it.id==item.getString("recipe")})
            require(item.get("name") is String&&AndroidDeployInventory.name(item.getString("name"))==item.getString("name"))
            require(item.get("panelResultKey") is String&&item.getString("panelResultKey").length<=400&&item.getString("panelResultKey").none(Char::isISOControl))
            // Reuse the exact legacy secret/schema constraints, not a second lax validator.
            AndroidDeployBackupSettings.validate(JSONObject().put("deployDrafts",JSONObject().put(item.getString("recipe"),item.getJSONObject("values"))))
        }
        for(recipe in selected.keys()){
            require(selected.get(recipe) is String)
            require(drafts.getJSONObject(selected.getString(recipe)).getString("recipe")==recipe)
        }
    }
}
