using System;
using System.Collections.Generic;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  async Task<bool> ImportDeploymentConnection(string link){bool? imported=null;await Run(async()=>{imported=await ImportServerText(link);});if(!imported.HasValue)throw new InvalidOperationException(status.Text);return imported.Value;}
  Window BuildConnectionPopup(DeploymentConnection profile,Func<string,Task> copy,Func<string,Task<bool>> import){
   if(profile==null||copy==null||import==null)throw new ArgumentException("Не заданы действия подключения.");
   var window=Dialog("Подключение · "+profile.Name,440,640);bool closed=false,pending=false,writing=false;var actions=new List<Button>();var panel=new DockPanel{Margin=new Thickness(16)};
   var feedback=Text("",12);feedback.Name="connectionActionFeedback";feedback.Text="";feedback.Visibility=Visibility.Collapsed;feedback.MaxHeight=64;
   Func<string,string,Func<Task<string>>,bool,Button> action=(label,name,work,mutating)=>{var button=Button(label,async()=>{if(closed||pending||busy||closing||closeRequested)return;pending=true;writing=mutating;foreach(var item in actions)item.IsEnabled=false;feedback.Text="Выполняем…";feedback.Visibility=Visibility.Visible;try{string message=await work();if(!closed)feedback.Text=message;}catch(Exception ex){if(!closed)feedback.Text=FailureExplanations.FromException(ex);}finally{pending=false;writing=false;if(!closed)foreach(var item in actions)item.IsEnabled=true;}});button.Name=name;actions.Add(button);return button;};
   var copyLink=action("Копировать подключение","copyDeploymentConnection",async()=>{await copy(profile.Link);return "Скопировано.";},false);
   var add=action("Добавить в VPN","importDeploymentConnection",async()=>await import(profile.Link)?"Подключение добавлено в VPN.":"Добавление отменено. Данные не изменены.",true);
   var close=Button("Закрыть",()=>window.Close());close.Name="closeDeploymentConnection";
   var footer=new StackPanel();footer.Children.Add(feedback);footer.Children.Add(CompactTools(copyLink,add,close));DockPanel.SetDock(footer,Dock.Bottom);panel.Children.Add(footer);
   var fields=new StackPanel();fields.Children.Add(Text(profile.Transport+" · "+profile.Endpoint,14));fields.Children.Add(Text("Ключ подключения · ссылка для импорта",13));var share=new TextBox{Name="connectionShareText",Text="••••••••",IsReadOnly=true,AcceptsReturn=true,TextWrapping=TextWrapping.Wrap,MinHeight=38,MaxHeight=140,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,HorizontalScrollBarVisibility=ScrollBarVisibility.Disabled};fields.Children.Add(share);bool keyShown=false;Button revealKey=null;revealKey=Button("Показать ключ",()=>{if(closed||pending||closing||closeRequested)return;keyShown=!keyShown;share.Text=keyShown?profile.Link:"••••••••";share.MinHeight=keyShown?90:38;revealKey.Content=keyShown?"Скрыть ключ":"Показать ключ";});revealKey.Name="revealDeploymentConnection";actions.Add(revealKey);fields.Children.Add(revealKey);window.Closed+=(s,e)=>share.Text="••••••••";
   if(!String.IsNullOrEmpty(profile.Secret)){fields.Children.Add(Text(profile.SecretLabel,13));var secret=new TextBox{Name="connectionSecretText",Text="••••••••",IsReadOnly=true,MinHeight=32,HorizontalScrollBarVisibility=ScrollBarVisibility.Auto};fields.Children.Add(secret);bool secretShown=false;Button reveal=null;reveal=Button("Показать пароль",()=>{if(closed||pending||closing||closeRequested)return;secretShown=!secretShown;secret.Text=secretShown?profile.Secret:"••••••••";reveal.Content=secretShown?"Скрыть пароль":"Показать пароль";});reveal.Name="revealConnectionSecret";actions.Add(reveal);fields.Children.Add(reveal);window.Closed+=(s,e)=>secret.Text="••••••••";fields.Children.Add(action("Копировать пароль","copyConnectionSecret",async()=>{await copy(profile.Secret);return "Скопировано.";},false));}
   if(profile.Calls.Length>0){fields.Children.Add(Text("Звонки VK · "+profile.Calls.Length,13));fields.Children.Add(new TextBox{Name="connectionVkCalls",Text=String.Join("\n",profile.Calls),IsReadOnly=true,AcceptsReturn=true,TextWrapping=TextWrapping.Wrap,Height=100,VerticalScrollBarVisibility=ScrollBarVisibility.Auto});}
   fields.Children.Add(Text("QR содержит полную ссылку и данные доступа. Передавайте их только доверенным людям. Генерация выполняется локально.",12));
   try{var qr=new Image{Name="connectionQr",Source=ConnectionQr.Bitmap(profile.Link),Width=240,Height=240,Stretch=Stretch.Uniform};RenderOptions.SetBitmapScalingMode(qr,BitmapScalingMode.NearestNeighbor);fields.Children.Add(new Border{Name="connectionQrSurface",Background=Brushes.White,Padding=new Thickness(6),HorizontalAlignment=HorizontalAlignment.Center,Child=qr});}catch(InvalidOperationException ex){fields.Children.Add(Text(ex.Message,12));}
   panel.Children.Add(new ScrollViewer{Name="connectionDetailsScroll",Content=fields,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,HorizontalScrollBarVisibility=ScrollBarVisibility.Disabled,Margin=new Thickness(0,0,0,8)});
   window.Closing+=(s,e)=>{if(writing){e.Cancel=true;feedback.Text="Дождитесь завершения добавления подключения.";feedback.Visibility=Visibility.Visible;}};window.Closed+=(s,e)=>closed=true;window.Content=panel;return window;
  }
 }
}
