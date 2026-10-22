package org.mcrf.client

/** Dialog-local ownership: cancelled/older loads must never replace the visible selection. */
class AppPickerLoadGate {
    private var revision=0L
    private var closed=false
    fun begin():Long?=if(closed)null else ++revision
    fun accepts(ticket:Long)=!closed&&ticket>0&&ticket==revision
    fun close(){closed=true;revision++}
    companion object {
        fun includes(system:Boolean,selected:Boolean,showSystem:Boolean)=!system||selected||showSystem
    }
}
