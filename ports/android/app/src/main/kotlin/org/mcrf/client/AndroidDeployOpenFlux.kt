package org.mcrf.client

import org.json.JSONObject
import java.net.URI

/** Deploy's contract is independent of the concurrently maintained Android client adapter. */
internal object AndroidDeployOpenFlux {
    const val CORE_COMMIT="f492a1a33b7581ad8f88118f97e0b866adda3cdc" // upstream v0.17.1
    val transports=listOf("direct","yandex","vyandex","boards","mailru","cupsonline","avito","telemost","script")
    val scripts=listOf("yandex","vyandex","boards","mailru","cupsonline","oneme-iceinject","oneme-webrtc","mtslink","bitrix","pruffme")
    // Existing encrypted schema stores this envelope in the carrier_url string for script only.
    fun script(value:String):JSONObject {
        require(value.length<=8192){"Параметры script превышают лимит зашифрованного черновика"}
        require(OpenFluxJsonNesting.bounded(value)){"Параметры script: слишком глубокий или некорректный JSON"}
        val data=runCatching {JSONObject(value)}.getOrElse {throw IllegalArgumentException("Script: укажите имя пакета, URL и настройки JSON")}
        require(data.keys().asSequence().all {it in setOf("name","url","settings")}){"Неизвестные параметры script"}
        require(data.opt("name") is String&&data.getString("name") in scripts){"Выберите закреплённый script-пакет; mtslink-webrtc/vkteams ещё не опубликованы"}
        require(!data.has("url")||data.opt("url") is String){"URL script должен быть строкой"}
        val url=data.optString("url")
        require(url.length<=8192&&url.none {it.isISOControl()||it in "#;"}){"URL script содержит недопустимые для серверной конфигурации символы"}
        val settings=if(data.has("settings"))data.optJSONObject("settings") else JSONObject()
        require(settings!=null&&settings.length()<=64){"Настройки script: JSON-объект строк (не более 64 полей)"}
        settings.keys().forEach {key->require(key.matches(Regex("[A-Za-z0-9_.-]{1,128}"))&&settings.opt(key) is String&&settings.getString(key).length<=8192){"Настройки script должны содержать только строки"}}
        return JSONObject().put("name",data.getString("name")).put("url",url).put("settings",JSONObject(settings.toString()))
    }
    fun validate(options:JSONObject) {
        val kind=options.optString("transport","direct")
        require(kind in transports){"Неизвестный транспорт OpenFlux"}
        val value=options.optString("carrier_url")
        when(kind){
            "direct"->Unit
            "script"->script(value)
            "cupsonline","avito"->require(value.length<=8192&&(value.isEmpty()||value.matches(Regex("[A-Za-z0-9+/_=-]+")))){"Для Cups/Avito нужен код комнат либо пустое поле для создания новых комнат"}
            else->{val uri=runCatching {URI(value)}.getOrNull();require(value.length<=8192&&uri!=null&&uri.scheme=="https"&&uri.host!=null&&uri.userInfo==null&&value.none {it.isISOControl()||it in "#;"}){"Нужна HTTPS-ссылка сервиса без управляющих символов"}}
        }
    }

