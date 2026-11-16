package org.mcrf.client

/** The group header owns the top corners; only the final row owns the bottom pair. */
internal object ServerRowShape {
    fun corners(density:Float,width:Int,height:Int,last:Boolean):FloatArray {
        val radius=if(last&&density.isFinite()&&density>0)minOf(16*density,width.coerceAtLeast(0)/2f,height.coerceAtLeast(0)/2f)else 0f
        return floatArrayOf(0f,0f,0f,0f,radius,radius,radius,radius)
    }
}
