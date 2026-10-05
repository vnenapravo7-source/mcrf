using System;
using System.Diagnostics;
using System.IO;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Cryptography;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;

namespace SplifyWin {
  // Unauthenticated Gateway Hello proves an interface connection, NOT voice.
  public static class DiscordInterfaceProbe {
    public const string Target="wss://gateway.discord.gg/?v=10&encoding=json";
    static byte[] Read(Stream stream,int count){var bytes=new byte[count];int position=0;while(position<count){int got=stream.Read(bytes,position,count-position);if(got==0)throw new IOException("Соединение закрыто сервером");position+=got;}return bytes;}
    static string Header(Stream stream){var result=new MemoryStream();int tail=0;while(result.Length<16384){int b=stream.ReadByte();if(b<0)throw new IOException("Нет ответа на WebSocket-запрос");result.WriteByte((byte)b);tail=(tail<<8)|b;if(tail==0x0d0a0d0a)return Encoding.ASCII.GetString(result.ToArray());}throw new InvalidDataException("Слишком большой заголовок Gateway");}
    public static bool ValidUpgrade(string response,string key){
      if(response==null||!response.StartsWith("HTTP/1.1 101 ",StringComparison.Ordinal))return false;
      string accept=null,upgrade=null,connection=null;foreach(var line in response.Split(new[]{"\r\n"},StringSplitOptions.None)){int colon=line.IndexOf(':');if(colon<0)continue;string name=line.Substring(0,colon).Trim(),value=line.Substring(colon+1).Trim();if(name.Equals("Sec-WebSocket-Accept",StringComparison.OrdinalIgnoreCase))accept=value;if(name.Equals("Upgrade",StringComparison.OrdinalIgnoreCase))upgrade=value;if(name.Equals("Connection",StringComparison.OrdinalIgnoreCase))connection=value;}
      string expected;using(var sha=SHA1.Create())expected=Convert.ToBase64String(sha.ComputeHash(Encoding.ASCII.GetBytes(key+"258EAFA5-E914-47DA-95CA-C5AB0DC85B11")));
      return accept==expected&&String.Equals(upgrade,"websocket",StringComparison.OrdinalIgnoreCase)&&connection!=null&&Array.Exists(connection.Split(','),x=>x.Trim().Equals("Upgrade",StringComparison.OrdinalIgnoreCase));
    }
    public static bool ValidHello(string payload){try{var json=new JavaScriptSerializer().DeserializeObject(payload) as System.Collections.Generic.Dictionary<string,object>;if(json==null||!json.ContainsKey("op")||Convert.ToInt32(json["op"])!=10||!json.ContainsKey("d"))return false;var data=json["d"] as System.Collections.Generic.Dictionary<string,object>;return data!=null&&data.ContainsKey("heartbeat_interval")&&Convert.ToDouble(data["heartbeat_interval"])>0;}catch{return false;}}
    static void SendControl(Stream stream,int opcode,byte[] payload){var mask=new byte[4];using(var random=RandomNumberGenerator.Create())random.GetBytes(mask);var frame=new byte[6+payload.Length];frame[0]=(byte)(128|opcode);frame[1]=(byte)(128|payload.Length);Array.Copy(mask,0,frame,2,4);for(int i=0;i<payload.Length;i++)frame[i+6]=(byte)(payload[i]^mask[i%4]);stream.Write(frame,0,frame.Length);}
    static string Hello(Stream stream){using(var message=new MemoryStream()){bool started=false;for(int frames=0;frames<16;frames++){var header=Read(stream,2);int opcode=header[0]&15;bool final=(header[0]&128)!=0;if((header[0]&112)!=0||(header[1]&128)!=0)throw new InvalidDataException("Некорректный WebSocket-кадр");long count=header[1]&127;if(count==126){var size=Read(stream,2);count=(size[0]<<8)|size[1];}else if(count==127)throw new InvalidDataException("Слишком большой Gateway-кадр");if(count>16384||message.Length+count>16384)throw new InvalidDataException("Слишком большой Gateway Hello");var data=Read(stream,(int)count);if(opcode>=8){if(!final||count>125)throw new InvalidDataException("Некорректный управляющий кадр");if(opcode==8)throw new IOException("Gateway закрыл соединение");if(opcode==9)SendControl(stream,10,data);else if(opcode!=10)throw new InvalidDataException("Неизвестный управляющий кадр");continue;}if(opcode==1&&!started)started=true;else if(opcode!=0||!started)throw new InvalidDataException("Ожидался текстовый Gateway Hello");message.Write(data,0,data.Length);if(final)return new UTF8Encoding(false,true).GetString(message.ToArray());}throw new InvalidDataException("Gateway Hello не получен");}}
    static void Socks(Stream stream){stream.Write(new byte[]{5,1,0},0,3);var auth=Read(stream,2);if(auth[0]!=5||auth[1]!=0)throw new IOException("Локальный VPN-прокси не принял соединение");var host=Encoding.ASCII.GetBytes("gateway.discord.gg");var request=new byte[7+host.Length];request[0]=5;request[1]=1;request[3]=3;request[4]=(byte)host.Length;Array.Copy(host,0,request,5,host.Length);request[request.Length-2]=1;request[request.Length-1]=187;stream.Write(request,0,request.Length);var reply=Read(stream,4);if(reply[0]!=5||reply[1]!=0||reply[2]!=0)throw new IOException("VPN не соединился с Gateway");int size=reply[3]==1?4:reply[3]==4?16:reply[3]==3?Read(stream,1)[0]:-1;if(size<0)throw new InvalidDataException("Некорректный SOCKS-ответ");Read(stream,size+2);}
    public static Task<ZapretEndpointResult> Run(bool proxy,CancellationToken token,int proxyPort=10808){return Task.Run(()=>{
      var watch=Stopwatch.StartNew();var result=new ZapretEndpointResult{Url=Target,Access=ServiceAccess.Unknown};using(var deadline=CancellationTokenSource.CreateLinkedTokenSource(token))using(var client=new TcpClient()){
        deadline.CancelAfter(5000);using(deadline.Token.Register(()=>client.Close()))try{
          token.ThrowIfCancellationRequested();client.ReceiveTimeout=client.SendTimeout=5000;client.NoDelay=true;
          client.Connect(proxy?"127.0.0.1":"gateway.discord.gg",proxy?proxyPort:443);
          using(var transport=client.GetStream()){transport.ReadTimeout=transport.WriteTimeout=5000;if(proxy)Socks(transport);
            using(var tls=new SslStream(transport,false)){tls.ReadTimeout=tls.WriteTimeout=5000;tls.AuthenticateAsClient("gateway.discord.gg",null,System.Security.Authentication.SslProtocols.Tls12,true);
              var nonce=new byte[16];using(var random=RandomNumberGenerator.Create())random.GetBytes(nonce);string key=Convert.ToBase64String(nonce);
              string request="GET /?v=10&encoding=json HTTP/1.1\r\nHost: gateway.discord.gg\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Key: "+key+"\r\nSec-WebSocket-Version: 13\r\n\r\n";var bytes=Encoding.ASCII.GetBytes(request);tls.Write(bytes,0,bytes.Length);
              if(!ValidUpgrade(Header(tls),key)){result.Detail="Gateway не подтвердил WebSocket-подключение; голос не проверен";return result;}
              if(!ValidHello(Hello(tls))){result.Detail="WebSocket открыт, но корректный Discord Hello не получен";return result;}
              result.Access=ServiceAccess.Available;result.Detail="Gateway: TLS проверен, WebSocket открыт, получен Discord Hello. Интерфейс, не RTC и не звук.";
              try{SendControl(tls,8,new byte[]{3,232});}catch{}return result;
            }
          }
        }catch(Exception ex){token.ThrowIfCancellationRequested();result.Access=ex is InvalidDataException?ServiceAccess.Unknown:ServiceAccess.Unavailable;result.Detail=deadline.IsCancellationRequested?"Gateway: превышено ожидание 5 секунд":ex is System.Security.Authentication.AuthenticationException?"Gateway: TLS-сертификат не прошёл проверку":"Gateway: соединение не подтверждено ("+ex.GetType().Name+")";return result;}finally{result.Milliseconds=(int)watch.ElapsedMilliseconds;}
      }
    },token);}
  }
}
