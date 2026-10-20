package org.mcrf.client

import android.content.Context
import android.os.Looper

/** Local installation identity only. No catalogue/download, APK hashing, account or profile read. */
internal object EngineLogInventory {
    fun refreshDue(now:Long,last:Long?)=last==null||now<last||now-last>=60_000
    fun snapshotAge(now:Long,last:Long?)=if(last==null)"Снимок версий движков ещё не получен."else"Снимок версий движков: ${((now-last).coerceAtLeast(0))/1000} с назад."
    data class Item(val kind:String,val versionCode:Long?,val legacy:Boolean=false,val unavailable:Boolean=false)
    fun format(items:List<Item>):String {
        val known=items.filter {it.kind in EngineModules.allKinds}.sortedBy {it.kind}
        if(known.isEmpty())return "Движки: сведения недоступны."
        return "Движки · локальные версии пакетов (не проверка актуальности):\n"+known.joinToString("\n") {item->
            val name=if(item.kind=="csqtt25")"CSQTT 2.5"else item.kind.uppercase(java.util.Locale.ROOT)
            val state=when {item.unavailable->"сведения недоступны/пакет несовместим";item.versionCode==null->"не установлен";item.versionCode<0->"сведения недоступны";else->"versionCode=${item.versionCode}"+if(item.legacy)" · общий старый пакет"else""}
            "$name: $state"
        }
    }
    fun read(context:Context):String {
        check(Looper.myLooper()!=Looper.getMainLooper()) {"Engine inventory must not block the UI"}
        val pm=context.packageManager
        return format(EngineModules.allKinds.map {kind->
            try {
                val present=try {pm.getApplicationInfo(EngineModules.packageName(kind),0);true}
                    catch(_:android.content.pm.PackageManager.NameNotFoundException){
                        if(kind !in EngineModules.kinds||kind in EngineModules.optionalCores||kind in setOf("csqtt25","tgws"))false
                        else try {val old=pm.getApplicationInfo(EngineModules.LEGACY,0);EngineModules.libraries(kind).all {java.io.File(old.nativeLibraryDir,it).isFile}}
                            catch(_:android.content.pm.PackageManager.NameNotFoundException){false}
                    }
                if(!present)return@map Item(kind,null)
                val info=EngineModules.installation(context,kind)
                Item(kind,pm.getPackageInfo(info.packageName,0).longVersionCode,info.packageName==EngineModules.LEGACY)
            }catch(_:android.content.pm.PackageManager.NameNotFoundException){Item(kind,null)}
            catch(_:Exception){Item(kind,null,unavailable=true)}
        })
    }
}
