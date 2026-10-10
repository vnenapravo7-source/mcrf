using System;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Security.AccessControl;
using System.Security.Principal;
using System.Threading;
using System.Windows.Forms;
namespace SplifyWin {
 public sealed class SingleInstance : IDisposable {
  [DllImport("user32.dll",CharSet=CharSet.Unicode)] static extern uint RegisterWindowMessage(string name);
  [DllImport("user32.dll")] static extern bool PostMessage(IntPtr hwnd,uint msg,IntPtr w,IntPtr l);
  [DllImport("user32.dll")] static extern bool ChangeWindowMessageFilterEx(IntPtr hwnd,uint msg,uint action,IntPtr info);
  public static readonly uint ActivationMessage=RegisterWindowMessage("MCRF.Activate."+WindowsIdentity.GetCurrent().User.Value);
  readonly Mutex mutex;bool owns;
  public SingleInstance(string suffix=null){var sid=WindowsIdentity.GetCurrent().User;var security=new MutexSecurity();security.AddAccessRule(new MutexAccessRule(sid,MutexRights.FullControl,AccessControlType.Allow));bool created;mutex=new Mutex(false,"Local\\MCRF.SingleInstance."+sid.Value+(suffix??""),out created,security);}
  public bool Acquire(){try{owns=mutex.WaitOne(0);}catch(AbandonedMutexException){owns=true;}return owns;}
  public static void ActivateExisting(){if(ActivationMessage!=0)PostMessage(new IntPtr(0xffff),ActivationMessage,IntPtr.Zero,IntPtr.Zero);}
  public static void AllowActivation(IntPtr handle){if(ActivationMessage!=0)ChangeWindowMessageFilterEx(handle,ActivationMessage,1,IntPtr.Zero);}
  public static void WaitForHandoff(string[] args){foreach(var arg in args){int id;if(!arg.StartsWith("--handoff-from=",StringComparison.Ordinal)||!Int32.TryParse(arg.Substring(15),out id)||id==Process.GetCurrentProcess().Id)continue;try{using(var process=Process.GetProcessById(id)){if(String.Equals(process.MainModule.FileName,Application.ExecutablePath,StringComparison.OrdinalIgnoreCase))process.WaitForExit(30000);}}catch(ArgumentException){}catch(System.ComponentModel.Win32Exception){}catch(InvalidOperationException){}}}
  public void Dispose(){if(owns){mutex.ReleaseMutex();owns=false;}mutex.Dispose();}
 }
}
