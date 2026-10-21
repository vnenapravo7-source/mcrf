using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Text.RegularExpressions;
using System.Security.Cryptography;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;
using Renci.SshNet;
using SplifyWin;
namespace Mcrf.Wpf {
 internal sealed class DeployRecipe {
  public string Id,Name,Repository,Commit,File,Sha256,Version,CoreCommit;
  public string Url{get{return "https://raw.githubusercontent.com/"+Repository+"/"+Commit+"/"+File;}}
  internal static readonly DeployRecipe[] All={
   new DeployRecipe{Id="3xui",Name="3x-ui (VPN)",Repository="MHSanaei/3x-ui",Commit="74c3cc38821cabd66ad316ea562851422d0b0063",File="install.sh",Sha256="9684bae4f769b2127fcceceb4f200045657381311867529e6ba76c6b58e2825d",Version="v3.10.0"},
   new DeployRecipe{Id="openflux",Name="OpenFlux",Repository="vnenapravo7-source/openflux-deploy",Commit="6e1b5a995d5fd1c00c6ff372d25f5f88b9e7e636",File="deploy.sh",Sha256="91f7a699faf930740eda0d0eadf81e7c6012ffe8ac41ff57c09d40838c413417",Version="6e1b5a9",CoreCommit="edcf77a1848a72370cfba9f346d3b897b61e7e7d"},
   new DeployRecipe{Id="swg",Name="SWG (csqtt, wdtt, qwdtt)",Repository="SanityProtocol/swg-panel",Commit="9c4a59d523ae4ec42fb8205198678c8a7ffc6263",File="bootstrap.sh",Sha256="a64586c2a49acc3bab520a28c86903aa6291c213e3cac79485696edf5c2e2fd5",Version="9c4a59d"}
  };
  public override string ToString(){return Name;}
 }
 internal sealed class DeployTarget {
  public string Host,User,Password,KeyFile,KeyPassword,PanelUser,PanelPassword,Domain;public int Port=22,PanelPort=8443;public bool AllowExisting=false;public DeployProvisionOptions Provision=new DeployProvisionOptions();
  internal void Validate(){if(String.IsNullOrWhiteSpace(Host)||Uri.CheckHostName(Host.Trim('[',']'))==UriHostNameType.Unknown||Host.IndexOfAny(new[]{'\r','\n','\0','/','@'})>=0||Port<1||Port>65535||PanelPort<1024||PanelPort>65535||PanelPort==Port||!Regex.IsMatch(User??"",@"^[a-z_][a-zA-Z0-9_-]{0,31}$"))throw new FormatException("Проверьте адрес, SSH-порт, порт панели и логин сервера.");if(!String.IsNullOrWhiteSpace(Domain)&&Uri.CheckHostName(Domain)==UriHostNameType.Unknown)throw new FormatException("Укажите домен без https:// и пути.");if(String.IsNullOrWhiteSpace(PanelUser)||PanelUser.Length>64||PanelUser.IndexOfAny(new[]{'\r','\n','\0'})>=0)throw new FormatException("Проверьте логин панели.");if(String.IsNullOrEmpty(KeyFile)&&String.IsNullOrEmpty(Password))throw new FormatException("Укажите пароль сервера или файл SSH-ключа.");if(!String.IsNullOrEmpty(KeyFile)&&!File.Exists(KeyFile))throw new FileNotFoundException("Файл SSH-ключа не найден.");}
 }
 internal sealed class DeployResult {public string panel_url{get;set;}public string username{get;set;}public string password{get;set;}public string certificate_sha256{get;set;}public bool ready{get;set;}public string[] connections{get;set;}public string csqtt_line{get;set;}public bool provision_requested{get;set;}public bool provision_complete{get;set;}public string provision_error{get;set;}public Dictionary<string,int> ports{get;set;}public string tunnel_address{get;set;}public string recipe_id{get;set;}public string server_host{get;set;}public string ssh_user{get;set;}public int ssh_port{get;set;}}
 internal static partial class Deployment {
  internal static string Quote(string text){if(text==null||text.IndexOf('\0')>=0)throw new FormatException("Недопустимое значение SSH.");return "'"+text.Replace("'","'\"'\"'")+"'";}
  internal static string RandomSecret(){var bytes=new byte[24];using(var rng=RandomNumberGenerator.Create())rng.GetBytes(bytes);return Convert.ToBase64String(bytes).TrimEnd('=').Replace('+','-').Replace('/','_');}
  internal static string Fingerprint(byte[] key){using(var sha=SHA256.Create())return "SHA256:"+Convert.ToBase64String(sha.ComputeHash(key)).TrimEnd('=');}
  internal static string SafeLog(string line,DeployTarget target){string s=Regex.Replace(line??"", @"\x1b\[[0-?]*[ -/]*[@-~]","");foreach(string secret in new[]{target.Password,target.KeyPassword,target.PanelPassword}.Where(v=>!String.IsNullOrEmpty(v)))s=s.Replace(secret,"<скрыто>");if(Regex.IsMatch(s,@"password|пароль|secret|api.?token|PRIVATE KEY",RegexOptions.IgnoreCase))return "Учётные данные обработаны · доступны только в результате установки.";return s.Length>500?s.Substring(0,500):s;}
  internal static string BuildScript(DeployRecipe recipe,DeployTarget target,string directory){
   if(!Regex.IsMatch(directory??"", @"^/tmp/mcrf-deploy-[a-f0-9]{32}$"))throw new FormatException("Небезопасный каталог установки.");target.Validate();target.Provision.Validate(recipe.Id,target);string host=String.IsNullOrWhiteSpace(target.Domain)?target.Host:target.Domain;var script=new StringBuilder("set -eu\numask 077\necho $$ > "+Quote(directory+"/pid")+"\ncd "+Quote(directory)+"\n");
   Action<string,string> env=(key,value)=>script.Append("export ").Append(key).Append('=').Append(Quote(value)).Append('\n');
   if(recipe.Id=="3xui"){env("XUI_NONINTERACTIVE","1");env("XUI_ENABLE_FAIL2BAN","false");env("XUI_USERNAME",target.PanelUser);env("XUI_PASSWORD",target.PanelPassword);env("XUI_PANEL_PORT",target.PanelPort.ToString());env("XUI_WEB_BASE_PATH",RandomSecret().Substring(0,18));env("XUI_SSL_MODE",String.IsNullOrWhiteSpace(target.Domain)?"none":"domain");env("XUI_DOMAIN",target.Domain??"");env("XUI_SERVER_IP",target.Host);script.Append("bash installer.sh ").Append(Quote(recipe.Version)).Append("\nsource /etc/x-ui/install-result.env\n");if(String.IsNullOrWhiteSpace(target.Domain)){string san=(Uri.CheckHostName(target.Host.Trim('[',']'))==UriHostNameType.Dns?"DNS:":"IP:")+target.Host.Trim('[',']');script.Append("install -d -m 700 /etc/x-ui/mcrf-tls\nopenssl req -x509 -newkey rsa:2048 -sha256 -nodes -days 365 -keyout /etc/x-ui/mcrf-tls/key.pem -out /etc/x-ui/mcrf-tls/cert.pem -subj '/CN=MCRF-panel' -addext ").Append(Quote("subjectAltName="+san)).Append(" >/dev/null 2>&1\nchmod 600 /etc/x-ui/mcrf-tls/key.pem /etc/x-ui/mcrf-tls/cert.pem\n/usr/local/x-ui/x-ui cert -webCert /etc/x-ui/mcrf-tls/cert.pem -webCertKey /etc/x-ui/mcrf-tls/key.pem\nsystemctl restart x-ui\n");}script.Append("export MCRF_PANEL_URL=").Append(Quote("https://"+(host.Contains(":")?"["+host.Trim('[',']')+"]":host)+":"+target.PanelPort+"/")).Append("\"${XUI_WEB_BASE_PATH}\"\nexport MCRF_PANEL_USER=\"$XUI_USERNAME\"\nexport MCRF_PANEL_PASS=\"$XUI_PASSWORD\"\nexport MCRF_PANEL_API_TOKEN=\"$XUI_API_TOKEN\"\nsystemctl is-active --quiet x-ui\n");}
   else if(recipe.Id=="openflux"){env("OPENFLUX_ADMIN_USER",target.PanelUser);env("OPENFLUX_ADMIN_PASSWORD",target.PanelPassword);env("OPENFLUX_DEPLOY_REVISION",recipe.Commit);env("OPENFLUX_PORT",target.PanelPort.ToString());script.Append("bash installer.sh --mode systemd --port ").Append(target.PanelPort).Append("\nsystemctl is-active --quiet openflux-panel\n");env("MCRF_PANEL_URL","https://"+(host.Contains(":")?"["+host+"]":host)+":"+target.PanelPort);env("MCRF_PANEL_USER",target.PanelUser);env("MCRF_PANEL_PASS",target.PanelPassword);}
   else if(recipe.Id=="swg"){
    env("SWG_REF",recipe.Commit);env("BASIC_USER",target.PanelUser);env("BASIC_PASS",target.PanelPassword);
    env("PANEL_DOMAIN",host+":"+target.PanelPort);env("PORT",target.PanelPort.ToString());env("TLS_MODE","selfsigned");env("SERVE_MODE","internal");
    // In this pinned SWG revision blank means report-only: existing tunnels are
    // candidates for manual adoption. "none" is an invalid interface name.
    env("MANAGE_IFACES","");
    // Keep our private SSH/uploaded files under 077. Only the public installer
    // needs its normal umask, so swgpanel can traverse the installed /opt code.
    script.Append("(umask 022; bash installer.sh master)\nsystemctl is-active --quiet swg-panel-server\n");
    env("MCRF_PANEL_URL","https://"+(host.Contains(":")?"["+host+"]":host)+":"+target.PanelPort+"/");
    script.Append("export MCRF_PANEL_USER=$(cut -d: -f1 /etc/swg-panel/auth)\n");env("MCRF_PANEL_PASS",target.PanelPassword);
   }
   else throw new FormatException("Неизвестная панель.");
   env("MCRF_PANEL_PORT",target.PanelPort.ToString());env("MCRF_PROVISION_KIND",recipe.Id);env("MCRF_PROVISION_HOST",target.Host);env("MCRF_PROVISION_OPTIONS",Convert.ToBase64String(Encoding.UTF8.GetBytes(new JavaScriptSerializer().Serialize(target.Provision))));
   script.Append("python3 - <<'MCRF_RESULT_PY'\n"+ProvisioningPython+"\n"+PanelProbePython+"\nMCRF_RESULT_PY\n");return script.ToString();
  }
  internal static async Task<DeployResult> Install(NetworkCore core,DeployRecipe recipe,DeployTarget target,Func<string,string,bool> trust,Action<string> say,CancellationToken token){
   target.Validate();target.Provision.Validate(recipe.Id,target);if(String.IsNullOrEmpty(target.PanelPassword))target.PanelPassword=RandomSecret();say("Проверяем закреплённый установщик "+recipe.Name+"…");string source=await core.DownloadRemoteTextAsync(recipe.Url);if(AppUpdates.Hash(Encoding.UTF8.GetBytes(source))!=recipe.Sha256)throw new IOException("Контрольная сумма установщика изменилась. Установка остановлена.");source=PinInstaller(recipe,source);token.ThrowIfCancellationRequested();
   return await Task.Run(()=>{AuthenticationMethod auth=String.IsNullOrEmpty(target.KeyFile)?(AuthenticationMethod)new PasswordAuthenticationMethod(target.User,target.Password):new PrivateKeyAuthenticationMethod(target.User,new PrivateKeyFile(target.KeyFile,String.IsNullOrEmpty(target.KeyPassword)?null:target.KeyPassword));var info=new ConnectionInfo(target.Host,target.Port,target.User,auth){Timeout=TimeSpan.FromSeconds(15)};string directory="/tmp/mcrf-deploy-"+Guid.NewGuid().ToString("N"),prefix="";bool created=false;DeployResult result=null;
    using(var ssh=new SshClient(info)){ssh.HostKeyReceived+=(s,e)=>e.CanTrust=trust(target.Host+":"+target.Port,Fingerprint(e.HostKey));say("Соединяемся по SSH…");ssh.Connect();token.ThrowIfCancellationRequested();using(var check=ssh.RunCommand("id -u; . /etc/os-release; printf '\\nMCRF_OS=%s\\n' \"$ID\"")){if(check.ExitStatus!=0||!Regex.IsMatch(check.Result,@"MCRF_OS=(debian|ubuntu)\b"))throw new InvalidOperationException("Автодеплой поддерживает Debian и Ubuntu.");if(!check.Result.StartsWith("0\n")){using(var sudo=ssh.RunCommand("sudo -n true"))if(sudo.ExitStatus!=0)throw new InvalidOperationException("Нужен root или sudo без запроса пароля.");prefix="sudo -n ";}}
     string existing=recipe.Id=="3xui"?"test -e /usr/local/x-ui/x-ui":recipe.Id=="openflux"?"test -d /etc/openflux-deploy":"test -d /etc/swg-panel";using(var check=ssh.RunCommand(prefix+"sh -c "+Quote(existing))){if(check.ExitStatus==0&&!target.AllowExisting)throw new InvalidOperationException("Панель уже установлена. Остановлено без изменений; обновите её через саму панель.");}
     using(var occupied=ssh.RunCommand("ss -ltnH "+Quote("sport = :"+target.PanelPort))){if(occupied.ExitStatus!=0)throw new IOException("Не удалось проверить занятость порта панели.");if(!String.IsNullOrWhiteSpace(occupied.Result))throw new IOException("Порт панели уже занят. Выберите другой порт.");}
     using(var prepare=ssh.RunCommand("umask 077; mkdir -m 700 "+Quote(directory))){if(prepare.ExitStatus!=0)throw new IOException("Не удалось создать изолированный каталог установки.");created=true;}
     try{using(var sftp=new SftpClient(info)){sftp.HostKeyReceived+=(s,e)=>e.CanTrust=trust(target.Host+":"+target.Port,Fingerprint(e.HostKey));sftp.Connect();foreach(var file in new[]{Tuple.Create("installer.sh",source),Tuple.Create("run.sh",BuildScript(recipe,target,directory))}){using(var bytes=new MemoryStream(Encoding.UTF8.GetBytes(file.Item2)))sftp.UploadFile(bytes,directory+"/"+file.Item1,false);sftp.ChangePermissions(directory+"/"+file.Item1,700);}}
      using(var command=ssh.CreateCommand(prefix+"setsid bash "+Quote(directory+"/run.sh"))){command.CommandTimeout=TimeSpan.FromMinutes(30);var running=command.BeginExecute();var output=Task.Run(()=>{using(var reader=new StreamReader(command.OutputStream,Encoding.UTF8)){string line;while((line=reader.ReadLine())!=null){if(line.StartsWith("MCRF_RESULT|")){try{result=new JavaScriptSerializer().Deserialize<DeployResult>(Encoding.UTF8.GetString(Convert.FromBase64String(line.Substring(12))));}catch{throw new IOException("Панель вернула повреждённый результат.");}}else say(SafeLog(line,target));}}});var errors=Task.Run(()=>{using(var reader=new StreamReader(command.ExtendedOutputStream,Encoding.UTF8)){string line;while((line=reader.ReadLine())!=null)say(SafeLog(line,target));}});
       while(!running.IsCompleted){if(token.IsCancellationRequested){StopJob(ssh,prefix,directory);throw new OperationCanceledException(token);}Thread.Sleep(100);}command.EndExecute(running);Task.WaitAll(output,errors);if(command.ExitStatus!=0)throw new IOException("Установщик завершился с ошибкой. Уже выполненные серверные изменения автоматически не откатываются.");if(result==null||!result.ready)throw new IOException("Панель не подтвердила готовность. Адрес и реквизиты не выдаются как успешный результат.");ValidateResult(result,target);result.recipe_id=recipe.Id;result.server_host=target.Host;result.ssh_user=target.User;result.ssh_port=target.Port;say("Установка завершена, HTTPS панели отвечает; сертификат получен через SSH.");return result;
      }
     }finally{if(created){if(result==null)StopJob(ssh,prefix,directory);try{using(var cleanup=ssh.RunCommand(prefix+"sh -c "+Quote("rm -f "+Quote(directory+"/run.sh")+" "+Quote(directory+"/installer.sh")+" "+Quote(directory+"/pid")+"; rmdir "+Quote(directory)))){if(cleanup.ExitStatus!=0)say("Не удалось убрать временные файлы установщика: "+directory);}}catch{say("SSH-соединение закрыто; временные файлы установщика могли остаться: "+directory);} }}
    }
   },token);
  }
  internal static string PinInstaller(DeployRecipe recipe,string source){
   if(recipe.Id!="openflux")return source;
   var before=@"  DEPLOY_REVISION=""$(git ls-remote ""https://github.com/${REPO}.git"" ""refs/heads/${REF}"" 2>/dev/null | awk '{print $1}' || true)""";
   var archive=@"  archive_ref=""${DEPLOY_REVISION:-refs/heads/${REF}}""";
   var clone=@"  git clone --quiet --depth 1 --single-branch --branch main ""$UPSTREAM_REPO"" ""$upstream_src""";
   if(!source.Contains(before)||!source.Contains(archive)||!source.Contains(clone)||!Regex.IsMatch(recipe.CoreCommit??"", @"^[a-f0-9]{40}$"))throw new IOException("Не удалось закрепить исходники OpenFlux. Установка остановлена до SSH.");
   var goMatch=Regex.Match(source,@"(?ms)^install_go\(\)\{\r?\n.*?^\}\r?$");
   if(!goMatch.Success||!goMatch.Value.Contains("rm -rf /usr/local/go"))throw new IOException("Не удалось изолировать Go для OpenFlux. Установка остановлена до SSH.");
   source=source.Substring(0,goMatch.Index)+OpenFluxGoInstaller+source.Substring(goMatch.Index+goMatch.Length);
   return source.Replace(before,"  DEPLOY_REVISION="+Quote(recipe.Commit)).Replace(archive,"  archive_ref=\"$DEPLOY_REVISION\"").Replace(clone,"  git init --quiet \"$upstream_src\"\n  git -C \"$upstream_src\" remote add origin \"$UPSTREAM_REPO\"\n  git -C \"$upstream_src\" fetch --quiet --depth 1 origin "+Quote(recipe.CoreCommit)+"\n  git -C \"$upstream_src\" checkout --quiet --detach FETCH_HEAD");
  }
  internal const string OpenFluxGoInstaller=@"install_go(){
  local version arch json sha file tmp toolchain
  version=""$(awk '/^go /{print $2;exit}' ""${1:-$PREFIX/source}/go.mod"")""
  [[ ""$version"" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || fail ""некорректная версия Go""
  arch=""$(uname -m)""; case ""$arch"" in x86_64) arch=amd64;; aarch64|arm64) arch=arm64;; *) fail ""архитектура $arch не поддерживается"";; esac
  toolchain=""$STATE_DIR/toolchains/go$version""
  if [ ! -x ""$toolchain/bin/go"" ]; then
    [ ! -e ""$toolchain"" ] || fail ""неполный каталог Go; сохранён без перезаписи""
    say ""Устанавливаю Go $version в каталог OpenFlux с проверкой SHA-256""
    tmp=""$(mktemp -d)""; file=""go${version}.linux-${arch}.tar.gz""
    json=""$(curl -fsSL 'https://go.dev/dl/?mode=json&include=all')""
    sha=""$(printf '%s' ""$json"" | python3 -c 'import json,sys; d=json.load(sys.stdin); v=sys.argv[1]; print(next(f[""sha256""] for r in d if r[""version""]==""go""+v for f in r[""files""] if f[""filename""]==""go""+v+"".linux-""+sys.argv[2]+"".tar.gz""))' ""$version"" ""$arch"")""
    curl -fsSL --retry 3 ""https://go.dev/dl/$file"" -o ""$tmp/$file""
    printf '%s  %s\n' ""$sha"" ""$tmp/$file"" | sha256sum -c - >/dev/null
    tar -C ""$tmp"" -xzf ""$tmp/$file""
    install -d -m 755 ""$STATE_DIR/toolchains""
    mv -- ""$tmp/go"" ""$toolchain""
    rm -f -- ""$tmp/$file""; rmdir -- ""$tmp""
  fi
  export GOROOT=""$toolchain""
  export PATH=""$toolchain/bin:$PATH""
  export GOCACHE=""${GOCACHE:-$STATE_DIR/go-cache}""
  export GOMODCACHE=""${GOMODCACHE:-$STATE_DIR/go-mod}""
  export GOPATH=""${GOPATH:-$STATE_DIR/go-path}""
  # The panel's updater must also find the private compiler when no system Go exists.
  if [ -f ""$CONFIG_DIR/panel.env"" ]; then
    printf 'PATH=%s/bin:/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin\n' ""$toolchain"" >>""$CONFIG_DIR/panel.env""
  fi
}";
  internal static void ValidateResult(DeployResult result,DeployTarget target){
   Uri panel;if(result==null||!result.ready||!Uri.TryCreate(result.panel_url,UriKind.Absolute,out panel)||panel.Scheme!="https"||panel.Port!=target.PanelPort||panel.UserInfo.Length>0||String.IsNullOrEmpty(result.username)||String.IsNullOrEmpty(result.password)||!Regex.IsMatch(result.certificate_sha256??"", @"^[a-fA-F0-9]{64}$"))throw new IOException("Панель не вернула проверенный HTTPS-адрес, сертификат и реквизиты.");
   if(result.connections==null)result.connections=new string[0];if(result.connections.Length>8)throw new IOException("Панель вернула слишком много первоначальных подключений.");
   if(!String.IsNullOrEmpty(result.csqtt_line)&&result.csqtt_line!="2.1"&&result.csqtt_line!="2.5")throw new IOException("Панель не подтвердила известную ветку CSQTT.");
   if(result.provision_complete&&target.Provision.create&&target.Provision.relay_kind=="csqtt"&&result.csqtt_line!=target.Provision.csqtt_line)throw new IOException("Узел не подтвердил выбранную серверную ветку CSQTT "+target.Provision.csqtt_line+".");
   if(result.csqtt_line=="2.5")result.connections=result.connections.Select(link=>Csqtt25Link.IsSealed(link)?link:Csqtt25Link.Seal(link)).ToArray();
   foreach(string link in result.connections){if(String.IsNullOrEmpty(link)||link.Length>65536||link.IndexOfAny(new[]{'\r','\n','\0'})>=0)throw new IOException("Панель вернула неподходящую ссылку подключения.");var nodes=Links.Read(link).ToArray();if(nodes.Length!=1||!new[]{"vless","openflux","qwdtt","wdtt","csqtt"}.Contains(nodes[0].Protocol))throw new IOException("Панель вернула неподдерживаемое подключение.");if(nodes[0].Protocol=="csqtt"&&!String.IsNullOrEmpty(result.csqtt_line)&&TurnProfile.Parse(link).Csqtt25!=(result.csqtt_line=="2.5"))throw new IOException("Формат ссылки не соответствует подтверждённой ветке CSQTT.");}
   if(result.provision_complete&&result.provision_requested&&result.connections.Length==0)throw new IOException("Подключение не подтверждено панелью.");
  }
  internal static string ProvisioningPython{get{using(var stream=typeof(Deployment).Assembly.GetManifestResourceStream("Mcrf.Deployment.Provisioning.py")){if(stream==null)throw new IOException("В EXE отсутствует настройка первоначальных подключений.");using(var reader=new StreamReader(stream,Encoding.UTF8))return reader.ReadToEnd();}}}
  internal const string PanelProbePython=@"import os,json,base64,ssl,socket,time,hashlib,urllib.parse
