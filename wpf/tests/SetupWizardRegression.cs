using System;
using System.Linq;
using System.Reflection;
using System.Collections.Generic;
using System.Threading;
using System.Threading.Tasks;
using SplifyWin;
class SetupWizardRegression {
 static readonly BindingFlags F=BindingFlags.Public|BindingFlags.NonPublic|BindingFlags.Static|BindingFlags.Instance;
 static readonly Assembly A=typeof(NetworkCore).Assembly;static int count;
 static void Check(bool ok,string label){if(!ok)throw new Exception(label);count++;Console.WriteLine("PASS "+label);}
 static ZapretCheckRow Row(string strategy,ServiceAccess access){return new ZapretCheckRow{StrategyId=strategy,Name=strategy??"Без обхода",Services=new List<ZapretServiceResult>{new ZapretServiceResult{Id="youtube",Access=access}}};}
 static int Main(string[] args){try{
  var flow=A.GetType("Mcrf.Wpf.SetupWizardFlow");var selection=A.GetType("Mcrf.Wpf.SetupWizardSelection");
  if(args.Length>0&&args[0]=="delta"){
   var deltaReport=new ZapretCheckReport();var row=Row("pending",ServiceAccess.Unknown);row.Services[0].Detail="original automated evidence";deltaReport.Rows.Add(row);var deltaItem=Activator.CreateInstance(selection,true);selection.GetField("Service",F).SetValue(deltaItem,ZapretChecks.Services.First(s=>s.Id=="youtube"));selection.GetField("Row",F).SetValue(deltaItem,row);
   flow.GetMethod("Record",F).Invoke(null,new object[]{deltaReport,deltaItem,true,null});Check(row.Services[0].Detail.Contains("original automated evidence"),"manual confirmation preserves automated evidence");
   flow.GetMethod("Record",F).Invoke(null,new object[]{deltaReport,deltaItem,false,null});Check(row.Services[0].Detail.Contains("original automated evidence")&&deltaReport.ManualResults.Count==2,"rejection retains prior evidence and both manual decisions");
   foreach(string output in new[]{"direct","zapret","byetube"}){row.StrategyId=output=="direct"?null:output=="byetube"?"byetube:0":"pending";row.Family=output=="byetube"?"ByeTube":"Flowseal";Check((string)selection.GetProperty("Output",F).GetValue(deltaItem,null)==output,"trial uses real output engine "+output);}
   Console.WriteLine("SETUP_WIZARD_DELTA_PASS "+count);return 0;
  }
  foreach(string mode in new[]{"pass","mixed","skip","cancel-before","cancel-during","choose-cancel","incomplete","resume","rejected-latest"})typeof(SetupWizardRegression).GetMethod("RunCase",F).MakeGenericMethod(selection).Invoke(null,new object[]{mode});
  var report=new ZapretCheckReport{Complete=true};report.Rows.Add(Row("failed",ServiceAccess.Unavailable));report.Rows.Add(Row("pending",ServiceAccess.Unknown));report.Rows.Add(Row("good",ServiceAccess.Available));report.Rows.Add(new ZapretCheckRow{StrategyId="broken",Error="startup failed"});
  var candidates=(ZapretCheckRow[])flow.GetMethod("Candidates",F).Invoke(null,new object[]{report,"youtube"});Check(candidates.Select(r=>r.StrategyId).SequenceEqual(new[]{"good","pending","failed"}),"weak candidates retained for manual verification, startup errors excluded");
  var item=Activator.CreateInstance(selection,true);selection.GetField("Service",F).SetValue(item,ZapretChecks.Services.First(s=>s.Id=="youtube"));selection.GetField("Row",F).SetValue(item,report.Rows[1]);
  flow.GetMethod("Record",F).Invoke(null,new object[]{report,item,true,new ZapretSettings{Strategy="pending"}});Check(report.Rows[1].Access("youtube")==ServiceAccess.Available&&report.ManualResults.Single().Works,"only explicit manual confirmation promotes unknown");
  flow.GetMethod("Record",F).Invoke(null,new object[]{report,item,false,null});Check(report.Rows[1].Access("youtube")==ServiceAccess.Unavailable&&!report.ManualResults.Last().Works,"manual rejection retained");
  var discord=ZapretChecks.Services.First(s=>s.Id=="discord");selection.GetField("Service",F).SetValue(item,discord);flow.GetMethod("Record",F).Invoke(null,new object[]{report,item,true,new ZapretSettings{Strategy="pending"}});Check(report.Rows[1].Access("discord-voice")==ServiceAccess.Available,"explicit two-sided Discord confirmation recorded separately");
  var video=A.GetType("SplifyWin.VideoProbe").GetMethod("CombineYouTube",F);
  foreach(ServiceAccess shorts in Enum.GetValues(typeof(ServiceAccess))){var combined=(ZapretEndpointResult)video.Invoke(null,new object[]{new ZapretEndpointResult{Access=ServiceAccess.Available,Detail="normal evidence",Milliseconds=10},new ZapretEndpointResult{Access=shorts,Detail="short evidence",Milliseconds=20}});Check(combined.Access==shorts&&combined.Milliseconds==30&&combined.Detail.Contains("normal evidence")&&combined.Detail.Contains("short evidence"),"YouTube aggregate preserves both evidence and Shorts verdict "+shorts);}
  var unknown=(ZapretEndpointResult)video.Invoke(null,new object[]{new ZapretEndpointResult{Access=ServiceAccess.Unknown,Detail="confirmation needed"},new ZapretEndpointResult{Access=ServiceAccess.Available}});Check(unknown.Access==ServiceAccess.Unknown,"unconfirmed normal video cannot turn green");
  Console.WriteLine("SETUP_WIZARD_REGRESSION_PASS "+count);return 0;
 }catch(Exception error){Console.WriteLine("SETUP_WIZARD_REGRESSION_FAIL "+error);return 1;}}
 static void RunCase<T>(string mode){
  var flow=A.GetType("Mcrf.Wpf.SetupWizardFlow");var chosen=new List<T>();var events=new List<string>();var report=new ZapretCheckReport{Complete=mode!="incomplete"};int applied=0;
  foreach(string id in new[]{"youtube","discord","instagram"}){var item=Activator.CreateInstance(typeof(T),true);typeof(T).GetField("Service",F).SetValue(item,ZapretChecks.Services.First(s=>s.Id==id));typeof(T).GetField("Row",F).SetValue(item,Row(id,ServiceAccess.Unknown));chosen.Add((T)item);}
  using(var cancellation=new CancellationTokenSource()){
   if(mode=="resume"||mode=="rejected-latest"){report.ManualResults=new List<ServiceManualResult>{new ServiceManualResult{ServiceId="youtube",StrategyId="youtube",Works=true}};if(mode=="rejected-latest")report.ManualResults.Add(new ServiceManualResult{ServiceId="youtube",StrategyId="youtube",Works=false});}
   if(mode=="cancel-before")cancellation.Cancel();
   Func<Task<T[]>> choose=()=>{events.Add("choose");return Task.FromResult(mode=="choose-cancel"?null:chosen.ToArray());};
   Func<T,CancellationToken,Task<bool?>> verify=(item,token)=>{string id=((ZapretService)typeof(T).GetField("Service",F).GetValue(item)).Id;events.Add(id);if(mode=="cancel-during"&&id=="discord")cancellation.Cancel();return Task.FromResult(mode=="skip"?(bool?)null:mode=="mixed"?(id=="youtube"?(bool?)true:id=="discord"?(bool?)false:null):(bool?)true);};
   Func<T[],Task> apply=items=>{applied=items.Length;events.Add("apply");return Task.FromResult(0);};Action save=()=>events.Add("save");bool stopped=false;
   try{((Task)flow.GetMethod("Run",F).Invoke(null,new object[]{report,choose,verify,apply,save,cancellation.Token})).GetAwaiter().GetResult();}catch(OperationCanceledException){stopped=true;}catch(InvalidOperationException){if(mode!="incomplete")throw;stopped=true;}
   if(mode=="pass")Check(applied==3&&String.Join(",",events)=="choose,youtube,save,discord,save,instagram,save,apply","proposal then all chosen services then final apply");
   else if(mode=="mixed")Check(applied==1&&events.Contains("instagram"),"failed and skipped services do not block later selected checks or get applied");
   else if(mode=="skip")Check(applied==0&&events.Contains("instagram"),"skip continues selected services without configuration changes");
   else if(mode=="resume")Check(applied==3&&!events.Contains("youtube")&&events.Contains("discord"),"resume reuses exact confirmed choice and continues unfinished services");
   else if(mode=="rejected-latest")Check(applied==3&&events.Contains("youtube"),"newest manual rejection prevents reuse even with equal timestamps");
   else if(mode=="choose-cancel")Check(!stopped&&events.SequenceEqual(new[]{"choose"}),"closing proposal never starts trials");
   else if(mode=="cancel-during")Check(stopped&&applied==0&&!events.Contains("instagram")&&events.Count(e=>e=="save")==1,"cancellation stops chain, preserves completed progress and prevents apply");
   else Check(stopped&&events.Count==0&&applied==0,"unstarted or incomplete scan cannot continue: "+mode);
  }
 }
}
