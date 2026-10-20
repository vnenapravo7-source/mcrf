package org.mcrf.client

import android.content.Context
import android.os.Looper
import android.util.AtomicFile
import org.json.JSONArray
import org.json.JSONObject
import org.mcrf.steerprep.SteerGeoProjection
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardOpenOption.READ
import java.nio.file.attribute.BasicFileAttributes
import java.security.MessageDigest

/** SOURCE ONLY explicit Geo preparation and read-only origin-bound capture.
 * No preview/start download, no old sing-box-cache fallback. The UI must invoke
 * prepare explicitly; a record is committed only after ALL categories succeed.
 * Content-addressed category files may remain after cancellation, but the prior
 * usable record and routing/profile settings are not replaced on failure.
 */
internal object SteerGeoPreparation {
    private val prepareLock=java.util.concurrent.locks.ReentrantLock()
    private fun sha(bytes:ByteArray)=MessageDigest.getInstance("SHA-256").digest(bytes).joinToString(""){"%02x".format(it.toInt() and 255)}
    internal fun identity(state:JSONObject,plan:SteerGeoProjection.Plan)=sha((GeoDataConfig.value(state).toString()+"\n"+plan.neededSelectors.joinToString(",")).toByteArray(Charsets.UTF_8))
    private fun sourceName(state:JSONObject,kind:String)=sha(GeoDataConfig.value(state).getString("${kind}Url").toByteArray(Charsets.UTF_8))+".dat"
    private fun host(context:Context) {
        require(Looper.myLooper()!=Looper.getMainLooper()){"Подготовка геоданных выполняется в фоне."}
        require(context.packageName=="org.mcrf.client"&&context.applicationInfo.uid==android.os.Process.myUid())
    }
    private fun root(filesDir:Path,create:Boolean):Path {
        val files=filesDir.toRealPath();val root=files.resolve("mcrf-geodata")
        if(create&&!Files.exists(root,NOFOLLOW_LINKS))Files.createDirectory(root)
        require(!Files.isSymbolicLink(root)&&Files.isDirectory(root,NOFOLLOW_LINKS)&&root.toRealPath().parent==files){"Геоданные Steer ещё не подготовлены."}
        return root
    }
    private data class Fingerprint(val bytes:Long,val sha:String)
    private fun metadata(path:Path,max:Long):BasicFileAttributes {
        val value=Files.readAttributes(path,BasicFileAttributes::class.java,NOFOLLOW_LINKS)
        require(value.isRegularFile&&!value.isSymbolicLink&&value.size() in 1..max){"Неверный файл геоданных Steer."};return value
    }
    private fun same(a:BasicFileAttributes,b:BasicFileAttributes)=a.isRegularFile&&b.isRegularFile&&!b.isSymbolicLink&&
        a.size()==b.size()&&a.lastModifiedTime()==b.lastModifiedTime()&&a.fileKey()==b.fileKey()
    private fun fingerprint(path:Path,max:Long):Fingerprint {
        val before=metadata(path,max);val hash=MessageDigest.getInstance("SHA-256");var count=0L
        Files.newInputStream(path,READ,NOFOLLOW_LINKS).use {input ->
            val part=ByteArray(16384)
            while(true){val n=input.read(part);if(n==-1)break;require(n>0&&count+n<=before.size());count+=n;hash.update(part,0,n)}
        }
        require(count==before.size()&&same(before,metadata(path,max))){"Геоданные изменились во время чтения."}
        return Fingerprint(count,hash.digest().joinToString(""){"%02x".format(it.toInt() and 255)})
    }
    private fun read(path:Path,max:Int):ByteArray {
        val before=metadata(path,max.toLong());val bytes=ByteArray(before.size().toInt())
        Files.newInputStream(path,READ,NOFOLLOW_LINKS).use {input ->
            var at=0
            while(at<bytes.size){val n=input.read(bytes,at,bytes.size-at);require(n>0);at+=n};require(input.read()==-1)
        }
        require(same(before,metadata(path,max.toLong()))){"Геоданные изменились во время чтения."};return bytes
    }
    private fun exact(row:JSONObject,vararg names:String){require(row.keys().asSequence().toSet()==names.toSet()){"Неверная запись геоданных Steer."}}
    private fun number(row:JSONObject,key:String):Long {val value=row.get(key);require(value is Int||value is Long);return (value as Number).toLong()}
    private fun string(row:JSONObject,key:String):String {val value=row.get(key);require(value is String);return value}
    private fun checkCurrent(current:()->Boolean){check(current()){"Настройки маршрутов изменились. Подготовь геоданные заново."}}
    private fun write(path:Path,bytes:ByteArray) {
        require(!Files.exists(path,NOFOLLOW_LINKS)||Files.isRegularFile(path,NOFOLLOW_LINKS))
        require(!Files.isSymbolicLink(path)&&!Files.isSymbolicLink(path.resolveSibling(path.fileName.toString()+".bak"))&&
            !Files.isSymbolicLink(path.resolveSibling(path.fileName.toString()+".new")))
        val atomic=AtomicFile(path.toFile());val output=atomic.startWrite()
        try{output.write(bytes);atomic.finishWrite(output)}catch(e:Exception){atomic.failWrite(output);throw e}
    }
    fun prepare(context:Context,state:JSONObject,current:()->Boolean):Int {
        host(context);check(prepareLock.tryLock()){"Подготовка геоданных Steer уже выполняется."}
        try {
            val contract=ClientPrimarySelection.steerContract(context)
            require(contract.model==2&&contract.geoRules&&contract.nativeAddressRules){"Установленный Steer не заявляет подготовку GeoIP/GeoSite."}
            return prepareCaptured(context,state,current)
        } finally {prepareLock.unlock()}
    }
    private fun prepareCaptured(context:Context,state:JSONObject,current:()->Boolean):Int {
        val captured=JSONObject(state.toString());GeoDataConfig.validate(GeoDataConfig.value(captured))
        val plan=SteerGeoProjection.plan(captured);checkCurrent(current)
        if(plan.neededSelectors.isEmpty())return 0
        val directory=root(context.filesDir.toPath(),true);val pins=JSONArray();val sources=JSONArray();var total=0L
        val kinds=plan.neededSelectors.map {plan.selector(it).getString("kind")}.toSet()
        for(kind in kinds){
            checkCurrent(current)
            val sourcePath=directory.resolve(sourceName(captured,kind))
            val newSource=!Files.exists(sourcePath,NOFOLLOW_LINKS)
            val bytes=if(newSource)GeoDataConfig.fetchForSteer(context,GeoDataConfig.value(captured),kind) else read(sourcePath,64*1024*1024)
            val sourceSha=sha(bytes)
            val codes=plan.neededSelectors.map {plan.selector(it)}.filter {it.getString("kind")==kind}.map {it.getString("code")}.toSet()
            val dataset=SteerGeoDat.Dataset(bytes,codes);val converted=hashMapOf<String,ByteArray>()
            sources.put(JSONObject().put("kind",kind).put("file",sourceName(captured,kind)).put("bytes",bytes.size).put("sha256",sourceSha))
            for(index in plan.neededSelectors){
                val selector=plan.selector(index);if(selector.getString("kind")!=kind)continue
                checkCurrent(current)
                val code=selector.getString("code");val key=code.lowercase(java.util.Locale.ROOT)
                val compiled=converted.getOrPut(key){dataset.convert(kind,code).toString().toByteArray(Charsets.UTF_8)}
                total+=compiled.size;require(compiled.isNotEmpty()&&compiled.size<=8*1024*1024&&total<=8L*1024*1024){"Геоданные превышают ограничения Steer."}
                val digest=sha(compiled);val target=directory.resolve("$digest.json")
                if(Files.exists(target,NOFOLLOW_LINKS))require(fingerprint(target,8L*1024*1024)==Fingerprint(compiled.size.toLong(),digest))
                else write(target,compiled)
                pins.put(JSONObject().put("selector",index).put("file","$digest.json").put("sha256",digest).put("bytes",compiled.size)
                    .put("kind",kind).put("code",selector.getString("code")))
            }
            if(newSource){
                // Only absent DATs may be published. A racing refresh must not
                // be overwritten by the earlier fetch; identical bytes may be reused.
                checkCurrent(current)
                GeoDataConfig.withSourceWriteLock(context,GeoDataConfig.value(captured),kind){destination ->
                    checkCurrent(current)
                    require(destination.toPath()==sourcePath)
                    if(Files.exists(sourcePath,NOFOLLOW_LINKS))require(fingerprint(sourcePath,64L*1024*1024)==Fingerprint(bytes.size.toLong(),sourceSha))
                    else write(sourcePath,bytes)
                }
            }
        }
        // Do not publish a mixed snapshot if a download/refresh raced conversion.
        checkSources(directory,captured,plan,sources);checkCurrent(current)
        val key=identity(captured,plan)
        val record=JSONObject().put("version",1).put("identity",key).put("sources",sources).put("pins",pins).toString().toByteArray(Charsets.UTF_8)
        require(record.size<=65536);write(directory.resolve("steer-$key.json"),record)
        return pins.length()
    }
    private fun checkSources(directory:Path,state:JSONObject,plan:SteerGeoProjection.Plan,sources:JSONArray) {
        val wanted=plan.neededSelectors.map {plan.selector(it).getString("kind")}.toSet();val seen=hashSetOf<String>()
        require(sources.length()==wanted.size&&sources.length() in 1..2)
        repeat(sources.length()){i ->
            val source=sources.getJSONObject(i);exact(source,"kind","file","bytes","sha256")
            val kind=string(source,"kind");require(kind in wanted&&seen.add(kind))
            val name=string(source,"file");require(name==sourceName(state,kind))
            val hash=string(source,"sha256");require(hash.matches(Regex("[0-9a-f]{64}")))
            val bytes=number(source,"bytes");require(bytes in 1..64L*1024*1024)
            require(fingerprint(directory.resolve(name),64L*1024*1024)==Fingerprint(bytes,hash)){"Исходная геобаза изменилась. Подготовь категории Steer заново."}
        }
        require(seen==wanted)
    }
    private fun record(bytes:ByteArray):JSONObject {
        // Bound nesting BEFORE the platform JSONObject parser. This is a private
        // host-generated index, not imported routing or a general JSON dialect.
        val text=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        var quoted=false;var escape=false;var depth=0
        for(ch in text){
            if(quoted){require(ch.code>=32);if(escape)escape=false else if(ch=='\\')escape=true else if(ch=='"')quoted=false}
            else when(ch){'"'->quoted=true;'[','{'->{depth++;require(depth<=8)};']','}'->{depth--;require(depth>=0)}}
        }
        require(depth==0&&!quoted&&!escape);return JSONObject(text)
    }
    fun capture(context:Context,state:JSONObject,plan:SteerGeoProjection.Plan):JSONArray {
        host(context)
        try{return captureFiles(context.filesDir.toPath(),state,plan)}
        catch(_:java.io.IOException){throw IllegalStateException("Снимок геоданных Steer недоступен. Подготовь категории заново.")}
        catch(_:IllegalArgumentException){throw IllegalStateException("Снимок геоданных Steer не совпадает с настройками. Подготовь категории заново.")}
        catch(_:org.json.JSONException){throw IllegalStateException("Снимок геоданных Steer повреждён. Подготовь категории заново.")}
    }
    internal fun captureFiles(filesDir:Path,state:JSONObject,plan:SteerGeoProjection.Plan):JSONArray {
        if(plan.neededSelectors.isEmpty())return JSONArray()
        val directory=root(filesDir,false);val key=identity(state,plan)
        val record=record(read(directory.resolve("steer-$key.json"),65536));exact(record,"version","identity","sources","pins")
        require(number(record,"version")==1L&&string(record,"identity")==key)
        checkSources(directory,state,plan,record.getJSONArray("sources"))
        val expected=record.getJSONArray("pins");require(expected.length()==plan.neededSelectors.size&&expected.length()<=64)
        val paths=linkedMapOf<String,String>();val byId=hashMapOf<Int,JSONObject>()
        repeat(expected.length()){i ->
            val pin=expected.getJSONObject(i);exact(pin,"selector","file","bytes","sha256","kind","code")
            val id=number(pin,"selector");require(id in 0..63);val index=id.toInt();require(index in plan.neededSelectors&&byId.put(index,pin)==null)
            val hash=string(pin,"sha256");require(hash.matches(Regex("[0-9a-f]{64}"))&&string(pin,"file")=="$hash.json")
            val selector=plan.selector(index);require(string(pin,"kind")==selector.getString("kind")&&string(pin,"code")==selector.getString("code"))
            require(number(pin,"bytes") in 1..8L*1024*1024);paths["geo-$index"]=directory.resolve("$hash.json").toString()
        }
        val actual=SteerGeoCacheManifest.captureFiles(filesDir,plan,paths)
        repeat(actual.length()){i ->val pin=actual.getJSONObject(i);val saved=byId.getValue(pin.getInt("selector"))
            require(pin.getLong("bytes")==number(saved,"bytes")&&pin.getString("sha256")==string(saved,"sha256")){"Подготовленная категория Steer изменилась."}
        }
        return actual
    }
}
