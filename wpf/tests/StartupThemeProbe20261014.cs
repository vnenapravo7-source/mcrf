using System;
using System.IO;
using System.Reflection;
using System.Windows;
using System.Windows.Markup;
using System.Windows.Controls;
using System.Windows.Media;
using System.Xml;

// Parses the exact production EXE resource; no test-entry-point replacement.
class StartupThemeProbe20261014 {
 static int checks;
 static void Check(bool value,string label){if(!value)throw new Exception(label);checks++;Console.WriteLine("PASS "+label);}
 [STAThread] static int Main(string[] args) {
  try {
   var assembly=Assembly.LoadFrom(Path.GetFullPath(args[0]));
   Console.WriteLine("ASSEMBLY "+assembly.GetName().Name);
   string theme;using(var stream=assembly.GetManifestResourceStream("Mcrf.Wpf.Theme.xaml"))using(var reader=new StreamReader(stream))theme=reader.ReadToEnd();
   if(args.Length>1&&args[1]=="--bootstrap") {
    var program=assembly.GetType("Mcrf.Wpf.Program",true);program.GetMethod("ConfigureDpi",BindingFlags.NonPublic|BindingFlags.Static).Invoke(null,null);
    var app=new Application{ShutdownMode=ShutdownMode.OnExplicitShutdown};program.GetMethod("LoadTheme",BindingFlags.NonPublic|BindingFlags.Static).Invoke(null,new object[]{app});
    var dictionary=app.Resources.MergedDictionaries[0];Check(dictionary.Count==31,"production bootstrap loads all 31 theme resources");
    foreach(var key in dictionary.Keys){var style=dictionary[key] as Style;if(style==null)continue;var element=(FrameworkElement)Activator.CreateInstance(style.TargetType);element.Style=style;var control=element as Control;if(control!=null)control.ApplyTemplate();element.Measure(new Size(300,100));element.Arrange(new Rect(0,0,300,100));Check(element.DesiredSize.Width>=0,"offline template "+key);}
    foreach(var key in new[]{"IconAction","NeonButton"}){var button=new Button{Style=(Style)dictionary[key],Content="Test"};button.ApplyTemplate();button.Measure(new Size(100,40));button.Arrange(new Rect(0,0,100,40));var surface=button.Template.FindName(key=="IconAction"?"Outline":"Surface",button);Check(surface!=null&&surface.GetType().Assembly==assembly,"custom surface resolves from actual assembly: "+key);}
    var before=app.Windows.Count;var window=(Window)Activator.CreateInstance(assembly.GetType("Mcrf.Wpf.MainWindow",true),new object[]{new[]{"--data",Path.GetFullPath(args[0])+".unused-test-profile"}});
    Check(window.Title=="MCRF"&&window.Icon!=null,"actual production main window constructs with application icon");
    var content=(FrameworkElement)window.Content;content.Measure(new Size(1366,865));content.Arrange(new Rect(0,0,1366,865));Check(content.DesiredSize.Width>0,"production shell measures without showing a window");
    Check(!window.IsVisible&&!Directory.Exists(Path.GetFullPath(args[0])+".unused-test-profile"),"no visible window or user profile changes");
    Console.WriteLine("STARTUP_OFFLINE_OK "+checks);return 0;
   }
   if(args.Length>1&&args[1]=="--identity-fix")theme=theme.Replace("assembly=MCRF.Wpf","assembly="+assembly.GetName().Name);
   using(var reader=XmlReader.Create(new StringReader(theme))) {
    var dictionary=(ResourceDictionary)XamlReader.Load(reader);
    Console.WriteLine("THEME_OK "+dictionary.Count);
   }
   return 0;
  } catch(Exception error) { Console.WriteLine(error.ToString());return 1; }
 }
}
