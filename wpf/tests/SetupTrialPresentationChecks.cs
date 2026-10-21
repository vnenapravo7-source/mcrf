using System;
using System.Linq;
using System.Reflection;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;
using SplifyWin;
class SetupTrialPresentationChecks {
 static readonly BindingFlags F=BindingFlags.Public|BindingFlags.NonPublic|BindingFlags.Static|BindingFlags.Instance;static int checks;
 static void Check(bool ok,string label){if(!ok)throw new Exception(label);checks++;Console.WriteLine("PASS "+label);}
 [STAThread]static int Main(){try{
  var assembly=typeof(NetworkCore).Assembly;assembly.GetType("Mcrf.Wpf.ManagedDependencies").GetMethod("Ensure",F).Invoke(null,null);var app=new Application();assembly.GetType("Mcrf.Wpf.Program").GetMethod("LoadTheme",F).Invoke(null,new object[]{app});
  var type=assembly.GetType("Mcrf.Wpf.MainWindow");var main=Activator.CreateInstance(type,new object[]{new[]{"--data","D:/MCRF/Builds/Windows/setup-trial-presentation-20261022/fixture"}});
  var needs=type.GetMethod("SetupTrialNeedsBrowser",F);var size=type.GetMethod("SetupTrialWindowSize",F);
  Check(!(bool)needs.Invoke(null,new object[]{"discord"}),"Discord voice does not request embedded browser");
  var youtube=(Size)size.Invoke(null,new object[]{"youtube"});var discord=(Size)size.Invoke(null,new object[]{"discord"});
  Check(discord.Width==820&&discord.Height==500,"Discord voice compact820x500");Check(youtube.Width==1100&&youtube.Height==800,"YouTube keeps full video viewport");
  Check(discord.Width<youtube.Width&&discord.Height<youtube.Height,"YouTube to Discord transition uses smaller requested dimensions");
  Check((bool)needs.Invoke(null,new object[]{"instagram"}),"other visual checks retain browser");
  assembly.GetType("SplifyWin.VideoBrowserLibraries").GetMethod("Install",F).Invoke(null,new object[]{"D:/MCRF/Builds/Windows/setup-trial-presentation-20261022/fixture"});
  object[] voiceArgs={"discord",null};var empty=type.GetMethod("SetupTrialBrowserHost",F).Invoke(main,voiceArgs);Check(empty==null&&voiceArgs[1]==null,"production voice host factory creates no white browser control");
  object[] videoArgs={"youtube",null};var host=(FrameworkElement)type.GetMethod("SetupTrialBrowserHost",F).Invoke(main,videoArgs);
  try{Check(host!=null&&videoArgs[1]!=null,"production video host remains available");var color=videoArgs[1].GetType().GetProperty("DefaultBackgroundColor").GetValue(videoArgs[1],null);Check((byte)color.GetType().GetProperty("R").GetValue(color,null)==28&&(byte)color.GetType().GetProperty("G").GetValue(color,null)==40&&(byte)color.GetType().GetProperty("B").GetValue(color,null)==68,"browser blank background is dark navy, not white");}finally{((IDisposable)host).Dispose();}
  var buttons=new[]{"Работает","Не работает — следующий вариант","Пропустить сервис","Отменить мастер"}.Select(label=>new Button{Content=label}).ToArray();var actions=(WrapPanel)type.GetMethod("CompactTools",F).Invoke(null,new object[]{buttons});actions.Measure(new Size(720,500));actions.Arrange(new Rect(0,0,720,actions.DesiredSize.Height));actions.UpdateLayout();
  Check(buttons.All(b=>b.TranslatePoint(new Point(b.ActualWidth,0),actions).X<=721)&&actions.ActualHeight<200,"voice actions wrap inside compact window");
  Check(!((Window)main).IsVisible,"tests do not open desktop windows or start a call");app.Shutdown();Console.WriteLine("SETUP_TRIAL_PRESENTATION_PASS "+checks);return 0;
 }catch(Exception error){Console.WriteLine("SETUP_TRIAL_PRESENTATION_FAIL "+error);return 1;}}
}
