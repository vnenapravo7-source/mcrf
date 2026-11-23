param([Parameter(Mandatory)][string]$Snapshot,[string]$RetainedEnabledStage,[switch]$ProtocolMatrix,
 [ValidateSet(10049,10050,10051,10052)][int]$ExpectedVersionCode=10049,[string]$ExpectedApkSha256,[string[]]$RetainedProtocolStages=@(),[switch]$IsolatedPhone,[switch]$Retain51ForDnsCorrection)
$ErrorActionPreference='Stop'
if($IsolatedPhone -and (!$ProtocolMatrix -or $ExpectedVersionCode -notin @(10051,10052))){throw 'Phone copy only for remaining composed cases'}
& "$PSScriptRoot/Assert-HumanBatchReady.ps1" -Operation build
function Hash([string]$Path){(Get-FileHash -LiteralPath $Path).Hash.ToLowerInvariant()}
function Save($Value,[string]$Path){[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 14),[Text.UTF8Encoding]::new($false))}
$taskSnapshot=(Resolve-Path $Snapshot).Path.Replace([char]92,[char]47)
if($taskSnapshot -cnotmatch '^D:/MCRF/Projects/MCRF/work/android-integration-[a-f0-9]{32}$'){throw 'Exact successful common snapshot required'}
$taskBuild=Get-Content "$taskSnapshot/build-result.json" -Raw|ConvertFrom-Json
if($taskBuild.exitCode -ne 0 -or !$taskBuild.sourceStable -or $taskBuild.sourceManifestSha256 -cne (Hash "$taskSnapshot/source-manifest.json")){throw 'Stable green common build required'}
$taskMapping="$taskSnapshot/ports/android/app/build/outputs/mapping/release/mapping.txt"
$taskApk="$taskSnapshot/ports/android/app/build/outputs/apk/release/app-universal-release.apk"
if($ExpectedVersionCode -eq 10049){
 if((Hash $taskApk) -cne '76156626489f2d827984eaee1fc807a064b21dd82f877ffd6da319497945b1c2'){throw 'Reviewed10049 APK required'}
}else{
 $taskCorrectionTasks=if($ExpectedVersionCode -eq 10051){':app:testDebugUnitTest|--tests|org.mcrf.client.PrimaryValidationDiagnosticTest|:app:assembleRelease|:engines:assembleSteerRelease'}else{':app:testDebugUnitTest|--tests|org.mcrf.client.SteerHelperPreparationTest|:app:assembleRelease'}
 if($ExpectedVersionCode -eq 10052){$taskCorrectionTasks=':app:testDebugUnitTest|--tests|org.mcrf.client.NativeRouteTunDnsTest|:app:assembleRelease'}
 if(!$ProtocolMatrix -or $ExpectedApkSha256 -cnotmatch '^[a-f0-9]{64}$' -or (Hash $taskApk) -cne $ExpectedApkSha256 -or
   ($taskBuild.task -join '|') -cne $taskCorrectionTasks){throw 'Exact successful correction and explicit APK pin required'}
}
$taskClasses=@{};$taskMethods=@{};$taskFields=@{};$taskOwner=''
foreach($line in Get-Content $taskMapping){
 if($line -cmatch '^(\S+) -> (\S+):$'){$taskOwner=$Matches[1];$taskClasses[$taskOwner]=$Matches[2];continue}
 if($line -cmatch '^\s+(?:[0-9]+:[0-9]+:)?([^ ]+) ([A-Za-z_][A-Za-z_0-9$]*)\(([^)]*)\).* -> (\S+)$'){
  $key="${taskOwner}::$($Matches[2])($($Matches[3]))";$taskMethods[$key]=@($taskMethods[$key]+@($Matches[4])|Where-Object {$_}|Sort-Object -Unique)
 }elseif($line -cmatch '^\s+[^ ]+ ([A-Za-z_][A-Za-z_0-9$]*) -> (\S+)$'){$taskFields["${taskOwner}::$($Matches[1])"]=$Matches[2]}
}
function Method([string]$Owner,[string]$Name,[string]$Parameters=''){$key="${Owner}::$Name($Parameters)";$value=@($taskMethods[$key]|Where-Object {$_});if($value.Count -ne 1){throw "Unique retained R8 method required: $key"};return $value[0]}
function Field([string]$Owner,[string]$Name){$value=$taskFields["${Owner}::$Name"];if(!$value){throw "Retained R8 field required: $Owner.$Name"};return $value}
$taskStage="D:/MCRF/Builds/Android/p3-steer-runtime-$([guid]::NewGuid().ToString('N'))"
New-Item -ItemType Directory -Path $taskStage,"$taskStage/classes","$taskStage/dex"|Out-Null
$taskTemplate="$PSScriptRoot/next-runtime/NextRuntimeInstrumentation.java"
$script:taskText=[IO.File]::ReadAllText($taskTemplate)
function Replace-Exact([string]$From,[string]$To,[int]$Count=1){if(($script:taskText.Split([string[]]@($From),[StringSplitOptions]::None).Count-1) -ne $Count){throw "Runtime template anchor changed: $From"};$script:taskText=$script:taskText.Replace($From,$To)}
$taskClassRemap=@{}
foreach($pair in @(@('w0.tk','org.mcrf.client.ProfileStore'),@('w0.ll','org.mcrf.client.RouteRules'),@('w0.en','org.mcrf.client.RoutingStore'),@('w0.b8','org.mcrf.client.ClientPrimarySelection'),@('w0.an','org.mcrf.client.RoutingPage'),@('y0.a','org.mcrf.steerprep.SteerMcrfSnapshotAdapter$Issue'))){
 $value=$taskClasses[$pair[1]];if(!$value){throw 'Mapped production class missing'};$taskClassRemap[$pair[0]]=$value
}
# One pass: a NEW alias can equal another OLD alias. Sequential replacement
# would silently turn currentService into status (u0 -> w0) in this build.
$script:taskText=[regex]::Replace($script:taskText,'"(?:w0\.tk|w0\.ll|w0\.en|w0\.b8|w0\.an|y0\.a)"',[Text.RegularExpressions.MatchEvaluator]{param($m)'"'+$taskClassRemap[$m.Value.Trim('"')]+'"'})
Replace-Exact 'invoke(store(name),"c",' ('invoke(store(name),"'+(Method 'org.mcrf.client.ProfileStore' 'read')+'",')
Replace-Exact 'invoke(store(name),"e",' ('invoke(store(name),"'+(Method 'org.mcrf.client.ProfileStore' 'write' 'java.lang.String')+'",')
Replace-Exact ('Class.forName("'+$taskClasses['org.mcrf.client.RouteRules']+'"),"c",') ('Class.forName("'+$taskClasses['org.mcrf.client.RouteRules']+'"),"'+(Method 'org.mcrf.client.RouteRules' 'defaults')+'",')
Replace-Exact 'invoke(routing,"b",' ('invoke(routing,"'+(Method 'org.mcrf.client.RoutingStore' 'load')+'",') 2
Replace-Exact 'invoke(routing,"e",' ('invoke(routing,"'+(Method 'org.mcrf.client.RoutingStore' 'snapshot')+'",') 2
Replace-Exact 'invoke(page,"g",' ('invoke(page,"'+(Method 'org.mcrf.client.RoutingPage' 'save' 'org.json.JSONObject')+'",')
Replace-Exact ('Class.forName("'+$taskClasses['org.mcrf.client.ClientPrimarySelection']+'"),"j",') ('Class.forName("'+$taskClasses['org.mcrf.client.ClientPrimarySelection']+'"),"'+(Method 'org.mcrf.client.ClientPrimarySelection' 'steerSnapshot' 'android.content.Context,org.json.JSONObject,boolean,java.lang.Boolean')+'",')
$taskFieldRemap=@{}
foreach($pair in @(@('s0','currentService'),@('W','generation'),@('o','core'),@('D','currentStart'),@('u0','status'),@('j0','healthUrl'))){$taskFieldRemap[$pair[0]]=Field 'org.mcrf.client.McrfVpnService' $pair[1]}
$script:taskText=[regex]::Replace($script:taskText,'"(?:s0|W|o|D|u0|j0)"',[Text.RegularExpressions.MatchEvaluator]{param($m)'"'+$taskFieldRemap[$m.Value.Trim('"')]+'"'})
Replace-Exact '==10048' ('=='+$ExpectedVersionCode);$script:taskText=$script:taskText.Replace('Exact10048',"Exact$ExpectedVersionCode").Replace('candidate10048',"candidate$ExpectedVersionCode")
# Only NEW Steer cases: never replay the accepted sing-box/spacing fixtures.
Replace-Exact 'context=getTargetContext();check(' 'check("true".equals(args.getString("steer"))&&!"true".equals(args.getString("spacingOnly"))&&!"true".equals(args.getString("steerContractOnly")),"Steer runtime checks only");context=getTargetContext();check('
Replace-Exact 'if(!steer){' 'if(steer){'
Replace-Exact 'Context context;Activity activity;Bundle args;' 'Context context;Activity activity;Bundle args;boolean ownsFixture=false;'
Replace-Exact 'check(service()==null,"Do not interrupt an active session");' @'
check(service()==null,"Do not interrupt an active session");
            if("true".equals(args.getString("physicalOnly"))){
                android.net.ConnectivityManager cm=context.getSystemService(android.net.ConnectivityManager.class);
                android.net.Network physical=cm.getActiveNetwork();
                android.net.NetworkCapabilities caps=cm.getNetworkCapabilities(physical);
                check(physical!=null&&caps!=null&&!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN),"Physical baseline needs a non-VPN network");
                result.putString("physicalDns",java.util.Arrays.toString(physical.getAllByName("www.gstatic.com")));
                java.net.HttpURLConnection connection=(java.net.HttpURLConnection)physical.openConnection(new URL("https://www.gstatic.com/generate_204"));
                connection.setConnectTimeout(3500);connection.setReadTimeout(3500);connection.setUseCaches(false);
                try{int code=connection.getResponseCode();result.putInt("physicalHttpsStatus",code);check(code==204,"Physical HTTPS baseline expected204");}finally{connection.disconnect();}
                result.putBoolean("passed",true);result.putBoolean("physicalOnly",true);return;
            }
            ownsFixture=true;
