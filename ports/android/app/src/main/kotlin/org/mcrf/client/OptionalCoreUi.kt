package org.mcrf.client

import android.widget.LinearLayout

/** Optional-core parameters live in Advanced; downloading modules is a separate action. */
class OptionalCoreUi(private val activity: MainActivity) {
    fun xrayPage():LinearLayout=build("xray")
    fun tlsPage():LinearLayout=build("tls")
    fun advancedXrayView():LinearLayout=build("xray")
    fun advancedTlsView():LinearLayout=build("tls")
    fun page(id:String):LinearLayout {require(id in setOf("xray","tls"));return build(id)}
    fun view():LinearLayout=build("all")
    private fun build(id:String):LinearLayout {
        val store=OptionalCoreStore(activity)
        val keys=OptionalCorePages.keys(id)
        val form=CoreTuningForm(activity,store.load().json()){store.save(OptionalCorePages.merge(store.load(),it,keys))}
            .grouped(OptionalCoreSettings().json()){store.load().json()}
        form.hint("Параметры применяются при следующем подключении и только к выбранному установленному модулю. Открытие настроек не скачивает движки и не переключает работающий VPN.")
        if(id!="tls") {
            form.section("Xray · VLESS / REALITY / XHTTP")
            form.toggle("xrayVlessDefault","Использовать Xray для VLESS","Для VLESS без явно выбранного ядра использовать отдельный Xray. Требуется установленный совместимый модуль. Явные параметры профиля сохраняются; неподдерживаемые сочетания отклоняются.")
            form.section("Xray · объединение потоков MUX")
            form.toggle("xrayMuxDefault","Объединять потоки Xray (MUX)","Несколько потоков через одно соединение при поддержке сервера. Явные параметры профиля важнее; Vision остаётся без MUX.")
            form.field("xrayMuxConcurrency","Число потоков Xray MUX","1–1024, по умолчанию 8. Действует только при включённом MUX и поддержке сервера.",true)
        }
        if(id!="xray") {
            form.section("Отдельный TLS-модуль · фрагментация")
            form.toggle("customFragmentDefault","Разбивать TLS ClientHello на записи","Отдельный TLS-модуль, только TCP. Явный fragment профиля важнее. Не путать со встроенной фрагментацией основного ядра выше.")
            form.field("fragmentSize","Размер фрагментов · байты","Число или диапазон min-max в пределах 1–16384, по умолчанию 10-100. Меньшие фрагменты увеличивают служебные расходы.")
            form.field("fragmentSleep","Пауза между группами · мс","Число или min-max в пределах 0–100, по умолчанию 2-5. Это пауза между группами, не между каждым TCP-пакетом.")
            form.section("Отдельный TLS-модуль · имя сервера SNI")
            form.toggle("mixedCaseSniDefault","Менять регистр букв имени TLS-сервера","Меняет SNI на проводе. Имя для проверки сертификата сохраняется; проверка сертификатов не отключается.")
        }
        form.finishGroups()
        form.hint("Xray поддерживает TCP и UDP. Отдельный TLS-модуль — только TCP; UDP через него отклоняется. Совместное использование Xray и этого TLS-модуля пока не поддерживается.")
        return form.view
    }
}
