using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  CancellationTokenSource deploymentCancellation;DeployResult deploymentResult;
  bool TrustDeploymentHost(string host,string fingerprint){return Dispatcher.Invoke(()=>{string path=Path.Combine(root,"deployment","known-hosts.json");var json=new System.Web.Script.Serialization.JavaScriptSerializer();var known=File.Exists(path)?json.Deserialize<Dictionary<string,string>>(File.ReadAllText(path)):new Dictionary<string,string>();string previous;if(known.TryGetValue(host,out previous)){if(previous!=fingerprint)throw new InvalidOperationException("SSH-ключ сервера изменился. Установка остановлена: сверьте ключ на сервере.");return true;}if(!Confirm("Первое SSH-подключение к "+host+"\n\nОтпечаток: "+fingerprint+"\n\nСверьте его с сервером или данными провайдера. Доверять этому ключу?","Проверка сервера"))return false;known[host]=fingerprint;AppUpdates.AtomicText(path,json.Serialize(known));return true;});}
  void DeploymentPage(){
   LoadDeploymentResult();var saved=deploymentPanels.Selected;
   var panel=new DockPanel();var content=new StackPanel{Name="deployPageContent"};
   var picker=new ComboBox{Name="deploySavedPanels",ItemsSource=new object[]{"Новая установка"}.Concat(deploymentPanels.Panels.Cast<object>()).ToArray(),SelectedItem=(object)saved??"Новая установка"};
   content.Children.Add(DeployField("Панели и серверы",picker));
   picker.SelectionChanged+=(s,e)=>{var chosen=picker.SelectedItem as DeploymentPanel;deploymentPanels.SelectedId=chosen==null?null:chosen.Id;deploymentResult=chosen==null?null:chosen.Result;SaveDeploymentResult();Page("Автодеплой");};
   if(saved!=null){
    var summary=Text("Сервер: "+saved.SshUser+"@"+saved.Host+":"+saved.SshPort+" · "+saved.Result.panel_url+"\nПодключений: "+(saved.Result.connections??new string[0]).Length+". Панель уже установлена — повторная установка не нужна.",12);summary.Margin=new Thickness(0,10,0,10);content.Children.Add(summary);
    content.Children.Add(Toolbar(Button("Открыть панель",()=>System.Diagnostics.Process.Start(new System.Diagnostics.ProcessStartInfo(saved.Result.panel_url){UseShellExecute=true})),AsyncButton("Проверить доступность",async()=>{await Deployment.CheckPanel(saved,CancellationToken.None);status.Text="Панель доступна; HTTPS-сертификат совпадает с сохранённым.";}),Button("Переименовать",()=>RenameSavedDeployment(saved))));
   }
   var form=new StackPanel{Name="deployForm"};content.Children.Add(form);
   form.Children.Add(Text(saved==null?"Установка на ваш сервер · Debian / Ubuntu. Нужен root или sudo без пароля. Установка меняет пакеты и сервисы; автоматического отката нет.":"Новое подключение в выбранной панели. SSH нужен для проверки свободных портов и подсетей; установщик не запускается.",12));
   var recipe=new ComboBox{Name="deployRecipe",ItemsSource=DeployRecipe.All,SelectedItem=saved==null?DeployRecipe.All[0]:(DeployRecipe.All.FirstOrDefault(r=>r.Id==saved.RecipeId)??DeployRecipe.All[0]),IsEnabled=saved==null||saved.RecipeId=="unknown"};
   var host=new TextBox{Name="deployHost",Text=saved==null?"":saved.Host,IsReadOnly=saved!=null};var port=new TextBox{Name="deploySshPort",Text=(saved==null?22:saved.SshPort).ToString()};var user=new TextBox{Name="deploySshUser",Text=saved==null?"root":saved.SshUser};
   var auth=new ComboBox{Name="deployAuth",ItemsSource=new[]{"Пароль сервера","SSH-ключ"},SelectedIndex=0};var password=new PasswordBox{Name="deployPassword"};var key=new TextBox{Name="deployKeyPath",IsReadOnly=true};var keyPassword=new PasswordBox{Name="deployKeyPassword",ToolTip="Пароль SSH-ключа, если задан."};
   var file=Button("Выбрать ключ",()=>{var dialog=new Microsoft.Win32.OpenFileDialog{Filter="SSH-ключ|*.*"};if(dialog.ShowDialog(this)==true)key.Text=dialog.FileName;});file.Name="deployKeyPicker";file.Margin=new Thickness(12,0,0,0);var keyLine=new DockPanel();DockPanel.SetDock(file,Dock.Right);keyLine.Children.Add(file);keyLine.Children.Add(key);
   form.Children.Add(DeployFields(new[]{DeployField("Панель",recipe),DeployField("Адрес сервера",host),DeployField("SSH-порт",port),DeployField("SSH-логин",user)},new[]{180d,-1d,90d,140d}));
   var passGroup=DeployField("Пароль сервера",password);var keyGroup=DeployField("Файл SSH-ключа",keyLine);var keyPassGroup=DeployField("Пароль ключа",keyPassword);var credentials=new Grid();credentials.ColumnDefinitions.Add(new ColumnDefinition());credentials.Children.Add(passGroup);credentials.Children.Add(keyGroup);form.Children.Add(DeployFields(new[]{DeployField("Авторизация",auth),(UIElement)credentials,keyPassGroup},new[]{180d,-1d,140d}));
   Action mode=()=>{passGroup.Visibility=auth.SelectedIndex==0?Visibility.Visible:Visibility.Collapsed;keyGroup.Visibility=keyPassGroup.Visibility=auth.SelectedIndex==1?Visibility.Visible:Visibility.Collapsed;};auth.SelectionChanged+=(s,e)=>mode();mode();
   var panelPort=new TextBox{Name="deployPanelPort",Text=(saved==null?8443:new Uri(saved.Result.panel_url).Port).ToString(),IsReadOnly=saved!=null};var domain=new TextBox{Name="deployDomain",ToolTip="Домен без https://; необязательно."};var panelUser=new TextBox{Name="deployPanelUser",Text=saved==null?"admin":saved.Result.username,IsReadOnly=saved!=null};
   var installFields=DeployFields(new[]{DeployField("Порт панели",panelPort),DeployField("Домен панели · необязательно",domain),DeployField("Логин панели",panelUser)},new[]{180d,-1d,140d});installFields.Visibility=saved==null?Visibility.Visible:Visibility.Collapsed;form.Children.Add(installFields);
   form.Children.Add(Text("SSH-пароль и ключ не сохраняются. Панель, сервер и готовые подключения хранятся зашифрованными.",12));
   Func<DeployProvisionOptions> readProfiles;form.Children.Add(DeploymentProfiles(recipe,out readProfiles,saved!=null));
   form.Children.Add(Text("Занятый порт автоматически заменим свободным. Существующие подключения не изменяются.",12));
   var log=new TextBox{Name="deployProgress",IsReadOnly=true,AcceptsReturn=true,Height=120,TextWrapping=TextWrapping.Wrap,HorizontalScrollBarVisibility=ScrollBarVisibility.Disabled,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,FontFamily=new System.Windows.Media.FontFamily("Consolas")};
   var progressGroup=new StackPanel{Name="deployProgressSection",Visibility=Visibility.Collapsed,Margin=new Thickness(0,12,0,0)};progressGroup.Children.Add(Text("Журнал операции",16));progressGroup.Children.Add(log);content.Children.Add(progressGroup);
   var result=new TextBox{Name="deployResult",IsReadOnly=true,AcceptsReturn=true,MinHeight=84,MaxHeight=160,TextWrapping=TextWrapping.Wrap,HorizontalScrollBarVisibility=ScrollBarVisibility.Disabled,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,Text=deploymentResult==null?"":DeploymentResultText(deploymentResult)};
   var resultGroup=new StackPanel{Name="deployResultSection",Visibility=deploymentResult==null?Visibility.Collapsed:Visibility.Visible,Margin=new Thickness(0,12,0,0)};resultGroup.Children.Add(Text("Реквизиты панели",16));resultGroup.Children.Add(result);content.Children.Add(resultGroup);
   var connectionSection=new StackPanel{Name="deployConnectionsSection",Margin=new Thickness(0,12,0,0)};FillDeploymentConnections(connectionSection);content.Children.Add(connectionSection);
   var execute=AsyncButton(saved==null?"Установить панель":"Создать подключение",async()=>{
    int sshPort,panelNumber;if(!Int32.TryParse(port.Text,out sshPort)||!Int32.TryParse(panelPort.Text,out panelNumber))throw new FormatException("Укажите числовые порты.");
    var choice=(DeployRecipe)recipe.SelectedItem;var target=new DeployTarget{Host=host.Text.Trim(),Port=sshPort,User=user.Text.Trim(),Password=auth.SelectedIndex==0?password.Password:null,KeyFile=auth.SelectedIndex==1?key.Text:null,KeyPassword=keyPassword.Password,PanelPort=panelNumber,Domain=domain.Text.Trim(),PanelUser=panelUser.Text.Trim(),Provision=readProfiles()};target.Validate();target.Provision.Validate(choice.Id,target);
    string question=saved==null?"Установить "+choice.Name+" на "+target.Host+"? Установщик изменяет сервер. Существующая панель не перезаписывается.":"Создать новое подключение в "+choice.Name+" на "+saved.Host+"?\n\nПанель не переустанавливается; старые подключения сохраняются. Для нового подберём свободные порты и отдельную подсеть.";
    if(!Confirm(question,saved==null?"Подтверждение автодеплоя":"Новое подключение"))return;
    int requestedCount=choice.Id=="3xui"?(target.Provision.tcp?1:0)+(target.Provision.grpc?1:0):1;
    if(saved!=null&&(saved.Result.connections??new string[0]).Length+requestedCount>256)throw new InvalidOperationException("В панели можно сохранить до 256 подключений.");
    if(saved==null&&deploymentPanels.Panels.Count>=64)throw new InvalidOperationException("Уже сохранено 64 панели.");
    if(saved!=null&&saved.RecipeId=="unknown"){saved.RecipeId=choice.Id;SaveDeploymentResult();}
    deploymentCancellation=new CancellationTokenSource();form.IsEnabled=false;picker.IsEnabled=false;progressGroup.Visibility=Visibility.Visible;log.Text="";
    try{
     Action<string> say=line=>Dispatcher.BeginInvoke(new Action(()=>{if(log.IsLoaded){if(log.Text.Length>50000)log.Text=log.Text.Substring(log.Text.Length-30000);log.AppendText(line+Environment.NewLine);log.ScrollToEnd();}}));
     var fresh=saved==null?await Deployment.Install(session.Core,choice,target,TrustDeploymentHost,say,deploymentCancellation.Token):await Deployment.AddConnections(saved,target,TrustDeploymentHost,say,deploymentCancellation.Token);
     var stored=DeploymentPanelStore.Remember(deploymentPanels,choice,target,fresh);deploymentResult=stored.Result;string warning=TrySaveDeploymentResult();result.Text=DeploymentResultText(deploymentResult);resultGroup.Visibility=Visibility.Visible;FillDeploymentConnections(connectionSection);RefreshDeploymentActions();
     status.Text=warning??(fresh.provision_complete?"Подключение готово. Панель, сервер и все ссылки сохранены.":"Панель сохранена; создание подключения не завершено. "+fresh.provision_error);
     if(warning==null)Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.ContextIdle,new Action(()=>Page("Автодеплой")));
    }finally{password.Clear();keyPassword.Clear();deploymentCancellation.Dispose();deploymentCancellation=null;form.IsEnabled=true;picker.IsEnabled=true;}
   });execute.Name=saved==null?"deployInstallPanel":"deployCreateConnection";Accent(execute);
   var stop=Button("Остановить",()=>{if(deploymentCancellation!=null)deploymentCancellation.Cancel();},true);
   var copy=AsyncButton("Копировать реквизиты",()=>{if(deploymentResult==null)throw new InvalidOperationException("Нет сохранённой панели.");return CopyText(DeploymentResultText(deploymentResult));});
   var import=AsyncButton("Добавить подключения",async()=>{if(deploymentResult==null||deploymentResult.connections==null||deploymentResult.connections.Length==0)throw new InvalidOperationException("Пока нет готовых подключений.");await ImportServerText(String.Join("\n",deploymentResult.connections));});
   Action refresh=()=>{import.Visibility=deploymentResult!=null&&(deploymentResult.connections??new string[0]).Length>0?Visibility.Visible:Visibility.Collapsed;copy.IsEnabled=deploymentResult!=null;};refreshDeploymentActions=refresh;refresh();
   var footer=Toolbar(execute,stop,copy,import);footer.Margin=new Thickness(0,12,0,0);DockPanel.SetDock(footer,Dock.Bottom);panel.Children.Add(footer);
   panel.Children.Add(new ScrollViewer{Name="deploymentScroll",Content=content,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,HorizontalScrollBarVisibility=ScrollBarVisibility.Disabled,Padding=new Thickness(0,0,12,0)});body.Children.Add(Card(panel));
  }
  Action refreshDeploymentActions;
  void RefreshDeploymentActions(){if(refreshDeploymentActions!=null)refreshDeploymentActions();}
  static StackPanel DeployField(string label,UIElement control){var element=control as FrameworkElement;if(element!=null){element.Margin=new Thickness(0);if(element is Control&&!(element is ScrollViewer)){element.Height=32;((Control)element).VerticalContentAlignment=VerticalAlignment.Center;}var dock=element as DockPanel;if(dock!=null)foreach(FrameworkElement child in dock.Children){child.Margin=new Thickness(child.Margin.Left,0,child.Margin.Right,0);if(child is Control)child.Height=dock.Children.OfType<TextBox>().Any(t=>t.AcceptsReturn)?56:32;}}var field=new StackPanel();var caption=Text(label,12);caption.Margin=new Thickness(0,0,0,4);field.Children.Add(caption);field.Children.Add(control);return field;}
  static Grid DeployFields(UIElement[] fields,double[] widths){var row=new Grid{Margin=new Thickness(0,4,0,8)};for(int i=0;i<fields.Length;i++){if(i>0)row.ColumnDefinitions.Add(new ColumnDefinition{Width=new GridLength(12)});row.ColumnDefinitions.Add(new ColumnDefinition{Width=widths[i]<0?new GridLength(1,GridUnitType.Star):new GridLength(widths[i])});Grid.SetColumn(fields[i],i*2);row.Children.Add(fields[i]);}return row;}
  static string DeploymentResultText(DeployResult r){return "Панель: "+r.panel_url+"\nЛогин: "+r.username+"\nПароль: "+r.password+(String.IsNullOrEmpty(r.certificate_sha256)?"":"\nSHA-256 сертификата: "+r.certificate_sha256)+(r.connections==null||r.connections.Length==0?"\nПодключения пока не созданы.":"\nГотовых подключений: "+r.connections.Length)+(String.IsNullOrEmpty(r.provision_error)?"":"\nНастройка подключения: "+r.provision_error);}
 }
}
