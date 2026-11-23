package org.mcrf.client

import android.content.Context
import android.os.UserHandle
import java.io.File
import java.util.concurrent.TimeUnit

/** Root-manager authorization is requested only after an explicit action in Anubis. */
internal object ManagedAppFreezeRoot {
    @Volatile
    var enabled=false
        private set

    private const val ROOT_CHECK="[ \"\$(/system/bin/id -u)\" = 0 ]"
    private const val COMMAND_TIMEOUT_SECONDS=10L

    fun authorize(context:Context) {
        requireOwner(context)
        invoke(ROOT_CHECK,30)
        enabled=true
    }

    fun stop() {
        enabled=false
    }

    fun change(context:Context,id:String,state:Int) {
        requireOwner(context)
        check(enabled)
        val action=ManagedAppFreezeCommand.action(context,id,state)
        try {
            // The action is an enum mapping and the shared validator restricts id to package characters.
            invoke("$ROOT_CHECK && exec /system/bin/pm $action --user 0 $id",COMMAND_TIMEOUT_SECONDS)
        } catch(error:Exception) {
            enabled=false
            throw error
        }
    }

    private fun requireOwner(context:Context) {
        require(UserHandle.getUserHandleForUid(context.applicationInfo.uid)==UserHandle.getUserHandleForUid(0))
    }

    private fun invoke(command:String,seconds:Long) {
        val discard=File("/dev/null")
        val process=ProcessBuilder("su","-c",command)
            .redirectInput(discard).redirectOutput(discard).redirectError(discard).start()
        try {
            check(process.waitFor(seconds,TimeUnit.SECONDS)&&process.exitValue()==0) {
                "Root access was denied or Android rejected the request"
            }
        } finally {
            if(process.isAlive)process.destroyForcibly()
        }
    }
}
