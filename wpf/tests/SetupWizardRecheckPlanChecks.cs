using System;
using System.Linq;
using System.Reflection;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
class SetupWizardRecheckPlanChecks {
 static readonly BindingFlags F=BindingFlags.Public|BindingFlags.NonPublic|BindingFlags.Instance|BindingFlags.Static;static readonly Assembly A=typeof(NetworkCore).Assembly;static int count;
 static void Check(bool ok,string text){if(!ok)throw new Exception(text);count++;Console.WriteLine("PASS "+text);}
 static ZapretCheckRow Row(string id,string family,ServiceAccess access){return new ZapretCheckRow{StrategyId=id,Name=id??"Без обхода",Family=family,Services=new List<ZapretServiceResult>{new ZapretServiceResult{Id="youtube",Access=access}}};}
 [STAThread]static int Main(){try{
  var flow=A.GetType("Mcrf.Wpf.SetupWizardFlow");var selection=A.GetType("Mcrf.Wpf.SetupWizardSelection");A.GetType("Mcrf.Wpf.ManagedDependencies").GetMethod("Ensure",F).Invoke(null,null);var app=new Application();A.GetType("Mcrf.Wpf.Program").GetMethod("LoadTheme",F).Invoke(null,new object[]{app});
  var mainType=A.GetType("Mcrf.Wpf.MainWindow");var main=Activator.CreateInstance(mainType,new object[]{new[]{"--data","D:/MCRF/Builds/Windows/setup-recheck-plan-20261022/fixture"}});
  var report=new ZapretCheckReport{Complete=true,WizardServiceIds=new List<string>{"youtube"}};report.Rows.Add(Row(null,null,ServiceAccess.Available));report.Rows.Add(Row("zapret-1","Flowseal",ServiceAccess.Unknown));report.Rows.Add(Row("zapret-2","V",ServiceAccess.Unavailable));report.Rows.Add(Row("byetube:0","ByeTube",ServiceAccess.Unknown));
  object[] args={report,null};var panel=(StackPanel)mainType.GetMethod("SetupConfigurationFields",F).Invoke(main,args);var picker=panel.Children.OfType<ComboBox>().Single();var read=(Delegate)args[1];
  Check(picker.SelectedIndex>0&&picker.Items[picker.SelectedIndex].ToString().StartsWith("Zapret"),"confirmed direct baseline is not silently selected over Zapret");
  Check(picker.Items[0].ToString().Contains("Напрямую (без Zapret)"),"direct option explicitly says no Zapret");
  var chosen=((Array)read.DynamicInvoke()).GetValue(0);var output=selection.GetProperty("Output",F);Check((string)output.GetValue(chosen,null)=="zapret","default selected output creates Zapret profile");
  var candidates=(ZapretCheckRow[])flow.GetMethod("TrialCandidates",F).Invoke(null,new object[]{report,chosen});Check(candidates.Length==2&&candidates.All(r=>r.Family!="ByeTube"&&!String.IsNullOrEmpty(r.StrategyId)),"failed Zapret trials cannot silently fall back to direct or ByeTube");
  picker.SelectedIndex=0;chosen=((Array)read.DynamicInvoke()).GetValue(0);candidates=(ZapretCheckRow[])flow.GetMethod("TrialCandidates",F).Invoke(null,new object[]{report,chosen});Check((string)output.GetValue(chosen,null)=="direct"&&candidates.Length==1,"explicit direct choice remains direct only");
  var onlyDirect=new ZapretCheckReport{Complete=true,WizardServiceIds=new List<string>{"youtube"},Rows=new List<ZapretCheckRow>{report.Rows[0]}};object[] directArgs={onlyDirect,null};var directPanel=(StackPanel)mainType.GetMethod("SetupConfigurationFields",F).Invoke(main,directArgs);Check(directPanel.Children.OfType<ComboBox>().Single().SelectedIndex==-1&&directPanel.Children.OfType<CheckBox>().Single().IsChecked==false,"no strategy available never auto-selects direct");
  typeof(SetupWizardRecheckPlanChecks).GetMethod("Recheck",F).MakeGenericMethod(selection).Invoke(null,new object[]{true});typeof(SetupWizardRecheckPlanChecks).GetMethod("Recheck",F).MakeGenericMethod(selection).Invoke(null,new object[]{false});
  var old=new ZapretProfile{Name="Old wizard",WizardServiceId="youtube"};var user=new ZapretProfile{Name="User profile"};var shared=new ZapretProfile{Name="Shared wizard",WizardServiceId="discord"};var fresh=new ZapretProfile{Name="YouTube",WizardServiceId="youtube"};
  var userRoute=new RouteList{Name="User YouTube",Target="proxy"};var userAssigned=new RouteList{Name="Other",Target="zapret",ZapretProfileId=shared.Id};var state=new ClientState{Lists=new List<RouteList>{userRoute,userAssigned,new RouteList{BuiltinPreset="wpf-setup:youtube",ZapretProfileId=old.Id},new RouteList{BuiltinPreset="wpf-setup:discord",ZapretProfileId=shared.Id}},ZapretProfiles=new List<ZapretProfile>{old,user,shared}};
  Action apply=()=>flow.GetMethod("MergePlan",F).Invoke(null,new object[]{state,new[]{fresh},new[]{new RouteList{Name="YouTube",BuiltinPreset="wpf-setup:youtube",Target="zapret",ZapretProfileId=fresh.Id},new RouteList{Name="Discord",BuiltinPreset="wpf-setup:discord",Target="direct"}},null});apply();apply();
  Check(state.Lists.Count(r=>r.BuiltinPreset=="wpf-setup:youtube")==1&&state.Lists.Count(r=>r.BuiltinPreset=="wpf-setup:discord")==1,"reapplying wizard plan updates owned routes without duplicates");
  Check(state.Lists.Contains(userRoute)&&state.Lists.Contains(userAssigned)&&state.ZapretProfiles.Contains(user)&&state.ZapretProfiles.Contains(shared),"unrelated user rules/profiles and referenced old wizard profile preserved");
  Check(!state.ZapretProfiles.Contains(old)&&state.ZapretProfiles.Count(p=>p.Id==fresh.Id)==1,"only orphaned explicitly-owned wizard profile is replaced");
  Check(state.Lists.First().Target=="zapret"&&state.Lists.First().ZapretProfileId==fresh.Id,"chosen Zapret assignment is linked to actual created profile");
  app.Shutdown();Console.WriteLine("SETUP_RECHECK_PLAN_PASS "+count);return 0;
 }catch(Exception error){Console.WriteLine("SETUP_RECHECK_PLAN_FAIL "+error);return 1;}}
 static void Recheck<T>(bool repeat){
  var flow=A.GetType("Mcrf.Wpf.SetupWizardFlow");var report=new ZapretCheckReport{Complete=true,ManualResults=new List<ServiceManualResult>()};var chosen=new List<T>();int verified=0,applied=0;
  foreach(string id in new[]{"youtube","discord"}){var row=Row(id,"Flowseal",ServiceAccess.Available);row.Services.Add(new ZapretServiceResult{Id="discord-voice",Access=ServiceAccess.Available});var item=Activator.CreateInstance(typeof(T),true);typeof(T).GetField("Service",F).SetValue(item,ZapretChecks.Services.First(s=>s.Id==id));typeof(T).GetField("Row",F).SetValue(item,row);chosen.Add((T)item);report.ManualResults.Add(new ServiceManualResult{ServiceId=id,StrategyId=id,Works=true});}
  Func<Task<T[]>> choose=()=>Task.FromResult(chosen.ToArray());Func<T,CancellationToken,Task<bool?>> verify=(item,token)=>{verified++;return Task.FromResult((bool?)true);};Func<T[],Task> apply=items=>{applied=items.Length;return Task.FromResult(0);};
  ((Task)flow.GetMethod("RunCore",F).Invoke(null,new object[]{report,choose,verify,apply,(Action)(()=>{}),CancellationToken.None,repeat})).GetAwaiter().GetResult();Check(verified==(repeat?2:0)&&applied==2,repeat?"explicit recheck opens both previously confirmed services":"ordinary resume still reuses confirmed decisions");
 }
}
