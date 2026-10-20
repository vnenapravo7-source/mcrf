package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** SOURCE ONLY compact DAT reader. Borrows one caller-owned immutable byte
 * snapshot; cursors keep slices, not copies/Field lists for the whole dataset.
 * Unselected category payloads are skipped. A shared finite field/output budget
 * covers lookup plus ALL selected conversions in this dataset view. */
internal object SteerGeoDat {
    private class Budget(val maxFields:Int,val maxOutputBytes:Int) {
        var fields=0;var output=0L
        fun field(){require(++fields<=maxFields){"Слишком сложная геобаза Steer."}}
        fun category(){output+=512;require(output<=maxOutputBytes){"Категории превышают ограничения Steer."}}
        fun string(value:String){
            // Upper bound on JSONObject's quoted UTF-8, including escapes,
            // comma and brackets/field-name reserve. Reject BEFORE serialization.
            var size=3L;var at=0
            while(at<value.length){val c=value[at++].code
                size+=when {c<32||c in 0x7f..0x9f||c in 0x2000..0x20ff->6;c==34||c==92||c==47->2;c<128->1;c<2048->2;
                    c in 0xd800..0xdbff->{require(at<value.length&&value[at].isLowSurrogate());at++;4};c in 0xdc00..0xdfff->error("Непарный символ геобазы.");else->3}
            }
            output+=size;require(output<=maxOutputBytes){"Категории превышают ограничения Steer."}
        }
    }
    private class Cursor(val bytes:ByteArray,val begin:Int,val end:Int,val budget:Budget) {
        private var at=begin
        var field=0;private set
        var wire=0;private set
        var scalar=0L;private set
        var start=0;private set
        var stop=0;private set
        private fun varint():Long {
            var value=0L
            for(shift in 0..63 step 7){
                require(at<end){"Обрезанная геобаза Steer."};val b=bytes[at++].toInt() and 255
                if(shift==63)require(b<=1);value=value or ((b and 127).toLong() shl shift)
                if(b<128)return value
            }
            error("Неверное число protobuf.")
        }
        fun next():Boolean {
            if(at==end)return false;budget.field()
            val key=varint();val number=key ushr 3;require(number in 1..536870911L)
            field=number.toInt();wire=(key and 7).toInt();scalar=0;start=at;stop=at
            when(wire){
                0->scalar=varint()
                1->{require(end-at>=8);at+=8}
                2->{val n=varint();require(n in 0..(end-at).toLong());start=at;at+=n.toInt();stop=at}
                5->{require(end-at>=4);at+=4}
                else->error("Неподдерживаемая структура protobuf.")
            }
            return true
        }
        fun nested():Cursor {require(wire==2);return Cursor(bytes,start,stop,budget)}
        fun text(max:Int):String {
            require(wire==2&&stop-start in 1..max)
            return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes,start,stop-start)).toString()
        }
        fun category():String? {
            require(wire==2);if(stop-start !in 1..64)return null
            for(i in start until stop){val ch=bytes[i].toInt();if(!(ch in 65..90||ch in 97..122||ch in 48..57||ch==45||ch==95))return null}
            return String(bytes,start,stop-start,Charsets.US_ASCII).lowercase(Locale.ROOT)
        }
    }
    internal class Dataset(private val bytes:ByteArray,codes:Set<String>,maxFields:Int=2_000_000,maxOutputBytes:Int=8*1024*1024) {
        private val budget=Budget(maxFields,maxOutputBytes)
        private val selected=hashMapOf<String,Pair<Int,Int>>()
        init {
            require(bytes.size in 1..64*1024*1024&&codes.size in 1..64&&maxFields in 1..2_000_000&&maxOutputBytes in 1..8*1024*1024)
            require(codes.all {it.matches(Regex("[a-zA-Z0-9_-]{1,64}"))})
            val wanted=codes.map {it.lowercase(Locale.ROOT)}.toSet();val root=Cursor(bytes,0,bytes.size,budget);var categories=0
            while(root.next())if(root.field==1){
                require(++categories<=16384);val row=root.nested();var seen=false;var code:String?=null
                while(row.next())if(row.field==1){require(!seen);seen=true;code=row.category()}
                require(seen){"Категория геобазы не имеет имени."}
                if(code!=null&&code in wanted){require(selected.put(code,root.start to root.stop)==null){"Неоднозначная категория геобазы."}}
            }
            require(selected.keys==wanted){"Выбранная категория отсутствует в геобазе."}
        }
        fun convert(kind:String,code:String):JSONObject {
            require(kind in setOf("geoip","geosite"))
            budget.category()
            val slice=selected[code.lowercase(Locale.ROOT)]?:error("Категория не выбрана в снимке.")
            val source=Cursor(bytes,slice.first,slice.second,budget)
            val rule=if(kind=="geoip")ip(source) else site(source)
            return JSONObject().put("version",3).put("rules",JSONArray().put(rule))
        }
        private fun ip(source:Cursor):JSONObject {
            val cidrs=JSONArray();var invert=false;var inversionSeen=false
            while(source.next())when(source.field){
                2->{
                    require(cidrs.length()<250000);val row=source.nested();var address:ByteArray?=null;var bits=0L;var prefixSeen=false
                    while(row.next())when(row.field){
                        1->{require(address==null&&row.wire==2&&row.stop-row.start in setOf(4,16));address=bytes.copyOfRange(row.start,row.stop)}
                        2->{require(!prefixSeen&&row.wire==0);prefixSeen=true;bits=row.scalar}
                    }
                    val ip=address?:error("В CIDR отсутствует адрес.");require(bits in 0..ip.size*8L)
                    if(ip.size==16){
                        var mapped=ip[10]==(-1).toByte()&&ip[11]==(-1).toByte()
                        if(mapped)for(i in 0 until 10)if(ip[i]!=0.toByte())mapped=false
                        require(!mapped){"Mapped IPv6 не поддержан в Geo Steer."}
                    }
                    for(bit in bits.toInt() until ip.size*8)ip[bit/8]=(ip[bit/8].toInt() and (1 shl (7-bit%8)).inv()).toByte()
                    val text="${InetAddress.getByAddress(ip).hostAddress}/$bits";budget.string(text);cidrs.put(text)
                }
                3->{require(!inversionSeen&&source.wire==0);inversionSeen=true;invert=source.scalar!=0L}
            }
            require(cidrs.length()>0);return JSONObject().put("ip_cidr",cidrs).apply {if(invert)put("invert",true)}
        }
        private fun site(source:Cursor):JSONObject {
            val groups=linkedMapOf("domain_keyword" to JSONArray(),"domain_regex" to JSONArray(),"domain_suffix" to JSONArray(),"domain" to JSONArray())
            var count=0;var names=0;var keywords=0;var regexes=0;var textBytes=0
            while(source.next())if(source.field==2){
                require(++count<=250000);val row=source.nested();var type=0L;var typeSeen=false;var value:String?=null
                while(row.next())when(row.field){
                    1->{require(!typeSeen&&row.wire==0);typeSeen=true;type=row.scalar}
                    2->{require(value==null);value=row.text(1024)}
                }
                require(type in 0..3);val text=value?:error("В категории отсутствует домен.")
                when(type.toInt()){
                    0->{require(++keywords<=4096);textBytes+=text.toByteArray(Charsets.UTF_8).size}
                    1->{require(++regexes<=128);textBytes+=text.toByteArray(Charsets.UTF_8).size}
                    else->{require(++names<=200000&&text.length<=254)}
                }
                require(textBytes<=1024*1024);budget.string(text);groups.getValue(groups.keys.elementAt(type.toInt())).put(text)
            }
            require(count>0);return JSONObject().apply {groups.forEach {(key,values)->if(values.length()>0)put(key,values)}}
        }
    }
    fun convert(bytes:ByteArray,kind:String,code:String)=Dataset(bytes,setOf(code)).convert(kind,code)
}
