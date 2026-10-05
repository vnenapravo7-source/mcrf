using System;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Web.Script.Serialization;
using Microsoft.Win32;

namespace SplifyWin {
  // The local mixed inbound is only useful to ordinary Windows apps when WinINET
  // points at it. Keep the user's previous proxy settings and restore them.
  public sealed class SystemProxy {
    const string KeyPath="Software\\Microsoft\\Windows\\CurrentVersion\\Internet Settings";
    const string LocalServer="127.0.0.1:10808";
    readonly string marker;
    readonly JavaScriptSerializer json=new JavaScriptSerializer();
    bool active;
    public sealed class Previous {public int Enabled {get;set;} public string Server {get;set;} public string Override {get;set;}}
    [DllImport("wininet.dll",SetLastError=true)] static extern bool InternetSetOption(IntPtr handle,int option,IntPtr buffer,int length);
    static void Notify(){InternetSetOption(IntPtr.Zero,39,IntPtr.Zero,0);InternetSetOption(IntPtr.Zero,37,IntPtr.Zero,0);}
    public SystemProxy(string root){marker=Path.Combine(root,"windows-proxy-restore.json");}
    public void Recover(){if(File.Exists(marker))RestoreSaved();}
    public void Enable(){if(active)return;Recover();using(var key=Registry.CurrentUser.OpenSubKey(KeyPath,true)){if(key==null)throw new InvalidOperationException("Не удалось открыть настройки прокси Windows.");var before=new Previous{Enabled=Convert.ToInt32(key.GetValue("ProxyEnable",0)),Server=Convert.ToString(key.GetValue("ProxyServer","")),Override=Convert.ToString(key.GetValue("ProxyOverride",""))};File.WriteAllText(marker,json.Serialize(before),new UTF8Encoding(false));key.SetValue("ProxyServer",LocalServer,RegistryValueKind.String);key.SetValue("ProxyOverride","<local>;localhost;127.*",RegistryValueKind.String);key.SetValue("ProxyEnable",1,RegistryValueKind.DWord);Notify();if(Convert.ToInt32(key.GetValue("ProxyEnable",0))!=1||Convert.ToString(key.GetValue("ProxyServer",""))!=LocalServer)throw new InvalidOperationException("Windows не применил настройки системного прокси.");active=true;}}
    void RestoreSaved(){var before=json.Deserialize<Previous>(File.ReadAllText(marker,Encoding.UTF8));using(var key=Registry.CurrentUser.OpenSubKey(KeyPath,true)){if(key==null)throw new InvalidOperationException("Не удалось восстановить настройки прокси Windows.");var current=Convert.ToString(key.GetValue("ProxyServer",""));if(String.Equals(current,LocalServer,StringComparison.OrdinalIgnoreCase)){key.SetValue("ProxyEnable",String.Equals(before.Server,LocalServer,StringComparison.OrdinalIgnoreCase)?0:before.Enabled,RegistryValueKind.DWord);if(before.Server.Length==0)key.DeleteValue("ProxyServer",false);else key.SetValue("ProxyServer",before.Server,RegistryValueKind.String);if(before.Override.Length==0)key.DeleteValue("ProxyOverride",false);else key.SetValue("ProxyOverride",before.Override,RegistryValueKind.String);Notify();}}File.Delete(marker);active=false;}
    public void Restore(){if(File.Exists(marker))RestoreSaved();active=false;}
  }
}
