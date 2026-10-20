package org.mcrf.client

import android.content.Context
import android.graphics.*
import android.util.Xml
import org.xmlpull.v1.XmlPullParser

/** Bundled SVG primitives only: no network, bitmap textures or per-frame parsing. */
object PatternWallpaper {
    const val KEY="themeWallpaper"
    val choices=linkedMapOf("none" to "Без узора").apply {WallpaperCombinations.all.forEach {put(it.id,it.title)}}
    @Volatile private var paths:List<List<Path>> = emptyList()
    private var loaded="none"
    @Synchronized fun configure(context:Context,id:String){
        val selected=WallpaperCombinations.canonical(id)
        if(selected==loaded)return
        val composition=WallpaperCombinations.all.firstOrNull {it.id==selected}
        val prepared=if(composition==null)emptyList()else listOf(composition.first,composition.second).map {asset->context.assets.open("wallpapers/$asset.svg").use {stream->
            val parser=Xml.newPullParser();parser.setInput(stream,"UTF-8")
            val result=mutableListOf<Path>()
            fun n(key:String)=parser.getAttributeValue(null,key)?.toFloatOrNull()?:0f
            while(parser.eventType!=XmlPullParser.END_DOCUMENT){
                if(parser.eventType==XmlPullParser.START_TAG){
                    val p=Path()
                    when(parser.name){
                        "circle"->p.addCircle(n("cx"),n("cy"),n("r"),Path.Direction.CW)
                        "rect"->p.addRoundRect(n("x"),n("y"),n("x")+n("width"),n("y")+n("height"),n("rx"),n("rx"),Path.Direction.CW)
                        "line"->{p.moveTo(n("x1"),n("y1"));p.lineTo(n("x2"),n("y2"))}
                        "polygon","polyline"->{
                            val points=parser.getAttributeValue(null,"points").trim().split(Regex("[,\\s]+" )).map(String::toFloat)
                            require(points.size in 4..128&&points.size%2==0)
                            p.moveTo(points[0],points[1]);for(i in 2 until points.size step 2)p.lineTo(points[i],points[i+1])
                            if(parser.name=="polygon")p.close()
                        }
                    }
                    if(!p.isEmpty)result.add(p)
                }
                parser.next()
            }
            require(result.isNotEmpty()&&result.size<=64);result.toList()
        }}
        paths=prepared;loaded=selected
    }
    fun draw(canvas:Canvas,bounds:Rect,density:Float){
        val captured=paths;if(captured.isEmpty())return
        val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style=Paint.Style.STROKE;strokeWidth=1.1f;strokeCap=Paint.Cap.ROUND;strokeJoin=Paint.Join.ROUND
            color=Loft.mix(Loft.ACCENT,HomeUiTheme.palette(Loft.dark).glow,.35f);alpha=if(Loft.dark)38 else 28
        }
        val tile=64*density;val scale=tile/120
        val saved=canvas.save();canvas.clipRect(bounds)
        for(y in 0..(bounds.height()/tile).toInt())for(x in 0..(bounds.width()/tile).toInt()){
            val stamp=canvas.save();canvas.translate(bounds.left+x*tile,bounds.top+y*tile);canvas.scale(scale,scale)
            canvas.rotate(if((x+y)%2==0)-8f else 8f,60f,60f)
            captured[(x+y)%captured.size].forEach {canvas.drawPath(it,paint)};canvas.restoreToCount(stamp)
        }
        canvas.restoreToCount(saved)
    }
}
