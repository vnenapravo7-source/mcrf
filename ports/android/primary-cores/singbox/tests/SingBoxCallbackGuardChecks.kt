package org.mcrf.engines.singbox

/** Runs real guards used by adapter; cannot exercise native gomobile proxies on host JVM. */
object SingBoxCallbackGuardChecks {
    @JvmStatic fun main(args: Array<String>) {
        var checks=0;var stopping=false;var prepareCalls=0;var allow=false;var race=false
        val guard=SingBoxCallbackGuard(7,{stopping}) {fd -> check(fd==42);prepareCalls++;if(race)stopping=true;allow}
        fun checkThat(value:Boolean) {check(value);checks++}
        fun rejects(code:String,run:()->Unit) {
            try {run()}catch(error:Exception) {check(error.message==code);checks++;return};error("Expected $code")
        }
        rejects("SOCKET_PREPARE_DENIED") {guard.protect(42)};checkThat(prepareCalls==1)
        allow=true;guard.protect(42);checkThat(prepareCalls==2)
        stopping=true;rejects("CORE_GENERATION_STOPPED") {guard.protect(42)};checkThat(prepareCalls==2)
        stopping=false;race=true;rejects("CORE_GENERATION_STOPPED") {guard.protect(42)};checkThat(prepareCalls==3)
        race=false;stopping=false;var opened=0
        rejects("PROXY_MODE_TUN_REFUSED") {guard.tun("proxy") {opened++;42}};checkThat(opened==0)
        checkThat(guard.tun("vpn") {opened++;42}==42 && opened==1)
        rejects("CORE_TUN_ALREADY_REQUESTED") {guard.tun("vpn") {opened++;42}};checkThat(opened==1)
        var events=0
        guard.event(8,true) {events++};checkThat(events==0)
        guard.event(7,true) {events++};checkThat(events==1)
        guard.event(7,false) {events++};checkThat(events==1)
        stopping=true;guard.event(7,true) {events++};checkThat(events==1)
        println("PASS $checks actual callback-guard checks; no JNI/platform/IPC execution")
    }
}
