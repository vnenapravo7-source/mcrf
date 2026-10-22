import java.security.MessageDigest
import java.nio.file.Files

// Inactive candidate-only recipe. Public legacy asset is NEVER edited.
val p2Candidate = providers.gradleProperty("steerP2Candidate").orNull == "true"
val gateFile = file(requireNotNull(providers.gradleProperty("p1SourceReadyReceipt").orNull))
val gateSha = requireNotNull(providers.gradleProperty("p1SourceReadySha256").orNull)
val repository = projectDir.parentFile.parentFile.parentFile.canonicalFile
require(gateSha.matches(Regex("[0-9a-f]{64}")) && gateFile.isAbsolute &&
    gateFile.absoluteFile.normalize() == gateFile.canonicalFile &&
    gateFile.canonicalFile.parentFile == repository.resolve("ports/android/coordination").canonicalFile &&
    gateFile.name.matches(if(p2Candidate)Regex("p2-progress-2026-10-20\\.json")else Regex("p1-progress-[0-9]{4}-[0-9]{2}-[0-9]{2}\\.json")) &&
    !Files.isSymbolicLink(gateFile.toPath()) && gateFile.isFile && gateFile.length() in 1L..262144L)
val bytes = gateFile.readBytes()
require(MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) } == gateSha)
val gate = groovy.json.JsonSlurper().parse(bytes) as Map<*, *>
val candidateCode=if(p2Candidate)requireNotNull(providers.gradleProperty("steerP2VersionCode").orNull).toInt() else 6
if(p2Candidate)require(candidateCode>=13&&candidateCode.toString()==gate["steerCandidateVersionCode"].toString())
require(gate["priority"].toString() == if (p2Candidate) "2" else "1")
require(gate["verificationAuthority"] == if (p2Candidate) "human-20261020-p2-whole-source-before-common-verification" else "human-20261019-p1-whole-source-before-common-verification")
require(listOf("sourceReady", "readyForBuild", "allowCommonVerification", "nativeSourceReady", "allowNativeCandidateBuild").all { gate[it] == true }) {
    "Finish the WHOLE active priority source package before compiling an external candidate"
}
if(p2Candidate){
    val items=(gate["items"] as List<*>).map {it as Map<*,*>}
    require(items.map {it["id"].toString()}.sorted()==listOf("11","2","20","6","7")&&items.all {it["sourceReady"]==true}) {"ALL named P2 items must be source-ready"}
}
@Suppress("UNCHECKED_CAST")
val capabilities = (groovy.json.JsonSlurper().parse(file("core/src/main/assets/steer-capabilities.json")) as Map<String, Any>).toMutableMap()
capabilities.putAll(mapOf(
    "port_version" to "0.3.3-p1-candidate.6", "mcrf_snapshot_model" to 2,
    "ipv6" to "capture_v6_native", "ipv4_compatibility" to "managed_dns_a_only",
    "local_proxy" to "authenticated_http_socks_vpn", "local_proxy_flow" to "observed_control_request",
    "per_app_routing" to "original_socket_uid", "per_flow_routing" to "original_socket_tuple",
    "per_rule_dns" to "owned_udp_doh_dot", "native_address_rules" to "owned_pinned_address",
    "preset_rules" to "owned_srs_address", "geo_rules" to "owned_pinned_geo",
    "candidate_only" to true, "runtime_accepted" to false
))
if(p2Candidate)capabilities.putAll(mapOf("port_version" to "0.3.3-p2-candidate.$candidateCode",
    "independent_probe" to "native_https_204_fresh_worker_v1",
    "route_backend" to "native_protocol_internal_socks_1"))
capabilities["unsupported"] = (capabilities.getValue("unsupported") as List<*>).filterNot { it in setOf("per_rule_dns", "mixed_local_proxy") }
capabilities["managed_dns_limits"] = mapOf("mapping_capacity" to 32768, "mapping_reuse" to "never_within_process",
    "ipv6" to "owned_AAAA_aliases", "udp_payload_max" to 4096, "host_must_be_inside_vpn" to true,
    "app_encrypted_dns" to "not_intercepted")
extra["p1CandidateCapabilities"] = groovy.json.JsonOutput.toJson(capabilities)
extra["p1CandidateReadinessSha256"] = gateSha
