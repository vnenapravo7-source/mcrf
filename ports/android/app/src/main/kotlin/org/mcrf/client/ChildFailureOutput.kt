package org.mcrf.client

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Drain stdout/stderr without retaining full lines, profiles or traffic payloads. */
internal object ChildFailureOutput {
    fun start(input:InputStream,detail:CarrierFailureDetail,onLine:(String)->Unit={}):Thread=Thread({
        try {drain(input,detail,onLine)}catch(error:Exception){detail.offer("error: чтение ответа клиента: ${error.javaClass.simpleName}: ${error.message.orEmpty()}")}
    },"mcrf-child-diagnostics").apply {isDaemon=true;start()}

    fun drain(input:InputStream,detail:CarrierFailureDetail,onLine:(String)->Unit) {
        input.use {
            val buffer=ByteArray(1024)
            val line=ByteArrayOutputStream()
            var dropping=false
            fun received(){
                if(!dropping&&line.size()>0){
                    val text=line.toString("UTF-8").trimEnd('\r')
                    detail.offer(text);onLine(text)
                }
                line.reset();dropping=false
            }
            while(true){
                val count=it.read(buffer);if(count<0)break
                for(i in 0 until count){
                    if(buffer[i].toInt()==10)received()
                    else if(!dropping&&line.size()<4096)line.write(buffer[i].toInt())
                    else {line.reset();dropping=true}
                }
            }
            received()
        }
    }
}
