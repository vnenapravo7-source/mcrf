using System;
using System.IO;
using System.Linq;
using System.Net;
using System.Net.Sockets;
using System.Text;
using System.Collections.Generic;
namespace SplifyWin {
 public static class DiscordCaptureFilter {
  // Based on upstream Zapret's Discord discovery and STUN WinDivert examples.
  // Voice RTP/ordinary UDP must not enter the desync queue merely by port.
  public static string Build(IEnumerable<string> networks){
   var ranges=new List<string>();
   foreach(string value in networks.Distinct()){
    var parts=value.Split('/');IPAddress address;if(parts.Length>2||!IPAddress.TryParse(parts[0],out address))throw new FormatException("Некорректный IP в фильтре голоса Discord.");
    byte[] first=address.GetAddressBytes(),last=(byte[])first.Clone();int bits=first.Length*8,prefix=bits;if(parts.Length==2&&!Int32.TryParse(parts[1],out prefix)||prefix<=0||prefix>bits)throw new FormatException("Некорректная сеть в фильтре голоса Discord.");
    for(int bit=prefix;bit<bits;bit++){first[bit/8]=(byte)(first[bit/8]&~(1<<(7-bit%8)));last[bit/8]=(byte)(last[bit/8]|(1<<(7-bit%8)));}
    string field=address.AddressFamily==AddressFamily.InterNetwork?"ip.DstAddr":"ipv6.DstAddr",family=first.Length==4?"ip":"ipv6";
    ranges.Add("("+family+" and "+field+">="+new IPAddress(first)+" and "+field+"<="+new IPAddress(last)+")");
   }
   string discovery="(udp.PayloadLength=74 and udp.Payload32[0]=0x00010046";for(int i=2;i<=17;i++)discovery+=" and udp.Payload32["+i+"]=0";discovery+=")";
   string stun="(udp.PayloadLength>=20 and udp.Payload32[1]=0x2112A442 and udp.Payload[0]<0x40)";
   // No fixed discovery port range: Discord may allocate a different voice port.
   string result="outbound and udp and udp.DstPort>=1024 and ("+discovery+" or "+stun+")"+(ranges.Count==0?"":" and ("+String.Join(" or ",ranges)+")");
   if(result.Length>12000)throw new InvalidOperationException("Слишком большой список IP для узкого фильтра Discord. Сократите голосовой список; широкий перехват автоматически не включается.");return result;
  }
  public static string Write(string directory,IEnumerable<string> networks){Directory.CreateDirectory(directory);string path=Path.Combine(directory,"discord-voice-capture.filter");File.WriteAllText(path,Build(networks),new UTF8Encoding(false));return "--wf-raw-part="+ZapretRuntime.Quote("@"+path)+" ";}
 }
}
