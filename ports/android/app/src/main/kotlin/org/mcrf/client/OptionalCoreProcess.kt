package org.mcrf.client

import android.content.Context
import android.content.pm.PackageManager
import android.system.Os
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.BooleanSupplier

/** Own optional-core lifecycle. Call from the service/probe worker, never the UI thread. */
class OptionalCoreProcess private constructor(val port: Int,val username: String,val password: String,val kind: String) : AutoCloseable {
    private var process: Process?=null
    private var directory: File?=null
    private val ready=AtomicBoolean(false)
    private val closed=AtomicBoolean(false)
    @Synchronized override fun close() {
        val first=closed.compareAndSet(false,true)
        process?.let { child ->
            OptionalCoreTermination.release(child,first)
        };process=null
        directory?.let { root ->
            // This directory only ever contains the generated config. Refuse
            // symlinks/foreign children rather than recursively following them.
            if(root.exists()) {
                check(root.isDirectory && !Files.isSymbolicLink(root.toPath())){"Некорректная папка optional core."}
                val files=checkNotNull(root.listFiles()){"Не удалось прочитать папку optional core."}
                files.forEach {file ->
                    check(file.name=="config.json" && file.isFile && file.canonicalFile.parentFile==root.canonicalFile && !Files.isSymbolicLink(file.toPath())){"Чужой файл в папке optional core; очистка остановлена."}
                    check(file.delete() || !file.exists()){"Не удалось удалить конфигурацию optional core."}
                }
                check(root.delete() || !root.exists()){"Не удалось удалить папку optional core."}
            }
        };directory=null
    }
    fun isAlive():Boolean= !closed.get() && ready.get() && process?.isAlive==true
    @Synchronized fun isClosed():Boolean=closed.get() && process==null && directory==null
    fun bridgeOutbound(tag: String): JSONObject = OptionalCoreConfig.bridgeOutbound(tag,port,username,password,kind)
    /** All upstream connections close; the service recreates with fresh credentials. */
    fun networkChanged()=close()
    companion object {
        val kinds=setOf("xray","tls-core")
        fun packageName(kind: String):String {require(kind in kinds);return "org.mcrf.engines.${if(kind=="tls-core") "tlscore" else kind}"}
        fun libraryName(kind: String):String {require(kind in kinds);return if(kind=="tls-core") "libtls_core.so" else "libxray.so"}
        private fun executable(context:Context,kind:String):File {
            val pm=context.packageManager
            val plugin=try {pm.getApplicationInfo(packageName(kind),PackageManager.GET_META_DATA)} catch(_:PackageManager.NameNotFoundException){throw IllegalArgumentException("Установи отдельный подписанный пакет $kind для архитектуры телефона.")}
            val sourceCommit=if(kind=="xray") "e662c22ab1096883c4df200a0e8068a89da04df1" else "ebe0747b41823e02335ad3c7ae531bc577e7bc75"
            val sourceRepository=if(kind=="xray") "throneproj/xray-core" else "throneproj/sing-box"
            require(plugin.enabled && plugin.metaData?.getInt("org.mcrf.engines.interface")==1 && plugin.metaData?.getString("org.mcrf.engines.kind")==kind &&
                plugin.metaData?.getInt("org.mcrf.engines.optional-core")==1 && plugin.metaData?.getString("org.mcrf.engines.source.commit")==sourceCommit && plugin.metaData?.getString("org.mcrf.engines.source.repository")==sourceRepository &&
                pm.checkSignatures(context.packageName,plugin.packageName)==PackageManager.SIGNATURE_MATCH) {"Подпись, исходник или интерфейс optional core не совпадает с MCRF."}
            val base=File(plugin.nativeLibraryDir).canonicalFile
            return File(base,libraryName(kind)).canonicalFile.also {require(it.parentFile==base&&it.isFile&&it.canExecute()) {"Нет optional core для архитектуры Android."}}
        }
        private fun waitExit(child: Process,cancelled: BooleanSupplier,timeoutMillis: Long):Int {
            val deadline=System.nanoTime()+timeoutMillis*1_000_000
            while(System.nanoTime()<deadline){if(cancelled.asBoolean)throw IllegalArgumentException("Подключение отменено.");if(child.waitFor(50,TimeUnit.MILLISECONDS))return child.exitValue()}
            throw IllegalArgumentException("Optional core не ответил вовремя.")
        }
        private fun describe(binary: File,kind: String,cancelled: BooleanSupplier,certificateStore: String?=null):JSONObject {
            val child=ProcessBuilder(binary.absolutePath,"--mcrf-describe").redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start()
            try {
            child.outputStream.close()
            val bytes=ByteArrayOutputStream();val reader=Thread({try {child.inputStream.use { input ->val buffer=ByteArray(512);while(bytes.size()<=8192){val n=input.read(buffer);if(n<0)break;bytes.write(buffer,0,n)}}}catch(_:Exception){}},"mcrf-optional-descriptor").apply {isDaemon=true;start()}
            require(waitExit(child,cancelled,3000)==0) {"Optional core не поддерживает descriptor API."};reader.join(1000)
            require(!reader.isAlive && bytes.size() in 1..8192) {"Некорректный descriptor optional core."}
            val descriptor=JSONObject(bytes.toString("UTF-8"));val upstream=if(kind=="xray") "e662c22ab1096883c4df200a0e8068a89da04df1" else "ebe0747b41823e02335ad3c7ae531bc577e7bc75"
            val ingress=if(kind=="xray") "authenticated-loopback-socks5-tcp-udp" else "authenticated-loopback-socks5-tcp"
            require(descriptor.getInt("api")==1 && descriptor.getString("kind")==kind && descriptor.getString("upstream")==upstream && descriptor.getString("ingress")==ingress) {"Обнови optional core: несовместимый API или исходник."}
            if(certificateStore!=null){val capabilities=descriptor.getJSONArray("capabilities");require((0 until capabilities.length()).any {capabilities.getString(it)=="certificate-store"}) {"Обнови TLS core для выбранного CA store."}}
            OptionalCoreTermination.release(child,false)
            return descriptor
            }catch(error:Throwable){OptionalCoreRetirement.fail(error,AutoCloseable {OptionalCoreTermination.release(child,false)})}
        }
        /** Same-signer, metadata and API checked read-only capability discovery. */
        fun capabilities(context:Context,kind:String,cancelled:BooleanSupplier):Set<String> {
            val descriptor=describe(executable(context,kind),kind,cancelled)
            val capabilities=descriptor.getJSONArray("capabilities")
            return (0 until capabilities.length()).map {capabilities.getString(it)}.toSet()
        }
        /** vpn=true requires the service to exclude this application's UID from
         * TUN. A subprocess cannot use the in-process libbox FD protector. */
        fun start(context:Context,request:OptionalCoreConfig.Request,cancelled:BooleanSupplier,vpn:Boolean,vpnUidBypassConfigured:Boolean):OptionalCoreProcess {
            require(!vpn || vpnUidBypassConfigured) {"VPN должен исключить UID приложения для сетевых сокетов optional core."}
            val binary=executable(context,request.kind)
            describe(binary,request.kind,cancelled,request.certificateStore)
            val port=ServerSocket(0,1,InetAddress.getByName("127.0.0.1")).use {it.localPort}
            val owner=OptionalCoreProcess(port,UUID.randomUUID().toString(),UUID.randomUUID().toString(),request.kind)
            try {
                if(cancelled.asBoolean)throw IllegalArgumentException("Подключение отменено.")
                val dir=File(context.cacheDir,"mcrf-optional-${UUID.randomUUID()}");require(dir.mkdir());owner.directory=dir;Os.chmod(dir.absolutePath,0x1c0)
                val file=File(dir,"config.json");val data=OptionalCoreConfig.privateConfig(request,port,owner.username,owner.password).toString().toByteArray()
                require(data.size<=65536) {"Профиль optional core больше 64 КБ."};require(file.createNewFile());Os.chmod(file.absolutePath,0x180);FileOutputStream(file).use {it.write(data)}
                // Native parsing is mandatory, even after model validation.
                val check=ProcessBuilder(binary.absolutePath,"--mcrf-check",file.absolutePath).directory(dir).redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null"))).redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start()
                owner.process=check;check.outputStream.close();require(waitExit(check,cancelled,10000)==0) {"Optional core отклонил параметры профиля."}
                val child=ProcessBuilder(binary.absolutePath,"--mcrf-config",file.absolutePath).directory(dir).redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start();owner.process=child
                Thread({try {child.inputStream.use {input->val line=StringBuilder();var dropping=false;while(true){val b=input.read();if(b<0)break;if(b==10){if(!dropping&&line.toString().trimEnd('\r')=="MCRF_READY")owner.ready.set(true);line.setLength(0);dropping=false}else if(line.length<1024&&!dropping)line.append(b.toChar())else{line.setLength(0);dropping=true}}}}catch(_:Exception){}},"mcrf-optional-output").apply {isDaemon=true;start()}
                val deadline=System.nanoTime()+10_000_000_000L
                while(System.nanoTime()<deadline){if(cancelled.asBoolean)throw IllegalArgumentException("Подключение отменено.");require(child.isAlive) {"Optional core завершился при запуске."};if(owner.ready.get()&&NativeCarrier.socksReady(port,owner.username,owner.password))return owner;Thread.sleep(50)}
                throw IllegalArgumentException("Optional core не открыл защищённый SOCKS listener.")
            } catch(error:Throwable){OptionalCoreRetirement.fail(error,owner)}
        }
        /** Read-only module/config preflight. Native construction/close only;
         * this does not bind a SOCKS/TUN/UDP listener or consume a server token. */
        fun check(context:Context,request:OptionalCoreConfig.Request,cancelled:BooleanSupplier) {
            val binary=executable(context,request.kind);describe(binary,request.kind,cancelled,request.certificateStore)
            val owner=OptionalCoreProcess(10900,UUID.randomUUID().toString(),UUID.randomUUID().toString(),request.kind)
            try {
                if(cancelled.asBoolean)throw IllegalArgumentException("Проверка отменена.")
                val dir=File(context.cacheDir,"mcrf-optional-${UUID.randomUUID()}");require(dir.mkdir());owner.directory=dir;Os.chmod(dir.absolutePath,0x1c0)
                val data=OptionalCoreConfig.privateConfig(request,owner.port,owner.username,owner.password).toString().toByteArray();require(data.size<=65536)
                val file=File(dir,"config.json");require(file.createNewFile());Os.chmod(file.absolutePath,0x180);FileOutputStream(file).use {it.write(data)}
                val child=ProcessBuilder(binary.absolutePath,"--mcrf-check",file.absolutePath).directory(dir).redirectOutput(ProcessBuilder.Redirect.to(File("/dev/null"))).redirectError(ProcessBuilder.Redirect.to(File("/dev/null"))).start();owner.process=child;child.outputStream.close()
                require(waitExit(child,cancelled,10000)==0) {"Optional core отклонил параметры профиля."}
            }catch(error:Throwable){OptionalCoreRetirement.fail(error,owner)}
            OptionalCoreRetirement.drain(owner)
        }
    }
}
