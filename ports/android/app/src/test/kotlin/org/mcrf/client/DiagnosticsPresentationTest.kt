package org.mcrf.client

import java.io.File
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.Assert.*
import org.junit.Test

class DiagnosticsPresentationTest {
    @Test fun missingEvidenceIsUnknownWhileConfirmedProbeFailuresRemainFailures() {
        val request=DiagnosticRequest(requireNotNull(ServiceTestCatalog.find("github-site")),DiagnosticPath.DIRECT)
        for(issue in listOf(DiagnosticIssue.INTERNAL,DiagnosticIssue.NO_MCRF_PROXY,DiagnosticIssue.CAPACITY)) {
            val result=DiagnosticResult.of(request,DiagnosticState.ERROR,issue)
            assertEquals(DiagnosticState.UNAVAILABLE,DiagnosticsPresentation.displayState(result))
        }
        for(issue in listOf(DiagnosticIssue.DNS_FAILED,DiagnosticIssue.CONNECT_FAILED,DiagnosticIssue.TLS_FAILED,
            DiagnosticIssue.HTTP_REJECTED,DiagnosticIssue.TIMEOUT)) {
            val result=DiagnosticResult.of(request,DiagnosticState.ERROR,issue)
            assertEquals(DiagnosticState.ERROR,DiagnosticsPresentation.displayState(result))
        }
        for(state in DiagnosticState.entries.filter {it!=DiagnosticState.ERROR}) {
            assertEquals(state,DiagnosticsPresentation.displayState(DiagnosticResult.of(request,state)))
        }
    }
    @Test fun resultRowsDistinguishErrorsFromUnmeasuredAndCancelledWork() {
        assertEquals("Успех",DiagnosticsPresentation.resultState(DiagnosticState.AVAILABLE))
        assertEquals("Ошибка",DiagnosticsPresentation.resultState(DiagnosticState.ERROR))
        for(state in listOf(DiagnosticState.UNAVAILABLE,DiagnosticState.CANCELLED))
            assertEquals("Не определено",DiagnosticsPresentation.resultState(state))
        assertEquals("Проверяем…",DiagnosticsPresentation.resultState(DiagnosticState.RUNNING))
        assertEquals("Ожидание",DiagnosticsPresentation.resultState(null))
    }
    @Test fun progressIsBoundedForEmptyPreparationPartialAndLargeQueues() {
        assertEquals(0,DiagnosticsPresentation.progress(0,0))
        assertEquals(0,DiagnosticsPresentation.progress(1,-1))
        assertEquals(0,DiagnosticsPresentation.progress(-1,12))
        assertEquals(25,DiagnosticsPresentation.progress(3,12))
        assertEquals(100,DiagnosticsPresentation.progress(15,12))
        assertEquals(100,DiagnosticsPresentation.progress(Int.MAX_VALUE,Int.MAX_VALUE))
    }
    @Test fun unmeasuredAndCancelledProbesAreNeverShownAsSuccess() {
        assertEquals("Доступен",DiagnosticsPresentation.state(DiagnosticState.AVAILABLE))
        assertEquals("Не определено",DiagnosticsPresentation.state(DiagnosticState.UNAVAILABLE))
        assertEquals("Отменено",DiagnosticsPresentation.state(DiagnosticState.CANCELLED))
        assertEquals("Ожидание",DiagnosticsPresentation.state(null))
        assertEquals("Ошибка",DiagnosticsPresentation.state(DiagnosticState.ERROR))
        for(state in DiagnosticState.entries)assertTrue(DiagnosticsPresentation.state(state).isNotBlank())
    }
    @Test fun allPartsKeepServiceIdentityIncludingDiscordVoiceAndDnsAcrossSearch() {
        for(category in ServiceTestCatalog.matching("Discord"))for(service in category.services)for(target in service.parts)
            assertEquals(R.drawable.service_brand_discord,ServiceBrandIcons.resourceFor(target.id))
        assertEquals(R.drawable.service_brand_discord,ServiceBrandIcons.resourceFor("discord-voice"))
        assertEquals(DiagnosticCheck.DNS,ServiceTestCatalog.find("discord-voice")?.check)
        for(target in ServiceTestCatalog.targets){
            val service=requireNotNull(ServiceTestCatalog.serviceFor(target.id))
            assertEquals(ServiceBrandIcons.resourceFor(service.id),ServiceBrandIcons.resourceFor(target.id))
            assertTrue(ServiceTestCatalog.iconLabel(service).isNotBlank())
        }
        assertNull(ServiceBrandIcons.resourceFor("missing"))
    }
    @Test fun everyDiscordCatalogueVariantAndMixedCaseNameUsesTheBrandDrawable() {
        val discordServices=ServiceTestCatalog.services.filter {service->
            service.id.contains("discord",true)||service.title.contains("discord",true)||
                service.parts.any {it.id.contains("discord",true)||it.title.contains("discord",true)}
        }
        assertTrue(discordServices.isNotEmpty())
        for(service in discordServices) {
            assertEquals(service.title,R.drawable.service_brand_discord,ServiceBrandIcons.resourceFor(service))
            for(part in service.parts) {
                assertEquals(part.id,R.drawable.service_brand_discord,ServiceBrandIcons.resourceFor(part.id))
                assertEquals(part.title,R.drawable.service_brand_discord,
                    ServiceBrandIcons.resourceFor(service.copy(id=part.id,title="${service.title} ${part.title}")))
            }
        }
        for(name in listOf("Discord голос","Discord DNS","DISCORD","voice-dIsCoRd-beta","my Discord variant")) {
            assertEquals(name,R.drawable.service_brand_discord,ServiceBrandIcons.resourceFor(name))
            assertEquals(name,R.drawable.service_brand_discord,
                ServiceBrandIcons.resourceFor(ServiceTestService("unknown-variant",name,emptyList())))
        }
    }
    @Test fun offlineDiscordVectorHasVisibleGeometryAndNoNetworkDependency() {
        val path="src/main/res/drawable/service_brand_discord.xml"
        val file=listOf(File(path),File("app/$path")).first {it.isFile}
        val document=DocumentBuilderFactory.newInstance().apply {isNamespaceAware=true}.newDocumentBuilder().parse(file)
        val vector=document.documentElement
        assertEquals("vector",vector.tagName)
        val paths=vector.getElementsByTagName("path")
        assertTrue(paths.length>0)
        val pathElement=paths.item(0) as org.w3c.dom.Element
        assertTrue(pathElement.getAttributeNS("http://schemas.android.com/apk/res/android","pathData").length>100)
        assertFalse(pathElement.getAttributeNS("http://schemas.android.com/apk/res/android","fillColor").isBlank())
        // Android PathParser requires seven numeric arguments per arc. SVG's
        // compressed flags (00/01) are legal SVG but not Android pathData.
        val data=pathElement.getAttributeNS("http://schemas.android.com/apk/res/android","pathData")
        val numbers=Regex("[-+]?(?:[0-9]*\\.[0-9]+|[0-9]+\\.?[0-9]*)")
        val arcs=Regex("[aA]([^a-zA-Z]*)").findAll(data).toList()
        assertTrue(arcs.isNotEmpty())
        for(arc in arcs) {
            val values=numbers.findAll(arc.groupValues[1]).map {it.value}.toList()
            assertEquals("Malformed Android arc: ${arc.value}",0,values.size%7)
            for(group in values.chunked(7)) {
                assertTrue("Invalid large-arc flag",group[3] in listOf("0","1"))
                assertTrue("Invalid sweep flag",group[4] in listOf("0","1"))
            }
        }
    }
}
