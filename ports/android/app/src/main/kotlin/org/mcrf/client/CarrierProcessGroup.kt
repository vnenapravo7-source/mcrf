package org.mcrf.client

import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.util.UUID

/** CSQTT wrapper establishes a dedicated group and waits for our ACK BEFORE it
 * can spawn the transport. Its signed module must explicitly declare contract1.
 * Children inherit that group; no worker may daemonize/escape it. Observing root
 * death is not group death. We never send a group signal: signal0 only probes.
 * No /proc PID guessing, hidden Process fields, global kill or directory sweep. */
internal class CarrierProcessGroup(private val executable:File,private val directory:File) {
    val nonce=UUID.randomUUID().toString().replace("-","")
    private var root:CarrierProcessGroupProof.Root?=null
    @Synchronized fun bind(line:String):String {
        check(root==null) {"CARRIER_GROUP_DUPLICATE"}
        val (pid,birth)=CarrierProcessGroupProof.record(line,nonce)
        val stat=CarrierProcessGroupProof.stat(pid,File("/proc/$pid/stat").readText())
        check(CarrierProcessGroupProof.owned(stat,birth,android.os.Process.myPid(),Os.stat("/proc/$pid").st_uid,android.os.Process.myUid(),
            Os.readlink("/proc/$pid/exe"),executable.canonicalPath,Os.readlink("/proc/$pid/cwd"),directory.canonicalPath)) {"CARRIER_GROUP_IDENTITY"}
        root=stat
        return "MCRF_OWNER_ACK|$nonce\n"
    }
    @Synchronized fun admitted():Boolean=root!=null
    /** Off-UI, retain exact owner/module lease until ESRCH confirms the ENTIRE
     * verified group vanished. Permission/IO failures or interrupts are NOT ACKs.
     * Without a bound root no ACK could be sent, hence no child could be spawned. */
    fun awaitDeath() {
        val pid=synchronized(this){root?.pid}?:return
        var interrupted=Thread.interrupted();var delay=50L
        try {
            while(true) {
                try {Os.kill(-pid,0)}catch(error:ErrnoException){if(error.errno==OsConstants.ESRCH)return}
                catch(_:Exception){/* Unknown/denied query is pending cleanup, never death. */}
                try{Thread.sleep(delay)}catch(_:InterruptedException){interrupted=true}
                delay=(delay*2).coerceAtMost(1000L)
            }
        }finally{if(interrupted)Thread.currentThread().interrupt()}
    }
    override fun toString()="CarrierProcessGroup(redacted)"
}
