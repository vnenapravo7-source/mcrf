package org.mcrf.client

import java.util.Locale

object WarpCountryChoices {
    val basic=listOf("Любая за пределами РФ" to "foreign","РФ" to "RU")
    fun values(all:Boolean,locale:Locale=Locale.forLanguageTag("ru"))=if(!all)basic else basic+
        Locale.getISOCountries().filter {it!="RU"}.map {Locale("",it).getDisplayCountry(locale) to it}.sortedBy {it.first}
    fun showAll(country:String)=country !in setOf("foreign","RU","any")
}
