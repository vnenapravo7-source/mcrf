package org.mcrf.client

/** Linear value scanning: quoted/nested secrets must not leak their remaining suffix. */
internal object DiagnosticSecrets {
    private val field=Regex("""(?i)(?<![A-Za-z0-9_])["']?(password|token|access_token|refresh_token|secret|clashSecret|apiSecret|uuid|private_?key|encryption_key|authorization|access_?key(?:_?id)?|secret_?key|aws_?secret_?access_?key|aws_?access_?key_?id|socks_pass|pass|credential|signature|x-amz-(?:credential|signature|security-token)|hashes|vk_hashes|call_hashes|vk_call_hash|vk_hash|call_hash|invite_link)["']?\s*[:=]\s*""")
    private val call=Regex("""(?i)https?://(?:(?:www|m)\.)?vk\.(?:com|ru)(?::443)?/call/[^\s"'<>]+""")
    fun redact(text:String):String {
        val input=text.replace(call,"[VK-звонок скрыт]")
        val output=StringBuilder(input.length)
        var offset=0
        while(true){
            val match=field.find(input,offset)?:break
            val start=match.range.last+1
            output.append(input,offset,start)
            val end=end(input,start,match.groupValues[1].endsWith("hashes",true))
            if(end>start)output.append("[скрыто]")
            offset=end.coerceAtLeast(start)
        }
        return output.append(input,offset,input.length).toString()
    }
    private fun end(text:String,start:Int,hashList:Boolean):Int {
        if(start>=text.length)return start
        val stack=java.util.ArrayDeque<Char>()
        var quote:Char?=null
        var escaped=false
        val first=text[start]
        if(first=='"'||first=='\'')quote=first
        else if(first=='['||first=='{')stack.addLast(if(first=='[')']' else '}')
        else {
            var index=start
            val delimiters=if(hashList)";\r\n}" else ",;\r\n}]"
            while(index<text.length&&text[index] !in delimiters)index++
            return index
        }
        var index=start+1
        while(index<text.length){
            val c=text[index]
            if(quote!=null){
                if(escaped)escaped=false
                else if(c=='\\')escaped=true
                else if(c==quote){quote=null;if(stack.isEmpty())return index+1}
            }else when(c){
                '"','\''->quote=c
                '['->stack.addLast(']')
                '{'->stack.addLast('}')
                ']', '}'->{if(stack.isEmpty()||stack.removeLast()!=c)return text.length;if(stack.isEmpty())return index+1}
            }
            index++
        }
        return text.length // Truncated sensitive value: hide the whole remaining fragment.
    }
}
