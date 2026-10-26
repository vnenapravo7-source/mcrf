param([Parameter(Mandatory)][string]$Source)
$ErrorActionPreference='Stop'
$taskRoot=(Resolve-Path -LiteralPath $Source).Path
if($taskRoot.Replace([char]92,[char]47) -notmatch '^D:/MCRF/Builds/Android/p3-steer-source-[a-f0-9]{32}/source$' -or (Get-Item -LiteralPath $taskRoot).LinkType){throw 'Fresh owned P3 Steer source only; never accepted/work/native sources'}
$taskPath="$taskRoot/src/proto/proxy/pxsocks.c"
$taskText=[IO.File]::ReadAllText($taskPath).Replace("`r`n","`n")
$taskOld=@'
    if (got < 3) return 0;
    /* [RSV(2) FRAG(1) адрес SOCKS5 данные]; фрагментированные (FRAG != 0) не поддерживаем. */
    if (rx[2] != 0) return 0;
    size_t o = 3, al;
    if (rx[o] == 1) al = 7; else if (rx[o] == 4) al = 19;
    else if (rx[o] == 3) { if (got < o + 2) return 0; al = 2 + rx[o + 1] + 2; }
    else return 0;
    if (got < o + al) return 0;
    return emit(arg, rx + o + al, got - o - al);
'@
$taskNew=@'
    size_t offset;
    if (!mcrf_socks_udp_payload(rx, got, &offset)) return 0;
    return emit(arg, rx + offset, got - offset);
'@
$taskOld=$taskOld.Replace("`r`n","`n");$taskNew=$taskNew.Replace("`r`n","`n")
if(($taskText.Split([string[]]@($taskOld),[StringSplitOptions]::None).Count-1) -ne 1){throw 'Pinned Steer SOCKS parser source anchor changed'}
if(Test-Path -LiteralPath "$taskRoot/src/proto/proxy/mcrf_socks_udp.h"){throw 'SOCKS parser overlay already applied'}
$taskInclude='#include "stack.h"'
if(($taskText.Split([string[]]@($taskInclude),[StringSplitOptions]::None).Count-1) -ne 1){throw 'Steer SOCKS includes changed'}
$taskText=$taskText.Replace($taskInclude,$taskInclude+"`n#include `"mcrf_socks_udp.h`"`n#include `"mcrf_socks_local_udp.h`"").Replace($taskOld,$taskNew)
$taskAssociate=@'
                /* UDP ASSOCIATE: адрес/порт отправителя неизвестны — 0.0.0.0:0. */
                rc = socks5_request(fd, 3, 0, 0, &bnd);
                if (rc == 0) {
                    if(bnd.family==6){
'@
$taskBound=@'
                const int private_helper = strcmp(n->vn.host,"127.0.0.1")==0 && n->user[0] && n->pass[0];
                uint16_t local_port=0;
                int local_fd=-1;
                if(private_helper) {
                    local_fd=mcrf_socks_local_udp_open(&local_port);
                    if(local_fd<0)rc=TR_EIO;
                }
                if(rc==0)rc=socks5_request(fd,3,private_helper?htonl(INADDR_LOOPBACK):0,local_port,&bnd);
                if(private_helper) {
                    if(rc==0 && mcrf_socks_local_udp_relay(local_fd,bnd.family,bnd.ip,bnd.port)!=0)rc=PX_EPROTO;
                    if(rc==0)s->udpfd=local_fd;else if(local_fd>=0)close(local_fd);
                } else if (rc == 0) {
                    if(bnd.family==6){
'@
$taskAssociate=$taskAssociate.Replace("`r`n","`n");$taskBound=$taskBound.Replace("`r`n","`n")
if(($taskText.Split([string[]]@($taskAssociate),[StringSplitOptions]::None).Count-1) -ne 1){throw 'Steer UDP association anchor changed'}
$taskText=$taskText.Replace($taskAssociate,$taskBound)
[IO.File]::WriteAllText($taskPath,$taskText,[Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath "$PSScriptRoot/mcrf_socks_udp.h" -Destination "$taskRoot/src/proto/proxy/mcrf_socks_udp.h"
Copy-Item -LiteralPath "$PSScriptRoot/mcrf_socks_local_udp.h" -Destination "$taskRoot/src/proto/proxy/mcrf_socks_local_udp.h"
Copy-Item -LiteralPath "$PSScriptRoot/mcrf_socks_udp_test.c" -Destination "$taskRoot/mcrf_socks_udp_test.c"
Copy-Item -LiteralPath "$PSScriptRoot/mcrf_socks_local_udp_test.c" -Destination "$taskRoot/mcrf_socks_local_udp_test.c"
Copy-Item -LiteralPath "$PSScriptRoot/mcrf_socks_udp_wire_test.c" -Destination "$taskRoot/mcrf_socks_udp_wire_test.c"
Write-Output 'P3_STEER_SOCKS_UDP_SOURCE_APPLIED_NOT_COMPILED'
