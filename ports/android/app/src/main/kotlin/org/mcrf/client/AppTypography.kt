package org.mcrf.client

import android.content.Context
import android.graphics.Typeface
import android.graphics.fonts.Font
import android.graphics.fonts.FontFamily
import android.net.Uri
import android.util.AtomicFile
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import java.io.File
import java.util.WeakHashMap

internal object AppTypography {
    const val ENABLED="appearanceNothingFont"
    const val INTERFACE="appearanceInterfaceFont"
    const val DISPLAY="appearanceDisplayFont"
    const val CUSTOM="custom"
    const val DEFAULT="MatrixSansPrint"
    enum class Scope(val key:String,val request:Int,val file:String) {
        INTERFACE(AppTypography.INTERFACE,61,"appearance-nothing.font"),
        DISPLAY(AppTypography.DISPLAY,62,"appearance-display.font")
    }
    val displayChoices=linkedMapOf(DEFAULT to "Matrix Sans Print","" to "Системный",
        "Tiny5" to "Tiny5","Jura" to "Jura","Tektur" to "Tektur","Bellota" to "Bellota",
        "MontserratAlternates" to "Montserrat Alternates","BalsamiqSans" to "Balsamiq Sans")
    fun choices(@Suppress("UNUSED_PARAMETER") scope:Scope)=displayChoices
    private const val MAX_BYTES=4*1024*1024
    private data class Imported(val stamp:Pair<Long,Long>,val face:Typeface?)
    private val imports=mutableMapOf<File,Imported>()
    private data class FontStyle(val base:Typeface,val weight:Int,val italic:Boolean)
    private data class Applied(val original:Typeface?,val face:Typeface?)
    private val styled=mutableMapOf<FontStyle,Typeface>()
    private val bundled=mutableMapOf<String,Typeface>()
    private val applied=WeakHashMap<TextView,Applied>()
    private var displayFace:Typeface?=null
    var active:Typeface?=null
        private set

    private fun fontFile(context:Context,scope:Scope,key:String=CUSTOM):File {
        val own=File(context.noBackupFilesDir,scope.file)
        return if(key=="ndot")File(context.noBackupFilesDir,"appearance-ndot.font") else own
    }
    fun hasFile(context:Context,scope:Scope,key:String)=fontFile(context,scope,key).isFile
    private fun bundled(context:Context,name:String)=bundled.getOrPut(name){
        Typeface.createFromAsset(context.assets,"fonts/$name.ttf")
    }

    fun selected(context:Context,scope:Scope):String{
        val prefs=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE)
        val key=prefs.getString(scope.key,null)
            ?:if(scope==Scope.INTERFACE&&prefs.getBoolean(ENABLED,false)&&hasFile(context,scope,CUSTOM))CUSTOM else DEFAULT
        if(key=="ndot"&&hasFile(context,scope,"ndot"))return CUSTOM
        return key.takeIf {it in choices(scope)||it==CUSTOM&&hasFile(context,scope,CUSTOM)}?:DEFAULT
    }

    fun configure(context:Context){
        fun resolve(scope:Scope):Typeface? {
            val name=selected(context,scope)
            if(name.isEmpty())return null
            if(name!=CUSTOM)return bundled(context,name)
            val prefs=context.getSharedPreferences("MainActivity",Context.MODE_PRIVATE)
            val file=fontFile(context,scope,if(prefs.getString(scope.key,null)=="ndot")"ndot"else CUSTOM)
            val stamp=file.lastModified() to file.length()
            imports[file]?.takeIf {it.stamp==stamp}?.let {return it.face}
            val face=runCatching {
                val family=FontFamily.Builder(Font.Builder(file).build()).build()
                Typeface.CustomFallbackBuilder(family).setSystemFallback("sans-serif").build()
            }.getOrNull()
            imports[file]=Imported(stamp,face)
            return face
        }
        val next=resolve(Scope.INTERFACE)
        val display=resolve(Scope.DISPLAY)
        if(active!==next||displayFace!==display){
            active=next
            displayFace=display
            styled.clear()
        }
    }

    fun importFont(context:Context,scope:Scope,key:String,uri:Uri){
        val target=fontFile(context,scope,key)
        val staged=File.createTempFile("appearance-", ".font",context.noBackupFilesDir)
        try {
            context.contentResolver.openInputStream(uri)?.use {input->
                staged.outputStream().use {output->
                    val buffer=ByteArray(8192)
                    var written=0
                    while(true){
                        val count=input.read(buffer)
                        if(count<0)break
                        written+=count
                        require(written<=MAX_BYTES){"Файл шрифта больше 4 МБ."}
                        output.write(buffer,0,count)
                    }
                }
            }?:error("Не удалось прочитать файл шрифта.")
            require(runCatching {Font.Builder(staged).build()}.isSuccess){"Выберите файл шрифта TTF или OTF."}
            val atomic=AtomicFile(target)
            val output=atomic.startWrite()
            try {staged.inputStream().use {it.copyTo(output)};atomic.finishWrite(output)}
            catch(error:Exception){atomic.failWrite(output);throw error}
        }finally{staged.delete()}
    }

    fun watch(root:View){
        root.viewTreeObserver.addOnGlobalLayoutListener {applyTree(root)}
        applyTree(root)
    }

    fun applyTree(root:View){
        if(active==null&&displayFace==null&&applied.isEmpty())return
        fun apply(view:View){
            if(view is TextView){
                val emphasis=isDisplayText(view)
                val face=if(emphasis)displayFace?:active else active
                val previous=applied[view]
                val old=view.typeface
                val original=if(previous!=null&&old==previous.face)previous.original else old
                if(face==null){
                    if(previous!=null&&old!=original)view.typeface=original
                    applied.remove(view)
                }else{
                    val key=FontStyle(face,original?.weight?:400,original?.isItalic==true)
                    val next=styled.getOrPut(key){Typeface.create(key.base,key.weight,key.italic)}
                    if(old!=next)view.typeface=next
                    if(previous==null||old!=view.typeface)applied[view]=Applied(original,view.typeface)
                }
            }
            if(view is ViewGroup)for(index in 0 until view.childCount)apply(view.getChildAt(index))
        }
        apply(root)
    }

    private fun isDisplayText(view:TextView):Boolean{
        if(view is android.widget.Button||view is android.widget.EditText||view is android.widget.CompoundButton)return false
        if(view.isAccessibilityHeading||view is ConnectionCaptionView)return true
        val tag=view.tag as? String?:return false
        return tag in setOf("server-ping","orbit-details-ping","home-turn-streams","display-font-sample","orbit-server-title")||
            tag.startsWith("orbit-ping-")
    }
}
