package org.mcrf.client

import java.io.ByteArrayInputStream
import java.io.DataInputStream
import java.math.BigInteger
import java.net.InetAddress
import org.json.JSONArray
import org.json.JSONObject

/** Independent bounded reader of the public MRS v1 wire format (Mihomo Meta branch).
 * A breadth-first trie is expanded to ordinary source rules, never executed as code. */
internal object MihomoMrs {
    private const val LIMIT=64*1024*1024
    fun decode(compressed:ByteArray,behavior:String):JSONObject {
        require(compressed.size in 1..LIMIT)
        val bytes=com.github.luben.zstd.ZstdInputStream(ByteArrayInputStream(compressed)).use {input->
            val out=java.io.ByteArrayOutputStream();val buffer=ByteArray(32768)
            while(true){val n=input.read(buffer);if(n<0)break;require(out.size()+n<=LIMIT){"Распакованный MRS больше 64 МБ."};out.write(buffer,0,n)};out.toByteArray()
        }
        return unpack(bytes,behavior)
    }
    internal fun unpack(bytes:ByteArray,behavior:String):JSONObject {
        require(bytes.size<=LIMIT)
        val input=DataInputStream(ByteArrayInputStream(bytes))
        require(input.readInt()==0x4d525301){"Неизвестный формат MRS."}
        require(input.readUnsignedByte()==when(behavior){"domain"->0;"ipcidr"->1;else->error("MRS поддерживает domain / ipcidr.")})
        require(input.readLong() in 0..1_000_000)
        val extra=input.readLong();require(extra in 0..65536&&extra<=input.available());input.skipBytes(extra.toInt())
        require(input.readUnsignedByte()==1)
        val result=if(behavior=="domain")domains(input)else addresses(input)
        require(input.available()==0){"Лишние данные после MRS."}
        val rules=JSONArray()
        result.keys().forEach {key->val a=result.getJSONArray(key)
            for(start in 0 until a.length() step 128)rules.put(JSONObject().put(key,JSONArray((start until minOf(start+128,a.length())).map(a::get))))
        }
        return JSONObject().put("version",3).put("rules",rules)
    }
    private fun count(input:DataInputStream,unit:Int,max:Int):Int {
        val n=input.readLong();require(n in 1..max.toLong()&&n*unit<=input.available());return n.toInt()
    }
    private fun domains(input:DataInputStream):JSONObject {
        val leaves=LongArray(count(input,8,100000)){input.readLong()}
        val bits=LongArray(count(input,8,200000)){input.readLong()}
        val labels=ByteArray(count(input,1,4_000_000));input.readFully(labels)
        fun bit(a:LongArray,index:Int):Boolean {require(index>=0&&index/64<a.size);return a[index/64] and (1L shl (index%64))!=0L}
        val parents=IntArray(labels.size+1);var node=0;var edge=0;var cursor=0
        while(node<=labels.size) {
            require(node<=edge){"Несвязное дерево MRS."}
            while(!bit(bits,cursor++)){require(edge<labels.size);parents[++edge]=node}
            node++
        }
        require(edge==labels.size)
        val exact=JSONArray();val suffix=JSONArray();val regex=JSONArray();var total=0;var size=0
        for(i in parents.indices)if(bit(leaves,i)) {
            require(++total<=1_000_000)
            val path=java.io.ByteArrayOutputStream();var current=i
            while(current>0){require(path.size()<1024);path.write(labels[current-1].toInt());current=parents[current]}
            val reversed=path.toByteArray().reversedArray()
            val decoder=Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            val value=StringBuilder(decoder.decode(java.nio.ByteBuffer.wrap(reversed)).toString()).reverse().toString().removePrefix("+")
            require(value.isNotBlank());size+=value.length;require(size<=24*1024*1024)
            when {
                value.contains('*')->regex.put("^"+value.split('*').joinToString("[^.]+") {java.util.regex.Pattern.quote(it)}+"$")
                value.startsWith('.')->regex.put("^.+\\."+java.util.regex.Pattern.quote(value.drop(1))+"$")
                else->exact.put(value)
            }
        }
        return JSONObject().apply {if(exact.length()>0)put("domain",exact);if(suffix.length()>0)put("domain_suffix",suffix);if(regex.length()>0)put("domain_regex",regex);require(length()>0)}
    }
    private fun addresses(input:DataInputStream):JSONObject {
        val ranges=count(input,32,500000);val cidrs=JSONArray()
        repeat(ranges) {
            val from=ByteArray(16).also(input::readFully);val to=ByteArray(16).also(input::readFully)
            fun mapped(a:ByteArray)=a.take(10).all {it==0.toByte()}&&a[10]==(-1).toByte()&&a[11]==(-1).toByte()
            val ipv4=mapped(from)&&mapped(to);val width=if(ipv4)32 else 128
            var low=BigInteger(1,if(ipv4)from.copyOfRange(12,16)else from)
            val high=BigInteger(1,if(ipv4)to.copyOfRange(12,16)else to);require(low<=high)
            while(low<=high) {
                require(cidrs.length()<1_000_000)
                val zeros=if(low==BigInteger.ZERO)width else low.lowestSetBit.coerceAtMost(width)
                val power=minOf(zeros,high.subtract(low).add(BigInteger.ONE).bitLength()-1)
                val raw=low.toByteArray();val fixed=ByteArray(width/8);val take=minOf(raw.size,fixed.size);raw.copyInto(fixed,fixed.size-take,raw.size-take)
                cidrs.put("${InetAddress.getByAddress(fixed).hostAddress}/${width-power}")
                low=low.add(BigInteger.ONE.shiftLeft(power))
            }
        }
        return JSONObject().put("ip_cidr",cidrs)
    }
}
