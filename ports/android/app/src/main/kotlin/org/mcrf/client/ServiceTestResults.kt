package org.mcrf.client

object ServiceTestResults {
    enum class Group(val title:String) {SUCCESS("Успех"),UNKNOWN("Не определено"),FAILURE("Провал")}
    fun group(result:DiagnosticResult)=when(result.state) {
        DiagnosticState.AVAILABLE->Group.SUCCESS
        DiagnosticState.ERROR->if(result.proxyFailure == null && result.issue in setOf(DiagnosticIssue.NO_ADDRESS,DiagnosticIssue.DNS_FAILED,DiagnosticIssue.CONNECT_FAILED,
            DiagnosticIssue.TLS_FAILED,DiagnosticIssue.HTTP_REJECTED,DiagnosticIssue.TIMEOUT))Group.FAILURE else Group.UNKNOWN
        else->Group.UNKNOWN
    }
    fun grouped(results:List<DiagnosticResult>)=Group.entries.associateWith {bucket->results.filter {group(it)==bucket}}
}
