using System;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Text;
using System.Collections;
using System.Collections.Generic;
using System.Web;
using System.Web.Script.Serialization;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using System.Windows.Media.Imaging;
using SplifyWin;
namespace Mcrf.Wpf {
 internal sealed class DeploymentConnection {
  public string Link,Name,Protocol,Transport,Endpoint,Secret,SecretLabel;public string[] Calls=new string[0];
  internal static Dictionary<string,object> DecodeOpenFlux(string link){
   const string prefix="openflux://v1/";if(!link.StartsWith(prefix,StringComparison.Ordinal))throw new FormatException("Неизвестный формат OpenFlux.");string payload=link.Substring(prefix.Length).Split('#')[0];if(payload.Length>65536)throw new FormatException("Слишком большая ссылка OpenFlux.");payload=payload.Replace('-','+').Replace('_','/');var bytes=Convert.FromBase64String(payload.PadRight((payload.Length+3)/4*4,'='));
   using(var input=new MemoryStream(bytes))using(var deflate=new DeflateStream(input,CompressionMode.Decompress))using(var output=new MemoryStream()){var buffer=new byte[4096];int count;while((count=deflate.Read(buffer,0,buffer.Length))>0){if(output.Length+count>262144)throw new FormatException("Слишком большой профиль OpenFlux.");output.Write(buffer,0,count);}var value=new JavaScriptSerializer{MaxJsonLength=262144}.Deserialize<Dictionary<string,object>>(new UTF8Encoding(false,true).GetString(output.ToArray()));if(value==null)throw new FormatException("Пустой профиль OpenFlux.");return value;}
  }
  internal static DeploymentConnection Read(string link){var node=Links.Read(link).Single();var result=new DeploymentConnection{Link=link,Name=node.Name,Protocol=node.Protocol,Transport=node.TransportLabel,Endpoint=node.Port>0?node.Host+":"+node.Port:node.Host};
   if(node.Protocol=="openflux"){var config=DecodeOpenFlux(link);object value;if(config.TryGetValue("name",out value)&&!String.IsNullOrWhiteSpace(Convert.ToString(value)))result.Name=Convert.ToString(value);if(config.TryGetValue("secret",out value)){result.Secret=Convert.ToString(value);result.SecretLabel="Ключ шифрования OpenFlux";}var transports=config.ContainsKey("transports")?config["transports"] as IEnumerable:null;var rows=transports==null?new Dictionary<string,object>[0]:transports.Cast<object>().Select(t=>t as Dictionary<string,object>).ToArray();if(rows.Length==0||rows.Length>32||rows.Any(t=>t==null))throw new FormatException("В профиле OpenFlux нет корректных транспортов.");result.Transport=String.Join(" / ",rows.Select(t=>t.ContainsKey("type")?Convert.ToString(t["type"]):""));result.Endpoint=String.Join(" / ",rows.Select(t=>t.ContainsKey("dial")?Convert.ToString(t["dial"]):t.ContainsKey("url")?Convert.ToString(t["url"]):""));if(String.IsNullOrWhiteSpace(result.Secret)||result.Secret.Length<16||result.Secret.Length>8192||result.Secret.Any(Char.IsControl))throw new FormatException("OpenFlux не выдал корректный ключ шифрования.");}
   else if(node.Protocol=="qwdtt"){var profile=QwdttProfile.Parse(link);result.Secret=profile.Password;result.SecretLabel="Пароль QWDTT";result.Calls=profile.Hashes.Select(h=>"https://vk.ru/call/join/"+h).ToArray();}
   else if(node.Protocol=="wdtt"||node.Protocol=="csqtt"){var profile=TurnProfile.Parse(link);result.Secret=profile.Password;result.SecretLabel="Пароль "+profile.Protocol.ToUpperInvariant();result.Calls=profile.Hashes.Select(h=>"https://vk.ru/call/join/"+h).ToArray();}
   else if(node.Protocol=="vless"){result.Secret=Uri.UnescapeDataString(new Uri(link).UserInfo);result.SecretLabel="UUID VLESS";}
   return result;
  }
 }
 internal static class ConnectionQr {
  [System.Runtime.CompilerServices.MethodImpl(System.Runtime.CompilerServices.MethodImplOptions.NoInlining)]
  internal static byte[] Png(string link){if(Encoding.UTF8.GetByteCount(link)>2331)throw new InvalidOperationException("Ссылка слишком длинная для QR. Скопируйте ключ подключения.");using(var generator=new QRCoder.QRCodeGenerator())using(var data=generator.CreateQrCode(link,QRCoder.QRCodeGenerator.ECCLevel.M))using(var code=new QRCoder.PngByteQRCode(data))return code.GetGraphic(6);}
  internal static BitmapSource Bitmap(string link){using(var stream=new MemoryStream(Png(link))){var image=new BitmapImage();image.BeginInit();image.CacheOption=BitmapCacheOption.OnLoad;image.StreamSource=stream;image.EndInit();image.Freeze();return image;}}
 }
 public sealed partial class MainWindow {
  DeploymentPanelRegistry deploymentPanels;
  bool deploymentResultUnsaved;
  void RenameSavedDeployment(DeploymentPanel saved){
   if(saved==null||busy||closing||closeRequested)return;string id=saved.Id,expected=DeploymentPanelStore.Identity(saved);bool closed=false;
   var dialog=Dialog("Название панели / сервера",520,340);dialog.MaxHeight=dialog.Height;dialog.SizeToContent=SizeToContent.Height;var text=new TextBox{Name="deployPanelName",Text=saved.Name??"",MaxLength=80,Margin=new Thickness(0,0,0,12)};var content=new StackPanel{Margin=new Thickness(20)};content.Children.Add(text);var feedback=Text("",12);feedback.Name="deployPanelNameFeedback";feedback.Visibility=Visibility.Collapsed;feedback.MaxHeight=56;content.Children.Add(feedback);
   var save=Button("Сохранить",()=>{if(closed||busy||closing||closeRequested)return;try{DeploymentPanelStore.Rename(root,deploymentPanels,id,expected,text.Text);}catch(Exception ex){feedback.Text=FailureExplanations.FromException(ex);feedback.Visibility=Visibility.Visible;return;}dialog.DialogResult=true;Page("Автодеплой");});save.Name="deployPanelNameSave";var actions=Toolbar(save,Button("Отмена",()=>dialog.DialogResult=false));actions.Margin=new Thickness(0);content.Children.Add(actions);dialog.Closed+=(s,e)=>closed=true;dialog.Content=content;dialog.ShowDialog();
  }
  void LoadDeploymentResult(){if(deploymentPanels!=null)return;try{deploymentPanels=DeploymentPanelStore.Load(root);deploymentResult=deploymentPanels.Selected==null?null:deploymentPanels.Selected.Result;}catch(Exception){deploymentPanels=null;status.Text="Реестр панелей не удалось прочитать. Исходный файл сохранён; не перезаписывайте его новой установкой.";throw;}}
  string TrySaveDeploymentResult(){try{SaveDeploymentResult();return null;}catch(Exception ex){if(!(ex is IOException)&&!(ex is UnauthorizedAccessException)&&!(ex is System.Security.SecurityException)&&!(ex is System.Security.Cryptography.CryptographicException))throw;deploymentResultUnsaved=true;return "Результат операции получен, но не сохранён на этом компьютере. Повторите сохранение или скопируйте реквизиты до закрытия программы.";}}
  void SaveDeploymentResult(){DeploymentPanelStore.Save(root,deploymentPanels);deploymentResultUnsaved=false;}
  int SuggestedDeploymentPort(int preferred){var used=new HashSet<int>();if(deploymentPanels!=null&&deploymentPanels.Selected!=null){string host=deploymentPanels.Selected.Host;foreach(var panel in deploymentPanels.Panels.Where(p=>String.Equals(p.Host,host,StringComparison.OrdinalIgnoreCase))){used.Add(new Uri(panel.Result.panel_url).Port);used.Add(panel.SshPort);foreach(string link in panel.Result.connections??new string[0]){var node=Links.Read(link).Single();if(node.Port>0)used.Add(node.Port);if(node.Protocol=="wdtt")used.Add(TurnProfile.Parse(link).WireGuardPort);}}}used.Add(56003);while(used.Contains(preferred)&&preferred<65535)preferred++;if(used.Contains(preferred))throw new InvalidOperationException("Не найден свободный порт. Укажите его вручную.");return preferred;}
  void FillDeploymentConnections(StackPanel section){
   section.Children.Clear();section.Visibility=deploymentResult==null||deploymentResult.connections==null||deploymentResult.connections.Length==0?Visibility.Collapsed:Visibility.Visible;if(section.Visibility!=Visibility.Visible)return;
   section.Children.Add(Text("Готовые подключения",18));section.Children.Add(Text("Ключ, QR и данные подключения — в кнопке «Открыть». Эти данные дают доступ к серверу: передавайте их только доверенным людям.",12));
   foreach(string link in deploymentResult.connections){var profile=DeploymentConnection.Read(link);var row=new StackPanel{Name="deployConnectionRow",Margin=new Thickness(0,0,0,14)};
    row.Children.Add(new TextBlock{Name="deployConnectionName",Text=profile.Name,FontSize=14,TextTrimming=TextTrimming.CharacterEllipsis,ToolTip=profile.Name});
    string description=profile.Transport+" · "+profile.Endpoint+(profile.Calls.Length==0?"":" · звонков VK: "+profile.Calls.Length);
    row.Children.Add(new TextBlock{Name="deployConnectionEndpoint",Text=description,FontSize=12,Foreground=Brush(CompactSecondary),TextWrapping=TextWrapping.Wrap,MaxHeight=40,TextTrimming=TextTrimming.CharacterEllipsis,ToolTip=description,Margin=new Thickness(0,4,0,8)});
    var show=Button("Открыть · ключ / QR",()=>ConnectionPopup(profile).ShowDialog());show.Name="deployShowConnection";show.Tag=profile;row.Children.Add(CompactTools(show,AsyncButton("Копировать",()=>CopyText(profile.Link))));section.Children.Add(row);
   }
  }
  Window ConnectionPopup(DeploymentConnection profile){return BuildConnectionPopup(profile,CopyText,ImportDeploymentConnection);}
 }
}
