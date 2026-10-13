using System;
using System.Text.RegularExpressions;
using System.Linq;
using SplifyWin;
namespace Mcrf.Wpf {
 internal sealed class DeployProvisionOptions {
  public bool create{get;set;}public bool tcp{get;set;}public bool grpc{get;set;}
  public int tcp_port{get;set;}public int grpc_port{get;set;}public int direct_port{get;set;}public int relay_port{get;set;}public int wg_port{get;set;}
  public string[] vk_calls{get;set;}public string sni{get;set;}public string grpc_service{get;set;}public string transport{get;set;}public string carrier_url{get;set;}public string relay_kind{get;set;}public string vk_call{get;set;}
  public DeployProvisionOptions(){tcp=grpc=true;tcp_port=12325;grpc_port=15931;sni="pinterest.com";grpc_service="";transport="direct";carrier_url="";direct_port=5443;relay_kind="qwdtt";vk_call="";relay_port=56000;wg_port=51820;}
  static void Port(int value,DeployTarget target){if(value<1024||value>65535||value==target.Port||value==target.PanelPort)throw new FormatException("Порт подключения должен быть от 1024 до 65535 и отличаться от портов SSH и панели.");}
  internal void Validate(string recipe,DeployTarget target){if(!create)return;
   if(recipe=="3xui"){if(!tcp&&!grpc)throw new FormatException("Выберите TCP и/или gRPC.");if(tcp)Port(tcp_port,target);if(grpc)Port(grpc_port,target);if(tcp&&grpc&&tcp_port==grpc_port)throw new FormatException("Для TCP и gRPC нужны разные порты.");if(Uri.CheckHostName(sni??"")!=UriHostNameType.Dns||!Regex.IsMatch(sni??"",@"^[A-Za-z0-9.-]{1,253}$"))throw new FormatException("Укажите домен REALITY без https:// и пути.");if((grpc_service??"").Length>128||!Regex.IsMatch(grpc_service??"",@"^[A-Za-z0-9_.-]*$"))throw new FormatException("В имени gRPC допустимы буквы, цифры, точка, дефис и подчёркивание.");}
   else if(recipe=="openflux"){if(!Array.Exists(new[]{"direct","yandex","vyandex","boards","telemost","mailru","cupsonline"},v=>v==transport))throw new FormatException("Выберите транспорт OpenFlux.");if(transport=="direct")Port(direct_port,target);else if(transport!="cupsonline"){Uri link;if(!Uri.TryCreate(carrier_url,UriKind.Absolute,out link)||link.Scheme!="https"||link.UserInfo.Length>0||carrier_url.Length>8192||carrier_url.IndexOfAny(new[]{'\r','\n','\0'})>=0)throw new FormatException("Для выбранного транспорта нужна HTTPS-ссылка сервиса.");}}
   else if(recipe=="swg"){if(!Array.Exists(new[]{"qwdtt","wdtt","csqtt"},v=>v==relay_kind))throw new FormatException("Выберите протокол SWG.");Port(relay_port,target);if(relay_kind!="csqtt"){Port(wg_port,target);if(relay_port==wg_port)throw new FormatException("Порты TURN и WireGuard должны различаться.");}vk_calls=VkCalls.CallLinks(vk_calls==null?vk_call:String.Join("\n",vk_calls));if(vk_calls.Any(v=>v.Length>512))throw new FormatException("Ссылка звонка VK слишком длинная.");if(relay_kind!="qwdtt")foreach(var link in vk_calls){string hash=VkCalls.CallHash(link);try{var encoded=hash.Replace('-','+').Replace('_','/');if(Convert.FromBase64String(encoded.PadRight((encoded.Length+3)/4*4,'=')).Length!=32)throw new FormatException();}catch{throw new FormatException("Для WDTT/CSQTT нужен 32-байтный хеш звонка VK.");}}vk_call=vk_calls[0];}
   else throw new FormatException("Неизвестная панель.");
  }
 }
}
