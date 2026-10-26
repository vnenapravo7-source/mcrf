using System;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  void CompactEditServer(ServerNode node){
   if(node==null||busy||closing||closeRequested)return;
   if(BsServers.Is(node)){QueueVkTurnEditor(node);return;}
   string id=node.Id;var window=Dialog("Параметры сервера",440,620);window.MaxHeight=window.Height;window.SizeToContent=SizeToContent.Height;var panel=new StackPanel{Margin=new Thickness(20,12,20,20)};
   var name=new TextBox{Name="serverParameterName",Text=ServerIdentity.Display(node.Name)};panel.Children.Add(Field("Название",name));var draft=new ServerParameterDraft(node);var readers=new System.Collections.Generic.List<Action>();
   foreach(var parameter in draft.Values.ToArray()){string key=parameter.Key;bool secret=key=="auth"||key.IndexOf("PrivateKey",StringComparison.OrdinalIgnoreCase)>=0||key.IndexOf("PresharedKey",StringComparison.OrdinalIgnoreCase)>=0;Control input;if(secret){var password=new PasswordBox{Name="serverParameter"+readers.Count,Password=parameter.Value};input=password;readers.Add(()=>draft.Values[key]=password.Password);}else{var text=new TextBox{Name="serverParameter"+readers.Count,Text=parameter.Value};input=text;readers.Add(()=>draft.Values[key]=text.Text.Trim());}input.ToolTip=draft.Labels[key];panel.Children.Add(Field(draft.Labels[key],input));}
   var feedback=Text("",11);feedback.Visibility=Visibility.Collapsed;panel.Children.Add(feedback);
   Button save=null;save=Button("Сохранить",async()=>{if(busy||closing||closeRequested)return;save.IsEnabled=false;try{if(String.IsNullOrWhiteSpace(name.Text))throw new FormatException("Укажите название.");foreach(var read in readers)read();string nextName=name.Text.Trim();var candidate=draft.Build(nextName);session.Core.ValidateImport(candidate);await session.ChangeConfiguration(()=>{var currentNode=session.State.Servers.FirstOrDefault(x=>x.Id==id);if(currentNode==null)throw new InvalidOperationException("Сервер уже удалён.");currentNode.Name=nextName;currentNode.Link=candidate.Link;currentNode.Protocol=candidate.Protocol;currentNode.Host=candidate.Host;currentNode.Port=candidate.Port;currentNode.Latency=-1;});window.Close();if(refreshServer!=null)refreshServer();if(refreshHome!=null)refreshHome();}catch(Exception ex){feedback.Text=FailureExplanations.UserMessage(JournalStyle.Redact(ex.Message));feedback.Visibility=Visibility.Visible;}finally{save.IsEnabled=true;}});
   panel.Children.Add(save);panel.Children.Add(Button("Отмена",()=>window.Close()));window.Content=panel;ApplyCompactControlPalette(panel);window.ShowDialog();
  }
 }
}
