package org.mcrf.client

import android.app.Activity
import android.app.Instrumentation
import android.os.Bundle
import java.net.UnknownHostException

/** Owned emulator only: real encrypted storage, atomic snapshot replacement and failure retention. */
class InviteSnapshotInstrumentation:Instrumentation() {
    override fun onCreate(arguments:Bundle?){super.onCreate(arguments);start()}
    override fun onStart(){
        val report=Bundle();var result=Activity.RESULT_CANCELED;var before:Map<String,String>?=null
        try {
            check(android.os.Build.MODEL.contains("sdk",true)&&!McrfVpnService.hasActiveSession())
            before=ProfileStore.snapshot(targetContext)
            fun link(port:Int)="socks://127.0.0.1:$port"
            val fixture=ConnectionCatalogue("").apply {add(link(10001)+"\n"+link(10002))}
            ProfileStore(targetContext,"connections.enc").write(fixture.serialize())
            val catalogue=Connections(targetContext).apply {load()}
            fun apply(ports:List<Int>) {
                val manifest=InviteCodeSync.Manifest(null,null,null,ports.map(::link))
                val plan=InviteCodeSync.prepareAvailable(manifest,{error("Unexpected subscription")},{error("Unexpected bypass")})
                plan.commitInvite(catalogue)
            }
            apply(listOf(10002,10003));check(catalogue.entries().size==3&&catalogue.inviteEntries().size==2)
            apply(listOf(10004));check(catalogue.entries().map {it.port}.toSet()==setOf(10001,10004))
            val saved=ProfileStore(targetContext,"connections.enc").read()
            try {
                InviteCodeSync.prepareAvailable(InviteCodeSync.Manifest(null,"https://unavailable.invalid/sub",null),{throw IllegalArgumentException("fetch",UnknownHostException())},{error("No partial bypass")})
                error("Failure not propagated")
            }catch(error:IllegalArgumentException){check(error.cause is UnknownHostException)}
            check(ProfileStore(targetContext,"connections.enc").read()==saved)
            apply(emptyList());check(catalogue.entries().map {it.port}==listOf(10001)&&catalogue.inviteEntries().isEmpty())
            report.putString("result","PASS: encrypted owned snapshot2→1→0, unrelated manual preserved, failed preparation leaves exact stored catalogue unchanged")
            result=Activity.RESULT_OK
        }catch(error:Throwable){report.putString("failure",error.stackTraceToString())}
        finally {before?.let {ProfileStore.replaceAll(targetContext,it)}}
        finish(result,report)
    }
}
