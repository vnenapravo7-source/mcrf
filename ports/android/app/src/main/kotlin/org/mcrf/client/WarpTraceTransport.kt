package org.mcrf.client

import java.io.BufferedInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.CancellationException
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.SNIHostName
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

/** Strict authenticated loopback SOCKS -> HTTPS. No direct fallback and no global proxy/auth state. */
object WarpTraceTransport {
    private const val HOST = "www.cloudflare.com"
    fun fetch(port: Int, user: String, password: String, cancelled: () -> Boolean): String {
        require(port in 1024..65535)
        val username=user.toByteArray(Charsets.UTF_8);val secret=password.toByteArray(Charsets.UTF_8)
        require(username.size in 1..255 && secret.size in 1..255)
        val socket=Socket();val finished=AtomicBoolean()
        val watchdog=Executors.newSingleThreadScheduledExecutor { task -> Thread(task,"mcrf-warp-trace-cancel").apply {isDaemon=true} }
        val deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(20)
        watchdog.scheduleAtFixedRate({if(!finished.get() && (cancelled() || System.nanoTime()>=deadline)) try {socket.close()} catch(_: Exception){}},0,100,TimeUnit.MILLISECONDS)
        try {
            if(cancelled()) throw CancellationException()
            socket.connect(InetSocketAddress("127.0.0.1",port),3000);socket.soTimeout=10000
            val out=socket.getOutputStream();val input=socket.getInputStream()
            out.write(byteArrayOf(5,1,2));out.flush()
            require(input.read()==5 && input.read()==2) {"Проверочный прокси не подтвердил аутентификацию."}
            out.write(byteArrayOf(1,username.size.toByte()));out.write(username);out.write(secret.size);out.write(secret);out.flush()
            require(input.read()==1 && input.read()==0) {"Проверочный прокси отклонил доступ."}
            val host=HOST.toByteArray(Charsets.US_ASCII)
            out.write(byteArrayOf(5,1,0,3,host.size.toByte()));out.write(host);out.write(byteArrayOf(1,187.toByte()));out.flush()
            require(input.read()==5 && input.read()==0 && input.read()==0) {"Туннель WARP не открыл HTTPS."}
            val length=when(input.read()) {1 -> 4;4 -> 16;3 -> input.read().also {require(it in 1..255)};else -> error("Некорректный ответ SOCKS.")}
            repeat(length+2) {require(input.read()>=0)}
            val tls=(SSLSocketFactory.getDefault() as SSLSocketFactory).createSocket(socket,HOST,443,true) as SSLSocket
            tls.use {
                it.sslParameters=it.sslParameters.apply {endpointIdentificationAlgorithm="HTTPS";serverNames=listOf(SNIHostName(HOST))}
                it.startHandshake()
                it.outputStream.write("GET /cdn-cgi/trace HTTP/1.1\r\nHost: $HOST\r\nAccept: text/plain\r\nAccept-Encoding: identity\r\nConnection: close\r\n\r\n".toByteArray(Charsets.US_ASCII));it.outputStream.flush()
                val body=readBody(BufferedInputStream(it.inputStream))
                if(cancelled()) throw CancellationException()
                return body
            }
        } catch(error: Exception) {
            if(cancelled() || Thread.currentThread().isInterrupted) throw CancellationException("Проверка WARP отменена.")
            throw IllegalArgumentException("HTTPS trace через выбранный туннель WARP не получен.")
        } finally {finished.set(true);watchdog.shutdownNow();try {socket.close()} catch(_: Exception){};secret.fill(0)}
    }
    internal fun readBody(input: InputStream): String {
        var headerBytes=0
        fun line(): String {
            val output=ByteArrayOutputStream()
            while(true) {
                val byte=input.read();require(byte>=0 && ++headerBytes<=16384) {"Некорректные заголовки trace."}
                if(byte==10) {val data=output.toByteArray();require(data.isNotEmpty() && data.last()==13.toByte());return String(data,0,data.size-1,Charsets.US_ASCII)}
                require(byte in 32..126 || byte==13 || byte==9);output.write(byte)
            }
        }
        require(line().matches(Regex("HTTP/1\\.[01] 200(?: .*)?"))) {"Trace не подтвердил HTTP200."}
        val headers=linkedMapOf<String,String>()
        while(true) {val text=line();if(text.isEmpty())break;val parts=text.split(':',limit=2);require(parts.size==2)
            val key=parts[0].trim().lowercase(java.util.Locale.ROOT);val value=parts[1].trim()
            if(key in setOf("content-length","transfer-encoding","content-encoding")) require(headers.put(key,value)==null) {"Неоднозначный ответ trace."}
        }
        require(headers["content-encoding"].let {it==null || it.equals("identity",true)})
        val result=ByteArrayOutputStream()
        fun bytes(count: Int) {require(count>=0 && result.size()+count<=8192);repeat(count) {val byte=input.read();require(byte>=0);result.write(byte)}}
        if(headers.containsKey("transfer-encoding")) {
            require(!headers.containsKey("content-length") && headers["transfer-encoding"].equals("chunked",true))
            while(true) {val chunk=line().substringBefore(';').trim();require(chunk.matches(Regex("[0-9a-fA-F]{1,8}")));val size=chunk.toLong(16);require(size<=8192)
                if(size==0L) {while(line().isNotEmpty()){};break}
                bytes(size.toInt());require(input.read()==13 && input.read()==10)
            }
        } else if(headers.containsKey("content-length")) {
            val size=headers.getValue("content-length").toIntOrNull();require(size!=null && size in 0..8192);bytes(size)
        } else {
            while(true) {val byte=input.read();if(byte<0)break;require(result.size()<8192);result.write(byte)}
        }
        val data=result.toByteArray();require(data.all {it.toInt() in 9..13 || it.toInt() in 32..126}) {"Trace должен быть обычным текстом."}
        return String(data,Charsets.US_ASCII)
    }
}
