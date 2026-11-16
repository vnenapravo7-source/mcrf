package org.mcrf.client

import java.io.ByteArrayOutputStream
import java.io.InputStream

/** Thread-confined SSH output. No logging, retry, socket ownership or protocol changes. */
internal class ServerManagementOutput(private val maxBytes:Int=1048576) {
    init {require(maxBytes in 1..1048576)}
    private val bytes=ByteArrayOutputStream(minOf(maxBytes,8192))
    private val buffer=ByteArray(minOf(maxBytes,8192))
    val size:Int get()=bytes.size()
    override fun toString()="ServerManagementOutput(redacted)"
    fun utf8():String=bytes.toString("UTF-8")
    fun drain(input:InputStream,checkActive:()->Unit) {
        while(true){
            checkActive()
            val available=input.available()
            if(available<=0)return
            val remaining=maxBytes-size
            check(remaining>0){"Ответ сервера слишком большой"}
            val requested=minOf(available,buffer.size,remaining)
            val count=input.read(buffer,0,requested)
            if(count<=0)return // EOF/temporary zero is not an output byte or a busy loop.
            check(count<=requested){"Некорректное чтение ответа сервера"}
            checkActive()
            bytes.write(buffer,0,count)
        }
    }
}
