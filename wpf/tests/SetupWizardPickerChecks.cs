using System;
using System.Linq;
using System.Reflection;
using System.Collections.Generic;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
class SetupWizardPickerChecks {
 static readonly BindingFlags F=BindingFlags.Public|BindingFlags.NonPublic|BindingFlags.Static|BindingFlags.Instance;
 static int count;static void Check(bool ok,string label){if(!ok)throw new Exception(label);count++;Console.WriteLine("PASS "+label);}
 [STAThread]static int Main(string[] arguments){try{
  var assembly=typeof(NetworkCore).Assembly;assembly.GetType("Mcrf.Wpf.ManagedDependencies").GetMethod("Ensure",F).Invoke(null,null);var app=new Application();assembly.GetType("Mcrf.Wpf.Program").GetMethod("LoadTheme",F).Invoke(null,new object[]{app});
  var mainType=assembly.GetType("Mcrf.Wpf.MainWindow");var main=Activator.CreateInstance(mainType,new object[]{new[]{"--data","D:/MCRF/Builds/Windows/setup-wizard-fix-20261022/picker-test-profile"}});
  var report=new ZapretCheckReport{Complete=true,WizardServiceIds=new List<string>{"youtube","discord","instagram"}};
  report.Rows.Add(new ZapretCheckRow{StrategyId="pending",Name="Жёлтый вариант",Services=report.WizardServiceIds.Select(id=>new ZapretServiceResult{Id=id,Access=ServiceAccess.Unknown}).ToList()});
  report.Rows.Add(new ZapretCheckRow{StrategyId="failed",Name="Красный вариант",Services=report.WizardServiceIds.Select(id=>new ZapretServiceResult{Id=id,Access=ServiceAccess.Unavailable}).ToList()});
  object[] args={report,null};var panel=(StackPanel)mainType.GetMethod("SetupConfigurationFields",F).Invoke(main,args);var read=(Delegate)args[1];
  if(arguments.Length==0){
  Check(panel.Children.OfType<CheckBox>().Count()==3&&panel.Children.OfType<ComboBox>().Count()==3,"production picker renders every selected service with no green results");
  Check(panel.Children.OfType<ComboBox>().All(c=>c.Items.Count==2&&c.SelectedIndex==0),"unknown and failed configurations selectable, unknown preferred");
  Check(((Array)read.DynamicInvoke()).Length==3,"all three selected services passed to sequential workflow");
  panel.Children.OfType<CheckBox>().Last().IsChecked=false;var selected=(Array)read.DynamicInvoke();Check(selected.Length==2&&!selected.Cast<object>().Any(x=>((ZapretService)x.GetType().GetField("Service",F).GetValue(x)).Id=="instagram"),"unchecked service excluded from workflow");
  panel.Measure(new Size(500,500));panel.Arrange(new Rect(0,0,500,panel.DesiredSize.Height));panel.UpdateLayout();
  Check(panel.Children.OfType<ComboBox>().All(c=>c.ActualWidth<=500&&c.ActualHeight>=30),"production picker fields fit compact width");
  Check(!((Window)main).IsVisible,"picker checks do not open desktop windows or start modules");
  }
  var navigated=new List<string>();
  foreach(string id in new[]{"youtube","rutor","discord"}){
   var service=ZapretChecks.Services.First(s=>s.Id==id);var links=(Panel)mainType.GetMethod("SetupTrialLinks",F).Invoke(main,new object[]{service,(Action<string>)(url=>navigated.Add(url))});
   Check(links.Children.Count==(id=="discord"?1:2),"all required manual destinations exposed for "+id);
   foreach(Button button in links.Children)button.RaiseEvent(new RoutedEventArgs(System.Windows.Controls.Primitives.ButtonBase.ClickEvent));
  }
  Check(navigated.Any(url=>url.Contains("/watch?"))&&navigated.Any(url=>url.Contains("%23shorts"))&&navigated.Any(url=>url.Contains("rutracker.org"))&&navigated.Contains("https://discord.com/app"),"production trial buttons navigate both YouTube modes, tracker companion and Discord interface");
  app.Shutdown();Console.WriteLine("SETUP_WIZARD_PICKER_PASS "+count);return 0;
 }catch(Exception error){Console.WriteLine("SETUP_WIZARD_PICKER_FAIL "+error);return 1;}}
}
