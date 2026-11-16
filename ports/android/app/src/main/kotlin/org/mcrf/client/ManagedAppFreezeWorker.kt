package org.mcrf.client

import android.content.Context
import android.content.pm.PackageManager
import android.os.Binder
import android.os.Parcel
import android.os.UserHandle
import java.util.concurrent.TimeUnit

/** Shizuku user service for reversible app enable-state changes. */
class ManagedAppFreezeWorker(private val context:Context):Binder() {
    override fun onTransact(code:Int,data:Parcel,reply:Parcel?,flags:Int):Boolean {
        if(code==DESTROY){
            require(getCallingUid() in setOf(0,2000,context.applicationInfo.uid))
            Thread { Thread.sleep(100);kotlin.system.exitProcess(0) }.start()
            return true
        }
        if(code!=CHANGE||reply==null)return super.onTransact(code,data,reply,flags)
        data.enforceInterface(DESCRIPTOR)
        val own=context.applicationInfo
        require(getCallingUid()==own.uid) {"Unexpected freeze request owner"}
        require(UserHandle.getUserHandleForUid(own.uid)==UserHandle.getUserHandleForUid(0)) {"Use the main Android profile"}
        val id=requireNotNull(data.readString())
        RouteRules.validatePackage(id)
        require(id!=own.packageName&&id!="moe.shizuku.privileged.api")
        val command=when(data.readInt()){
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER->{
                require(ManagedAppFreezePolicy.packageBlockReason(context,id)==null)
                "disable-user"
            }
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED->"enable"
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT->"default-state"
            else->error("Unsupported application state")
        }
        val process=ProcessBuilder("/system/bin/pm",command,"--user","0",id)
            .redirectOutput(java.io.File("/dev/null")).redirectError(java.io.File("/dev/null")).start()
        try{
            check(process.waitFor(8,TimeUnit.SECONDS)&&process.exitValue()==0) {"Android rejected the application state change"}
        }finally {if(process.isAlive)process.destroyForcibly()}
        reply.writeNoException()
        return true
    }

    companion object {
        internal const val DESCRIPTOR="org.mcrf.client.ManagedAppFreeze"
        internal const val CHANGE=android.os.IBinder.FIRST_CALL_TRANSACTION
        private const val DESTROY=16777115
    }
}

