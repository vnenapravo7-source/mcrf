using System;
using System.IO;
using System.Net;
using System.Net.Sockets;
using System.Net.Security;
using System.Security.Authentication;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Security.Cryptography;
using System.Web.Script.Serialization;
using Renci.SshNet;
namespace Mcrf.Wpf {
 internal static partial class Deployment {
  internal static Task CheckPanel(DeploymentPanel panel,CancellationToken token){
   DeploymentPanelStore.Validate(panel);
   return Task.Run(()=>{
    token.ThrowIfCancellationRequested();var uri=new Uri(panel.Result.panel_url);
    // Framework's HttpWebRequest has only a process-global TLS policy. Keep
    // this probe's TLS version and certificate pin local to its own stream.
    using(var tcp=new TcpClient())using(token.Register(()=>tcp.Close())){
     var connect=tcp.BeginConnect(uri.DnsSafeHost,uri.Port,null,null);
     using(var wait=connect.AsyncWaitHandle)if(!wait.WaitOne(15000))throw new IOException("Истекло время подключения к панели.");
     token.ThrowIfCancellationRequested();tcp.EndConnect(connect);tcp.ReceiveTimeout=15000;tcp.SendTimeout=15000;
     using(var tls=new SslStream(tcp.GetStream(),false,(sender,cert,chain,errors)=>{
      if(cert==null)return false;using(var sha=SHA256.Create())return String.Equals(BitConverter.ToString(sha.ComputeHash(cert.GetRawCertData())).Replace("-",""),panel.Result.certificate_sha256,StringComparison.OrdinalIgnoreCase);
     })){
      tls.ReadTimeout=15000;tls.WriteTimeout=15000;tls.AuthenticateAsClient(uri.DnsSafeHost,null,SslProtocols.Tls12,false);
      byte[] request=Encoding.ASCII.GetBytes("GET "+uri.PathAndQuery+" HTTP/1.1\r\nHost: "+uri.Authority+"\r\nConnection: close\r\n\r\n");tls.Write(request,0,request.Length);tls.Flush();
      var status=new StringBuilder();int ch;while(status.Length<1024&&(ch=tls.ReadByte())>=0&&ch!='\n')status.Append((char)ch);
      token.ThrowIfCancellationRequested();var parts=status.ToString().TrimEnd('\r').Split(' ');int code;
      if(parts.Length<2||!parts[0].StartsWith("HTTP/1.")||!Int32.TryParse(parts[1],out code)||code<200||code>=400)throw new IOException("Панель не подтвердила доступность.");
     }
    }
   },token);
  }
  internal static string BuildManagementScript(DeploymentPanel panel,DeployTarget target){
   DeploymentPanelStore.Validate(panel);target.Validate();target.Provision.Validate(panel.RecipeId,target);if(!String.Equals(target.Host,panel.Host,StringComparison.OrdinalIgnoreCase)||target.PanelPort!=new Uri(panel.Result.panel_url).Port)throw new FormatException("SSH-сервер не соответствует сохранённой панели.");
   // Reuse the accepted API implementation, not the installer. All secrets are
   // private uploaded bytes, never SSH command arguments or command output.
   var credentials=new{panel_url=panel.Result.panel_url,username=panel.Result.username,password=panel.Result.password,certificate_sha256=panel.Result.certificate_sha256,ready=true,connections=new string[0]};
   string payload=Convert.ToBase64String(Encoding.UTF8.GetBytes(new JavaScriptSerializer().Serialize(new{result=credentials,options=target.Provision,kind=panel.RecipeId,host=panel.Host})));
   return ProvisioningPython+"\n_private=json.loads(base64.b64decode('"+payload+"'))\nr=_private['result']\nr['connections']=[]\nr.pop('csqtt_line',None)\nr=configure_initial(r,_private['kind'],_private['options'],_private['host'])\nprint('MCRF_RESULT|'+base64.b64encode(json.dumps(r).encode()).decode(),flush=True)\n";
  }
  internal static Task<DeployResult> AddConnections(DeploymentPanel panel,DeployTarget target,Func<string,string,bool> trust,Action<string> say,CancellationToken token){
   DeploymentPanelStore.Validate(panel);target.Validate();target.Provision.Validate(panel.RecipeId,target);if(!target.Provision.create)throw new FormatException("Выберите создание подключения.");
   target.PanelPassword=panel.Result.password;
   return Task.Run(()=>{
    AuthenticationMethod auth=String.IsNullOrEmpty(target.KeyFile)?(AuthenticationMethod)new PasswordAuthenticationMethod(target.User,target.Password):new PrivateKeyAuthenticationMethod(target.User,new PrivateKeyFile(target.KeyFile,String.IsNullOrEmpty(target.KeyPassword)?null:target.KeyPassword));var info=new ConnectionInfo(target.Host,target.Port,target.User,auth){Timeout=TimeSpan.FromSeconds(15)};
    string directory="/tmp/mcrf-deploy-"+Guid.NewGuid().ToString("N"),prefix="";DeployResult fresh=null;bool created=false;
    using(var ssh=new SshClient(info)){ssh.HostKeyReceived+=(s,e)=>e.CanTrust=trust(target.Host+":"+target.Port,Fingerprint(e.HostKey));ssh.Connect();token.ThrowIfCancellationRequested();
     using(var uid=ssh.RunCommand("id -u")){if(uid.ExitStatus!=0)throw new IOException("Не удалось проверить SSH-доступ.");if(uid.Result.Trim()!="0"){using(var sudo=ssh.RunCommand("sudo -n true"))if(sudo.ExitStatus!=0)throw new IOException("Для проверки портов нужен root или sudo без пароля.");prefix="sudo -n ";}}
     using(var prepare=ssh.RunCommand("umask 077; mkdir -m 700 "+Quote(directory))){if(prepare.ExitStatus!=0)throw new IOException("Не удалось создать закрытый каталог операции.");created=true;}
     try{
      string operation=BuildManagementScript(panel,target);
      string runner="set -eu\numask 077\necho $$ > "+Quote(directory+"/pid")+"\nexec flock -n '/run/lock/mcrf-provision.lock' python3 "+Quote(directory+"/manage.py")+"\n";
      using(var sftp=new SftpClient(info)){sftp.HostKeyReceived+=(s,e)=>e.CanTrust=trust(target.Host+":"+target.Port,Fingerprint(e.HostKey));sftp.Connect();foreach(var file in new[]{Tuple.Create("manage.py",operation),Tuple.Create("run.sh",runner)}){using(var bytes=new MemoryStream(Encoding.UTF8.GetBytes(file.Item2)))sftp.UploadFile(bytes,directory+"/"+file.Item1,false);sftp.ChangePermissions(directory+"/"+file.Item1,700);}}
      using(var command=ssh.CreateCommand(prefix+"setsid bash "+Quote(directory+"/run.sh"))){command.CommandTimeout=TimeSpan.FromMinutes(12);var running=command.BeginExecute();var output=Task.Run(()=>{using(var reader=new StreamReader(command.OutputStream,Encoding.UTF8)){string line;while((line=reader.ReadLine())!=null){if(line.StartsWith("MCRF_RESULT|"))fresh=new JavaScriptSerializer().Deserialize<DeployResult>(Encoding.UTF8.GetString(Convert.FromBase64String(line.Substring(12))));else say(SafeLog(line,target));}}});var errors=Task.Run(()=>{using(var reader=new StreamReader(command.ExtendedOutputStream,Encoding.UTF8)){string line;while((line=reader.ReadLine())!=null)say(SafeLog(line,target));}});
       while(!running.IsCompleted){if(token.IsCancellationRequested){StopJob(ssh,prefix,directory);throw new OperationCanceledException(token);}Thread.Sleep(100);}command.EndExecute(running);Task.WaitAll(output,errors);if(command.ExitStatus!=0||fresh==null)throw new IOException("Операция не завершена. Установка панели не запускалась; проверьте её журнал.");ValidateResult(fresh,target);fresh.recipe_id=panel.RecipeId;fresh.server_host=target.Host;fresh.ssh_user=target.User;fresh.ssh_port=target.Port;return fresh;
      }
     }finally{if(created){if(fresh==null)StopJob(ssh,prefix,directory);using(var cleanup=ssh.RunCommand(prefix+"sh -c "+Quote("rm -f -- "+Quote(directory+"/manage.py")+" "+Quote(directory+"/run.sh")+" "+Quote(directory+"/pid")+"; rmdir -- "+Quote(directory))))if(cleanup.ExitStatus!=0)say("Временный каталог операции сохранён: "+directory);}}
    }
   },token);
  }
 }
}
