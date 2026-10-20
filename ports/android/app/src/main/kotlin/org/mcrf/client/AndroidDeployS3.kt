package org.mcrf.client

import org.json.JSONArray
import org.json.JSONObject
import java.util.Base64

/** Own pinned SSH recipe, not an upstream installer/panel with invented API semantics. */
object AndroidDeployS3 {
    const val GO_VERSION="1.27.2"
    private const val GO_AMD64="ecbadb99091a3f46e31f5f934b068b1864eafa7995211b39eaddf76996045fe5"
    private const val GO_ARM64="94f3e30b8e374bc285e7dadc11e0865726b9bc6e85b841ccceaabc0214c6b7c8"
    private fun operationKey(operation:String):String{
        require(operation.matches(Regex("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}"))) {"Некорректный идентификатор сервиса S3."}
        return operation.replace("-","")
    }
    fun service(operation:String)="mcrf-s3bypass-"+operationKey(operation)
    fun profile(options:JSONObject,operation:String):JSONObject {
        val root=options.optString("s3_prefix","mcrf/").trimEnd('/')
        require(root.isNotEmpty()) {"Укажи отдельный непустой префикс S3 для этого сервера."}
        val storage=JSONObject().put("type","s3").put("bucket",options.getString("s3_bucket"))
            .put("endpoint",options.getString("s3_endpoint")).put("region",options.getString("s3_region"))
            .put("prefix",root+"/"+operationKey(operation)+"/").put("sessionsDir","sessions")
            .put("accessKey",options.getString("s3_access_key")).put("secretKey",options.getString("s3_secret_key"))
        val raw=JSONObject().put("type",S3BypassProfile.KIND).put("name","S3 Bypass")
            .put("settings",JSONObject().put("storage",storage).put("tuning",JSONObject()))
        return S3BypassProfile.fromJson(raw.toString(),raw).carrier!!
    }
    fun validate(target:AndroidDeployTarget) {
        require(target.options.optBoolean("create",true)) {"S3 Bypass устанавливает сервис, не панель. Создание подключения обязательно."}
        profile(target.options,target.operationId)
    }
    fun link(profile:JSONObject)="s3bypass://"+Base64.getUrlEncoder().withoutPadding().encodeToString(profile.toString().toByteArray(Charsets.UTF_8))
    fun validateResult(result:JSONObject,target:AndroidDeployTarget?=null) {
        val operation=result.getString("operation_id")
        require(operation.matches(Regex("[a-f0-9-]{36}"))&&result.getString("service")==service(operation))
        require(result.optString("recipe_id")==S3BypassProfile.KIND&&result.optString("core_commit")==S3BypassProfile.COMMIT)
        for(key in listOf("ready","provision_requested","provision_complete"))require(result.get(key) is Boolean&&result.getBoolean(key))
        val links=result.getJSONArray("connections");require(links.length()==1)
        val parsed=ServerImport.read(links.getString(0)).single()
        require(parsed.protocol==S3BypassProfile.KIND&&parsed.carrier!=null)
        if(target!=null)require(operation==target.operationId&&link(profile(target.options,target.operationId))==links.getString(0)) {"Результат S3 не соответствует подтверждённому запросу."}
    }
    fun build(target:AndroidDeployTarget,dir:String):String {
        AndroidDeployPlan.validate(S3BypassProfile.KIND,target)
        require(dir.matches(Regex("/tmp/mcrf-deploy-[a-f0-9]{32}")))
        val client=profile(target.options,target.operationId)
        val storage=JSONObject(client.getJSONObject("settings").getJSONObject("storage").toString())
        storage.put("prefix",target.options.optString("s3_prefix","mcrf/").trimEnd('/')+"/")
        val config=JSONObject().put("log",JSONObject().put("loglevel","warning"))
            .put("inbounds",JSONArray().put(JSONObject().put("tag","s3-in").put("protocol","fedarisha")
                .put("settings",JSONObject().put("storage",storage)
                    .put("clients",JSONArray().put(JSONObject().put("id",target.operationId.replace("-",""))))
                    .put("webhook",JSONObject().put("enabled",false).put("autoSetup",false)))))
            .put("outbounds",JSONArray().put(JSONObject().put("protocol","freedom").put("tag","direct")))
        val result=JSONObject().put("recipe_id",S3BypassProfile.KIND).put("operation_id",target.operationId)
            .put("service",service(target.operationId)).put("core_commit",S3BypassProfile.COMMIT)
            .put("ready",true).put("provision_requested",true).put("provision_complete",true)
            .put("connections",JSONArray().put(link(client)))
        fun encoded(value:String)=Base64.getEncoder().encodeToString(value.toByteArray(Charsets.UTF_8))
        val prelude="set -eu\numask 077\necho $$ > "+AndroidDeployPlan.quote(dir+"/pid")+
            "\ncd "+AndroidDeployPlan.quote(dir)+"\n"+
            "export MCRF_S3_SERVICE="+AndroidDeployPlan.quote(service(target.operationId))+"\n"+
            "export MCRF_S3_STAGE="+AndroidDeployPlan.quote(dir)+"\n"+
            "export MCRF_S3_CONFIG="+AndroidDeployPlan.quote(encoded(config.toString()))+"\n"+
            "export MCRF_S3_RESULT="+AndroidDeployPlan.quote(encoded(result.toString()))+"\n"+
            "export MCRF_S3_COMMIT="+AndroidDeployPlan.quote(S3BypassProfile.COMMIT)+"\n"+
            "export MCRF_GO_VERSION="+AndroidDeployPlan.quote(GO_VERSION)+"\n"+
            "export MCRF_GO_AMD64="+AndroidDeployPlan.quote(GO_AMD64)+"\n"+
            "export MCRF_GO_ARM64="+AndroidDeployPlan.quote(GO_ARM64)+"\n"
        return prelude+installer
    }
    // No bucket/account creation, lifecycle policy edits, remote scripts or unrelated service restarts.
    internal val installer="""
test "@(id -u)" = 0
test "@(systemctl --version | head -n 1 | awk '{print @2}')" -ge 247
test ! -e "/etc/systemd/system/@MCRF_S3_SERVICE.service"
test ! -e "/opt/@MCRF_S3_SERVICE"
export DEBIAN_FRONTEND=noninteractive
apt-get update -qq
apt-get install -y -qq ca-certificates curl git python3 tar
case "@(uname -m)" in
  x86_64) arch=amd64; sha="@MCRF_GO_AMD64";;
  aarch64|arm64) arch=arm64; sha="@MCRF_GO_ARM64";;
  *) echo 'S3 Bypass: unsupported server architecture'; exit 1;;
esac
curl --fail --silent --show-error --proto '=https' --tlsv1.2 "https://go.dev/dl/go@MCRF_GO_VERSION.linux-@arch.tar.gz" -L --proto-redir '=https' -o go.tar.gz
printf '%s  go.tar.gz\n' "@sha" | sha256sum -c - >/dev/null
mkdir toolchain
tar -xzf go.tar.gz -C toolchain
git init --quiet source
git -C source remote add origin https://github.com/Fedarisha/Xray-core-fedarisha.git
git -C source fetch --quiet --depth 1 origin "@MCRF_S3_COMMIT"
git -C source checkout --quiet --detach FETCH_HEAD
test "@(git -C source rev-parse HEAD)" = "@MCRF_S3_COMMIT"
# Upstream defaults to editing a bucket lifecycle rule. This recipe must not do that.
python3 - <<'MCRF_S3_SOURCE_PY'
from pathlib import Path
path=Path('source/proxy/fedarisha/inbound.go')
text=path.read_text()
call='registerLifecycle(s.ctx, s.tag, s.config.GetStorage(), store)'
if text.count(call)!=1:
    raise RuntimeError('Pinned S3 lifecycle boundary changed; no service installed')
path.write_text(text.replace(call,'// MCRF: bucket lifecycle is controlled by its owner, not deployment.'))
MCRF_S3_SOURCE_PY
export GOTOOLCHAIN=local
export CGO_ENABLED=0
export GOENV=off GOWORK=off GOFLAGS= GOEXPERIMENT=
unset GOROOT
(cd source; ../toolchain/go/bin/go build -mod=readonly -trimpath -o ../s3bypass ./main)
install -d -m 700 "/opt/@MCRF_S3_SERVICE"
install -m 755 s3bypass "/opt/@MCRF_S3_SERVICE/s3bypass"
install -m 644 source/LICENSE "/opt/@MCRF_S3_SERVICE/LICENSE"
python3 - <<'MCRF_S3_CONFIG_PY'
import base64,json,os
name=os.environ['MCRF_S3_SERVICE']
root='/opt/'+name
config=base64.b64decode(os.environ['MCRF_S3_CONFIG'],validate=True)
json.loads(config)
with open(root+'/config.json','xb') as out:
    out.write(config)
os.chmod(root+'/config.json',0o600)
unit='''[Unit]
Description=MCRF S3 Bypass
After=network-online.target
Wants=network-online.target
[Service]
Type=simple
DynamicUser=yes
LoadCredential=config.json:'''+root+'''/config.json
ExecStart='''+root+'''/s3bypass run -config %d/config.json
Restart=on-failure
RestartSec=5
NoNewPrivileges=yes
PrivateTmp=yes
ProtectSystem=strict
ProtectHome=yes
RestrictSUIDSGID=yes
[Install]
WantedBy=multi-user.target
'''
# Only the executable is accessible to DynamicUser; root-only config uses systemd credentials.
os.chmod(root,0o711)
with open('/etc/systemd/system/'+name+'.service','x') as out:
    out.write(unit)
MCRF_S3_CONFIG_PY
systemctl daemon-reload
systemctl enable --now "@MCRF_S3_SERVICE.service"
sleep 2
systemctl is-active --quiet "@MCRF_S3_SERVICE.service"
# Only newly generated inputs in this operation's exact private directory.
test "@(pwd -P)" = "@MCRF_S3_STAGE"
case "@MCRF_S3_STAGE" in /tmp/mcrf-deploy-????????????????????????????????) ;; *) exit 1;; esac
rm -rf -- source toolchain go.tar.gz s3bypass
# Local service readiness is not remote-client traffic acceptance.
printf 'MCRF_RESULT|%s\n' "@MCRF_S3_RESULT"
""".trimIndent().replace('@','$')+"\n"
}
