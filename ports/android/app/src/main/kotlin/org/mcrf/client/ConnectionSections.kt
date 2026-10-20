package org.mcrf.client

/** Presentation/explicit-start scope only. Never rewrites the encrypted catalogue. */
object ConnectionSections {
    fun belongs(entry:ServerImport.Server,whitelists:Boolean)= (entry.carrier!=null)==whitelists
    fun entries(all:List<ServerImport.Server>,whitelists:Boolean)=all.filter {belongs(it,whitelists)}
    fun policy(whitelists:Boolean)=if(whitelists)AutoSelection.BS_ENGINES else AutoSelection.LATENCY
    fun importDestination(entries:List<ServerImport.Server>):String?=when {
        entries.isEmpty()->null
        entries.all {belongs(it,true)}->ClientSettingsNavigation.WHITELISTS
        entries.all {belongs(it,false)}->"Главная"
        else->null // Mixed subscriptions keep their current section, never lose either family.
    }
}