u=urllib.parse.urlsplit(os.environ['MCRF_PANEL_URL'])
port=int(os.environ['MCRF_PANEL_PORT'])
if u.scheme!='https' or u.port!=port or not u.hostname or u.username:
    raise RuntimeError('Panel returned invalid HTTPS address')
ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT)
ctx.check_hostname=False
ctx.verify_mode=ssl.CERT_NONE
last=None
for attempt in range(25):
    try:
        with socket.create_connection(('127.0.0.1',port),timeout=3) as tcp:
            with ctx.wrap_socket(tcp,server_hostname=u.hostname) as tls:
                cert=tls.getpeercert(binary_form=True)
                path=urllib.parse.quote(u.path or '/',safe='/')
                request='GET '+path+' HTTP/1.1\r\nHost: '+u.netloc+'\r\nConnection: close\r\n\r\n'
                tls.sendall(request.encode('ascii'))
                status=tls.recv(1024).split(b'\r\n',1)[0].split()
                if not cert or len(status)<2 or not status[0].startswith(b'HTTP/') or not 200<=int(status[1])<500:
                    raise RuntimeError('Panel HTTPS health check failed')
                fingerprint=hashlib.sha256(cert).hexdigest()
                break
    except (OSError,ValueError,RuntimeError) as e:
        last=e
        time.sleep(1)
else:
    raise RuntimeError('Panel HTTPS did not become ready') from last
r={'panel_url':os.environ['MCRF_PANEL_URL'],'username':os.environ['MCRF_PANEL_USER'],'password':os.environ['MCRF_PANEL_PASS'],'certificate_sha256':fingerprint,'ready':True,'connections':[]}
if os.environ.get('MCRF_PROVISION_KIND'):
    options=json.loads(base64.b64decode(os.environ['MCRF_PROVISION_OPTIONS']))
    r=configure_initial(r,os.environ['MCRF_PROVISION_KIND'],options,os.environ['MCRF_PROVISION_HOST'])
print('MCRF_RESULT|'+base64.b64encode(json.dumps(r).encode()).decode())";
  static void StopJob(SshClient ssh,string prefix,string directory){if(!ssh.IsConnected)return;try{using(var stop=ssh.RunCommand(prefix+"sh -c "+Quote("p=$(cat "+Quote(directory+"/pid")+" 2>/dev/null || true); case \"$p\" in ''|*[!0-9]*) exit 0;; esac; kill -TERM -- -\"$p\" 2>/dev/null || true"))){} }catch{}}
 }
}
