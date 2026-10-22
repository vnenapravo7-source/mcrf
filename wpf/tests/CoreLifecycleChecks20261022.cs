using System;
using System.IO;
using System.Diagnostics;
using System.Threading;
using SplifyWin;
using Mcrf.Wpf;
class LifecycleChecks {
 static int n;static void Check(bool value,string name){if(!value)throw new Exception(name);Console.WriteLine("PASS "+name);n++;}
 static int Main(string[] args){if(args.Length>0&&args[0]==CoreProcessHost.Switch)return CoreProcessHost.Run(args);try{
  string root=Path.GetFullPath(args[0]);Directory.CreateDirectory(root);string config=Path.Combine(root,"fixture.json");File.WriteAllText(config,"{}");
  Check(TunIdentity.ForProfile(root)==TunIdentity.ForProfile(root+Path.DirectorySeparatorChar),"TUN identity stable across trailing separator");Check(TunIdentity.ForProfile(root)!=TunIdentity.ForProfile(root+"-other"),"isolated profiles never reuse common TUN name");
  var boundary=new UiFailureBoundary();int recovery=0,reports=0;var error=new InvalidOperationException("An ItemsControl is inconsistent with its items source.");boundary.Handle(error,e=>{recovery++;boundary.Handle(error,x=>recovery++,x=>reports++);},e=>reports++);Check(recovery==1&&reports==1,"reentrant layout failure cannot cascade dialogs");Check(UiFailureBoundary.IsItemsFailure(new Exception("wrapper",error)),"inner ItemsControl failure detected for view recovery");
  for(int i=0;i<3;i++){File.Delete(config+".closed");using(var p=Process.Start(CoreProcessHost.StartInfo(Path.Combine(root,"sing-box.exe"),config))){var ready=p.StandardOutput.ReadLineAsync();Check(ready.Wait(10000)&&ready.Result=="FIXTURE_READY","owned hidden core starts "+i);p.StandardInput.WriteLine("STOP");p.StandardInput.Flush();Check(p.WaitForExit(CoreProcessHost.StopTimeout)&&p.ExitCode==0,"owned core host stops cleanly "+i);Check(File.Exists(config+".closed"),"CTRL_BREAK reaches child cleanup instead of hard kill "+i);}}
  Console.WriteLine("RESULT "+n+" checks PASS");return 0;
 }catch(Exception e){Console.Error.WriteLine(e);return 1;}}
}
