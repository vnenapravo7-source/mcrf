package org.mcrf.client

import java.net.IDN
import java.net.URI
import java.util.Locale

/** Two physical-network sites, then a selected-outbound site. */
object BsDetectionSites {
    val keys=listOf("bsDetectionSite1","bsDetectionSite2","bsDetectionSite3")
    val defaults=listOf("google.com","gitlab.com","youtube.com")
    val presets=listOf("google.com","gitlab.com","github.com","youtube.com","discord.gg","pinterest.com","wikipedia.org","netflix.com","livew.com","microsoft.com","twitch.tv","zoom.us")
    fun normalize(value:String):String {
        val text=value.trim()
        require(text.length in 1..1024) {"Укажите домен сайта."}
        val uri=URI(if("://" in text)text else "https://$text")
        require(uri.scheme.equals("https",true)&&uri.rawUserInfo==null&&uri.port==-1&&uri.rawQuery==null&&uri.rawFragment==null&&uri.rawPath.orEmpty() in setOf("","/")) {"Нужен домен без порта, пути и параметров."}
        val host=IDN.toASCII(uri.host?:error("Укажите домен, например github.com."),IDN.USE_STD3_ASCII_RULES).lowercase(Locale.ROOT)
        val labels=host.split('.')
        require(host.length<=253&&labels.size>=2&&labels.all {it.length in 1..63&&it.matches(Regex("[a-z0-9](?:[a-z0-9-]*[a-z0-9])?"))}&&labels.last().any {it in 'a'..'z'}&&labels.last() !in setOf("local","localhost","internal","invalid","test")) {"Укажите публичный домен сайта."}
        return host
    }
    fun load(read:(String,String)->String)=keys.mapIndexed {i,key->runCatching {normalize(read(key,defaults[i]))}.getOrDefault(defaults[i])}
    fun url(host:String)="https://${normalize(host)}/"
}
