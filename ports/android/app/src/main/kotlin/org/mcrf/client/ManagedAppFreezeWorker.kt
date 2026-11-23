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
        val command=ManagedAppFreezeCommand.action(context,id,data.readInt())
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

internal object ManagedAppFreezeCommand {
    fun action(context:Context,id:String,state:Int):String {
        validateTarget(id,context.packageName)
        val action=actionForState(state)
        if(state==PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER)
            require(ManagedAppFreezePolicy.packageBlockReason(context,id)==null)
        return action
    }

    fun validateTarget(id:String,ownPackage:String) {
        RouteRules.validatePackage(id)
        require(id!=ownPackage&&id!="moe.shizuku.privileged.api")
    }

    fun actionForState(state:Int):String=when(state){
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER->"disable-user"
        PackageManager.COMPONENT_ENABLED_STATE_ENABLED->"enable"
        PackageManager.COMPONENT_ENABLED_STATE_DEFAULT->"default-state"
        PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED->"disable-until-used"
        else->error("Unsupported application state")
    }
}

