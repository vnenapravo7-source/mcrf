using System;
using System.Collections.Generic;
using System.Collections;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Security.Cryptography;
using System.Net;
using System.Net.Sockets;
using System.Net.NetworkInformation;
using System.Text;
using System.Threading.Tasks;
using System.Threading;
using System.Web;
using System.Web.Script.Serialization;

namespace SplifyWin {
  public static class DnsOptions {
    public static readonly string[] Providers={"Cloudflare","Google","Quad9","AdGuard","Яндекс","Comss.one","Свой","Системный"};
    public static string[] Protocols(string provider){if(provider=="Системный")return new[]{"Системный","UDP"};if(provider=="Cloudflare")return new[]{"UDP","DoT","DoH","DoH3"};if(provider=="Google")return new[]{"UDP","DoT","DoH"};if(provider=="Quad9"||provider=="Comss.one"||provider=="Свой")return new[]{"UDP","DoT","DoH","DoH3","DoQ"};if(provider=="AdGuard")return new[]{"UDP","DoT","DoH","DoQ"};return new[]{"UDP","DoT","DoH"};}
    public static Dictionary<string,object> Build(ClientState state){
      var provider=String.IsNullOrEmpty(state.DnsProvider)?"Cloudflare":state.DnsProvider;
      var protocol=String.IsNullOrEmpty(state.DnsProtocol)?"DoH":state.DnsProtocol;
      if(!Providers.Contains(provider)||!Protocols(provider).Contains(protocol))throw new FormatException("Этот DNS-провайдер не поддерживает "+protocol+". Выберите другой протокол.");
      if(provider=="Системный")return new Dictionary<string,object>{{"type","local"},{"tag","chosen"}};
      string host="",server="";
      switch(provider){case "Cloudflare":host="cloudflare-dns.com";server="1.1.1.1";break;case "Google":host="dns.google";server="8.8.8.8";break;case "Quad9":host="dns.quad9.net";server="9.9.9.9";break;case "AdGuard":host="dns.adguard-dns.com";server="94.140.14.14";break;case "Яндекс":host="common.dot.dns.yandex.net";server="77.88.8.8";break;case "Comss.one":host="dns.comss.one";server="195.133.25.16";break;}
      string path="/dns-query";int port=protocol=="UDP"?53:protocol=="DoT"||protocol=="DoQ"?853:443;
      if(provider=="Свой"){
        var raw=(state.DnsCustom??"").Trim();Uri uri;
        if(raw.IndexOf("://",StringComparison.Ordinal)<0){IPAddress bare;if(!IPAddress.TryParse(raw,out bare)&&Uri.CheckHostName(raw)==UriHostNameType.Unknown)throw new FormatException("Укажите IP или имя DNS-сервера.");raw=(protocol=="UDP"?"udp":protocol=="DoT"?"tls":protocol=="DoQ"?"quic":protocol=="DoH3"?"h3":"https")+"://"+(IPAddress.TryParse(raw,out bare)&&bare.AddressFamily==AddressFamily.InterNetworkV6?"["+raw+"]":raw);}
        if(!Uri.TryCreate(raw,UriKind.Absolute,out uri)||String.IsNullOrEmpty(uri.Host)||uri.UserInfo.Length>0||uri.Fragment.Length>0)throw new FormatException("Укажите адрес DNS с протоколом: udp://, tls://, https://, h3:// или quic://.");
        var expected=protocol=="UDP"?"udp":protocol=="DoT"?"tls":protocol=="DoQ"?"quic":protocol=="DoH3"?"h3":"https";
        if(!uri.Scheme.Equals(expected,StringComparison.OrdinalIgnoreCase))throw new FormatException("Для "+protocol+" адрес должен начинаться с "+expected+"://");
        host=uri.Host.Trim('[',']');IPAddress normalized;if(IPAddress.TryParse(host,out normalized))host=normalized.ToString();server=host;if(!uri.IsDefaultPort)port=uri.Port;
        if(protocol=="DoH"||protocol=="DoH3")path=uri.AbsolutePath=="/"?"/dns-query":uri.AbsolutePath;
      }
      var type=protocol=="DoT"?"tls":protocol=="DoQ"?"quic":protocol=="DoH3"?"h3":protocol=="DoH"?"https":"udp";
      var result=new Dictionary<string,object>{{"type",type},{"tag","chosen"},{"server",server},{"server_port",port}};
      if(protocol=="DoH"||protocol=="DoH3")result["path"]=path;
      if(protocol!="UDP")result["tls"]=new Dictionary<string,object>{{"enabled",true},{"server_name",host}};
      IPAddress parsedIp;if(provider=="Свой"&&!IPAddress.TryParse(server,out parsedIp))result["domain_resolver"]="bootstrap";
      return result;
    }
  }
  public sealed class NetworkCore {
    readonly StateStore store; readonly JavaScriptSerializer json=new JavaScriptSerializer{MaxJsonLength=Int32.MaxValue};
    readonly string trafficApiSecret=Guid.NewGuid().ToString("N");readonly int trafficApiPort;
    readonly Dictionary<string,string> activeNodes=new Dictionary<string,string>();string activeServerId="";
    public string ActiveServerId{get{return activeServerId;}}
    public string ActiveSelectionError{get;private set;}
    public void PollActiveServer(){try{if(!Running){activeServerId="";return;}if(activeNodes.Count==1){activeServerId=activeNodes.Values.First();return;}var request=(HttpWebRequest)WebRequest.Create("http://127.0.0.1:"+trafficApiPort+"/proxies/proxy");request.Proxy=null;request.Timeout=800;request.ReadWriteTimeout=800;request.Headers[HttpRequestHeader.Authorization]="Bearer "+trafficApiSecret;using(var response=request.GetResponse())using(var reader=new StreamReader(response.GetResponseStream())){var data=new JavaScriptSerializer().Deserialize<Dictionary<string,object>>(reader.ReadToEnd());object now;string id;if(data.TryGetValue("now",out now)&&activeNodes.TryGetValue(Convert.ToString(now),out id))activeServerId=id;ActiveSelectionError=null;}}catch(Exception ex){ActiveSelectionError=ex.Message;}}
    Process process;StreamWriter coreWriter;readonly object logSync=new object();
    readonly List<Process> sidecars=new List<Process>();
    readonly object startupLock=new object();volatile CancellationTokenSource startupCancellation;
    public bool Running {get{var current=process;if(current==null)return false;try{lock(sidecars)return !current.HasExited&&sidecars.All(x=>!x.HasExited);}catch(InvalidOperationException){return false;}}}
    public readonly OptionalEngines Engines;
    public NetworkCore(StateStore store){this.store=store;Engines=new OptionalEngines(store.Root);var listener=new TcpListener(IPAddress.Loopback,0);listener.Start();trafficApiPort=((IPEndPoint)listener.LocalEndpoint).Port;listener.Stop();EnsureBundledCore();}
    public string DataRoot{get{return store.Root;}}
    public string WarpScoutExecutable{get{return AppUpdates.ActiveFile(store.Root,"warp",Path.Combine(store.Root,"core","warpscout.exe"));}}
    public string TrafficApiSecret {get{return trafficApiSecret;}}
    public int TrafficApiPort {get{return trafficApiPort;}}
    void EnsureBundledCore(){var coreDir=Path.Combine(store.Root,"core");Directory.CreateDirectory(coreDir);var assembly=Assembly.GetExecutingAssembly();var stamp=Path.Combine(coreDir,"bundle-verified.txt");Func<string> signature=()=>assembly.ManifestModule.ModuleVersionId+"|"+String.Join("|",Directory.GetFiles(coreDir).Where(p=>p!=stamp&&!p.EndsWith(".new")).OrderBy(p=>p).Select(p=>Path.GetFileName(p)+":"+new FileInfo(p).Length+":"+File.GetLastWriteTimeUtc(p).Ticks));if(File.Exists(stamp)&&File.ReadAllText(stamp)==signature())return;foreach(var item in new[]{new[]{"SplifyWin.ByeTube.ciadpi.exe","ciadpi.exe"},new[]{"SplifyWin.ByeTube.LICENSE","LICENSE.byedpi"},new[]{"SplifyWin.ByeTube.NOTICES","NOTICES.byetube"},new[]{"SplifyWin.Core.sing-box.exe","sing-box.exe"},new[]{"SplifyWin.Core.libcronet.dll","libcronet.dll"},new[]{"SplifyWin.Core.LICENSE","LICENSE.sing-box"},new[]{"SplifyWin.Core.LICENSE.turn-engines.txt","LICENSE.turn-engines.txt"},new[]{"SplifyWin.Core.LICENSE.openflux","LICENSE.openflux"},new[]{"SplifyWin.Core.tgws.exe","tgws.exe"},new[]{"SplifyWin.Core.LICENSE.tgws","LICENSE.tgws"},new[]{"SplifyWin.Core.warpscout.exe","warpscout.exe"},new[]{"SplifyWin.Core.LICENSE.warpscout","LICENSE.warpscout"},new[]{"SplifyWin.Core.curl.exe","curl.exe"},new[]{"SplifyWin.Core.curl-ca-bundle.crt","curl-ca-bundle.crt"},new[]{"SplifyWin.Core.LICENSE.curl","LICENSE.curl"},new[]{"SplifyWin.Core.LICENSE.dependencies.zip","LICENSE.dependencies.zip"}}){BundledResources.Install(item[0],Path.Combine(coreDir,item[1]));}File.WriteAllText(stamp,signature());}
    public string Executable {get{var baseDir=AppDomain.CurrentDomain.BaseDirectory;var candidates=new[]{Path.Combine(store.Root,"core","sing-box.exe"),Path.Combine(baseDir,"sing-box.exe"),Path.Combine(baseDir,"bin","sing-box.exe")};return candidates.FirstOrDefault(File.Exists);}}
    public string OpenFluxExecutable {get{return Engines.Executable("openflux","openflux.exe");}}
    public string CSQTTExecutable{get{return Engines.Executable("csqtt","csqtt-client.exe");}}
    public string WDTTExecutable{get{return Engines.Executable("wdtt","wdtt-client.exe")??Engines.Executable("csqtt","wdtt-client.exe");}}
    public bool HasEngine(ServerNode node){return node!=null&&(node.Protocol=="csqtt"?Engines.Installed("csqtt"):node.Protocol=="wdtt"?WDTTExecutable!=null:node.Protocol=="openflux"?OpenFluxExecutable!=null:node.Protocol!="qwdtt");}
    public string TelegramExecutable {get{return AppUpdates.ActiveFile(store.Root,"telegram",Path.Combine(store.Root,"core","tgws.exe"));}}
    public string CurlExecutable {get{return Path.Combine(store.Root,"core","curl.exe");}}
    public Task<string> DownloadTextAsync(string address){return Task.Run(()=>PresetStorage.Contains(address)?PresetStorage.Read(store.Root,address):DownloadText(address));}public Task<string> DownloadRemoteTextAsync(string address){return Task.Run(()=>DownloadText(address));}
    string DownloadText(string address){Uri url;if(!Uri.TryCreate(address,UriKind.Absolute,out url)||url.Scheme!=Uri.UriSchemeHttps)throw new FormatException("Для онлайн-списка или подписки нужна ссылка HTTPS.");if(!File.Exists(CurlExecutable))throw new FileNotFoundException("Встроенный HTTPS-загрузчик не найден.");var cert=Path.Combine(store.Root,"core","curl-ca-bundle.crt");var args="--silent --show-error --fail --location --compressed --connect-timeout 8 --max-time 25 --max-filesize 10485760 --proto =https --proto-redir =https --cacert \""+cert+"\" --config -";var psi=new ProcessStartInfo(CurlExecutable,args){UseShellExecute=false,CreateNoWindow=true,RedirectStandardInput=true,RedirectStandardOutput=true,RedirectStandardError=true,StandardOutputEncoding=Encoding.UTF8,StandardErrorEncoding=Encoding.UTF8,WorkingDirectory=Path.GetDirectoryName(CurlExecutable)};using(var p=Process.Start(psi)){if(p==null)throw new InvalidOperationException("HTTPS-загрузчик не запустился.");p.StandardInput.WriteLine("url = \""+url.AbsoluteUri.Replace("\\","\\\\").Replace("\"","\\\"")+"\"");p.StandardInput.Close();var output=p.StandardOutput.ReadToEnd();var error=p.StandardError.ReadToEnd();if(!p.WaitForExit(30000)){p.Kill();throw new TimeoutException("Сервер не ответил за 30 секунд.");}if(p.ExitCode!=0)throw new InvalidOperationException("Не удалось загрузить HTTPS-ссылку: "+error.Replace(url.AbsoluteUri,"<скрыто>").Trim());return output;}}
    public string LogPath {get{return Path.Combine(store.Root,"core.log");}}
    public async Task<int> Probe(ServerNode n){try{var watch=Stopwatch.StartNew();using(var client=new TcpClient()){var task=client.ConnectAsync(n.Host,n.Port);if(await Task.WhenAny(task,Task.Delay(1800))!=task||!client.Connected)return -1;return (int)watch.ElapsedMilliseconds;}}catch{return -1;}}
    public async Task<int> ProbeIcmp(ServerNode n){try{using(var ping=new Ping()){var reply=await ping.SendPingAsync(n.Host,1800);return reply.Status==IPStatus.Success?(int)reply.RoundtripTime:-1;}}catch{return -1;}}
    public async Task<int> ProbeAll(ClientState state){var tasks=state.Servers.Select(async n=>{if(n.Protocol=="openflux"){n.Healthy=true;n.Latency=-1;return;}if(n.Protocol=="wireguard"||n.Protocol=="awg"||n.Protocol=="hysteria"||n.Protocol=="hysteria2"||n.Protocol=="tuic"){n.Healthy=true;n.Latency=await ProbeIcmp(n);return;}if(TurnEngines.IsTurn(n)){n.Healthy=HasEngine(n);n.Latency=await ProbeIcmp(n);return;}if(n.Protocol=="qwdtt"){n.Healthy=false;n.Latency=-1;return;}var ms=await Probe(n);n.Latency=ms;n.Healthy=ms>=0;}).ToArray();await Task.WhenAll(tasks);state.Servers=state.Servers.OrderBy(n=>n.Latency<0?int.MaxValue:n.Latency).ToList();return state.Servers.Count(n=>n.Healthy);}
    static Dictionary<string,object> D(params object[] pairs){var x=new Dictionary<string,object>();for(int i=0;i<pairs.Length;i+=2)x[(string)pairs[i]]=pairs[i+1];return x;}
    static string Q(Uri u,string key){return HttpUtility.ParseQueryString(u.Query)[key];}
    static string User(Uri u){return Uri.UnescapeDataString(u.UserInfo);}
    static Dictionary<string,object> Tls(Uri u){var security=Q(u,"security")??"";if(security=="none")return null;var unsafeValue=Q(u,"insecure")??Q(u,"allowInsecure")??"";var x=D("enabled",true,"server_name",Q(u,"sni")??Q(u,"serverName")??u.Host,"insecure",unsafeValue=="1"||unsafeValue.Equals("true",StringComparison.OrdinalIgnoreCase));var alpn=Q(u,"alpn");if(!String.IsNullOrWhiteSpace(alpn))x["alpn"]=alpn.Split(new[]{','},StringSplitOptions.RemoveEmptyEntries).Select(z=>z.Trim()).ToArray();var fp=Q(u,"fp");if(security=="reality"){x["reality"]=D("enabled",true,"public_key",Q(u,"pbk")??"","short_id",Q(u,"sid")??"");x["utls"]=D("enabled",true,"fingerprint",String.IsNullOrEmpty(fp)?"chrome":fp);}else if(!String.IsNullOrEmpty(fp))x["utls"]=D("enabled",true,"fingerprint",fp);return x;}
    static Dictionary<string,object> QuicTls(Uri u){var tls=Tls(u);if(tls!=null){tls.Remove("utls");IPAddress sniIp,serverIp;if((u.Scheme=="hysteria2"||u.Scheme=="hy2")&&IPAddress.TryParse(Convert.ToString(tls["server_name"]),out sniIp)&&!IPAddress.TryParse(u.Host,out serverIp))tls["server_name"]=u.Host;}return tls;}
    static Dictionary<string,object> Transport(Uri u){var t=Q(u,"type")??Q(u,"network");if(String.IsNullOrEmpty(t)||t=="tcp")return null;if(t=="splithttp")t="xhttp";var x=D("type",t);if(t=="ws"||t=="httpupgrade"){x["path"]=Q(u,"path")??"/";if(!String.IsNullOrEmpty(Q(u,"host")))x["headers"]=D("Host",Q(u,"host"));}if(t=="xhttp"){x["path"]=Q(u,"path")??"/";AddIf(x,"host",Q(u,"host"));AddIf(x,"mode",Q(u,"mode"));}if(t=="grpc")x["service_name"]=Q(u,"serviceName")??"";return x;}
    static void AddIf(Dictionary<string,object> x,string key,object value){if(value!=null)x[key]=value;}
    bool Supported(ServerNode n){return n!=null&&!String.IsNullOrEmpty(n.Protocol)&&HasEngine(n);}
    List<ServerNode> SelectedNodes(ClientState state){if(state.IndependentWarpOutputs&&!state.VpnEnabled)return new List<ServerNode>();var nodes=state.Servers.Where(n=>Supported(n)&&(!state.IndependentWarpOutputs||!WarpPicker.IsWarp(n)));return state.AutoSelect?nodes.ToList():nodes.Where(n=>n.Id==state.SelectedServerId).ToList();}
    public void ValidateSelection(ClientState state){if(state==null||state.Servers==null||state.Lists==null)throw new FormatException("Настройки подключения повреждены. Импортируйте профиль заново.");WarpOutputs.Validate(state);if((!state.IndependentWarpOutputs||state.VpnEnabled)&&(state.Mode=="proxy"||state.Lists.Any(x=>x!=null&&x.Enabled&&x.Target=="proxy"))&&SelectedNodes(state).Count==0){var selected=state.AutoSelect?state.Servers.FirstOrDefault(x=>!Supported(x)):state.Servers.FirstOrDefault(x=>x.Id==state.SelectedServerId);if(selected!=null&&!Supported(selected))throw new NotSupportedException(selected.Protocol.ToUpperInvariant()+": профиль импортирован, но его клиентский движок ещё не подключён. Это не ошибка сервера; подключение пока недоступно.");throw new InvalidOperationException("Сначала выберите сервер в разделе «Серверы».");}foreach(var node in SelectedNodes(state))if(String.IsNullOrWhiteSpace(node.Link)||String.IsNullOrWhiteSpace(node.Host))throw new FormatException("В выбранном профиле нет адреса или конфигурации. Импортируйте его заново.");}
    public Dictionary<string,object> Outbound(ServerNode n,string tag){
      if(n.Protocol=="csqtt"||n.Protocol=="wdtt"||n.Protocol=="qwdtt")throw new NotSupportedException(n.Protocol+" требует отдельный клиентский движок; этот профиль пока нельзя подключить.");
      if(n.Protocol=="vmess"){var raw=n.Link.Substring(8).Split('#')[0].Replace('-','+').Replace('_','/');var b=raw.PadRight((raw.Length+3)/4*4,'=');var v=json.Deserialize<Dictionary<string,object>>(Encoding.UTF8.GetString(Convert.FromBase64String(b)));var o=D("type","vmess","tag",tag,"server",n.Host,"server_port",n.Port,"uuid",Convert.ToString(v["id"]),"security",v.ContainsKey("scy")?Convert.ToString(v["scy"]):"auto");int aid;if(v.ContainsKey("aid")&&Int32.TryParse(Convert.ToString(v["aid"]),out aid))o["alter_id"]=aid;if(v.ContainsKey("tls")&&Convert.ToString(v["tls"])=="tls")o["tls"]=D("enabled",true,"server_name",v.ContainsKey("sni")?Convert.ToString(v["sni"]):n.Host);var network=v.ContainsKey("net")?Convert.ToString(v["net"]):"";if(network=="splithttp")network="xhttp";if(network.Length>0&&network!="tcp"){var transport=D("type",network);if(network=="ws"||network=="httpupgrade"){transport["path"]=v.ContainsKey("path")?Convert.ToString(v["path"]):"/";if(v.ContainsKey("host")&&!String.IsNullOrEmpty(Convert.ToString(v["host"])))transport["headers"]=D("Host",Convert.ToString(v["host"]));}else if(network=="xhttp"){transport["path"]=v.ContainsKey("path")?Convert.ToString(v["path"]):"/";if(v.ContainsKey("host"))transport["host"]=Convert.ToString(v["host"]);}else if(network=="grpc")transport["service_name"]=v.ContainsKey("path")?Convert.ToString(v["path"]):"";o["transport"]=transport;}return o;}
      var u=new Uri(n.Link);var o2=D("tag",tag,"server",n.Host,"server_port",n.Port);
      if(n.Protocol=="vless"){o2["type"]="vless";o2["uuid"]=User(u);AddIf(o2,"flow",Q(u,"flow"));AddIf(o2,"tls",(Q(u,"security")=="tls"||Q(u,"security")=="reality")?Tls(u):null);AddIf(o2,"transport",Transport(u));}
      else if(n.Protocol=="trojan"){o2["type"]="trojan";o2["password"]=User(u);o2["tls"]=Tls(u);AddIf(o2,"transport",Transport(u));}
      else if(n.Protocol=="hysteria2"){o2["type"]="hysteria2";o2["password"]=User(u);o2["tls"]=QuicTls(u);if(!String.IsNullOrEmpty(Q(u,"obfs")))o2["obfs"]=D("type",Q(u,"obfs"),"password",Q(u,"obfs-password")??"");int speed;if(Int32.TryParse(Q(u,"upmbps"),out speed))o2["up_mbps"]=speed;if(Int32.TryParse(Q(u,"downmbps"),out speed))o2["down_mbps"]=speed;}
      else if(n.Protocol=="hysteria"){o2["type"]="hysteria";o2["auth_str"]=Q(u,"auth")??User(u);o2["up"]=Q(u,"up")??((Q(u,"upmbps")??"100")+" Mbps");o2["down"]=Q(u,"down")??((Q(u,"downmbps")??"100")+" Mbps");o2["tls"]=QuicTls(u);AddIf(o2,"obfs",Q(u,"obfs"));}
      else if(n.Protocol=="tuic"){o2["type"]="tuic";var user=User(u).Split(new[]{':'},2);o2["uuid"]=user[0];o2["password"]=user.Length>1?user[1]:"";o2["tls"]=QuicTls(u);AddIf(o2,"congestion_control",Q(u,"congestion_control"));AddIf(o2,"udp_relay_mode",Q(u,"udp_relay_mode"));}
      else if(n.Protocol=="ss"){o2["type"]="shadowsocks";var auth=User(u);if(!auth.Contains(":")){try{auth=Encoding.UTF8.GetString(Convert.FromBase64String(auth.PadRight((auth.Length+3)/4*4,'=')));}catch{}}var parts=auth.Split(new[]{':'},2);if(parts.Length!=2)throw new FormatException("Некорректная ссылка Shadowsocks");o2["method"]=parts[0];o2["password"]=parts[1];}
      else if(n.Protocol=="socks"||n.Protocol=="socks5"){o2["type"]="socks";o2["version"]="5";var auth=User(u).Split(new[]{':'},2);if(auth.Length>0)o2["username"]=auth[0];if(auth.Length>1)o2["password"]=auth[1];}
      else if(n.Protocol=="http"||n.Protocol=="https"){o2["type"]="http";var auth=User(u).Split(new[]{':'},2);if(auth.Length>0)o2["username"]=auth[0];if(auth.Length>1)o2["password"]=auth[1];}
      else if(n.Protocol=="anytls"){o2["type"]="anytls";o2["password"]=User(u);o2["tls"]=Tls(u);}
      else if(n.Protocol=="naive"){o2["type"]="naive";var auth=User(u).Split(new[]{':'},2);o2["username"]=auth[0];o2["password"]=auth.Length>1?auth[1]:"";o2["tls"]=D("enabled",true,"server_name",Q(u,"sni")??u.Host);}
      else if(n.Protocol=="ssh"){o2["type"]="ssh";var auth=User(u).Split(new[]{':'},2);o2["user"]=auth[0];if(auth.Length>1)o2["password"]=auth[1];}
      else throw new NotSupportedException("Протокол "+n.Protocol+" пока нельзя запустить.");
      return o2;
    }
    public Dictionary<string,object> WireGuardEndpoint(ServerNode n,string tag){var f=Links.WireGuardFields(n.Link);var addresses=f["Address"].Split(',').Select(x=>x.Trim()).ToArray();var allowed=f.ContainsKey("AllowedIPs")?f["AllowedIPs"].Split(',').Select(x=>x.Trim()).ToArray():new[]{"0.0.0.0/0","::/0"};var peer=D("address",n.Host,"port",n.Port,"public_key",f["PublicKey"],"allowed_ips",allowed);if(f.ContainsKey("PresharedKey"))peer["pre_shared_key"]=f["PresharedKey"];var endpoint=D("type","wireguard","tag",tag,"address",addresses,"private_key",f["PrivateKey"],"peers",new[]{peer},"domain_resolver",D("server","bootstrap","strategy","prefer_ipv4"));string value;int number;if(f.TryGetValue("MTU",out value)&&Int32.TryParse(value,out number))endpoint["mtu"]=number;if(f.TryGetValue("PersistentKeepalive",out value))peer["persistent_keepalive_interval"]=Int32.TryParse(value,out number)?(object)number:value;
      foreach(var key in new[]{"Jc","Jmin","Jmax","S1","S2","S3","S4","H1","H2","H3","H4","I1","I2","I3","I4","I5"})if(f.TryGetValue(key,out value)&&value.Length>0){var outputKey=key.ToLowerInvariant();uint unsigned;endpoint[outputKey]=(key.StartsWith("H")&&UInt32.TryParse(value,out unsigned))?(object)(long)unsigned:Int32.TryParse(value,out number)?(object)number:value;}
      foreach(var mapping in new[]{new[]{"HeaderProtectionKey","header_protection_key"},new[]{"ContentPaddingAddition","content_padding_addition"},new[]{"RekeyAfterTime","rekey_after_time"},new[]{"RekeyTimeout","rekey_timeout"},new[]{"RejectAfterTime","reject_after_time"},new[]{"KeepaliveTimeout","keepalive_timeout"},new[]{"MaxHandshakeAttempts","max_handshake_attempts"}})if(f.TryGetValue(mapping[0],out value)&&value.Length>0)endpoint[mapping[1]]=Int32.TryParse(value,out number)?(object)number:value;
      foreach(var mapping in new[]{new[]{"RandomTrailers","random_trailers"},new[]{"DisableCookies","disable_cookies"}})if(f.TryGetValue(mapping[0],out value))endpoint[mapping[1]]=value.Equals("true",StringComparison.OrdinalIgnoreCase)||value=="1";
      return endpoint;}
    public Dictionary<string,object> Build(ClientState state){
      foreach(var list in state.Lists.Where(l=>l.Enabled&&ZapretRoutes.IsZapret(l)))ZapretRoutes.Validate(list,state.ZapretProfiles??new List<ZapretProfile>());
      var outputs=new List<object>{D("type","direct","tag","direct"),D("type","block","tag","block")};
      var endpoints=new List<object>();
      var selected=SelectedNodes(state);
      var tags=new List<string>();int i=0;foreach(var n in selected){var tag="node-"+(++i);if(n.Protocol=="wireguard"||n.Protocol=="awg")endpoints.Add(WireGuardEndpoint(n,tag));else if(n.Protocol=="openflux"||TurnEngines.IsTurn(n))outputs.Add(D("type","socks","tag",tag,"server","127.0.0.1","server_port",19080+i,"version","5"));else outputs.Add(Outbound(n,tag));tags.Add(tag);}
      if(tags.Count>1)outputs.Add(D("type","urltest","tag","proxy","outbounds",tags,"url","https://www.gstatic.com/generate_204","interval","5m","tolerance",50));
      var proxy=tags.Count==0?"block":tags.Count==1?tags[0]:"proxy";
      WarpOutputs.Validate(state);if(state.IndependentWarpOutputs&&state.WarpEnabled)foreach(var warp in WarpOutputs.ActiveProfiles(state))endpoints.Add(WireGuardEndpoint(warp,WarpOutputs.Tag(warp)));
      if(state.ByeTubeEnabled){ByeTube.Validate(state.ByeTubeStrategy);outputs.Add(D("type","socks","tag","byetube","server","127.0.0.1","server_port",ByeTube.Port,"version","5"));}
      if(state.Lists.Any(x=>x.Enabled&&x.Target=="tgws"))outputs.Add(D("type","socks","tag","tgws","server","127.0.0.1","server_port",19070,"version","5"));
      bool systemDns=state.DnsProvider=="Системный"&&(state.ExitDns==null||!state.ExitDns.Any(d=>ExitDnsRouting.Active(state,d.Target)));
      var rules=new List<object>();var helperPaths=new[]{Path.Combine(store.Root,"core","ciadpi.exe"),TelegramExecutable,OpenFluxExecutable,CSQTTExecutable,WDTTExecutable}.Where(p=>!String.IsNullOrEmpty(p)&&File.Exists(p)).Distinct().ToArray();if(helperPaths.Length>0)rules.Add(D("process_path",helperPaths,"action","route","outbound","direct"));if(!systemDns)rules.Add(D("protocol","dns","action","hijack-dns"));rules.Add(D("action","sniff"));if(tags.Count>0)rules.Add(D("inbound",new[]{"diagnostic"},"action","route","outbound",proxy));if(state.Mode=="tun"&&tags.Count>0)rules.Add(D("inbound",new[]{"tun-in"},"domain",new[]{"connectivitycheck.gstatic.com","www.gstatic.com","www.cloudflare.com"},"action","route","outbound",proxy));
      bool exclusionsAdded=false;
      foreach(var list in state.Lists.Where(x=>x.Enabled)){if(list.Target=="proxy"&&state.IndependentWarpOutputs&&!state.VpnEnabled)continue;if(list.Target=="proxy"&&!exclusionsAdded){rules.AddRange(VpnPresetRules.DirectRules(state.Lists));exclusionsAdded=true;}var target=list.Target=="direct"||list.Target=="zapret"?"direct":list.Target=="block"?"block":list.Target=="tgws"?"tgws":list.Target=="byetube"?(state.ByeTubeEnabled?"byetube":"block"):WarpOutputs.IsTarget(list.Target)?(state.WarpEnabled?list.Target:"block"):proxy;foreach(var rule in RouteCompiler.Compile(list,target)){if(list.Target=="byetube"&&state.ByeTubeEnabled){var match=new Dictionary<string,object>(rule);match.Remove("action");match.Remove("outbound");rules.Add(D("type","logical","mode","and","rules",new[]{match,D("network","udp")},"action","route","outbound","block"));}rules.Add(rule);}}
      var tun=D("type","tun","tag","tun-in","interface_name","mcrf","address",new[]{"172.19.0.1/30"},"dns_mode",systemDns?"disabled":"hijack","stack","gvisor","auto_route",true,"strict_route",true);
      var inbound=new List<object>();if(state.Mode=="tun")inbound.Add(tun);else inbound.Add(D("type","mixed","tag","local-proxy","listen","127.0.0.1","listen_port",10808));if(tags.Count>0)inbound.Add(D("type","mixed","tag","diagnostic","listen","127.0.0.1","listen_port",10809));
      var dnsServer=DnsOptions.Build(state);var dnsServers=new List<object>{dnsServer,D("type","local","tag","bootstrap")};foreach(var output in outputs){var dial=output as Dictionary<string,object>;if(dial!=null&&dial.ContainsKey("server"))dial["domain_resolver"]=D("server","bootstrap","strategy","prefer_ipv4");}
      var config=D("log",D("level","info","timestamp",true),"dns",D("servers",dnsServers,"final","chosen"),"inbounds",inbound,"outbounds",outputs,"route",D("auto_detect_interface",true,"default_domain_resolver","chosen","rules",rules,"final",state.Mode=="proxy"?proxy:"direct"));
      if(state.Lists.Any(l=>l.Enabled&&l.Target=="proxy"&&l.ExcludeRussia))((Dictionary<string,object>)config["route"])["rule_set"]=VpnPresetRules.RussiaSets();
      var exitDnsRules=ExitDnsRouting.Add(state,dnsServers,proxy);if(exitDnsRules.Count>0)((Dictionary<string,object>)config["dns"])["rules"]=exitDnsRules;
      config["experimental"]=D("clash_api",D("external_controller","127.0.0.1:"+trafficApiPort,"secret",trafficApiSecret));
      if(state.Lists.Any(l=>l.Enabled&&l.Target=="proxy"&&l.ExcludeRussia))((Dictionary<string,object>)config["experimental"])["cache_file"]=D("enabled",true,"path",Path.Combine(store.Root,"geo-rules-cache.db"));
      if(endpoints.Count>0)config["endpoints"]=endpoints;return config;
    }
    public string WriteConfig(ClientState state){var file=Path.Combine(store.Root,"sing-box.json");SecretStorage.WriteRuntime(file,json.Serialize(Build(state)));return file;}
    public string Check(string file){var exe=Executable;if(exe==null)throw new FileNotFoundException("В папке приложения отсутствует sing-box.exe");var psi=new ProcessStartInfo(exe,"--disable-color check -c \""+file+"\""){UseShellExecute=false,CreateNoWindow=true,RedirectStandardError=true,RedirectStandardOutput=true};using(var p=Process.Start(psi)){var outText=p.StandardOutput.ReadToEnd();var err=p.StandardError.ReadToEnd();p.WaitForExit(15000);if(!p.HasExited){p.Kill();throw new TimeoutException("Проверка конфигурации превысила 15 секунд");}if(p.ExitCode!=0)throw new InvalidOperationException((err+"\n"+outText).Trim());return (outText+"\n"+err).Trim();}}
    static string Ini(string value){if(value==null||value.IndexOfAny(new[]{'\r','\n'})>=0)throw new FormatException("Некорректный OpenFlux-профиль");return value;}
    Dictionary<string,object> ParseOpenFlux(string link){if(!File.Exists(OpenFluxExecutable))throw new FileNotFoundException("OpenFlux-движок отсутствует в приложении");var psi=new ProcessStartInfo(OpenFluxExecutable,"--parse-link -"){UseShellExecute=false,CreateNoWindow=true,RedirectStandardInput=true,RedirectStandardOutput=true,RedirectStandardError=true};using(var p=Process.Start(psi)){p.StandardInput.WriteLine(link);p.StandardInput.Close();var output=p.StandardOutput.ReadToEnd();var error=p.StandardError.ReadToEnd();if(!p.WaitForExit(10000)){p.Kill();throw new TimeoutException("OpenFlux не ответил на импорт ссылки");}var result=json.Deserialize<Dictionary<string,object>>(output);if(result.ContainsKey("error"))throw new FormatException(Convert.ToString(result["error"]));if(p.ExitCode!=0||!result.ContainsKey("config"))throw new FormatException("OpenFlux: "+error);return (Dictionary<string,object>)result["config"];}}
    public List<Dictionary<string,string>> ReadConnections(){
      var request=(HttpWebRequest)WebRequest.Create("http://127.0.0.1:"+trafficApiPort+"/connections");request.Proxy=null;request.Timeout=700;request.ReadWriteTimeout=700;request.Headers[HttpRequestHeader.Authorization]="Bearer "+trafficApiSecret;
      using(var response=request.GetResponse())using(var reader=new StreamReader(response.GetResponseStream())){
        var data=json.Deserialize<Dictionary<string,object>>(reader.ReadToEnd());var result=new List<Dictionary<string,string>>();object raw;if(!data.TryGetValue("connections",out raw))return result;
        foreach(var item in (IEnumerable)raw){var row=item as Dictionary<string,object>;object metadata;if(row==null||!row.TryGetValue("metadata",out metadata))continue;var fields=metadata as Dictionary<string,object>;if(fields==null)continue;result.Add(fields.ToDictionary(x=>x.Key,x=>Convert.ToString(x.Value)));}return result;
      }
    }
    public async Task<string> ProbeIsolatedAsync(ServerNode node,string url,CancellationToken cancellation){
      return await ProbeIsolatedAsync(node,url,cancellation,null);
    }
    public async Task<string> ProbeIsolatedAsync(ServerNode node,string url,CancellationToken cancellation,ClientState dnsSettings){
      return await Task.Run(()=>{
        Uri checkUrl;if(!Uri.TryCreate(url,UriKind.Absolute,out checkUrl)||checkUrl.Scheme!="https"||checkUrl.UserInfo.Length>0)throw new FormatException("Проверка требует HTTPS-адрес без учётных данных.");
        if(!Supported(node))throw new NotSupportedException("Для этого протокола клиентский движок не подключён.");
        var state=new ClientState{Mode="proxy",AutoSelect=false,SelectedServerId=node.Id,Servers=new List<ServerNode>{node}};
        if(dnsSettings!=null){state.DnsProvider=dnsSettings.DnsProvider;state.DnsProtocol=dnsSettings.DnsProtocol;state.DnsCustom=dnsSettings.DnsCustom;}
        var config=Build(state);var listener=new TcpListener(IPAddress.Loopback,0);listener.Start();int port=((IPEndPoint)listener.LocalEndpoint).Port;listener.Stop();
        config["inbounds"]=new[]{D("type","mixed","tag","local-proxy","listen","127.0.0.1","listen_port",port)};config.Remove("experimental");
        string file=Path.Combine(store.Root,"probe-"+Guid.NewGuid().ToString("N")+".json");Process owned=null,curl=null;var helpers=new List<Process>();
        try{cancellation.ThrowIfCancellationRequested();
          if(TurnEngines.IsTurn(node)||node.Protocol=="openflux"){
            listener.Start();int helperPort=((IPEndPoint)listener.LocalEndpoint).Port;listener.Stop();
            var outputs=(IEnumerable)config["outbounds"];foreach(var raw in outputs){var dial=raw as Dictionary<string,object>;if(dial!=null&&Convert.ToString(dial["tag"])=="node-1")dial["server_port"]=helperPort;}
            if(TurnEngines.IsTurn(node))TurnEngines.Start(node,CSQTTExecutable,WDTTExecutable,helperPort,child=>helpers.Add(child),WriteCoreLog,cancellation);else helpers.Add(StartOpenFlux(node,helperPort,helperPort));
          }
          SecretStorage.WriteRuntime(file,json.Serialize(config));Check(file);cancellation.ThrowIfCancellationRequested();
          var engineErrors=new Queue<string>();var errorGate=new object();
          owned=OwnedJob.Start(new ProcessStartInfo(Executable,"--disable-color run -c \""+file+"\""){UseShellExecute=false,CreateNoWindow=true,RedirectStandardError=true,RedirectStandardOutput=true});
          owned.ErrorDataReceived+=(sender,args)=>{if(args.Data!=null&&args.Data.IndexOf("error",StringComparison.OrdinalIgnoreCase)>=0)lock(errorGate){engineErrors.Enqueue(JournalStyle.Redact(args.Data));while(engineErrors.Count>8)engineErrors.Dequeue();}};owned.BeginErrorReadLine();owned.BeginOutputReadLine();
          var readiness=Stopwatch.StartNew();bool ready=false;
          while(readiness.ElapsedMilliseconds<3000){cancellation.ThrowIfCancellationRequested();if(owned.HasExited)throw new IOException("Движок проверки завершился до открытия порта.");try{using(var socket=new TcpClient()){var attempt=socket.ConnectAsync("127.0.0.1",port);if(attempt.Wait(50)&&socket.Connected){ready=true;break;}}}catch{}Thread.Sleep(30);}
          if(!ready)throw new TimeoutException("Движок проверки не открыл локальный порт за 3 секунды.");
          bool warpProbe=WarpPicker.IsWarp(node);          curl=OwnedJob.Start(new ProcessStartInfo(CurlExecutable,"--silent --show-error --ipv4 --fail --connect-timeout "+(warpProbe?7:3)+" --max-time "+(warpProbe?10:5)+" --noproxy \"\" --proxy http://127.0.0.1:"+port+" --cacert \""+Path.Combine(store.Root,"core","curl-ca-bundle.crt")+"\" \""+url+"\""){UseShellExecute=false,CreateNoWindow=true,RedirectStandardOutput=true,RedirectStandardError=true});
          var output=curl.StandardOutput.ReadToEndAsync();var error=curl.StandardError.ReadToEndAsync();var requestTime=Stopwatch.StartNew();while(!curl.WaitForExit(100)){cancellation.ThrowIfCancellationRequested();if(requestTime.ElapsedMilliseconds>(warpProbe?12000:7000))throw new TimeoutException("HTTPS-проверка превысила "+(warpProbe?12:7)+" секунд.");}cancellation.ThrowIfCancellationRequested();
          if(curl.ExitCode!=0){string details;lock(errorGate)details=String.Join("\n",engineErrors);throw new IOException("Изолированная проверка выхода не прошла (curl "+curl.ExitCode+"). "+JournalStyle.Redact(error.GetAwaiter().GetResult())+(details.Length==0?"":"\n"+details));}return output.GetAwaiter().GetResult();
        }finally{foreach(var child in new[]{curl,owned}.Concat(helpers))DisposeOwned(child);if(File.Exists(file))File.Delete(file);}
      },cancellation);
    }
    public void ValidateImport(ServerNode node){if(node.Protocol=="openflux")ParseOpenFlux(node.Link);if(TurnEngines.IsTurn(node))TurnProfile.Parse(node.Link);}
    Process StartOpenFlux(ServerNode node,int port,int index){var config=ParseOpenFlux(node.Link);var secret=Ini(Convert.ToString(config["secret"]));var secretFile=Path.Combine(store.Root,"openflux-secret-"+index+".txt");SecretStorage.WriteRuntime(secretFile,secret);var ini=new StringBuilder("[Interface]\nRole = client\nInbound = socks5\nSocks5 = 127.0.0.1:").Append(port).Append("\nEncryptionKeyFile = ").Append(secretFile).Append("\nURL = ").Append(Ini(Convert.ToString(config["context"]))).Append("\n");var transports=config["transports"] as IEnumerable;if(transports==null)throw new FormatException("В OpenFlux-профиле нет транспорта");foreach(var raw in transports){var t=raw as Dictionary<string,object>;if(t==null)throw new FormatException("Некорректный транспорт OpenFlux");var type=Ini(Convert.ToString(t["type"]));var name=t.ContainsKey("name")?Ini(Convert.ToString(t["name"])):type;if(!name.All(c=>Char.IsLetterOrDigit(c)||c=='-'||c=='_'))throw new FormatException("Некорректное имя транспорта OpenFlux");ini.Append("\n[Transport \"").Append(name).Append("\"]\nType = ").Append(type).Append("\n");foreach(var pair in new[]{new[]{"url","URL"},new[]{"dial","Dial"},new[]{"priority","Priority"}})if(t.ContainsKey(pair[0]))ini.Append(pair[1]).Append(" = ").Append(Ini(Convert.ToString(t[pair[0]]))).Append('\n');}
      var confFile=Path.Combine(store.Root,"openflux-"+index+".conf");SecretStorage.WriteRuntime(confFile,ini.ToString());var psi=new ProcessStartInfo(OpenFluxExecutable,"--config=\""+confFile+"\" --socks5=127.0.0.1:"+port){UseShellExecute=false,CreateNoWindow=true,WorkingDirectory=Path.GetDirectoryName(OpenFluxExecutable)};var p=OwnedJob.Start(psi);if(p==null)throw new InvalidOperationException("OpenFlux не запустился");for(int attempt=0;attempt<50;attempt++){if(p.HasExited){p.Dispose();throw new InvalidOperationException("OpenFlux завершился до открытия локального порта");}try{using(var socket=new TcpClient()){var task=socket.ConnectAsync("127.0.0.1",port);if(task.Wait(100)&&socket.Connected)return p;}}catch{}Thread.Sleep(100);}try{p.Kill();}catch{}p.Dispose();throw new TimeoutException("OpenFlux не открыл локальный SOCKS-порт");}
    Process StartTelegram(){if(!File.Exists(TelegramExecutable))throw new FileNotFoundException("Telegram WS-движок отсутствует в приложении");var psi=new ProcessStartInfo(TelegramExecutable,"-host 127.0.0.1 -port 19070"){UseShellExecute=false,CreateNoWindow=true,WorkingDirectory=Path.GetDirectoryName(TelegramExecutable)};var p=OwnedJob.Start(psi);if(p==null)throw new InvalidOperationException("Telegram WS-движок не запустился");for(int attempt=0;attempt<50;attempt++){if(p.HasExited){p.Dispose();throw new InvalidOperationException("Telegram WS-движок завершился до открытия порта");}try{using(var socket=new TcpClient()){var task=socket.ConnectAsync("127.0.0.1",19070);if(task.Wait(100)&&socket.Connected)return p;}}catch{}Thread.Sleep(100);}try{p.Kill();}catch{}p.Dispose();throw new TimeoutException("Telegram WS-движок не открыл локальный порт");}
    void WriteCoreLog(string line){if(line==null)return;line=ClientJournal.Stamp(line);lock(logSync){if(coreWriter!=null){coreWriter.WriteLine(line);coreWriter.Flush();}else File.AppendAllText(LogPath,line+Environment.NewLine,new UTF8Encoding(false));}}
    static bool TunReady(){return NetworkInterface.GetAllNetworkInterfaces().Any(x=>x.OperationalStatus==OperationalStatus.Up&&(x.Name.IndexOf("mcrf",StringComparison.OrdinalIgnoreCase)>=0||x.GetIPProperties().UnicastAddresses.Any(a=>a.Address.AddressFamily==AddressFamily.InterNetwork&&a.Address.ToString()=="172.19.0.1")));}
    public void Start(ClientState state){
      lock(startupLock){
        var startupWatch=Stopwatch.StartNew();long phaseStart=0;Action<string> phase=label=>{long now=startupWatch.ElapsedMilliseconds;WriteCoreLog("VPN · "+label+": "+(now-phaseStart)+" мс; всего "+now+" мс");phaseStart=now;};
        if(Running)return;StopOwned();ValidateSelection(state);
        var startup=new CancellationTokenSource();startupCancellation=startup;var token=startup.Token;
        try{
          activeNodes.Clear();activeServerId="";var nodes=SelectedNodes(state);int index=0;
          foreach(var node in nodes){if(!HasEngine(node))throw new FileNotFoundException("Скачайте движок "+node.Protocol.ToUpperInvariant()+" в разделе «Движки».");activeNodes["node-"+(++index)]=node.Id;}
          phase("Проверка выбора и остановка прежних процессов");var file=WriteConfig(state);phase("Подготовка конфигурации");Check(file);phase("Проверка конфигурации движком");token.ThrowIfCancellationRequested();
          Action<Process> register=child=>{lock(sidecars)sidecars.Add(child);token.ThrowIfCancellationRequested();};
          if(state.ByeTubeEnabled)register(ByeTube.Start(Path.Combine(store.Root,"core","ciadpi.exe"),state.ByeTubeStrategy,WriteCoreLog,token));
          if(state.Lists.Any(x=>x.Enabled&&x.Target=="tgws")){register(StartTelegram());phase("Готовность Telegram WS");}
          int i=0;foreach(var node in nodes){token.ThrowIfCancellationRequested();i++;
            if(node.Protocol=="openflux")register(StartOpenFlux(node,19080+i,i));
            else if(TurnEngines.IsTurn(node))TurnEngines.Start(node,CSQTTExecutable,WDTTExecutable,19080+i,register,WriteCoreLog,token);
            if(node.Protocol=="openflux"||TurnEngines.IsTurn(node))phase("Готовность транспорта "+node.Protocol.ToUpperInvariant()+" · "+i+"/"+nodes.Count);
          }
          token.ThrowIfCancellationRequested();
          var psi=new ProcessStartInfo(Executable,"--disable-color run -c \""+file+"\""){UseShellExecute=false,CreateNoWindow=true,WorkingDirectory=Path.GetDirectoryName(Executable),RedirectStandardOutput=true,RedirectStandardError=true};
          lock(logSync){coreWriter=new StreamWriter(new FileStream(LogPath,FileMode.Append,FileAccess.Write,FileShare.ReadWrite),new UTF8Encoding(false)){AutoFlush=true};}
          process=new Process{StartInfo=psi};process.OutputDataReceived+=(s,e)=>WriteCoreLog(e.Data);process.ErrorDataReceived+=(s,e)=>WriteCoreLog(e.Data);
          if(!process.Start())throw new InvalidOperationException("Сетевой движок не запустился");
          OwnedJob.Attach(process);process.BeginOutputReadLine();process.BeginErrorReadLine();Thread.Sleep(80);
          if(process.HasExited)throw new InvalidOperationException("Сетевой движок завершился: "+LastCoreError());
          phase("Запуск sing-box");
          int proxyPort=state.Mode=="proxy"?10808:10809;
          if(state.Mode!="proxy"){bool ready=false;for(int attempt=0;attempt<150;attempt++){token.ThrowIfCancellationRequested();ready=TunReady();if(ready||process.HasExited)break;Thread.Sleep(100);}
            if(!ready)throw new InvalidOperationException("TUN-интерфейс не появился. Проверьте права администратора и журнал.");
            phase("Готовность интерфейса TUN");
          }
          if(state.Mode=="proxy"||nodes.Count>0){bool ready=false;for(int attempt=0;attempt<30;attempt++){token.ThrowIfCancellationRequested();
            try{using(var socket=new TcpClient()){var connection=socket.ConnectAsync("127.0.0.1",proxyPort);if(connection.Wait(100)&&socket.Connected){ready=true;break;}}}catch{}Thread.Sleep(100);
          }if(!ready)throw new InvalidOperationException("Локальный прокси не открыл порт "+proxyPort+". Проверьте журнал.");}
          phase("Готовность локального прокси");
          token.ThrowIfCancellationRequested();
        }catch{phase("Сбой запуска");StopOwned();throw;}
        finally{if(startupCancellation==startup)startupCancellation=null;startup.Dispose();}
      }
    }
    string LastCoreError(){try{using(var stream=new FileStream(LogPath,FileMode.Open,FileAccess.Read,FileShare.ReadWrite)){stream.Seek(Math.Max(0,stream.Length-2000),SeekOrigin.Begin);using(var reader=new StreamReader(stream))return reader.ReadToEnd();}}catch{return "откройте журнал для подробностей";}}
    static void DisposeOwned(Process child){if(child==null)return;try{if(!child.HasExited){if(child.StartInfo.RedirectStandardInput){try{child.StandardInput.WriteLine("STOP");child.StandardInput.Flush();child.WaitForExit(1000);}catch{}}if(!child.HasExited){child.Kill();child.WaitForExit(2000);}}}catch{}child.Dispose();}
    void StopOwned(){var current=process;process=null;DisposeOwned(current);Process[] children;lock(sidecars){children=sidecars.ToArray();sidecars.Clear();}foreach(var child in children)DisposeOwned(child);lock(logSync){if(coreWriter!=null){coreWriter.Dispose();coreWriter=null;}}}
    public void Stop(){var pending=startupCancellation;try{if(pending!=null)pending.Cancel();}catch(ObjectDisposedException){}lock(startupLock)StopOwned();}
  }
}
