using System;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Automation;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using System.Windows.Media.Animation;
using System.Windows.Shapes;
using SplifyWin;

namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  static readonly string[] CompactAdvancedPages={"Zapret & Byetube","VK TURN","WARP","Мастер настройки","События","Автодеплой","Генератор стратегий","Редактор стратегий","Результаты","Маршрутизация","Шаблоны игр"};

  bool IsCompactAdvancedPage(string name){return IsCompactSettingsPage(name)||name=="Обновления"||CompactAdvancedPages.Contains(name);}

  internal void StyleCompactDialog(Window dialog,Border surface){
   dialog.Foreground=Brush(CompactPrimary);surface.Background=Brush(CompactLight?"#F5F8FA":"#141B24");surface.BorderBrush=Brush(CompactOutline);ApplyCompactControlPalette(surface);
   foreach(var button in CompactLogicalChildren<Button>(surface)){if(button.Name=="dialogClose"){button.Style=(Style)FindResource("BareButton");button.Foreground=Brush(CompactPrimary);}else if(button.Style==(Style)FindResource("PaletteChoice")||button.Style==(Style)FindResource("IconAction")||button.Style==(Style)FindResource("BareButton")){button.Foreground=Brush(CompactPrimary);}else StyleCompactButton(button);}
   foreach(var capture in CompactLogicalChildren<Border>(surface).Where(item=>item.Name=="captureStatus")){capture.Background=Brush("#CC102D24");foreach(var text in CompactLogicalChildren<TextBlock>(capture))text.Foreground=Brush("#83D7A8");}
  }

  async Task ToggleCompactTheme(){
   if(session==null)return;
   string previous=session.State.CompactTheme;
   await CompactPersist(()=>session.State.CompactTheme=previous=="light"?"dark":"light",()=>session.State.CompactTheme=previous,()=>{
    ApplyWorkspacePalette(false);
    Page(current);
   });
  }

  void UpdateCompactThemeButton(){
   if(compactThemeButton==null)return;
   var icon=new Grid{Width=20,Height=20};
   if(CompactLight){
    icon.Children.Add(new Ellipse{Width=8,Height=8,Stroke=Brush(CompactPrimary),StrokeThickness=1.6,HorizontalAlignment=HorizontalAlignment.Center,VerticalAlignment=VerticalAlignment.Center});
    for(int i=0;i<8;i++)icon.Children.Add(new Path{Data=Geometry.Parse("M10,1 L10,4"),Stroke=Brush(CompactPrimary),StrokeThickness=1.4,RenderTransform=new RotateTransform(i*45,10,10)});
   }else icon.Children.Add(new Path{Data=Geometry.Parse("M14.8,14.6 C10.1,16.5 5.2,13.2 5.2,8.2 C5.2,4.7 7.5,2.1 10.2,1.2 C8.8,4.8 10.1,9.2 14.8,10.5 C16.1,10.9 17.3,10.8 18.2,10.5 C17.6,12.1 16.5,13.6 14.8,14.6 Z"),Stroke=Brush(CompactPrimary),StrokeThickness=1.6,Fill=Brushes.Transparent});
   compactThemeButton.Content=icon;
   compactThemeButton.ToolTip=CompactLight?"Включить тёмную тему":"Включить светлую тему";
  }

  void CompactDashboardParity(){
   CompactPageChrome("");
   var stack=new StackPanel{Margin=new Thickness(0,0,0,8)};
   KeyboardNavigation.SetTabNavigation(stack,KeyboardNavigationMode.Continue);
   var connection=new Border{Name="compactConnection",Padding=new Thickness(14,10,14,12),Margin=new Thickness(0,0,0,10),Child=CompactConnectionControlsParity()};
   StyleCompactCard(connection,true);stack.Children.Add(connection);
   var mode=CompactModeControlsParity();mode.Margin=new Thickness(0,0,0,12);stack.Children.Add(mode);
   stack.Children.Add(CompactServerControlsParity());
   body.Children.Add(CompactScroll(stack));
   FinishCompactPagePalette();
  }

  Path CompactScenePath(string data,double thickness=1.6,double opacity=1){
   return new Path{Data=Geometry.Parse(data),Stroke=Brush(CompactVisualAccent),StrokeThickness=thickness,Fill=Brushes.Transparent,Opacity=opacity,StrokeStartLineCap=PenLineCap.Round,StrokeEndLineCap=PenLineCap.Round,StrokeLineJoin=PenLineJoin.Round};
  }

  UIElement CompactPowerSceneParity(string phase){
   var canvas=new Canvas{Width=120,Height=112};
   string style=session.State.CompactConnectionStyle;
   bool animate=!diagnostic&&session.State.CompactAnimations&&SystemParameters.ClientAreaAnimation;
   if(phase=="stopping"){
    var shackle=CompactScenePath("M39,48 V34 C39,15 81,15 81,34 V48",3,.82);canvas.Children.Add(shackle);
    var body=CompactScenePath("M30,45 L90,45 L90,96 L30,96 Z",2.2);canvas.Children.Add(body);
    var dial=new Grid{Width=34,Height=34};dial.Children.Add(new Ellipse{Stroke=Brush(CompactVisualAccent),StrokeThickness=2});dial.Children.Add(CompactScenePath("M17,5 V17 L25,22",2));Canvas.SetLeft(dial,43);Canvas.SetTop(dial,55);canvas.Children.Add(dial);
    if(animate){var rotate=new RotateTransform(0,17,17);dial.RenderTransform=rotate;rotate.BeginAnimation(RotateTransform.AngleProperty,new DoubleAnimation(0,300,TimeSpan.FromMilliseconds(760)){EasingFunction=new CubicEase{EasingMode=EasingMode.EaseInOut}});shackle.RenderTransformOrigin=new Point(.5,1);shackle.BeginAnimation(OpacityProperty,new DoubleAnimation(.82,.25,TimeSpan.FromMilliseconds(760)));}
    return new Viewbox{Child=canvas,Stretch=Stretch.Uniform};
   }
   if(style=="classic"){
    canvas.Children.Add(CompactScenePath("M25,20 H95 V92 H25 Z M25,38 H95 M42,20 V92",1.5,.7));
    var power=CompactScenePath("M60,43 V63 M48,48 C35,60 42,82 60,82 C78,82 85,60 72,48",3);canvas.Children.Add(power);
    if(phase=="starting"){canvas.Children.Add(CompactScenePath("M35,102 C48,108 72,108 85,102",1.5,.58));if(animate){var pulse=new DoubleAnimation(.35,1,TimeSpan.FromMilliseconds(540)){AutoReverse=true,RepeatBehavior=RepeatBehavior.Forever};power.BeginAnimation(OpacityProperty,pulse);}}
    return new Viewbox{Child=canvas,Stretch=Stretch.Uniform};
   }
   if(style=="magnet"){
    var magnet=CompactScenePath("M20,25 V60 C20,101 88,101 88,60 V25 M20,25 H42 V58 C42,72 66,72 66,58 V25 H88",7,.9);canvas.Children.Add(magnet);
    var mark=CompactScenePath("M93,42 L105,54 L93,66 L81,54 Z M87,54 H99",2);canvas.Children.Add(mark);
    if(phase=="starting"){canvas.Children.Add(CompactScenePath("M78,42 H88 M76,54 H88 M78,66 H88",1.4,.62));if(animate){var move=new TranslateTransform();mark.RenderTransform=move;move.BeginAnimation(TranslateTransform.XProperty,new DoubleAnimation(14,-12,TimeSpan.FromMilliseconds(620)){AutoReverse=true,RepeatBehavior=RepeatBehavior.Forever,EasingFunction=new CubicEase{EasingMode=EasingMode.EaseInOut}});}}
    return new Viewbox{Child=canvas,Stretch=Stretch.Uniform};
   }
   string logo="M66,26 L78,26 L88,36 L80,44 L94,58 L80,72 L66,58 L52,72 L38,58 L52,44 L44,36 L54,26 L66,38 L78,26 M66,38 L80,52 L66,66 L52,52 Z";
   if(phase=="off"){
    var outline=CompactScenePath(logo,2,.84);outline.RenderTransform=new TranslateTransform(-6,8);canvas.Children.Add(outline);
    return new Viewbox{Child=canvas,Stretch=Stretch.Uniform};
   }
   var worker=CompactScenePath("M24,30 A8,8 0 1 0 40,30 A8,8 0 1 0 24,30 M28,38 L27,68 L18,94 M27,68 L42,94 M28,45 L17,61 M29,46 L47,56",2,.86);canvas.Children.Add(worker);
   var floor=CompactScenePath("M8,96 H112",1,.35);canvas.Children.Add(floor);
   var anvil=CompactScenePath("M68,75 H104 L96,82 H88 V94 H75 V82 H68 Z",1.8,.72);canvas.Children.Add(anvil);
   var emblem=CompactScenePath(logo,2,phase=="active"?1:.72);emblem.RenderTransform=new ScaleTransform(.62,.62,66,52);canvas.Children.Add(emblem);
   if(phase=="starting"){
    var hammer=CompactScenePath("M44,55 L58,28 M52,22 L68,30 L63,40 L47,32 Z",2.4);hammer.RenderTransformOrigin=new Point(.36,.74);canvas.Children.Add(hammer);
    var sparks=CompactScenePath("M61,58 L66,52 M64,62 L72,60 M58,55 L58,47",1.5,.75);canvas.Children.Add(sparks);
    if(animate){var swing=new RotateTransform(-24,47,55);hammer.RenderTransform=swing;swing.BeginAnimation(RotateTransform.AngleProperty,new DoubleAnimation(-24,28,TimeSpan.FromMilliseconds(420)){AutoReverse=true,RepeatBehavior=RepeatBehavior.Forever,EasingFunction=new CubicEase{EasingMode=EasingMode.EaseInOut}});sparks.BeginAnimation(OpacityProperty,new DoubleAnimation(0,.9,TimeSpan.FromMilliseconds(210)){AutoReverse=true,RepeatBehavior=RepeatBehavior.Forever});}
   }else if(phase=="active"){
    emblem.Fill=Brush(AlphaColor(CompactAccentColor,72));
    if(animate)emblem.BeginAnimation(OpacityProperty,new DoubleAnimation(.72,1,TimeSpan.FromMilliseconds(900)){AutoReverse=true,RepeatBehavior=RepeatBehavior.Forever});
   }
   return new Viewbox{Child=canvas,Stretch=Stretch.Uniform};
  }

  UIElement CompactConnectionControlsParity(){
   var panel=new StackPanel{VerticalAlignment=VerticalAlignment.Center};
   var sceneHost=new Border{Name="compactPowerSceneHost",Width=session.State.CompactConnectionStyle=="classic"?104:220,Height=session.State.CompactConnectionStyle=="classic"?104:164,HorizontalAlignment=HorizontalAlignment.Center,Background=Brushes.Transparent};AndroidConnectionScene androidScene=null;bool rendererUnavailable=false;if(session.State.CompactConnectionStyle!="classic"){androidScene=TryCreateConnectionScene(out rendererUnavailable);sceneHost.Child=androidScene;}
   var power=Button("",async()=>await Run(async()=>{bool stopping=session.Core.Running;stoppingConnection=stopping;if(refreshHome!=null)refreshHome();try{await ToggleVpnWithPresentation();RecordEvent(session.Core.Running?(session.State.Mode=="proxy"?"Прокси запущен":"VPN запущен"):"Подключение отключено");}finally{stoppingConnection=false;if(refreshHome!=null)refreshHome();}}));
   power.Name="compactPower";power.Opacity=1;power.Style=(Style)FindResource("BareButton");power.Padding=new Thickness(0);power.Margin=new Thickness(0);power.Background=Brushes.Transparent;power.Content=sceneHost;AutomationProperties.SetName(power,"Подключить или отключить MCRF");panel.Children.Add(power);
   var state=Text("Отключено",18);state.Name="compactConnectionState";state.FontWeight=FontWeights.SemiBold;state.TextAlignment=TextAlignment.Center;state.Margin=new Thickness(0,0,0,4);panel.Children.Add(state);
   var target=Text("",11);target.Name="compactConnectionTarget";target.TextAlignment=TextAlignment.Center;target.Foreground=Brush(CompactSecondary);target.Visibility=Visibility.Collapsed;panel.Children.Add(target);
   var detail=Text("Нажмите, чтобы подключиться",11);detail.Name="compactConnectionHint";detail.TextAlignment=TextAlignment.Center;detail.Foreground=Brush(CompactSecondary);detail.Margin=new Thickness(6,0,6,6);detail.TextWrapping=TextWrapping.Wrap;panel.Children.Add(detail);
   var stats=new TextBlock{Name="compactConnectionStats",FontSize=10,TextAlignment=TextAlignment.Center,Foreground=Brush(CompactMuted),Margin=new Thickness(0,0,0,8)};panel.Children.Add(stats);
   var autoRow=new Grid{Name="compactAutoBsRow",Margin=new Thickness(2,4,2,0)};autoRow.ColumnDefinitions.Add(new ColumnDefinition());autoRow.ColumnDefinitions.Add(new ColumnDefinition{Width=GridLength.Auto});
   var autoLabel=new StackPanel{VerticalAlignment=VerticalAlignment.Center};autoLabel.Children.Add(new TextBlock{Text="Автоопределение БС",FontSize=11,Foreground=Brush(CompactSecondary)});autoRow.Children.Add(autoLabel);var cadenceValues=CompactCadenceValues(session.State.AutoBsIntervalSeconds,15);var cadence=new ComboBox{Name="compactAutoBsInterval",ItemsSource=cadenceValues.Select(CompactCadenceLabel).ToArray(),SelectedIndex=Math.Max(0,Array.IndexOf(cadenceValues,session.State.AutoBsIntervalSeconds)),Width=84,MinWidth=0,VerticalAlignment=VerticalAlignment.Center,Margin=new Thickness(0,0,8,0),ToolTip="Интервал проверки сети"};Grid.SetColumn(cadence,1);autoRow.ColumnDefinitions.Insert(1,new ColumnDefinition{Width=GridLength.Auto});autoRow.Children.Add(cadence);cadence.SelectionChanged+=async(s,e)=>{if(cadence.SelectedIndex<0||busy)return;int previous=session.State.AutoBsIntervalSeconds;await CompactPersist(()=>session.State.AutoBsIntervalSeconds=cadenceValues[cadence.SelectedIndex],()=>{cadence.SelectedIndex=Math.Max(0,Array.IndexOf(cadenceValues,previous));});};
   var autoBs=new CheckBox{Name="compactAutoBs",IsChecked=session.State.AutoDetectBs,Margin=new Thickness(0),VerticalAlignment=VerticalAlignment.Center};AutomationProperties.SetName(autoBs,"Автоопределение БС");autoBs.Click+=async(s,e)=>{bool wanted=autoBs.IsChecked==true;await Run(()=>CompactSetAutoBs(wanted),()=>autoBs.IsChecked=session.State.AutoDetectBs);autoBs.IsChecked=session.State.AutoDetectBs;};Grid.SetColumn(autoBs,2);autoRow.Children.Add(autoBs);panel.Children.Add(autoRow);
   refreshHome=()=>{
    bool pending=startingConnection||session.Core.Starting;bool active=session.Core.Running&&!pending&&session.State.VpnEnabled;
    UpdateConnectionClock();
    string phase=stoppingConnection?"stopping":pending?"starting":active?"active":"off";
    bool powerEnabled=!busy&&!closing&&!closeRequested&&!pending&&!stoppingConnection;string sceneKey=session.State.CompactConnectionStyle+":"+phase+":"+powerEnabled;
    if(androidScene!=null&&!String.IsNullOrWhiteSpace(androidScene.RendererError))rendererUnavailable=true;if(rendererUnavailable)power.ToolTip="Анимация недоступна — используется классическая кнопка подключения.";if(androidScene!=null&&!rendererUnavailable){androidScene.Present(session.State.CompactConnectionStyle=="magnet"?"magnet":"blueprint",phase=="starting"?"CONNECTING":phase=="active"?"CONNECTED":phase=="stopping"?"STOPPING":!String.IsNullOrWhiteSpace(session.Core.LastFailure)?"FAILED":"IDLE",session.State.CompactAnimations&&SystemParameters.ClientAreaAnimation,traffic!=null&&traffic.Available?(traffic.DownKbps+traffic.UpKbps)/1024d:0,CompactLight?"#DDE3E8":"#0E1217",CompactSurface,CompactPrimary,CompactMuted,CompactOutline);}else if(!Object.Equals(sceneHost.Tag,sceneKey)){sceneHost.Tag=sceneKey;sceneHost.Child=AndroidClassicPowerScene(phase);}
    var id=session.Core.Running&&!String.IsNullOrEmpty(session.Core.ActiveServerId)?session.Core.ActiveServerId:session.State.SelectedServerId;
    var node=session.State.Servers.FirstOrDefault(n=>n.Id==id);
    state.Text=stoppingConnection?"Отключение…":pending?"Подключение…":active?(session.State.Mode=="proxy"?"Прокси подключён":"VPN подключён"):"Отключено";
    power.IsEnabled=powerEnabled;AutomationProperties.SetName(power,(session.Core.Running?"Отключить":"Подключить")+" · "+state.Text);AutomationProperties.SetHelpText(power,rendererUnavailable?"Анимация недоступна; используется классическая кнопка.":busy?"Выполняется операция.":state.Text);
    state.Foreground=Brush(active?"#4BC9B8":CompactPrimary);
    target.Text=node==null?"Сервер не выбран":ServerIdentity.Display(node.Name);target.Visibility=active&&node!=null?Visibility.Visible:Visibility.Collapsed;
    string failure=session.Core.LastFailure;
    detail.Text=stoppingConnection?"Закрываем соединения и восстанавливаем сеть":pending?(!System.String.IsNullOrWhiteSpace(session.Core.StartupStatus)?session.Core.StartupStatus:"Подготавливаем подключение…"):!String.IsNullOrWhiteSpace(failure)?FailureExplanations.UserMessage(failure):active&&session.State.Mode=="proxy"?"Локальный прокси · 127.0.0.1:"+session.Core.LocalProxyPort:active&&session.State.AutoDetectBs?(session.State.AutoBsActive?"Активен транспорт белых списков":"Активен обычный VPN"):active?"Нажмите, чтобы отключить":"Нажмите, чтобы подключиться";
    detail.Foreground=Brush(!String.IsNullOrWhiteSpace(failure)?CompactError:CompactSecondary);
    stats.Text="Время · "+(active?(DateTime.UtcNow-connectedSince).ToString(@"hh\:mm\:ss"):"00:00:00")+"     "+(traffic!=null&&traffic.Available?"↓ "+Rate(traffic.DownKbps)+"  ↑ "+Rate(traffic.UpKbps):"↓ —  ↑ —");
    autoBs.IsChecked=session.State.AutoDetectBs;if(refreshServer!=null)refreshServer();
   };
   refreshHome();return panel;
  }

  FrameworkElement CompactModeControlsParity(){
   var root=new Grid{Name="compactModeSwitch",Height=48,ClipToBounds=true};
   var surface=new Border{CornerRadius=new CornerRadius(24),Background=Brush(CompactLight?"#B8E6EAEC":"#9221272E"),BorderBrush=Brush(CompactOutline),BorderThickness=new Thickness(1)};root.Children.Add(surface);
   var selector=new Border{CornerRadius=new CornerRadius(22),Margin=new Thickness(3),Background=Brush(CompactLight?"#E6FFFFFF":"#D92D343C"),BorderBrush=Brush(AlphaColor(CompactAccentColor,118)),BorderThickness=new Thickness(1),HorizontalAlignment=HorizontalAlignment.Left};root.Children.Add(selector);
   var buttons=new Grid();buttons.ColumnDefinitions.Add(new ColumnDefinition());buttons.ColumnDefinitions.Add(new ColumnDefinition());root.Children.Add(buttons);
   var vpn=CompactModeButton("compactModeVpn","VPN","shield",()=>CompactSetModeInline("tun"));var proxy=CompactModeButton("compactModeProxy","Только прокси","proxy",()=>CompactSetModeInline("proxy"));
   foreach(var button in new[]{vpn,proxy}){button.Style=(Style)FindResource("BareButton");button.Background=Brushes.Transparent;button.BorderBrush=Brushes.Transparent;button.Foreground=Brush(CompactPrimary);button.Margin=new Thickness(2);}
   Grid.SetColumn(proxy,1);buttons.Children.Add(vpn);buttons.Children.Add(proxy);
   Action<bool> update=animated=>{double half=Math.Max(0,(root.ActualWidth-6)/2);selector.Width=half;double target=session.State.Mode=="proxy"?half:0;var transform=selector.RenderTransform as TranslateTransform;if(transform==null){transform=new TranslateTransform();selector.RenderTransform=transform;}if(animated&&session.State.CompactAnimations&&SystemParameters.ClientAreaAnimation)transform.BeginAnimation(TranslateTransform.XProperty,new DoubleAnimation(transform.X,target,TimeSpan.FromMilliseconds(220)){EasingFunction=new CubicEase{EasingMode=EasingMode.EaseOut},FillBehavior=FillBehavior.HoldEnd});else{transform.BeginAnimation(TranslateTransform.XProperty,null);transform.X=target;}bool tun=session.State.Mode=="tun";vpn.Opacity=tun?1:.72;proxy.Opacity=tun?.72:1;vpn.Foreground=Brush(tun?CompactVisualAccent:CompactSecondary);proxy.Foreground=Brush(tun?CompactSecondary:CompactVisualAccent);foreach(var text in VisualChildren<TextBlock>(vpn))text.Foreground=vpn.Foreground;foreach(var text in VisualChildren<TextBlock>(proxy))text.Foreground=proxy.Foreground;foreach(var icon in VisualChildren<Path>(vpn))icon.Stroke=vpn.Foreground;foreach(var icon in VisualChildren<Path>(proxy))icon.Stroke=proxy.Foreground;};
   root.Loaded+=(s,e)=>update(false);root.SizeChanged+=(s,e)=>update(false);root.Tag=update;return root;
  }

  async Task CompactSetModeInline(string value){
   if(session.State.Mode==value)return;
   await session.ChangeConfiguration(()=>session.State.Mode=value);
   var mode=VisualChildren<Grid>(body).FirstOrDefault(g=>g.Name=="compactModeSwitch");
   var update=mode==null?null:mode.Tag as Action<bool>;if(update!=null)update(true);
   if(refreshHome!=null)refreshHome();
  }

  Button CompactActionTile(string name,string label,string icon,Action action){
   var content=new StackPanel{VerticalAlignment=VerticalAlignment.Center};var vector=CompactIcon(icon,19);vector.HorizontalAlignment=HorizontalAlignment.Center;content.Children.Add(vector);content.Children.Add(new TextBlock{Text=label,FontSize=9.5,TextAlignment=TextAlignment.Center,Margin=new Thickness(0,4,0,0)});
   var button=Button("",action);button.Name=name;button.Content=content;button.Height=54;button.Margin=new Thickness(3,0,3,0);button.Padding=new Thickness(4);button.Foreground=Brush(CompactPrimary);button.Background=Brush(CompactLight?"#C9DDE2E5":"#84242B32");button.BorderBrush=Brush(CompactOutline);button.BorderThickness=new Thickness(1);if(name=="compactAddServer"){StyleCompactButton(button,true);button.Padding=new Thickness(4);vector.Stroke=Brush(CompactVisualAccent);}AutomationProperties.SetName(button,label);return button;
  }

  Button CompactAsyncActionTile(string name,string label,string icon,Func<Task> action){
   var button=AsyncButton("",action);var content=new StackPanel{VerticalAlignment=VerticalAlignment.Center};var vector=CompactIcon(icon,19);vector.HorizontalAlignment=HorizontalAlignment.Center;content.Children.Add(vector);content.Children.Add(new TextBlock{Text=label,FontSize=9.5,TextAlignment=TextAlignment.Center,Margin=new Thickness(0,4,0,0)});button.Name=name;button.Content=content;button.Height=54;button.Margin=new Thickness(3,0,3,0);button.Padding=new Thickness(4);button.Foreground=Brush(CompactPrimary);button.Background=Brush(CompactLight?"#C9DDE2E5":"#84242B32");button.BorderBrush=Brush(CompactOutline);button.BorderThickness=new Thickness(1);AutomationProperties.SetName(button,label);return button;
  }

  UIElement CompactAutoSelectParity(){
   var details=new StackPanel{Name="compactAutoDetails",Margin=new Thickness(12,10,12,12),Visibility=Visibility.Collapsed};
   var header=new Grid{Margin=new Thickness(12,9,8,9)};header.ColumnDefinitions.Add(new ColumnDefinition());header.ColumnDefinitions.Add(new ColumnDefinition{Width=GridLength.Auto});header.ColumnDefinitions.Add(new ColumnDefinition{Width=GridLength.Auto});
   var words=new StackPanel();var title=new TextBlock{Text="Автовыбор",FontSize=13,Foreground=Brush(CompactPrimary)};var summary=new TextBlock{Name="compactAutoSummary",FontSize=10,Foreground=Brush(CompactMuted),Margin=new Thickness(0,2,0,0)};words.Children.Add(title);words.Children.Add(summary);header.Children.Add(words);
   var toggle=new CheckBox{Name="compactAutoSelectToggle",IsChecked=session.State.AutoSelect,VerticalAlignment=VerticalAlignment.Center,Margin=new Thickness(8,0,3,0)};Grid.SetColumn(toggle,1);header.Children.Add(toggle);
   var mode=new ComboBox{Name="compactAutoSelect",ItemsSource=new[]{"С минимальной задержкой","Последний выбранный","Первый доступный"},SelectedIndex=session.State.AutoSelectMode=="recent"?1:session.State.AutoSelectMode=="first"?2:0};
   var tolerance=new Slider{Name="compactAutoTolerance",Minimum=15,Maximum=150,TickFrequency=15,IsSnapToTickEnabled=true,Value=session.State.AutoSelectToleranceMs};
   var toleranceValue=new TextBlock{Text=session.State.AutoSelectToleranceMs+" мс",FontSize=10,Foreground=Brush(CompactMuted),HorizontalAlignment=HorizontalAlignment.Right};
   tolerance.ValueChanged+=(s,e)=>toleranceValue.Text=((int)tolerance.Value)+" мс";
   var intervalValues=CompactCadenceValues(session.State.AutoSelectIntervalSeconds,5);var interval=new ComboBox{Name="compactAutoInterval",ItemsSource=intervalValues.Select(CompactCadenceLabel).ToArray(),SelectedIndex=Math.Max(0,Array.IndexOf(intervalValues,session.State.AutoSelectIntervalSeconds))};
   var failureOnly=new CheckBox{Name="compactAutoFailureOnly",Content="Переключать только при недоступности текущего",IsChecked=session.State.AutoSelectFailureOnly,Margin=new Thickness(0,6,0,2)};
   details.Children.Add(Field("Стратегия",mode));details.Children.Add(Field("Допуск задержки",tolerance));details.Children.Add(toleranceValue);details.Children.Add(Field("Проверять каждые",interval));details.Children.Add(failureOnly);
   var cardContent=new StackPanel();cardContent.Children.Add(header);cardContent.Children.Add(details);
   var card=new Border{Name="compactAutoSelectCard",Child=cardContent,Margin=new Thickness(0,0,0,10)};StyleCompactCard(card);
   var expand=Button("",()=>{bool opened=details.Visibility!=Visibility.Visible;details.Visibility=opened?Visibility.Visible:Visibility.Collapsed;var arrow=VisualChildren<Button>(header).FirstOrDefault(b=>b.Name=="compactAutoSelectExpand");if(arrow!=null)arrow.RenderTransform=new RotateTransform(opened?180:0,15,15);});expand.Name="compactAutoSelectExpand";AutomationProperties.SetName(expand,"Настройки автовыбора");expand.ToolTip="Настройки автовыбора";AutomationProperties.SetName(toggle,"Автовыбор сервера");expand.Style=(Style)FindResource("BareButton");expand.Content=CompactIcon("chevron",15);expand.Width=30;expand.Height=30;expand.Padding=new Thickness(0);expand.MinHeight=0;expand.HorizontalAlignment=HorizontalAlignment.Right;expand.VerticalAlignment=VerticalAlignment.Center;expand.Margin=new Thickness(1,0,0,0);Grid.SetColumn(expand,2);header.Children.Add(expand);
   Action updateSummary=()=>summary.Text=!session.State.AutoSelect?"Выключен":session.State.AutoSelectMode=="recent"?"Последний выбранный":session.State.AutoSelectMode=="first"?"Первый доступный":"С минимальной задержкой";updateSummary();
   bool syncingAuto=false;Action syncAuto=()=>{syncingAuto=true;try{toggle.IsChecked=session.State.AutoSelect;mode.SelectedIndex=session.State.AutoSelectMode=="recent"?1:session.State.AutoSelectMode=="first"?2:0;interval.SelectedIndex=Math.Max(0,Array.IndexOf(intervalValues,session.State.AutoSelectIntervalSeconds));failureOnly.IsChecked=session.State.AutoSelectFailureOnly;tolerance.Value=session.State.AutoSelectToleranceMs;mode.IsEnabled=interval.IsEnabled=session.State.AutoSelect;failureOnly.IsEnabled=session.State.AutoSelect&&session.State.AutoSelectMode=="fast";tolerance.IsEnabled=failureOnly.IsEnabled&&!session.State.AutoSelectFailureOnly;}finally{syncingAuto=false;}updateSummary();};
   Func<Action,Task> persist=async apply=>{if(closing||closeRequested||busy){syncAuto();return;}details.IsEnabled=toggle.IsEnabled=false;try{await Run(async()=>{await session.ChangeConfiguration(()=>{apply();session.State.AutoSelectSettingsVersion=1;});status.Text="Настройки автовыбора сохранены.";status.Foreground=Brush(CompactSecondary);},syncAuto);}finally{if(!closing&&!closeRequested){details.IsEnabled=toggle.IsEnabled=true;syncAuto();}}};
   toggle.Click+=async(s,e)=>{bool wanted=toggle.IsChecked==true;await persist(()=>session.State.AutoSelect=wanted);};
   mode.SelectionChanged+=async(s,e)=>{if(syncingAuto||mode.SelectedIndex<0)return;string wanted=mode.SelectedIndex==1?"recent":mode.SelectedIndex==2?"first":"fast";await persist(()=>session.State.AutoSelectMode=wanted);};
   Func<Task> saveTolerance=async()=>{if(syncingAuto||!tolerance.IsEnabled)return;int previous=session.State.AutoSelectToleranceMs,next=(int)tolerance.Value;if(previous==next)return;await persist(()=>session.State.AutoSelectToleranceMs=next);};tolerance.PreviewMouseUp+=async(s,e)=>await saveTolerance();tolerance.KeyUp+=async(s,e)=>{if(e.Key==Key.Left||e.Key==Key.Right||e.Key==Key.Up||e.Key==Key.Down||e.Key==Key.Home||e.Key==Key.End||e.Key==Key.PageUp||e.Key==Key.PageDown)await saveTolerance();};
   interval.SelectionChanged+=async(s,e)=>{if(syncingAuto||interval.SelectedIndex<0)return;int wanted=intervalValues[interval.SelectedIndex];await persist(()=>session.State.AutoSelectIntervalSeconds=wanted);};
   failureOnly.Click+=async(s,e)=>{bool wanted=failureOnly.IsChecked==true;await persist(()=>session.State.AutoSelectFailureOnly=wanted);};card.Tag=syncAuto;syncAuto();
   return card;
  }

  UIElement CompactServerControlsParity(){
   var panel=new StackPanel{Name="compactServers"};var automaticCard=(FrameworkElement)CompactAutoSelectionRow();panel.Children.Add(automaticCard);
   var actions=new UniformGrid{Rows=1,Columns=session.State.CompactAdvancedFeatures?4:3,Margin=new Thickness(-3,0,-3,12)};
   actions.Children.Add(CompactActionTile("compactAddServer","Добавить","add",CompactAddServerDialog));
   var subscriptionRefresh=CompactAsyncActionTile("updateSubscriptions","Обновить","refresh",UpdateSubscriptions);ConfigureSubscriptionRefresh(subscriptionRefresh);actions.Children.Add(subscriptionRefresh);
   actions.Children.Add(CompactActionTile("compactVkTurn","VK TURN","turn",()=>Page("VK TURN")));
   if(session.State.CompactAdvancedFeatures)actions.Children.Add(CompactActionTile("compactWarp","WARP","warp",()=>Page("WARP")));panel.Children.Add(actions);
   var heading=new Grid{Margin=new Thickness(4,0,4,8)};heading.ColumnDefinitions.Add(new ColumnDefinition());heading.ColumnDefinitions.Add(new ColumnDefinition{Width=GridLength.Auto});heading.Children.Add(new TextBlock{Text="Серверы",FontSize=18,FontWeight=FontWeights.SemiBold,Foreground=Brush(CompactPrimary)});
   var right=new StackPanel{Orientation=Orientation.Horizontal};var count=new TextBlock{FontSize=10,Foreground=Brush(CompactMuted),VerticalAlignment=VerticalAlignment.Center,Margin=new Thickness(0,0,7,0)};right.Children.Add(count);var ping=CompactAsyncToolButton("compactPingAll","Проверить задержку", "ping",async()=>{status.Text="Проверяем задержку серверов…";await session.ProbeServers();lastServerProbe=DateTime.UtcNow;if(refreshServer!=null)refreshServer();status.Text="Проверка задержки серверов завершена."; });ping.Style=(Style)FindResource("BareButton");ping.Width=32;ping.Height=32;ping.Padding=new Thickness(5);right.Children.Add(ping);Grid.SetColumn(right,1);heading.Children.Add(right);panel.Children.Add(heading);
   var list=new StackPanel{Name="compactServerList"};panel.Children.Add(list);
   refreshServer=CreateServerListRefresh(list,count);bool autoEnabled=session.State.AutoSelect;var refreshRows=refreshServer;refreshServer=()=>{if(closing||closeRequested)return;if(autoEnabled!=session.State.AutoSelect){autoEnabled=session.State.AutoSelect;var sync=automaticCard.Tag as Action;if(sync!=null)sync();}refreshRows();};refreshServer();return panel;
  }

  Button CompactServerActionsParity(ServerNode node){
   string nodeId=node.Id;
   var more=Button("",()=>{});more.Name="compactServerActions";more.Style=(Style)FindResource("BareButton");more.Padding=new Thickness(6);more.Content=new System.Windows.Shapes.Path{Data=Geometry.Parse("M 3,14 L 3,18 L 7,18 L 18,7 L 14,3 Z M 12,5 L 16,9"),Stroke=Brush(CompactPrimary),StrokeThickness=1.5,Width=18,Height=18,Stretch=Stretch.Uniform};more.ToolTip="Изменить сервер";System.Windows.Automation.AutomationProperties.SetName(more,"Изменить сервер");var menu=new ContextMenu{Background=Brush(CompactLight?"#E2E7EB":"#171E28"),Foreground=Brush(CompactPrimary),BorderBrush=Brush(CompactOutline),BorderThickness=new Thickness(1),Padding=new Thickness(6),MinWidth=206};var menuFrame=new FrameworkElementFactory(typeof(Border));menuFrame.SetBinding(Border.BackgroundProperty,new System.Windows.Data.Binding("Background"){RelativeSource=new System.Windows.Data.RelativeSource(System.Windows.Data.RelativeSourceMode.TemplatedParent)});menuFrame.SetValue(Border.CornerRadiusProperty,new CornerRadius(12));menuFrame.SetValue(Border.PaddingProperty,new Thickness(6));menuFrame.AppendChild(new FrameworkElementFactory(typeof(ItemsPresenter)));menu.Template=new ControlTemplate(typeof(ContextMenu)){VisualTree=menuFrame};
   Action<string,Action> add=(label,action)=>{var item=new MenuItem{Header=label,Foreground=Brush(CompactPrimary),Padding=new Thickness(10,9,10,9)};if(label=="Проверить задержку"&&!AutomaticServers.Allowed(node)){item.IsEnabled=false;item.ToolTip="OpenFlux: замер не поддерживается, доступно ручное подключение."; }var frame=new FrameworkElementFactory(typeof(Border));frame.SetValue(Border.CornerRadiusProperty,new CornerRadius(7));frame.SetValue(Border.PaddingProperty,new Thickness(10,9,10,9));var labelHost=new FrameworkElementFactory(typeof(ContentPresenter));labelHost.SetValue(ContentPresenter.ContentSourceProperty,"Header");frame.AppendChild(labelHost);var template=new ControlTemplate(typeof(MenuItem)){VisualTree=frame};var hover=new Trigger{Property=MenuItem.IsHighlightedProperty,Value=true};hover.Setters.Add(new Setter(Control.BackgroundProperty,Brush(AlphaColor(CompactAccentColor,36))));template.Triggers.Add(hover);frame.SetBinding(Border.BackgroundProperty,new System.Windows.Data.Binding("Background"){RelativeSource=new System.Windows.Data.RelativeSource(System.Windows.Data.RelativeSourceMode.TemplatedParent)});var disabled=new Trigger{Property=UIElement.IsEnabledProperty,Value=false};disabled.Setters.Add(new Setter(UIElement.OpacityProperty,0.45));template.Triggers.Add(disabled);item.Template=template;item.Click+=(s,e)=>{if(busy||closing||closeRequested)return;var currentNode=session.State.Servers.FirstOrDefault(n=>n.Id==nodeId);if(currentNode==null){status.Text="Сервер уже удалён.";return;}node=currentNode;try{action();}catch(Exception ex){string detail=JournalStyle.Redact(ex.Message);status.Text=FailureExplanations.UserMessage(detail);RecordEvent("Ошибка: "+detail);}};menu.Items.Add(item);};
   add("Проверить задержку",()=>CompactProbeServer(node));add("Изменить",()=>CompactEditServer(node));add("Копировать подключение",()=>CompactShareServer(node.Id));add("Удалить",()=>CompactDeleteServer(node));more.ContextMenu=menu;more.Unloaded+=(s,e)=>menu.IsOpen=false;more.Click+=(s,e)=>{if(busy||closing||closeRequested)return;var currentNode=session.State.Servers.FirstOrDefault(n=>n.Id==nodeId);if(currentNode==null){status.Text="Сервер уже удалён.";return;}node=currentNode;CompactEditServer(node);};return more;
  }

  Border CompactSettingsCard(string title,string search,UIElement content){return CompactSettingsSection(title,search,content);}

  UIElement CompactAppearanceParity(){
   var panel=new StackPanel();panel.Children.Add(Text("Готовые палитры",11));
   var palettes=new UniformGrid{Rows=4,Columns=2,Margin=new Thickness(-3,0,-3,8)};
   Action<string,string,string,string> add=(label,accent,card,background)=>{var swatches=new StackPanel{Orientation=Orientation.Horizontal,HorizontalAlignment=HorizontalAlignment.Center};swatches.Children.Add(new Ellipse{Width=12,Height=12,Fill=Brush(accent),Margin=new Thickness(0,0,5,0)});swatches.Children.Add(new TextBlock{Text=label,FontSize=10.5});var button=Button("",async()=>{string oldMode=session.State.CompactAccent,oldAccent=session.State.CompactAccentColor,oldCard=session.State.CompactCardColor,oldBackground=session.State.CompactBackgroundColor;await CompactPersist(()=>{session.State.CompactAccent="palette";session.State.CompactAccentColor=accent;session.State.CompactCardColor=card;session.State.CompactBackgroundColor=background;},()=>{session.State.CompactAccent=oldMode;session.State.CompactAccentColor=oldAccent;session.State.CompactCardColor=oldCard;session.State.CompactBackgroundColor=oldBackground;},()=>{ApplyWorkspacePalette(false);Page("Оформление");});});button.Style=(Style)FindResource("PaletteChoice");button.Foreground=Brush(CompactPrimary);button.Name="compactPalette"+palettes.Children.Count;button.ToolTip=label;AutomationProperties.SetName(button,label);button.Content=swatches;button.Margin=new Thickness(3);button.Padding=new Thickness(8);button.Background=Brush(AlphaColor(card,CompactLight?(byte)54:(byte)150));button.BorderBrush=Brush(AlphaColor(accent,100));button.BorderThickness=new Thickness(String.Equals(session.State.CompactAccentColor,accent,StringComparison.OrdinalIgnoreCase)?2:1);palettes.Children.Add(button);};
   add("Океан","#65D6C7","#171C22","#0E1217");add("Север","#7EB8F7","#18202A","#0D1218");add("Фиолет","#AA91F5","#1B1926","#111019");add("Графит","#AEBBC4","#1B1F22","#111315");add("Лес","#82C99B","#18241F","#0F1813");add("Янтарь","#E5BB78","#27221B","#18150F");add("Роза","#DB9FB8","#291D25","#180F15");add("Лаванда","#B8ACE5","#211E2A","#13111B");panel.Children.Add(palettes);
   var gallery=Button("Все палитры Android · 40",AppearanceGallery);gallery.Name="compactAllPalettes";panel.Children.Add(gallery);
   var styleKeys=new[]{"blueprint","classic","magnet"};var style=new ComboBox{Name="compactConnectionStyle",ItemsSource=new[]{"Чертёж","Классическое окно","Магнитный разбор"},SelectedIndex=Math.Max(0,Array.IndexOf(styleKeys,session.State.CompactConnectionStyle))};panel.Children.Add(Field("Сцена подключения",style));
   var pattern=new Slider{Name="compactPatternOpacity",Minimum=0,Maximum=100,Value=Math.Max(0,Math.Min(100,session.State.CompactWallpaperOpacity)),TickFrequency=5,IsSnapToTickEnabled=true};panel.Children.Add(Field("Непрозрачность узоров · 0–100%",pattern));pattern.ValueChanged+=(s,e)=>{if(wallpaperSurface!=null)wallpaperSurface.Opacity=pattern.Value/100d;};
   // Animation preferences remain stored; the redundant toggle is no longer exposed.
   bool syncingStyle=false;style.SelectionChanged+=async(s,e)=>{if(style.SelectedIndex<0||syncingStyle)return;string previous=session.State.CompactConnectionStyle;await CompactPersist(()=>session.State.CompactConnectionStyle=styleKeys[style.SelectedIndex],()=>session.State.CompactConnectionStyle=previous);syncingStyle=true;try{style.SelectedIndex=Math.Max(0,Array.IndexOf(styleKeys,session.State.CompactConnectionStyle));}finally{syncingStyle=false;}};
   Func<Task> savePattern=async()=>{int previous=session.State.CompactWallpaperOpacity,next=(int)pattern.Value;if(previous==next)return;await CompactPersist(()=>session.State.CompactWallpaperOpacity=next,()=>{session.State.CompactWallpaperOpacity=previous;pattern.Value=previous;},()=>ApplyWorkspacePalette(false));};pattern.PreviewMouseUp+=async(s,e)=>await savePattern();pattern.KeyUp+=async(s,e)=>{if(e.Key==Key.Left||e.Key==Key.Right||e.Key==Key.Up||e.Key==Key.Down||e.Key==Key.Home||e.Key==Key.End||e.Key==Key.PageUp||e.Key==Key.PageDown)await savePattern();};
   panel.Margin=new Thickness(0,0,0,16);return panel;
  }

  UIElement CompactAdvancedToolsParity(){
   var panel=new StackPanel();
   foreach(var pair in new[]{new[]{"Мастер настройки","settings"},new[]{"Маршрутизация","routes"},new[]{"Автодеплой","deploy"}}){string page=pair[0];var row=Button("",()=>Page(page));row.Style=(Style)FindResource("BareButton");row.HorizontalContentAlignment=HorizontalAlignment.Stretch;row.Padding=new Thickness(4,8,4,8);var content=new Grid();content.ColumnDefinitions.Add(new ColumnDefinition{Width=new GridLength(32)});content.ColumnDefinitions.Add(new ColumnDefinition());content.ColumnDefinitions.Add(new ColumnDefinition{Width=GridLength.Auto});var icon=CompactIcon(pair[1],18);icon.VerticalAlignment=VerticalAlignment.Center;content.Children.Add(icon);var label=new TextBlock{Text=page=="Маршрутизация"?"Сохранённые правила и захват процесса":page,FontSize=12,VerticalAlignment=VerticalAlignment.Center};Grid.SetColumn(label,1);content.Children.Add(label);var arrow=CompactIcon("chevron",14);arrow.VerticalAlignment=VerticalAlignment.Center;Grid.SetColumn(arrow,2);content.Children.Add(arrow);row.Content=content;panel.Children.Add(row);}return panel;
  }

  UIElement CompactZapretParity(){
   var panel=new StackPanel{Margin=new Thickness(0,0,0,8)};var engine=new ComboBox{Name="compactZapretEngine",ItemsSource=new[]{"Zapret","ByeTube"},SelectedItem=engineInterface,Margin=new Thickness(0,0,0,10)};engine.SelectionChanged+=(s,e)=>{string next=Convert.ToString(engine.SelectedItem);if(next==engineInterface)return;if(busy){engine.SelectedItem=engineInterface;return;}engineInterface=next;Page("Zapret & Byetube");};panel.Children.Add(Field("Движок",engine));
   if(engineInterface=="ByeTube"){var content=ByeTubeWorkspace();CompactNormalizeAdvanced(content);panel.Children.Add(content);return panel;}
   panel.Children.Add(Text("Профили применяются только к назначенным маршрутам.",11));
   var generator=Button("Генератор стратегий",()=>Page("Генератор стратегий"));var editor=Button("Редактор стратегий",()=>Page("Редактор стратегий"));StyleCompactButton(generator);StyleCompactButton(editor);var tools=CompactButtonPair(generator,editor);panel.Children.Add(tools);
   foreach(var profile in session.State.ZapretProfiles.ToArray()){
    var p=profile;var form=new StackPanel();var name=new TextBox{Name="zapretProfileName_"+p.Id,Text=p.Name,Margin=new Thickness(0,0,0,8)};name.LostKeyboardFocus+=async(s,e)=>{string value=name.Text.Trim();if(value.Length==0){name.Text=p.Name;return;}if(value!=p.Name){string previous=p.Name;await CompactPersist(()=>p.Name=value,()=>{p.Name=previous;name.Text=previous;});var section=VisualAncestor<Border>(name);while(section!=null&&section.Name!="compactSettingsSection")section=VisualAncestor<Border>(VisualTreeHelper.GetParent(section));if(section!=null){section.Tag=(p.Name+" профиль zapret стратегия маршруты").ToLowerInvariant();var heading=CompactLogicalChildren<TextBlock>(section).FirstOrDefault();if(heading!=null){heading.Text=p.Name;heading.ToolTip=p.Name;}}}};form.Children.Add(Field("Название",name));
    var strategy=new ComboBox{Name="compactZapretStrategy_"+p.Id,ItemsSource=new[]{"Загрузка стратегий…"},SelectedIndex=0,IsEnabled=false,Margin=new Thickness(0,0,0,8)};form.Children.Add(Field("Стратегия",strategy));var strategyFeedback=Text("Загрузка стратегий…",11);strategyFeedback.Name="zapretStrategyState_"+p.Id;form.Children.Add(strategyFeedback);
    var enabled=new CheckBox{Name="zapretProfileEnabled_"+p.Id,Content="Профиль включён",IsChecked=p.Enabled,Margin=new Thickness(0,2,0,8)};enabled.Click+=async(s,e)=>{await Run(async()=>{if(enabled.IsChecked==true&&(String.IsNullOrEmpty(p.Settings.Strategy)||strategy.SelectedItem==null))throw new InvalidOperationException("Сначала назначьте стратегию.");await session.ChangeConfiguration(()=>p.Enabled=enabled.IsChecked==true);});enabled.IsChecked=p.Enabled;};form.Children.Add(enabled);
    var presets=AsyncButton("Маршруты",()=>AssignProfileLists(p));var hotkey=AsyncButton("Горячая клавиша",()=>ProfileHotkeyDialog(p));var remove=AsyncButton("Удалить",async()=>{if(session.State.Lists.Any(r=>r.Target=="zapret"&&r.ZapretProfileId==p.Id))throw new InvalidOperationException("Сначала переназначьте маршруты профиля.");if(!Confirm("Удалить профиль «"+p.Name+"»?","Профиль"))return;await session.ChangeConfiguration(()=>session.State.ZapretProfiles.Remove(p));if(selectedProfile!=null&&selectedProfile.Id==p.Id)selectedProfile=session.State.ZapretProfiles.FirstOrDefault();RegisterHotkeys();RefreshAfterOperation("Zapret & Byetube");});foreach(var button in new[]{presets,hotkey,remove})StyleCompactButton(button);var profileActions=Columns(presets,hotkey);profileActions.Margin=new Thickness(0,0,0,8);form.Children.Add(profileActions);form.Children.Add(remove);form.Children.Clear();var profileCard=CompactZapretProfileTable(p.Id,name,enabled,strategy,strategyFeedback,presets,hotkey,remove);panel.Children.Add(profileCard);RoutedEventHandler load=null;load=(s,e)=>{strategy.Loaded-=load;CompactLoadZapretStrategies(strategy,p,strategyFeedback);};strategy.Loaded+=load;
   }
   if(session.State.ZapretProfiles.Count==0)panel.Children.Add(CompactSettingsCard("Профилей пока нет","zapret",Text("Создайте профиль и выберите стратегию.",12)));
   var add=AsyncButton("Новый профиль",()=>EditZapretProfile(null));add.Name="compactAddZapretProfile";StyleCompactButton(add,true);var games=Button("Шаблоны игр",()=>Page("Шаблоны игр"));games.Name="compactZapretGames";var profileHeader=CompactButtonPair(add,games);int firstProfile=panel.Children.Cast<UIElement>().Select((x,i)=>new{Item=x,Index=i}).Where(x=>(x.Item as FrameworkElement)!=null&&((FrameworkElement)x.Item).Name=="compactZapretProfile").Select(x=>x.Index).DefaultIfEmpty(panel.Children.Count).First();panel.Children.Insert(firstProfile,profileHeader);return panel;
  }

  async void CompactLoadZapretStrategies(ComboBox picker,ZapretProfile profile,TextBlock feedback){
   try{var catalog=await session.Strategies();if(closing||current!="Zapret & Byetube"||!picker.IsLoaded)return;var options=catalog.ToArray();picker.DisplayMemberPath="ShortName";picker.ItemsSource=options;picker.SelectedItem=options.FirstOrDefault(item=>item.Id==profile.Settings.Strategy);picker.IsEnabled=options.Length>0;picker.ToolTip=options.Length==0?"Стратегии не найдены. Проверьте обновления движка Zapret.":String.IsNullOrEmpty(profile.Settings.Strategy)?"Выберите стратегию":picker.SelectedItem==null?"Сохранённая стратегия отсутствует в текущем каталоге.":null;feedback.Text=options.Length==0?"Нет доступных стратегий. Проверьте обновления движка Zapret.":!String.IsNullOrEmpty(profile.Settings.Strategy)&&picker.SelectedItem==null?"Сохранённая стратегия отсутствует в текущем каталоге.":"";feedback.Visibility=feedback.Text.Length==0?Visibility.Collapsed:Visibility.Visible;bool syncing=false;picker.SelectionChanged+=async(s,e)=>{if(syncing)return;var selected=picker.SelectedItem as ZapretStrategy;if(selected==null)return;if(!busy)await Run(()=>session.ChangeConfiguration(()=>{profile.Settings.Strategy=selected.Id;profile.Settings.Family=selected.Family;}));var saved=session.State.ZapretProfiles.FirstOrDefault(item=>item.Id==profile.Id);syncing=true;try{picker.SelectedItem=saved==null?null:options.FirstOrDefault(item=>item.Id==saved.Settings.Strategy);}finally{syncing=false;}};}catch(Exception ex){if(!closing&&picker.IsLoaded){picker.ItemsSource=new[]{"Не удалось загрузить стратегии"};picker.SelectedIndex=0;picker.IsEnabled=false;feedback.Text=FailureExplanations.UserMessage(JournalStyle.Redact(ex.Message));feedback.Visibility=Visibility.Visible;status.Text=feedback.Text;}}
  }

  void CompactSettingsParity(){
   CompactPageChrome("Настройки");var panel=new StackPanel{Name="compactSettingsSections",Margin=new Thickness(0,0,0,4)};
   var advanced=new CheckBox{Name="compactAdvancedFeatures",Content="Расширенные функции",IsChecked=session.State.CompactAdvancedFeatures,Margin=new Thickness(0,4,0,4)};var intro=new StackPanel{Margin=new Thickness(0,0,0,14)};intro.Children.Add(advanced);intro.Children.Add(Text("Показывает дополнительные сетевые разделы внутри компактного окна.",10));panel.Children.Add(intro);
   var advancedStack=new StackPanel();advancedStack.Children.Add(CompactSettingsLink("Мастер настройки",()=>Page("Мастер настройки")));advancedStack.Children.Add(CompactSettingsLink("Автодеплой",()=>Page("Автодеплой")));panel.Children.Add(advancedStack);
   panel.Children.Add(CompactSettingsLink("Автоопределение БС",()=>Page("Автоопределение БС")));
   var proxySettings=CompactSettingsLink("Локальный прокси",()=>Page("Локальный прокси"));proxySettings.Name="compactAdvancedProxy";panel.Children.Add(proxySettings);
   panel.Children.Add(CompactSettingsLink("Поведение программы",()=>Page("Поведение программы")));
   panel.Children.Add(CompactSettingsLink("Обновления",()=>Page("Обновления")));
   panel.Children.Add(CompactSettingsLink("Оформление",()=>Page("Оформление")));
   Action sync=()=>advancedStack.Visibility=proxySettings.Visibility=session.State.CompactAdvancedFeatures?Visibility.Visible:Visibility.Collapsed;sync();
   advanced.Click+=async(s,e)=>{bool previous=session.State.CompactAdvancedFeatures,wanted=advanced.IsChecked==true;await CompactPersist(()=>{session.State.CompactAdvancedFeatures=wanted;session.State.InterfaceMode=wanted?0:1;},()=>{session.State.CompactAdvancedFeatures=previous;advanced.IsChecked=previous;});advanced.IsChecked=session.State.CompactAdvancedFeatures;sync();ApplyInterfaceMode();};
   panel.Children.Add(CompactRecoveryButtons());body.Children.Add(CompactScroll(panel));FinishCompactPagePalette();SyncInterfaceControls();
  }

  UIElement CompactAdvancedContent(UIElement element){
   var border=element as Border;if(border!=null&&border.Child!=null){var child=border.Child;border.Child=null;element=child;}
   var scroll=element as ScrollViewer;if(scroll!=null&&scroll.Content is UIElement){var child=(UIElement)scroll.Content;scroll.Content=null;element=child;}
   return element;
  }

  void CompactNormalizeAdvanced(DependencyObject root){
   foreach(var button in CompactLogicalChildren<Button>(root)){
    if(Equals(button.Tag,"CompactKeepStyle")||button.Style==(Style)FindResource("BareButton")||button.Style==(Style)FindResource("IconAction"))continue;
    var padding=button.Padding;bool small=!Double.IsNaN(button.Height)&&button.Height<36;
    StyleCompactButton(button,button.Background is LinearGradientBrush);if(small)button.Padding=padding;
   }
   foreach(var element in CompactLogicalChildren<FrameworkElement>(root).Concat(new[]{root as FrameworkElement}).Where(item=>item!=null).Distinct()){
    if(!Double.IsNaN(element.Width)&&element.Width>328)element.Width=Double.NaN;
    if(element.MinWidth>328)element.MinWidth=0;
    element.MaxWidth=Math.Min(element.MaxWidth,328);
   }
   foreach(var grid in CompactLogicalChildren<Grid>(root).Where(item=>Equals(item.Tag,"CompactStackColumns")&&item.ColumnDefinitions.Count>1&&item.Children.Count>1).ToArray()){
    var children=grid.Children.Cast<UIElement>().ToArray();grid.Children.Clear();grid.ColumnDefinitions.Clear();grid.RowDefinitions.Clear();grid.ColumnDefinitions.Add(new ColumnDefinition());
    for(int i=0;i<children.Length;i++){grid.RowDefinitions.Add(new RowDefinition{Height=GridLength.Auto});Grid.SetColumn(children[i],0);Grid.SetRow(children[i],i);var child=children[i] as FrameworkElement;if(child!=null)child.Margin=new Thickness(0,0,0,8);grid.Children.Add(children[i]);}
   }
   foreach(var card in CompactLogicalChildren<Border>(root).Where(item=>item.CornerRadius.TopLeft>=12&&item.BorderThickness.Left>0)){card.Background=Brush(CompactSurface);card.BorderBrush=Brush(CompactOutline);card.Padding=new Thickness(12);card.Margin=new Thickness(0,0,0,10);}
   foreach(var table in CompactLogicalChildren<DataGrid>(root)){
    table.Background=Brushes.Transparent;table.Foreground=Brush(CompactPrimary);table.BorderBrush=Brush(CompactOutline);table.RowBackground=Brushes.Transparent;table.AlternatingRowBackground=Brush(AlphaColor(CompactAccentColor,18));table.HorizontalGridLinesBrush=Brush(CompactOutline);table.VerticalGridLinesBrush=Brush(CompactOutline);table.MinHeight=180;table.MaxHeight=290;
    var header=new Style(typeof(DataGridColumnHeader),(Style)FindResource(typeof(DataGridColumnHeader)));header.Setters.Add(new Setter(Control.BackgroundProperty,Brush(CompactSurface)));header.Setters.Add(new Setter(Control.ForegroundProperty,Brush(CompactPrimary)));header.Setters.Add(new Setter(Control.PaddingProperty,new Thickness(6,0,6,0)));table.ColumnHeaderStyle=header;
    var rowStyle=new Style(typeof(DataGridRow));rowStyle.Setters.Add(new Setter(Control.ForegroundProperty,Brush(CompactPrimary)));var selected=new Trigger{Property=DataGridRow.IsSelectedProperty,Value=true};selected.Setters.Add(new Setter(Control.BackgroundProperty,Brush(AlphaColor(CompactAccentColor,65))));rowStyle.Triggers.Add(selected);table.RowStyle=rowStyle;
    table.LoadingRow+=(s,e)=>{var row=e.Row;Dispatcher.BeginInvoke(new Action(()=>{if(row.IsLoaded)ApplyCompactControlPalette(row);}),System.Windows.Threading.DispatcherPriority.Loaded);};
    if(table.Name=="warpProfiles"){table.MinHeight=Math.Min(180,44+table.Items.Count*40);table.MaxHeight=Math.Min(290,44+table.Items.Count*40);}
    if(table.Name=="strategyArgumentTable"){table.Columns[2].Visibility=Visibility.Collapsed;table.Columns[0].MinWidth=100;table.Columns[0].Width=new DataGridLength(1,DataGridLengthUnitType.Star);table.Columns[1].MinWidth=150;table.Columns[1].Width=new DataGridLength(1.7,DataGridLengthUnitType.Star);table.FontSize=12;}
   }
   foreach(var input in CompactLogicalChildren<ListBox>(root).Where(item=>item.Name=="wpfParameterName")){input.MinHeight=140;input.MaxHeight=180;input.Background=Brush(CompactSurface);input.Foreground=Brush(CompactPrimary);}
   foreach(var input in CompactLogicalChildren<TextBox>(root).Where(item=>item.Name=="strategyBatText")){input.MinHeight=240;input.MaxHeight=340;}
   foreach(var input in CompactLogicalChildren<PasswordBox>(root)){input.Foreground=Brush(CompactPrimary);input.Background=Brush(CompactLight?"#FFFFFFFF":"#FF20262D");input.BorderBrush=Brush(CompactOutline);}
   foreach(var tabs in CompactLogicalChildren<TabControl>(root)){tabs.Background=Brushes.Transparent;tabs.Foreground=Brush(CompactPrimary);tabs.BorderBrush=Brush(CompactOutline);}
   foreach(var uniform in CompactLogicalChildren<UniformGrid>(root).Where(item=>item.Columns>2&&!Equals(item.Tag,"CompactPreserveGrid")))uniform.Columns=2;
   ApplyCompactControlPalette(root);
  }

  void CompactAdvancedPage(string name){
   if(IsCompactSettingsPage(name)){CompactSettingsPage(name);return;}
   if(name=="Обновления"){CompactPageChrome("Обновления");body.Children.Add(CompactUpdatesPanel());FinishCompactPagePalette();return;}if(name=="События"){CompactPageChrome("Логи");CompactLogPage();FinishCompactPagePalette();return;}
   if(name=="Маршрутизация"){CompactRoutesPage();return;}
   if(name=="Результаты"){CompactPageChrome("Результаты");ResultsPage();CompactNormalizeAdvanced(body);foreach(var table in CompactLogicalChildren<DataGrid>(body)){table.MinHeight=80;table.MaxHeight=Double.PositiveInfinity;table.Columns[0].Width=140;foreach(var column in table.Columns.Skip(1))column.Width=110;}foreach(var card in body.Children.OfType<Border>())StyleCompactCard(card);FinishCompactPagePalette();return;}
   CompactPageChrome(name=="Zapret & Byetube"?"Zapret":name);if(name=="Zapret & Byetube"){var content=CompactZapretParity();body.Children.Add(CompactScroll(content));FinishCompactPagePalette();ApplyCompactControlPalette(content);return;}Action build=null;
   if(name=="Zapret & Byetube")build=ZapretWorkspace;else if(name=="VK TURN")build=VkTurnPage;else if(name=="WARP")build=SimpleWarpPage;else if(name=="Мастер настройки")build=SetupPage;else if(name=="События")build=CompactLogPage;else if(name=="Автодеплой")build=DeploymentPage;else if(name=="Генератор стратегий")build=GeneratorPage;else if(name=="Редактор стратегий")build=FullStrategyEditor;else if(name=="Результаты")build=ResultsPage;else if(name=="Шаблоны игр")build=CompactGameTemplates;
   if(build==null){CompactSettingsParity();return;}
   build();var captured=body.Children.Cast<UIElement>().ToArray();body.Children.Clear();var stack=new StackPanel{Margin=new Thickness(0,0,0,8)};foreach(var original in captured){var child=CompactAdvancedContent(original);var element=child as FrameworkElement;if(element!=null){element.MaxWidth=352;element.HorizontalAlignment=HorizontalAlignment.Stretch;element.Margin=new Thickness(0,0,0,8);}CompactNormalizeAdvanced(child);stack.Children.Add(child);}body.Children.Add(CompactScroll(stack));FinishCompactPagePalette();ApplyCompactControlPalette(stack);
  }
 }
}
