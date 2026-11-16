package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.math.BigInteger
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.zip.Inflater

/** SRS wire-format reader, independent of any traffic engine. Format references:
 * sing-box/common/srs and sing/common/domain. Unknown fields fail the whole
 * capture; decoding a field does not imply that native routing admits it. */
internal object BsRuleSetBinary {
    private const val MAX_BYTES=24*1024*1024
    private const val MAX_ITEMS=262144
    fun decode(bytes:ByteArray):JSONObject {
        require(bytes.size in 5..MAX_BYTES&&bytes[0]==83.toByte()&&bytes[1]==82.toByte()&&bytes[2]==83.toByte()) {"БС: некорректный SRS."}
        val version=bytes[3].toInt()and 255;require(version in 1..5) {"БС: версия SRS пока не поддерживается."}
        val inflater=Inflater();val output=ByteArrayOutputStream()
        try {
            inflater.setInput(bytes,4,bytes.size-4);val block=ByteArray(8192)
            while(!inflater.finished()) {
                val size=inflater.inflate(block)
                require((size>0||inflater.finished())&&output.size()+size<=MAX_BYTES) {"БС: повреждённый или слишком большой SRS."}
                output.write(block,0,size)
            }
            require(inflater.remaining==0) {"БС: лишние данные после SRS."}
        }finally {inflater.end()}
        val reader=Reader(output.toByteArray());val count=reader.count();val rules=JSONArray()
        repeat(count){rules.put(reader.rule(0))};require(reader.finished()) {"БС: лишние правила в SRS."}
        return JSONObject().put("version",version).put("rules",rules).also {
            require(it.toString().toByteArray(Charsets.UTF_8).size<=MAX_BYTES) {"БС: развёрнутый SRS слишком большой."}
        }
    }
    private class Reader(private val bytes:ByteArray) {
        private var position=0;private var ruleCount=0
        fun finished()=position==bytes.size
        private fun byte():Int {require(position<bytes.size){"БС: SRS оборван."};return bytes[position++].toInt()and 255}
        private fun raw(size:Int):ByteArray {require(size>=0&&size<=bytes.size-position);return bytes.copyOfRange(position,position+size).also {position+=size}}
        private fun variable():Long {
            var value=0L
            for(shift in 0..63 step 7){val next=byte();require(shift!=63||next<=1);value=value or ((next and 127).toLong() shl shift);if(next<128){require(value>=0);return value}}
            error("БС: переполнение длины SRS.")
        }
        fun count(max:Int=MAX_ITEMS)=variable().also {require(it<=max)}.toInt()
        private fun long():Long {var value=0L;repeat(8){value=(value shl 8)or byte().toLong()};return value}
        private fun bool():Boolean=when(byte()){0->false;1->true;else->error("БС: некорректный флаг SRS.")}
        private fun text(value:ByteArray)=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(value)).toString()
        private fun strings():JSONArray {val count=count();val result=JSONArray();repeat(count){result.put(text(raw(count(1024*1024))))};return result}
        private fun shorts():JSONArray {val count=count();val result=JSONArray();repeat(count){result.put((byte() shl 8)or byte())};return result}
        private fun type(value:Int)=when(value){0->"wifi";1->"cellular";2->"ethernet";3->"other";else->error("БС: неизвестный тип сети SRS.")}
        fun rule(depth:Int):JSONObject {
            require(depth<=64&&++ruleCount<=MAX_ITEMS)
            return when(byte()) {
                0->defaultRule()
                1->{val mode=when(byte()){0->"and";1->"or";else->error("БС: неизвестный режим SRS.")};val children=JSONArray();repeat(count()){children.put(rule(depth+1))};JSONObject().put("type","logical").put("mode",mode).put("rules",children).apply {if(bool())put("invert",true)}}
                else->error("БС: неизвестное правило SRS.")
            }
        }
        private fun defaultRule():JSONObject {
            val result=JSONObject();val seen=hashSetOf<Int>()
            while(true) {
                val item=byte();require(seen.add(item)){"БС: повторяющееся поле SRS."}
                val key=when(item){0->"query_type";1->"network";3->"domain_keyword";4->"domain_regex";5->"source_ip_cidr";6->"ip_cidr";7->"source_port";8->"source_port_range";9->"port";10->"port_range";11->"process_name";12->"process_path";13->"package_name";14->"wifi_ssid";15->"wifi_bssid";17->"process_path_regex";18->"network_type";19->"network_is_expensive";20->"network_is_constrained";21->"network_interface_address";22->"default_interface_address";23->"package_name_regex";else->""}
                when(item) {
                    0,7,9->result.put(key,shorts())
                    1,3,4,8,10,11,12,13,14,15,17,23->result.put(key,strings())
                    2->{val(domains,suffixes)=domains();require(domains.isNotEmpty()||suffixes.isNotEmpty());if(domains.isNotEmpty())result.put("domain",JSONArray(domains));if(suffixes.isNotEmpty())result.put("domain_suffix",JSONArray(suffixes))}
                    5,6->result.put(key,ipRanges())
                    16->result.put("adguard_domain",JSONArray(keys().map {stored->var value=stored;val prefix=when(value.firstOrNull()){'\r'->{value=value.substring(1);""};'\n'->{value=value.substring(1);"||"};else->"|"};val end=if(value.endsWith('\b')){value=value.dropLast(1);""}else "^";prefix+value+end}))
                    18->{val values=JSONArray();repeat(count()){values.put(type(byte()))};result.put(key,values)}
                    19,20->result.put(key,true)
                    21->{val values=JSONObject();repeat(count(4)){val type=type(byte());require(!values.has(type));val entries=JSONArray();repeat(count()){entries.put(prefix())};values.put(type,entries)};result.put(key,values)}
                    22->{val values=JSONArray();repeat(count()){values.put(prefix())};result.put(key,values)}
                    255->{if(bool())result.put("invert",true);return result}
                    else->error("БС: неизвестное поле SRS ($item).")
                }
            }
        }
        private fun address():ByteArray {val size=count(16);require(size==4||size==16);return raw(size)}
        private fun ip(value:ByteArray)=if(value.size==4)java.net.InetAddress.getByAddress(value).hostAddress!! else java.net.Inet6Address.getByAddress(null,value,-1).hostAddress!!
        private fun prefix():String {val value=address();val bits=byte();require(bits<=value.size*8);return "${ip(value)}/$bits"}
        private fun ipRanges():JSONArray {
            require(byte()==1);val count=long();require(count in 0..MAX_ITEMS.toLong());val result=JSONArray()
            repeat(count.toInt()) {
                val first=address();val last=address();require(first.size==last.size);val bits=first.size*8;var from=BigInteger(1,first);val to=BigInteger(1,last);require(from<=to)
                while(from<=to) {
                    require(result.length()<MAX_ITEMS)
                    val aligned=if(from.signum()==0)bits else from.lowestSetBit
                    val remaining=to.subtract(from).add(BigInteger.ONE);val hostBits=minOf(aligned,remaining.bitLength()-1)
                    val raw=from.toByteArray();val address=ByteArray(first.size);val size=minOf(raw.size,address.size);raw.copyInto(address,address.size-size,raw.size-size)
                    result.put("${ip(address)}/${bits-hostBits}");from=from.add(BigInteger.ONE.shiftLeft(hostBits))
                }
            };return result
        }
        private fun domains():Pair<List<String>,List<String>> {
            val exact=sortedSetOf<String>();val suffix=sortedSetOf<String>();val legacy=sortedSetOf<String>()
            keys().forEach {value->require(value.isNotEmpty());when(value[0]){'\r'->legacy.add(value.substring(1));'\n'->suffix.add(value.substring(1));else->exact.add(value)}}
            legacy.forEach {value->if(value.startsWith('.')&&exact.remove(value.substring(1)))suffix.add(value.substring(1))else suffix.add(value)}
            return exact.toList() to suffix.toList()
        }
        /** LOUDS is traversed by BFS indices, with no rank/select table or
         * recursive per-character objects. Every bitmap edge must be consumed. */
        private fun keys():List<String> {
            require(byte()==0)
            fun words():LongArray {val count=count(16385);return LongArray(count){long()}}
            val leaves=words();val bitmap=words();val labels=raw(count(1_000_000));val nodes=labels.size+1
            require(bitmap.isNotEmpty())
            fun bit(words:LongArray,index:Int)=index/64<words.size&&(((words[index/64] ushr(index%64))and 1L)!=0L)
            val parent=IntArray(nodes){-1};val edge=ByteArray(nodes);var next=1;var cursor=0;var label=0
            for(node in 0 until nodes) {
                require(node<next)
                while(true){require(cursor<bitmap.size*64);if(bit(bitmap,cursor++))break;require(next<nodes&&label<labels.size);parent[next]=node;edge[next]=labels[label++];next++}
            }
            require(next==nodes&&label==labels.size)
            for(index in cursor until bitmap.size*64)require(!bit(bitmap,index))
            for(index in nodes until leaves.size*64)require(!bit(leaves,index))
            val result=ArrayList<String>();val path=ByteArray(1024)
            for(node in 0 until nodes)if(bit(leaves,node)) {
                var current=node;var size=0
                while(current!=0){require(size<path.size);path[size++]=edge[current];current=parent[current];require(current>=0)}
                val value=ByteArray(size){path[size-it-1]};val stored=text(value)
                val points=stored.codePoints().toArray();result.add(String(points.reversedArray(),0,points.size))
                require(result.size<=MAX_ITEMS)
            }
            return result
        }
    }
}
