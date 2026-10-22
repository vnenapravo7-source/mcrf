// Synthetic loopback-only process used to inspect the Windows launch boundary.
using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Web.Script.Serialization;
class TurnLaunchFixture {
 static string Arg(string[] a,string key){for(int i=0;i<a.Length;i++){if(a[i]==key&&i+1<a.Length)return a[i+1];if(a[i].StartsWith(key+"="))return a[i].Substring(key.Length+1).Trim('"');}return "";}
 static void Socks(string address){var parts=address.Split(':');var listener=new TcpListener(IPAddress.Loopback,Int32.Parse(parts.Last()));listener.Start();System.Threading.Tasks.Task.Run(()=>{while(true){try{var c=listener.AcceptTcpClient();using(c){var s=c.GetStream();var bytes=new byte[3];s.Read(bytes,0,3);s.Write(new byte[]{5,0},0,2);}}catch{return;}}});}
 static void Main(string[] args){var json=new JavaScriptSerializer();string kind=Path.GetFileNameWithoutExtension(typeof(TurnLaunchFixture).Assembly.Location),payload="";if(kind=="qwdtt-client")payload=File.ReadAllText(Arg(args,"-startup-config"));else if(kind!="csqtt-netstack")payload=Console.ReadLine();if(kind=="wdtt-client"){string b=payload.Substring("START_CONFIG|".Length).Replace('-','+').Replace('_','/');payload=System.Text.Encoding.UTF8.GetString(Convert.FromBase64String(b.PadRight((b.Length+3)/4*4,'=')));}File.WriteAllText(Path.Combine(Environment.GetEnvironmentVariable("MCRF_FIXTURE_CAPTURE")??AppDomain.CurrentDomain.BaseDirectory,kind+".capture.json"),json.Serialize(new{args=args,payload=payload}));
 if(kind=="csqtt-netstack"){Socks(Arg(args,"-socks-listen"));Console.WriteLine("PROXY_READY|fixture");}
 else if(kind=="wdtt-client"){Console.WriteLine("PROXY_READY|fixture");}
 else if(kind=="qwdtt-client"){if(Arg(args,"-mode")=="rawsocks")Console.WriteLine("MCRF_QWDTT_RAW_CONFIG|10.77.0.2|1.1.1.1|1300");else{Socks(Arg(args,"-socks"));Console.WriteLine("PROXY_READY|fixture");}}
 else Console.WriteLine("__CSQTT_EVENT__|CONFIG|{\"config\":\"TUNCONF:10.77.0.2:1.1.1.1\"}");Console.Out.Flush();while(Console.ReadLine()!=null){}
 }
}
