package org.mcrf.client

/** Local roster indices retain the identity of each row after filtering. No remote calls. */
object ServerClientSearch {
    fun indices(names:List<String>,ids:List<String>,query:String):List<Int> {
        require(names.size==ids.size)
        val words=query.trim().split(Regex("\\s+")).filter {it.isNotEmpty()}
        return names.indices.filter {index->
            words.all {word->names[index].contains(word,ignoreCase=true)||ids[index].contains(word,ignoreCase=true)}
        }
    }
}
