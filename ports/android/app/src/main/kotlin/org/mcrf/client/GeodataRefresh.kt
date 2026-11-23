package org.mcrf.client

/** A failed refresh may reuse only a complete snapshot that passes current category validation. */
internal object GeodataRefresh {
    fun bytes(fetch:()->ByteArray,cached:()->ByteArray?,validate:(ByteArray)->Unit):ByteArray {
        try {return fetch().also(validate)} catch(refresh:Exception) {
            if(refresh is InterruptedException || refresh is java.util.concurrent.CancellationException || Thread.currentThread().isInterrupted)throw refresh
            val previous=cached()?:throw refresh
            try {validate(previous)} catch(invalid:Exception) {refresh.addSuppressed(invalid);throw refresh}
            return previous
        }
    }
}
