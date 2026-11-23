package org.mcrf.client

import android.widget.LinearLayout
import android.view.View
import org.json.JSONObject

/** The full route dialog commits to the PROFILE draft only; cancel has no external effect. */
internal class NetworkProfileRuleAdapter(
    private val activity:MainActivity,private val owner:LinearLayout,
    private val draft:ProfileRoutingDraft,private val changed:()->Unit
):RouteRuleUi.Session {
    private val token=draft.editToken()
    private val attachment=object:View.OnAttachStateChangeListener {
        override fun onViewAttachedToWindow(view:View)=Unit
        override fun onViewDetachedFromWindow(view:View){dismiss()}
    }
    init {owner.addOnAttachStateChangeListener(attachment)}
    override val scope=RouteRuleUi.Scope.PROFILE
    override fun snapshot()=draft.snapshot()
    override fun revision()=draft.revision()
    override fun generation()=draft.generation
    override fun editable(rule:JSONObject)=TextGeoRules.editable(rule)
    override fun dismiss() {
        token.cancel();owner.removeOnAttachStateChangeListener(attachment)
    }
    override fun commit(revision:String,generation:String,edit:(JSONObject)->Unit,saved:()->Unit,failed:(Exception)->Unit) {
        AndroidDeployIo.submit({draft.edit(revision,generation,validate={PresetAssets.validate(activity,it)},token=token,change=edit)}) {result->
            if(!token.isActive()||!owner.isAttachedToWindow||activity.isFinishing||activity.isDestroyed)return@submit
            result.onSuccess {saved();changed()}.onFailure {failed(it as? Exception?:IllegalArgumentException("Не удалось проверить правило."))}
        }
    }
}
