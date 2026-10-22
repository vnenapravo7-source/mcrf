package org.mcrf.client

/** ONE installed-module worker holding its update-usage lease. Exact inputs only. */
class WorkerValidationCache<T> {
    private var key:List<String>?=null
    private var value:T?=null
    private var closed=false
    @Synchronized fun get(profile:String,settings:String,mode:String,capabilities:String):T? =
        if(!closed&&key==listOf(profile,settings,mode,capabilities))value else null
    @Synchronized fun put(profile:String,settings:String,mode:String,capabilities:String,result:T){
        if(!closed){key=listOf(profile,settings,mode,capabilities);value=result}
    }
    @Synchronized fun close(){closed=true;key=null;value=null}
}