'@
Replace-Exact 'No VK, accounts or external servers.' 'No VK or accounts. Optional physicalOnly performs one ordinary verified-TLS gstatic connectivity GET; runtime fixture does not forward external traffic.'
Replace-Exact 'if(context!=null&&service()!=null)' 'if(ownsFixture&&context!=null&&service()!=null)'
Replace-Exact 'if(context!=null)context.getSharedPreferences' 'if(ownsFixture&&context!=null)context.getSharedPreferences'
Replace-Exact 'boolean ownsFixture=false;' 'boolean ownsFixture=false;P3SteerFixtureNetwork fixtureNetwork;'
Replace-Exact 'ownsFixture=true;' 'fixtureNetwork=new P3SteerFixtureNetwork(context.getSystemService(android.net.ConnectivityManager.class).getActiveNetwork());ownsFixture=true;'
Replace-Exact 'if(in.readUnsignedByte()!=5||in.readUnsignedByte()!=1)return;in.readUnsignedByte();int type=in.readUnsignedByte();String host;' 'if(in.readUnsignedByte()!=5)return;int command=in.readUnsignedByte();in.readUnsignedByte();int type=in.readUnsignedByte();String host;'
Replace-Exact 'else return;in.readUnsignedShort();' 'else return;int destinationPort=in.readUnsignedShort();if(command==3){fixtureNetwork.associate(peer,in,out,proxyHost);return;}if(command!=1)return;if(fixtureNetwork.relay(host,destinationPort,peer,in,out,fail))return;'
Replace-Exact 'routes.put("defaultTarget","block");' 'routes.put("defaultTarget","block");routes.put("globalDns",new JSONObject().put("provider","Свой").put("protocol","UDP").put("custom","198.51.100.53"));'
Replace-Exact 'setHealth();count=requests.get();before=generation();' 'count=fixtureNetwork.tlsConnects.get();before=generation();'
Replace-Exact 'while(requests.get()==count&&SystemClock.elapsedRealtime()<end)' 'while(fixtureNetwork.tlsConnects.get()==count&&SystemClock.elapsedRealtime()<end)'
Replace-Exact 'check(requests.get()>count&&generation()==before,' 'check(fixtureNetwork.tlsConnects.get()>count&&generation()==before,'
Replace-Exact 'try{if(socks!=null)socks.close();}' 'try{if(fixtureNetwork!=null){result.putInt("healthTlsConnects",fixtureNetwork.tlsConnects.get());result.putInt("ownedDnsQueries",fixtureNetwork.dnsQueries.get());fixtureNetwork.close();}if(socks!=null)socks.close();}'
Replace-Exact 'Intent launch=context.getPackageManager().getLaunchIntentForPackage(context.getPackageName());' 'Intent launch=new Intent(Intent.ACTION_MAIN).setClassName(context,"org.mcrf.client.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);'
Replace-Exact 'write("routing.enc",routes.toString());' 'routes.getJSONArray("rules").put(new JSONObject().put("id","owned-health").put("name","Health only").put("mode","addresses").put("text",String.join("\n",fixtureNetwork.healthAddresses)).put("target","proxy").put("enabled",true));write("routing.enc",routes.toString());'
Replace-Exact 'fail.set(true);before=generation();wake();waitReady(before);fail.set(false);' 'fail.set(true);before=generation();wake();long faultDeadline=SystemClock.elapsedRealtime()+18000;while(generation()==before&&SystemClock.elapsedRealtime()<faultDeadline)Thread.sleep(50);check(generation()>before,"Dead wake did not retire old generation");fail.set(false);waitReady(before);'
$taskRetained=$null
if($RetainedEnabledStage){
 $taskPrior=(Resolve-Path $RetainedEnabledStage).Path.Replace([char]92,[char]47)
 if($taskPrior -cnotmatch '^D:/MCRF/Builds/Android/p3-steer-runtime-[a-f0-9]{32}$'){throw 'Exact prior runtime stage required'}
 $taskPriorReceipt=Get-Content "$taskPrior/receipt.json" -Raw|ConvertFrom-Json
 $taskPriorLog=[IO.File]::ReadAllText("$taskPrior/instrument.txt")
 if($taskPriorReceipt.mainApkSha256 -cne (Hash $taskApk) -or ([regex]::Matches($taskPriorLog,'phase=ENABLED_ROUTE_TRAFFIC_PASS')).Count -ne 1){throw 'Same-production retained enabled phase required'}
 $taskRetained=@{stage=$taskPrior;logSha256=Hash "$taskPrior/instrument.txt";phase='ENABLED_ROUTE_TRAFFIC_PASS'}
 Replace-Exact 'check(traffic(),"Enabled route must pass actual TUN traffic to owned SOCKS responder");checks++;status("ENABLED_ROUTE_TRAFFIC_PASS");' 'status("ENABLED_ROUTE_TRAFFIC_RETAINED_NOT_REPLAYED");'
}
[IO.File]::WriteAllText("$taskStage/NextRuntimeInstrumentation.java",$script:taskText,[Text.UTF8Encoding]::new($false))
Copy-Item -LiteralPath "$PSScriptRoot/next-runtime/AndroidManifest.xml" -Destination "$taskStage/AndroidManifest.xml"
Copy-Item -LiteralPath "$PSScriptRoot/next-runtime/P3SteerFixtureNetwork.java" -Destination "$taskStage/P3SteerFixtureNetwork.java"
$taskJavaSources=@("$taskStage/NextRuntimeInstrumentation.java","$taskStage/P3SteerFixtureNetwork.java")
$taskProtocolProofs=@();$taskProtocolPasses=[Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
$taskScopedRetention=$null
if($Retain51ForDnsCorrection){
 if(!$IsolatedPhone -or $ExpectedVersionCode -ne 10052){throw 'Scoped retained51 applies only to isolated52 dispatcher correction'}
 $taskOldRoot='D:/MCRF/Projects/MCRF/work/android-integration-9b74382bcc264078878aa0745a4c286c'
 $taskOldRows=@(Get-Content "$taskOldRoot/source-manifest.json" -Raw|ConvertFrom-Json)
 $taskNewRows=@(Get-Content "$taskSnapshot/source-manifest.json" -Raw|ConvertFrom-Json)
 $taskOldMap=@{};$taskNewMap=@{}
 foreach($row in $taskOldRows){if($row.path -match '^ports/android/app/src/main/|^work/steer-android-port/android-vpn/(module-contract|integration-helper)/'){$taskOldMap[$row.path]=$row.sha256}}
 foreach($row in $taskNewRows){if($row.path -match '^ports/android/app/src/main/|^work/steer-android-port/android-vpn/(module-contract|integration-helper)/'){$taskNewMap[$row.path]=$row.sha256}}
 $taskDeltas=@(@($taskOldMap.Keys)+@($taskNewMap.Keys)|Sort-Object -Unique|Where-Object {$taskOldMap[$_] -cne $taskNewMap[$_]})
 $taskExpected=@('ports/android/app/src/main/kotlin/org/mcrf/client/McrfVpnService.kt','ports/android/app/src/main/kotlin/org/mcrf/client/NativeRouteTunDns.kt')
 if(($taskDeltas -join '|') -cne (($taskExpected|Sort-Object) -join '|')){throw 'Unexpected production source delta: cannot retain closed51 protocol cases'}
 $taskService='ports/android/app/src/main/kotlin/org/mcrf/client/McrfVpnService.kt'
 $taskOldService=[IO.File]::ReadAllText("$taskOldRoot/$taskService")
 $taskNewService=[IO.File]::ReadAllText("$taskSnapshot/$taskService")
 if($taskOldService.Replace('prepared=configured.config;localProxyPort=configured.localPort?:0','prepared=if(nativePreparation!=null)NativeRouteTunDns.apply(configured.config)else configured.config;localProxyPort=configured.localPort?:0') -cne $taskNewService){throw 'Only dispatcher TUN DNS insertion may change the service'}
 $taskScopedRetention=@{sourceVersionCode=10051;acceptedOnNewVersion=$false;productionDeltas=$taskDeltas;oldManifestSha256=Hash "$taskOldRoot/source-manifest.json";newManifestSha256=Hash "$taskSnapshot/source-manifest.json";affectedCaseReexecuted='independent-route';closedCasesReplayed=$false}
}
foreach($taskPriorStage in $RetainedProtocolStages){
 $taskPriorRoot=(Resolve-Path $taskPriorStage).Path.Replace([char]92,[char]47)
 if(!$ProtocolMatrix -or $taskPriorRoot -cnotmatch '^D:/MCRF/Builds/Android/p3-steer-runtime-[a-f0-9]{32}$'){throw 'Exact composed runtime stage required'}
 $taskPrior=Get-Content "$taskPriorRoot/receipt.json" -Raw|ConvertFrom-Json
 $taskSameMain=$taskPrior.mainApkSha256 -ceq (Hash $taskApk)
 $taskScoped51=$Retain51ForDnsCorrection -and $ExpectedVersionCode -eq 10052 -and $taskPrior.mainApkSha256 -ceq '0e28d3338902a83b29b3401fd7ca55751a5fb061d3878d8506343bcff8a053a4'
 if($taskPrior.mode -cne 'composed-original-protocol-app-matrix' -or (!$taskSameMain -and !$taskScoped51) -or $taskPrior.apkSha256 -cne (Hash $taskPrior.apk)){throw 'Retained proof requires same production or explicit51 dispatcher-only correction scope'}
 $taskPriorLog="$taskPriorRoot/protocol-instrument.txt"
 if(Test-Path "$taskPriorRoot/protocol-ecdsa-instrument.txt"){$taskPriorLog="$taskPriorRoot/protocol-ecdsa-instrument.txt"}
 $taskPriorRaw=[IO.File]::ReadAllText($taskPriorLog);$taskThese=@()
 foreach($match in [regex]::Matches($taskPriorRaw,'(?m)^INSTRUMENTATION_STATUS: phase=(COMPOSED_APP_[A-Z_]+_PASS)\s*$')){
  $phase=$match.Groups[1].Value
  if($phase -cnotmatch '^COMPOSED_APP_(?:TUIC_MANUAL_TUN|ANYTLS_MANUAL_TUN|NAIVE_MANUAL_TUN|SSH_MANUAL_TUN|AUTOMATIC_HELPER_TUN|INDEPENDENT_SSH_ROUTE_WITH_TUIC_MAIN)_PASS$' -or !$taskProtocolPasses.Add($phase)){throw 'Unknown or duplicate retained protocol phase'}
  $taskThese+=,$phase
 }
 if(!$taskThese.Count){throw 'No real completed protocol phase to retain'}
 $taskProtocolProofs+=@{stage=$taskPriorRoot;log=$taskPriorLog;logSha256=Hash $taskPriorLog;cases=$taskThese;helperApkSha256=$taskPrior.apkSha256}
}
if($ProtocolMatrix){
 $taskParent=[IO.File]::ReadAllText("$taskStage/NextRuntimeInstrumentation.java").Replace('public final class NextRuntimeInstrumentation','public class NextRuntimeInstrumentation')
 [IO.File]::WriteAllText("$taskStage/NextRuntimeInstrumentation.java",$taskParent,[Text.UTF8Encoding]::new($false))
 $taskMatrix=[IO.File]::ReadAllText("$PSScriptRoot/protocol-app-fixture/P3ProtocolAppInstrumentation.java")
 $taskMatrix=$taskMatrix.Replace('==10049',"==$ExpectedVersionCode").Replace('Exact10049',"Exact$ExpectedVersionCode")
 $taskMatrix=$taskMatrix.Replace('@@RETAINED_PROTOCOL_PHASES@@',(@($taskProtocolPasses|Sort-Object) -join ','))
 $taskMatrix=$taskMatrix.Replace('@@RULE_CLASS@@',$taskClasses['org.mcrf.client.RouteRules']).Replace('@@DEFAULTS_METHOD@@',(Method 'org.mcrf.client.RouteRules' 'defaults'))
 $taskMatrix=$taskMatrix.Replace('@@SELECTION_CLASS@@',$taskClasses['org.mcrf.client.ClientPrimarySelection']).Replace('@@SNAPSHOT_METHOD@@',(Method 'org.mcrf.client.ClientPrimarySelection' 'steerSnapshot' 'android.content.Context,org.json.JSONObject,boolean,java.lang.Boolean'))
 foreach($pair in @(@('@@CORE_FIELD@@','core'),@('@@START_FIELD@@','currentStart'),@('@@STATUS_FIELD@@','status'),@('@@SELECTED_FIELD@@','selectedId'))){$taskMatrix=$taskMatrix.Replace($pair[0],(Field 'org.mcrf.client.McrfVpnService' $pair[1]))}
 if($taskMatrix.Contains('@@')){throw 'Unmapped composed test symbol'}
 if($IsolatedPhone){
  $taskMatrix=$taskMatrix.Replace('check(Build.HARDWARE.contains("ranchu")||Build.FINGERPRINT.contains("generic"),"Owned emulator only");','check("org.mcrf.p3test".equals(getTargetContext().getPackageName()),"Separate test UID required; main is forbidden");')
  $taskMatrix=$taskMatrix.Replace('P3ProtocolFixtureNetwork protocolNetwork;ContentProviderClient servers;','@Override boolean traffic(){try(ContentProviderClient probe=context.getContentResolver().acquireContentProviderClient(Uri.parse("content://org.mcrf.p3traffic"))){check(probe!=null,"Separate traffic UID missing");return probe.call("traffic",null,null).getBoolean("passed");}catch(Exception error){return false;}}P3ProtocolFixtureNetwork protocolNetwork;ContentProviderClient servers;')
  $taskMatrix=$taskMatrix.Replace('P3ProtocolFixtureNetwork protocolNetwork;ContentProviderClient servers;','P3ProtocolFixtureNetwork protocolNetwork;ContentProviderClient servers;@Override public void callActivityOnResume(Activity value){activity=value;super.callActivityOnResume(value);}')
  $taskMatrix=$taskMatrix.Replace('Bundle result=new Bundle();int checks=0;try{','Bundle result=new Bundle();int checks=0;Thread owner=Thread.currentThread();Thread watchdog=new Thread(()->{try{Thread.sleep(25000);for(StackTraceElement frame:owner.getStackTrace())android.util.Log.i("McrfP3Fixture",frame.toString());}catch(InterruptedException ignored){}},"owned-fixture-watchdog");watchdog.setDaemon(true);watchdog.start();try{')
  $taskMatrix=$taskMatrix.Replace('activity=startActivitySync(new Intent(Intent.ACTION_MAIN).setClassName(context,"org.mcrf.client.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));waitForIdleSync();','context.startActivity(new Intent(Intent.ACTION_MAIN).setClassName(context,"org.mcrf.client.MainActivity").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));long activityUntil=SystemClock.elapsedRealtime()+15000;while(activity==null&&SystemClock.elapsedRealtime()<activityUntil)Thread.sleep(50);check(activity!=null,"Isolated activity did not resume");status("ISOLATED_COPY_ACTIVITY_RESUMED");')
  $taskMatrix=$taskMatrix.Replace('check(VpnService.prepare(context)==null,"Normal VPN consent must already exist on owned emulator");','Intent consent=VpnService.prepare(context);if(consent!=null){context.startActivity(consent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));status("ISOLATED_COPY_NORMAL_VPN_CONSENT_PENDING");long until=SystemClock.elapsedRealtime()+90000;while(VpnService.prepare(context)!=null&&SystemClock.elapsedRealtime()<until)Thread.sleep(200);}check(VpnService.prepare(context)==null,"Normal isolated-copy VPN consent required");')
  $taskMatrix=$taskMatrix.Replace('for(String name:new String[]{"profile.enc"', 'for(Network network:context.getSystemService(ConnectivityManager.class).getAllNetworks()){NetworkCapabilities caps=context.getSystemService(ConnectivityManager.class).getNetworkCapabilities(network);check(caps==null||!caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN),"Another VPN is active; leave it untouched");}for(String name:new String[]{"profile.enc"')
  $taskMatrix=$taskMatrix.Replace('context.startActivity(consent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));','runOnMainSync(()->activity.startActivityForResult(consent,901));')
  $taskMatrix=$taskMatrix.Replace('}catch(Exception ignored){}}','}catch(Exception error){Bundle diagnostic=new Bundle();diagnostic.putString("ownedRelayFailure",error.getClass().getSimpleName()+": "+error.getMessage());sendStatus(0,diagnostic);}}')
  $taskMatrix=$taskMatrix.Replace('int port=in.readUnsignedShort();if(command==3)', 'int port=in.readUnsignedShort();Bundle dial=new Bundle();dial.putString("ownedRelayRequest",authenticated+":"+command+":"+host+":"+port);sendStatus(0,dial);if(command==3)')
  $taskMatrix=$taskMatrix.Replace('ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);Network physical=', 'ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);cm.registerNetworkCallback(new NetworkRequest.Builder().clearCapabilities().addTransportType(NetworkCapabilities.TRANSPORT_VPN).build(),new ConnectivityManager.NetworkCallback(){@Override public void onAvailable(Network n){Bundle diagnostic=new Bundle();diagnostic.putString("vpnNetwork",n.toString());diagnostic.putInt("actualUid",android.os.Process.myUid());diagnostic.putString("capabilities",String.valueOf(cm.getNetworkCapabilities(n)));try(Socket socket=new Socket()){n.bindSocket(socket);diagnostic.putString("testUidBind","accepted");}catch(Exception e){diagnostic.putString("testUidBind",e.getMessage());}sendStatus(0,diagnostic);}});Network physical=')
  $taskDiagnostic='new Thread(()->{String previous="";for(int i=0;i<60;i++){try{Object current=service();if(current==null)return;Object helper=field(current,"'+(Field 'org.mcrf.client.McrfVpnService' 'compactSession')+'");if(helper!=null){Object owner=field(helper,"'+(Field 'org.mcrf.client.CompactProtocolSession' 'owner')+'");Object detail=invoke(owner,"'+(Method 'org.mcrf.client.OptionalCoreProcess' 'diagnostic' 'java.lang.String')+'",new Class<?>[]{String.class},"owned fixture");if(detail!=null&&!detail.equals(previous)){previous=detail.toString();Bundle out=new Bundle();out.putString("ownedHelperDiagnostic",previous);sendStatus(0,out);}}Thread.sleep(50);}catch(Exception ignored){return;}}},"owned-helper-diagnostic").start();'
  $taskMatrix=$taskMatrix.Replace('sendStatus(0,diagnostic);}});Network physical=',('sendStatus(0,diagnostic);'+$taskDiagnostic+'}});Network physical='))
 }
 [IO.File]::WriteAllText("$taskStage/P3ProtocolAppInstrumentation.java",$taskMatrix,[Text.UTF8Encoding]::new($false))
 Copy-Item "$PSScriptRoot/protocol-app-fixture/P3ProtocolFixtureNetwork.java" "$taskStage/P3ProtocolFixtureNetwork.java"
 $taskJavaSources+=@("$taskStage/P3ProtocolAppInstrumentation.java","$taskStage/P3ProtocolFixtureNetwork.java")
 $taskManifest=[IO.File]::ReadAllText("$taskStage/AndroidManifest.xml").Replace('</manifest>','<queries><package android:name="org.mcrf.ownedprotocols" /></queries><instrumentation android:name="org.mcrf.runtime.P3ProtocolAppInstrumentation" android:targetPackage="org.mcrf.client" android:functionalTest="true" /></manifest>')
 [IO.File]::WriteAllText("$taskStage/AndroidManifest.xml",$taskManifest,[Text.UTF8Encoding]::new($false))
}
if($IsolatedPhone){
 Copy-Item "$PSScriptRoot/protocol-app-fixture/P3OwnedTrafficProvider.java" "$taskStage/P3OwnedTrafficProvider.java"
 $taskJavaSources+="$taskStage/P3OwnedTrafficProvider.java"
 $taskManifest=[IO.File]::ReadAllText("$taskStage/AndroidManifest.xml").Replace('android:targetPackage="org.mcrf.client"','android:targetPackage="org.mcrf.p3test"')
 $taskManifest=[regex]::Replace($taskManifest,'<application ([^>]+)/>','<application $1><provider android:name="org.mcrf.runtime.P3OwnedTrafficProvider" android:authorities="org.mcrf.p3traffic" android:exported="true" /></application>')
 $taskManifest=$taskManifest.Replace('<application ','<uses-permission android:name="android.permission.INTERNET"/><application ')
 [IO.File]::WriteAllText("$taskStage/AndroidManifest.xml",$taskManifest,[Text.UTF8Encoding]::new($false))
}
$taskReceipt=@{state='COMPILING_TEST_ONLY';snapshot=$taskSnapshot;mainApkSha256=Hash $taskApk;mappingSha256=Hash $taskMapping;templateSha256=Hash $taskTemplate;sourceSha256=Hash "$taskStage/NextRuntimeInstrumentation.java";fixtureNetworkSha256=Hash "$taskStage/P3SteerFixtureNetwork.java";productionRebuilt=$false;phoneTouched=$false;steerOnly=$true;startupAccessField=Field 'org.mcrf.client.MainActivity' 'startupAccessStarted'}
$taskReceipt.retainedEnabled=$taskRetained
$taskReceipt.mode=$(if($ProtocolMatrix){'composed-original-protocol-app-matrix'}else{'steer-runtime'});$taskReceipt.steerOnly=!$ProtocolMatrix
$taskReceipt.expectedVersionCode=$ExpectedVersionCode
$taskReceipt.retainedProtocolCases=$taskProtocolProofs
$taskReceipt.scoped51Retention=$taskScopedRetention
$taskReceipt.isolatedPhone=[bool]$IsolatedPhone
$taskSdk='C:/Users/dieonfire/AppData/Local/Android/Sdk';$taskJava='C:/Program Files/Java/jdk-17/bin';$taskTools="$taskSdk/build-tools/36.0.0";$taskAndroid="$taskSdk/platforms/android-34/android.jar"
function Run([string]$Name,[string]$Exe,[string[]]$Arguments){& $Exe @Arguments *> "$taskStage/$Name.log";if($LASTEXITCODE){throw "$Name failed; preserve test-only log"}}
try{
 Run 'javac' "$taskJava/javac.exe" (@('--release','8','-encoding','UTF-8','-classpath',$taskAndroid,'-d',"$taskStage/classes")+$taskJavaSources)
 Run 'jar' "$taskJava/jar.exe" @('cf',"$taskStage/classes.jar",'-C',"$taskStage/classes",'.')
 Run 'd8' "$taskTools/d8.bat" @('--release','--min-api','29','--lib',$taskAndroid,'--output',"$taskStage/dex","$taskStage/classes.jar")
 Run 'aapt' "$taskTools/aapt.exe" @('package','-f','-M',"$taskStage/AndroidManifest.xml",'-I',$taskAndroid,'-F',"$taskStage/unsigned.apk")
 Push-Location "$taskStage/dex";try{Run 'aapt-add' "$taskTools/aapt.exe" @('add',"$taskStage/unsigned.apk",'classes.dex')}finally{Pop-Location}
 Run 'zipalign' "$taskTools/zipalign.exe" @('-f','4',"$taskStage/unsigned.apk","$taskStage/aligned.apk")
 Run 'sign' "$taskTools/apksigner.bat" @('sign','--ks','C:/Users/dieonfire/.android/debug.keystore','--ks-key-alias','androiddebugkey','--ks-pass','pass:android','--key-pass','pass:android','--out',"$taskStage/probe.apk","$taskStage/aligned.apk")
 Run 'signature' "$taskTools/apksigner.bat" @('verify','--print-certs',"$taskStage/probe.apk")
 if([IO.File]::ReadAllText("$taskStage/signature.log") -notmatch 'certificate SHA-256 digest: fed3be06ab752dd71f6223290e02a7771ff5559b67a43dce38ace8e56ab150b0'){throw 'Trusted same-signature helper required'}
 $taskReceipt.state='TEST_ONLY_COMPILED_NOT_RUN';$taskReceipt.apk="$taskStage/probe.apk";$taskReceipt.apkSha256=Hash "$taskStage/probe.apk"
}catch{$taskReceipt.state='FAILED_TEST_ONLY_PRESERVED';$taskReceipt.failure=$_.Exception.Message;throw}
finally{Save $taskReceipt "$taskStage/receipt.json";Write-Output "P3_STEER_RUNTIME_PROBE=$taskStage"}
