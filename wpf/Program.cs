using System;
using System.IO;
using System.Windows;
using System.Windows.Markup;
using System.Reflection;
using System.Xml;
using System.Text;
using System.Runtime.CompilerServices;
using SplifyWin;
namespace Mcrf.Wpf {
 static class Program {
  internal static void ConfigureDpi(){AppContext.SetSwitch("Switch.System.Windows.DoNotScaleForDpiChanges",false);AppContext.SetSwitch("Switch.System.Windows.DoNotUsePresentationDpiCapabilityTier2OrGreater",false);}
  internal static void LoadTheme(Application app){ManagedDependencies.Ensure();var assembly=Assembly.GetExecutingAssembly();using(var stream=assembly.GetManifestResourceStream("Mcrf.Wpf.Theme.xaml")){if(stream==null)throw new IOException("В EXE отсутствует тема интерфейса.");string theme;using(var reader=new StreamReader(stream))theme=reader.ReadToEnd();theme=theme.Replace("urn:mcrf:ui","clr-namespace:Mcrf.Wpf;assembly="+System.Security.SecurityElement.Escape(assembly.GetName().Name));using(var reader=XmlReader.Create(new StringReader(theme),new XmlReaderSettings{DtdProcessing=DtdProcessing.Prohibit,XmlResolver=null}))app.Resources.MergedDictionaries.Add((ResourceDictionary)XamlReader.Load(reader));}}

  [STAThread]static int Main(string[] args){try{ManagedDependencies.Ensure();return Run(args);}catch(Exception error){ReportStartupFailure(error,args);return 1;}}
  [MethodImpl(MethodImplOptions.NoInlining)]static int Run(string[] args){using(var single=new SingleInstance()){if(!single.Acquire()){MessageBox.Show("Сначала закройте основной MCRF. Одновременный запуск двух клиентов может вызвать конфликт сетевых движков.","MCRF");return 0;}ConfigureDpi();var app=new Application();app.DispatcherUnhandledException+=(s,e)=>{MessageBox.Show(e.Exception.Message,"MCRF");e.Handled=true;};LoadTheme(app);return app.Run(new MainWindow(args));}}
  static void ReportStartupFailure(Exception error,string[] args){string log=null;try{string root=args.Length>1&&args[0]=="--data"?Path.GetFullPath(args[1]):Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"mcrf-wpf");Directory.CreateDirectory(root);log=Path.Combine(root,"startup-error.log");string detail=JournalStyle.Redact(error.ToString());if(detail.Length>32768)detail=detail.Substring(0,32768);File.AppendAllText(log,DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss")+"\n"+detail+"\n",new UTF8Encoding(false));}catch{log=null;}MessageBox.Show("Не удалось запустить MCRF.\n\n"+JournalStyle.Redact(error.Message)+(log==null?"":"\n\nПодробности: "+log),"MCRF · ошибка запуска",MessageBoxButton.OK,MessageBoxImage.Error);}
 }
}
