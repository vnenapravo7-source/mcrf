package org.mcrf.client

internal object AppReleasePagination {
    fun complete(rowCount:Int):Boolean {require(rowCount in 0..100);return rowCount<100}
}
