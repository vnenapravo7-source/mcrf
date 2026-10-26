using System;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Controls;
namespace Mcrf.Wpf {public sealed partial class MainWindow {
  internal Func<Task<bool?>> StartupStateReadForTests;int startupReadGeneration;
  async void ReadStartupStateInBackground(){
   int revision=++startupReadGeneration;bool? observed;
   try{observed=await (StartupStateReadForTests!=null?StartupStateReadForTests():Task.Run(()=>WpfWindowsStartup.ReadState()));}catch{observed=null;}
   if(closing||closeRequested||revision!=startupReadGeneration)return;
   startupStateKnown=observed.HasValue;if(observed.HasValue)startupEnabled=observed.Value;
   foreach(var check in CompactLogicalChildren<CheckBox>(body).Concat(VisualChildren<CheckBox>(body)).Distinct().Where(c=>c.Name=="compactStartupEnabled")){
    check.IsChecked=startupStateKnown?(bool?)startupEnabled:null;check.Content=startupStateKnown?"Автозапуск Windows":"Автозапуск · неизвестно";
   }
  }
}}
