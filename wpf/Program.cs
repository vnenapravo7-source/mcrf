using System;
using System.IO;
using System.Windows;
using System.Windows.Markup;
using System.Reflection;
using SplifyWin;
namespace Mcrf.Wpf {
 static class Program {
  internal static void ConfigureDpi(){AppContext.SetSwitch("Switch.System.Windows.DoNotScaleForDpiChanges",false);AppContext.SetSwitch("Switch.System.Windows.DoNotUsePresentationDpiCapabilityTier2OrGreater",false);}
  internal static void LoadTheme(Application app){ManagedDependencies.Ensure();using(var stream=Assembly.GetExecutingAssembly().GetManifestResourceStream("Mcrf.Wpf.Theme.xaml")){if(stream==null)throw new IOException("В EXE отсутствует тема интерфейса.");app.Resources.MergedDictionaries.Add((ResourceDictionary)XamlReader.Load(stream));}}

  [STAThread]static void Main(string[] args){using(var single=new SingleInstance()){if(!single.Acquire()){MessageBox.Show("Сначала закройте основной MCRF. Одновременный запуск двух клиентов может вызвать конфликт сетевых движков.","MCRF");return;}ConfigureDpi();var app=new Application();LoadTheme(app);app.DispatcherUnhandledException+=(s,e)=>{MessageBox.Show(e.Exception.Message,"MCRF");e.Handled=true;};app.Run(new MainWindow(args));}}
 }
}
