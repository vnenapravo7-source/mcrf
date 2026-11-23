package org.mcrf.client

import android.content.Context
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** One isolated IP check at a time, with no change to the active connection. */
internal object ServerCountryLookup {
    private val executor=Executors.newSingleThreadScheduledExecutor {task->
        Thread(task,"mcrf-new-server-country").apply {isDaemon=true}
    }
    private val pending=linkedSetOf<String>()
    private var scheduled=false

    fun enqueue(context:Context,entries:List<ServerImport.Server>){
        val ids=entries.filter {it.configuration==null&&it.protocol !in AutoSelection.AUTOMATIC_EXCLUDED}.map {it.id}
        if(ids.isEmpty())return
        val app=context.applicationContext
        executor.execute {
            pending.addAll(ids)
            while(pending.size>1024)pending.remove(pending.first())
            schedule(app,0)
        }
    }

    private fun schedule(app:Context,seconds:Long){
        if(scheduled||pending.isEmpty())return
        scheduled=true
        executor.schedule({
            scheduled=false
            if(McrfVpnService.hasActiveSession()||ProbeCoordinator.measurements!=null){
                schedule(app,10)
            }else{
                val id=pending.first()
                pending.remove(id)
                if(!checkCountry(app,id))pending.add(id)
                schedule(app,if(id in pending)10 else 1)
            }
        },seconds,TimeUnit.SECONDS)
    }

    private fun checkCountry(app:Context,id:String):Boolean{
        val token=DiagnosticCancellation(45_000)
        try {
            val catalogue=Connections(app).apply {load()}
            val entry=catalogue.entries().firstOrNull {it.id==id}?:return true
            if(catalogue.exitTests().optJSONObject(id)?.optString("country").orEmpty().isNotBlank())return true
            val country=DiagnosticsTemporaryProxy.open(app,entry,token).use {session->
                AutoExclusions.country(session.probe.exitTrace(token))
            }
            catalogue.recordExitCountry(entry,country)
            return true
        }catch(error:DiagnosticsProxyStartupException){
            return error.limitation!=ServerComparisonLimit.BUSY&&
                !(error.limitation==ServerComparisonLimit.PATH_CHANGED&&McrfVpnService.hasActiveSession())
        }catch(_:Exception){
            // The marker remains available and a later manual IP test can supply the country.
            return true
        }finally{token.cancel()}
    }
}
