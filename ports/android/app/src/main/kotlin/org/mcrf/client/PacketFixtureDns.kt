package org.mcrf.client

/** Strict, nonce-scoped UDP proof for a private DEBUG peer; never a general DNS resolver. */
object PacketFixtureDns {
    fun query(nonce:String,ipv6:Boolean,id:Int):ByteArray {
        require(nonce.matches(Regex("[0-9a-f]{32}"))&&id in 0..65535)
        val name=byteArrayOf(32)+nonce.toByteArray(Charsets.US_ASCII)+byteArrayOf(7)+"invalid".toByteArray(Charsets.US_ASCII)+byteArrayOf(0)
        return byteArrayOf((id ushr 8).toByte(),id.toByte(),1,0,0,1,0,0,0,0,0,0)+name+
            byteArrayOf(0,if(ipv6)28 else 1,0,1)
    }
    fun verify(query:ByteArray,response:ByteArray,ipv6:Boolean) {
        require(query.size==58&&response.size==query.size+12+if(ipv6)16 else 4) {"PACKET_DNS_SIZE"}
        require(response[0]==query[0]&&response[1]==query[1]&&
            response.copyOfRange(2,12).contentEquals(byteArrayOf(0x81.toByte(),0x80.toByte(),0,1,0,1,0,0,0,0))&&
            response.copyOfRange(12,query.size).contentEquals(query.copyOfRange(12,query.size))) {"PACKET_DNS_QUESTION"}
        val answer=byteArrayOf(0xc0.toByte(),12,0,if(ipv6)28 else 1,0,1,0,0,0,0,0,if(ipv6)16 else 4)
        val address=if(ipv6)byteArrayOf(0xfd.toByte(),0x77,0,0,0,0,0,0,0,0,0,0,0,0,0,1)else byteArrayOf(10,77,0,1)
        require(response.copyOfRange(query.size,response.size).contentEquals(answer+address)) {"PACKET_DNS_ANSWER"}
    }
}
