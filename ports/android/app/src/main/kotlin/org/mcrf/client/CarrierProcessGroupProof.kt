package org.mcrf.client

/** A fresh private nonce plus OS-verified root, not an arbitrary PID from logs.
 * This proof NEVER authorizes signalling/killing a process group. */
internal object CarrierProcessGroupProof {
    const val PREFIX="MCRF_PROCESS_GROUP_V1|"
    data class Root(val pid:Int,val parent:Int,val group:Int,val birth:String)
    fun stat(pid:Int,text:String):Root {
        require(pid>1&&text.length in 1..16384&&text.substringBefore(' ').toIntOrNull()==pid) {"CARRIER_GROUP_STAT"}
        val fields=text.substringAfterLast(") ","").trim().split(Regex(" +"))
        require(fields.size>=20) {"CARRIER_GROUP_STAT"}
        val parent=fields[1].toIntOrNull();val group=fields[2].toIntOrNull();val birth=fields[19]
        require(parent!=null&&parent>0&&group!=null&&group>0&&birth.matches(Regex("[1-9][0-9]{0,23}"))) {"CARRIER_GROUP_STAT"}
        return Root(pid,parent,group,birth)
    }
    fun record(line:String,nonce:String):Pair<Int,String> {
        require(nonce.matches(Regex("[0-9a-f]{32}"))&&line.length<=192) {"CARRIER_GROUP_RECORD"}
        val fields=line.split('|')
        require(fields.size==4&&fields[0]=="MCRF_PROCESS_GROUP_V1"&&fields[1]==nonce) {"CARRIER_GROUP_RECORD"}
        val pid=fields[2].toIntOrNull()
        require(pid!=null&&pid>1&&fields[2]==pid.toString()&&fields[3].matches(Regex("[1-9][0-9]{0,23}"))) {"CARRIER_GROUP_RECORD"}
        return pid to fields[3]
    }
    fun owned(root:Root,reportedBirth:String,hostPid:Int,uid:Int,hostUid:Int,actualExecutable:String,expectedExecutable:String,actualDirectory:String,expectedDirectory:String)=
        root.pid!=hostPid&&root.parent==hostPid&&root.group==root.pid&&root.birth==reportedBirth&&uid==hostUid&&
            actualExecutable==expectedExecutable&&actualDirectory==expectedDirectory
}
