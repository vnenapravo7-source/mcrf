param([Parameter(Mandatory)][string]$PreparedSource)
$ErrorActionPreference='Stop'
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 10),[Text.UTF8Encoding]::new($false))}
$taskParent=(Resolve-Path $PreparedSource).Path.Replace([char]92,[char]47)
if($taskParent -cnotmatch '^D:/MCRF/Builds/Android/p3-steer-source-[a-f0-9]{32}$'){throw 'Exact owned prepared source required'}
$taskPrior=Get-Content "$taskParent/receipt.json" -Raw|ConvertFrom-Json -AsHashtable
if($taskPrior.newVersion -ne 15 -or $taskPrior.manifestSha256 -cne (Hash "$taskParent/SOURCE_MANIFEST.json")){throw 'Frozen Steer15 source required'}
$taskRoot="D:/MCRF/Builds/Android/p3-steer-source-$([guid]::NewGuid().ToString('N'))";New-Item -ItemType Directory -Path $taskRoot|Out-Null
foreach($row in Get-Content "$taskParent/SOURCE_MANIFEST.json" -Raw|ConvertFrom-Json){
 if([IO.Path]::IsPathRooted($row.path) -or '..' -in ($row.path -split '[/\\]')){throw 'Unsafe source path'}
 $from=Get-Item -LiteralPath "$taskParent/$($row.path)";if($from.LinkType -or (Hash $from.FullName) -cne $row.sha256){throw 'Original source changed'}
 $to="$taskRoot/$($row.path)";New-Item -ItemType Directory -Path (Split-Path $to) -Force|Out-Null;Copy-Item $from.FullName $to
}
$taskPath="$taskRoot/source/src/proto/proxy/pxsub.c";$taskText=[IO.File]::ReadAllText($taskPath)
$taskOld=@'
    if (sl_host_leads_nowhere(n->vn.host)) {
        snprintf(n->skip_reason, sizeof(n->skip_reason), "%.20s: отвечать некому", n->vn.host);
        return 1;
    }
'@
$taskText=$taskText.Replace("`r`n","`n");$taskStart=$taskText.IndexOf('static int socks_parse(');$taskEnd=$taskText.IndexOf('/* ---- http CONNECT',$taskStart)
if($taskStart -lt 0 -or $taskEnd -le $taskStart){throw 'Exact SOCKS parser required'}
$taskPart=$taskText.Substring($taskStart,$taskEnd-$taskStart)
if(($taskPart.Split([string[]]@($taskOld),[StringSplitOptions]::None).Count-1) -ne 1){throw 'Exact SOCKS-only host refusal anchor required'}
$taskPart=$taskPart.Replace($taskOld,$taskOld.Replace('if (sl_host_leads_nowhere(n->vn.host))','if (sl_host_leads_nowhere(n->vn.host) && !mcrf_private_socks_profile(n->vn.host,n->vn.port,ver,n->user,n->pass))'))
$taskText='#include "mcrf_private_socks_profile.h"'+"`n"+$taskText.Substring(0,$taskStart)+$taskPart+$taskText.Substring($taskEnd)
[IO.File]::WriteAllText($taskPath,$taskText,[Text.UTF8Encoding]::new($false))
Copy-Item "$PSScriptRoot/mcrf_private_socks_profile.h" "$taskRoot/source/src/proto/proxy/mcrf_private_socks_profile.h"
Copy-Item "$PSScriptRoot/mcrf_private_socks_profile_test.c" "$taskRoot/source/mcrf_private_socks_profile_test.c"
$taskCmake="$taskRoot/source/android-vpn/core/src/main/cpp/CMakeLists.txt"
$taskAdd=@'

add_executable(mcrf_private_socks_profile_test EXCLUDE_FROM_ALL "${STEER}/mcrf_private_socks_profile_test.c")
target_include_directories(mcrf_private_socks_profile_test PRIVATE "${STEER}/src/proto/proxy")
target_compile_options(mcrf_private_socks_profile_test PRIVATE -UNDEBUG -O2)
target_link_options(mcrf_private_socks_profile_test PRIVATE -Wl,-z,max-page-size=16384)
'@
[IO.File]::WriteAllText($taskCmake,[IO.File]::ReadAllText($taskCmake)+$taskAdd,[Text.UTF8Encoding]::new($false))
# Paths in the retained TLS manifest must point to this new exact copy.
$taskTls=[IO.File]::ReadAllText("$taskRoot/tls.json").Replace($taskParent,$taskRoot).Replace($taskParent.Replace('/','\\'),$taskRoot.Replace('/','\\'))
[IO.File]::WriteAllText("$taskRoot/tls.json",$taskTls,[Text.UTF8Encoding]::new($false))
$taskRows=@(Get-ChildItem $taskRoot -Recurse -File|ForEach-Object {@{path=[IO.Path]::GetRelativePath($taskRoot,$_.FullName).Replace([char]92,[char]47);sha256=Hash $_.FullName;bytes=$_.Length}}|Sort-Object path)
Save $taskRows "$taskRoot/SOURCE_MANIFEST.json"
$taskPrior.newVersion=16;$taskPrior.source="$taskRoot/source";$taskPrior.parentPreparedSource=$taskParent;$taskPrior.privateHelperProfile=1;$taskPrior.manifestSha256=Hash "$taskRoot/SOURCE_MANIFEST.json";$taskPrior.compiled=$false;$taskPrior.executed=$false
Save $taskPrior "$taskRoot/receipt.json";Write-Output "P3_STEER_PRIVATE_SOURCE=$taskRoot"