    /** Narrow, guarded adapter for the pinned server panel, applied before its build, never to Android runtime. */
    val panelPatch="""
import hashlib, pathlib, sys
root = pathlib.Path(sys.argv[1])
for filename, expected in {'manager.go':'b5862e71c6502d9c2236be408da3f0bc7b48a1fa06f636a4e358afc0520203cd', 'share.go':'a8be5d7a9f17659be5c96f8409d0d3f4cf59dfb310935b1e6900e21cc3afcdd8'}.items():
    if hashlib.sha256((root / filename).read_bytes()).hexdigest() != expected:
        raise RuntimeError('Pinned panel source digest mismatch; no adapter files changed')
def replace(source, old, new, count=1):
    if source.count(old) != count:
        raise RuntimeError('Pinned OpenFlux panel contract changed; installation stopped')
    return source.replace(old, new)
manager = (root / 'manager.go').read_text()
share = (root / 'share.go').read_text()
manager = replace(manager, 'type TransportLink struct {', 'type TransportLink struct {\n\tParams map[string]any `json:"params,omitempty"`')
manager = replace(manager, '"avito", "telemost", "direct":', '"avito", "telemost", "direct", "script":', 2)
manager = replace(manager, 'func validateConfig(c Config) error {', '''func validateConfig(c Config) error {
    if c.Transport == "script" && len(c.Transports) == 0 { return fmt.Errorf("script requires a protected transport entry") }
''')
manager = replace(manager, 'if link.Priority < 1 || link.Priority > 1000 {', '''if link.Type == "script" { if err := validateDeployScript(link); err != nil { return err } }
        if link.Priority < 1 || link.Priority > 1000 {''')
manager = replace(manager, 'func validateTransportURL(kind, value string, enabled bool) error {', '''func validateTransportURL(kind, value string, enabled bool) error {
    if kind == "script" { if len(value) > 8192 || strings.ContainsAny(value, "\\x00\\r\\n#;") { return fmt.Errorf("invalid script URL") }; return nil }
''')
manager = replace(manager, 'if seen[link.Type] {', 'if link.Type == "script" || seen[link.Type] {')
manager = replace(manager, 'fmt.Fprintf(&b, "URL = %s\\n", link.URL)', '''fmt.Fprintf(&b, "URL = %s\\n", link.URL)
            if link.Type == "script" {
                name, _ := link.Params["name"].(string)
                settings, _ := json.Marshal(link.Params["settings"])
                fmt.Fprintf(&b, "Path = /var/lib/openflux-deploy/scripts/mcrf-9349c01a4f2dd707/%s.flux\\nPubkey = d8bf9c958b994c2faab886cade5f28213f254911f87abe5e34756a289ae91354\\nName = %s\\nParams = %s\\n", name, name, base64.RawURLEncoding.EncodeToString(settings))
            }''')
manager = replace(manager, '"direct_ports_ready": dockerDirectReady()', '"direct_ports_ready": dockerDirectReady(), "mcrf_script_deploy": 1')
share = replace(share, 'type shareTransport struct {', 'type shareTransport struct {\n\tParams map[string]any `json:"params,omitempty"`')
share = replace(share, 't := shareTransport{Type: link.Type, URL: link.URL, Priority: link.Priority}', 't := shareTransport{Type: link.Type, URL: link.URL, Priority: link.Priority, Params: link.Params}')
share = replace(share, 'case "cupsonline":', 'case "cupsonline", "avito":')
share = replace(share, '"mailru", "avito", "telemost":', '"mailru", "telemost":')
share = replace(share, '\t\tcase "direct":', '\t\tcase "script":\n\t\t\tif err := validateDeployScript(link); err != nil { return shareConfig{}, err }\n\t\tcase "direct":')
helper = '''package main
import "fmt"
func validateDeployScript(link TransportLink) error {
    name, ok := link.Params["name"].(string)
    names := map[string]bool{"yandex":true,"vyandex":true,"boards":true,"mailru":true,"cupsonline":true,"oneme-iceinject":true,"oneme-webrtc":true,"mtslink":true,"bitrix":true,"pruffme":true}
    if !ok || !names[name] { return fmt.Errorf("unavailable script package") }
    if len(link.Params) != 2 { return fmt.Errorf("script accepts only name and settings") }
    settings, ok := link.Params["settings"].(map[string]any)
    if !ok || len(settings) > 64 { return fmt.Errorf("invalid script settings") }
    for key, value := range settings { if len(key) == 0 || len(key) > 128 { return fmt.Errorf("invalid setting key") }; text, ok := value.(string); if !ok || len(text) > 32768 { return fmt.Errorf("invalid setting value") } }
    return nil
}
'''
# All anchor checks finish before any file is replaced.
(root / 'manager.go').write_text(manager)
(root / 'share.go').write_text(share)
(root / 'mcrf_script_deploy.go').write_text(helper)
""".trimIndent()
}
