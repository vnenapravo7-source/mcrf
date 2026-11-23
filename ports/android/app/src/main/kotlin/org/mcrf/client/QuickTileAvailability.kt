package org.mcrf.client

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/** This controls real TileService components, not VPN state or a cosmetic preference.
 * Android owns placement in Quick Settings; enabling a component does not pin it. */
internal object QuickTileAvailability {
    data class Spec(val title:String,val service:Class<out McrfQuickTile>,val icon:Int)
    const val ROUTE_TITLE="Маршруты MCRF"
    fun previewIcon(context:Context,resource:Int):android.graphics.drawable.Icon {
        val size=(24*context.resources.displayMetrics.density).toInt().coerceAtLeast(24)
        val image=android.graphics.Bitmap.createBitmap(size,size,android.graphics.Bitmap.Config.ARGB_8888)
        requireNotNull(context.getDrawable(resource)).mutate().apply {
            setBounds(0,0,size,size)
            draw(android.graphics.Canvas(image))
        }
        return android.graphics.drawable.Icon.createWithBitmap(image)
    }
    val tiles=listOf(
        Spec("VPN",VpnQuickTile::class.java,R.drawable.qs_rkn_diamond),
        Spec("БС",BsQuickTile::class.java,R.drawable.qs_bs),
        Spec(ROUTE_TITLE,RulesQuickTile::class.java,R.drawable.qs_rules),
        Spec("Автоопределение БС",AutoBsQuickTile::class.java,R.drawable.qs_auto_bs),
        Spec("Anubis",AnubisQuickTile::class.java,R.drawable.qs_anubis))

    internal fun available(state:Int,manifestEnabled:Boolean):Boolean=when(state) {
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT->manifestEnabled
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED->true
        else->false
    }
    fun enabled(context:Context,spec:Spec):Boolean {
        val component=ComponentName(context,spec.service)
        val manager=context.packageManager
        val state=manager.getComponentEnabledSetting(component)
        @Suppress("DEPRECATION")
        val declared=manager.getServiceInfo(component,PackageManager.MATCH_DISABLED_COMPONENTS).enabled
        return available(state,declared)
    }
    fun setEnabled(context:Context,spec:Spec,enabled:Boolean) {
        require(spec in tiles)
        context.packageManager.setComponentEnabledSetting(ComponentName(context,spec.service),
            if(enabled)PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
            PackageManager.DONT_KILL_APP)
    }
}
