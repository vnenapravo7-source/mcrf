using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Net;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading.Tasks;
using System.Windows.Forms;
namespace SplifyWin {
  public static class HostsRepair {
    public const string Source="https://raw.githubusercontent.com/StressOzz/Zapret-Manager/main/Zapret-Manager.sh";
    const string Start="# MCRF META BEGIN",End="# MCRF META END";
    public static string Parse(string script){return Parse(script,"instagram");}
    public static string Parse(string script,string service){
      string variable;string[] permitted;if(service=="instagram"){variable="INSTAGRAM";permitted=new[]{"instagram.com","cdninstagram.com","facebook.com","fbcdn.net","fbsbx.com","fb.com"};}else if(service=="rutor"){variable="RUTOR";permitted=new[]{"rutor.info"};}else throw new FormatException("Для этого сервиса в Zapret-Manager нет проверяемого блока hosts. Не подставляем случайные IP.");
      var match=Regex.Match(script??"",@"(?ms)(?:^|;\s*)"+variable+@"=""(.*?)""");
      if(!match.Success)throw new FormatException("В источнике не найден выбранный блок hosts. Формат мог измениться.");
      var chosen=new Dictionary<string,string>(StringComparer.OrdinalIgnoreCase);
      foreach(var raw in match.Groups[1].Value.Replace("\\n","\n").Split('\n')){
        var line=raw.Split('#')[0].Trim();var fields=line.Split(new[]{' ','\t','\r'},StringSplitOptions.RemoveEmptyEntries);if(fields.Length<2)continue;
        IPAddress ip;if(!IPAddress.TryParse(fields[0],out ip)||ip.AddressFamily!=System.Net.Sockets.AddressFamily.InterNetwork)throw new FormatException("Некорректный IP в рекомендациях.");
        var b=ip.GetAddressBytes();if(b[0]==0||b[0]==10||b[0]==127||b[0]>=224||b[0]==169&&b[1]==254||b[0]==192&&b[1]==168||b[0]==172&&b[1]>=16&&b[1]<=31)throw new FormatException("Непубличный IP в рекомендациях.");
        foreach(var domain in fields.Skip(1)){if(!Regex.IsMatch(domain,@"^[a-z0-9.-]+$")||!permitted.Any(s=>domain==s||domain.EndsWith("."+s,StringComparison.OrdinalIgnoreCase)))throw new FormatException("Посторонний домен в рекомендациях.");if(!chosen.ContainsKey(domain))chosen.Add(domain,ip.ToString());}
      }
      if(chosen.Count==0||chosen.Count>100)throw new FormatException("Некорректное число записей.");
      return String.Join("\r\n",chosen.Select(p=>p.Value+" "+p.Key));
    }
    static int Find(byte[] data,byte[] token,int start){for(int i=start;i<=data.Length-token.Length;i++){bool same=true;for(int j=0;j<token.Length;j++)if(data[i+j]!=token[j]){same=false;break;}if(same)return i;}return -1;}
    public static byte[] Compose(byte[] original,string entries){
      if(original.Length>1024*1024)throw new InvalidDataException("hosts слишком большой.");
      if(original.Length>1&&((original[0]==255&&original[1]==254)||(original[0]==254&&original[1]==255)))throw new InvalidDataException("UTF-16 hosts не изменяется автоматически.");
      var begin=Encoding.ASCII.GetBytes(Start);var end=Encoding.ASCII.GetBytes(End);int a=Find(original,begin,0),b=Find(original,end,0);
      if((a<0)!=(b<0)||a>=0&&(b<a||Find(original,begin,a+begin.Length)>=0||Find(original,end,b+end.Length)>=0))throw new InvalidDataException("Повреждён или продублирован блок MCRF в hosts.");
      int suffix=original.Length;if(a>=0){if((b>0&&original[b-1]!=10)||(b+end.Length<original.Length&&original[b+end.Length]!=13&&original[b+end.Length]!=10))throw new InvalidDataException("Маркер конца MCRF должен быть на отдельной строке.");if(a>0&&original[a-1]!=10)throw new InvalidDataException("Маркер MCRF должен быть на отдельной строке.");suffix=b+end.Length;if(suffix<original.Length&&original[suffix]==13)suffix++;if(suffix<original.Length&&original[suffix]==10)suffix++;}
      using(var output=new MemoryStream()){
        int prefix=a<0?original.Length:a;output.Write(original,0,prefix);
        if(!String.IsNullOrEmpty(entries)){if(prefix>0&&original[prefix-1]!=10){var newline=Encoding.ASCII.GetBytes("\r\n");output.Write(newline,0,newline.Length);}var block=Encoding.ASCII.GetBytes(Start+"\r\n"+entries.Trim()+"\r\n"+End+"\r\n");output.Write(block,0,block.Length);}
        if(a>=0)output.Write(original,suffix,original.Length-suffix);return output.ToArray();
      }
    }
    [DllImport("dnsapi.dll",SetLastError=true)]static extern bool DnsFlushResolverCache();
    internal static bool SharingViolation(IOException error){int code=Marshal.GetHRForException(error)&65535;return code==32||code==33;}
    public const int LockRetryCount=3,LockRetryDelayMs=15000;
    public static bool IsHostsFileFailure(Exception error){return !(error is OperationCanceledException)&&error.ToString().IndexOf(HostsEditor.PathName,StringComparison.OrdinalIgnoreCase)>=0;}
    internal static void RetryLockedFile(Action action,System.Threading.CancellationToken token,Action<int> retry=null,Action<int> wait=null){
      for(int attempt=0;;attempt++){
        token.ThrowIfCancellationRequested();bool locked=false;
        try{action();return;}catch(IOException ex){if(!SharingViolation(ex)||attempt>=LockRetryCount)throw;locked=true;}
        if(locked){if(retry!=null)retry(attempt+1);if(wait!=null)wait(LockRetryDelayMs);else if(token.WaitHandle.WaitOne(LockRetryDelayMs))token.ThrowIfCancellationRequested();}
      }
    }
    public static byte[] ReadHostsFile(string path){byte[] result=null;RetryLockedFile(()=>result=File.ReadAllBytes(path),System.Threading.CancellationToken.None);return result;}
    public static Task ApplyAsync(string root,string entries,byte[] expected=null,bool makeBackup=true){return Task.Run(()=>Apply(root,entries,expected,makeBackup));}
    internal static void ReplaceHostsFile(string path,byte[] original,byte[] next){ReplaceHostsFile(path,original,next,null);}
    internal static void ReplaceHostsFile(string path,byte[] original,byte[] next,Action<int> wait){
      try{RetryLockedFile(()=>ReplaceHostsFileOnce(path,original,next),System.Threading.CancellationToken.None,null,wait);}
      catch(IOException error){throw new IOException("Не удалось записать hosts ("+path+"): "+error.Message,error);}
    }
    static void ReplaceHostsFileOnce(string path,byte[] original,byte[] next){
      string temp=path+".mcrf-"+Guid.NewGuid().ToString("N")+".tmp";
      try{
        File.WriteAllBytes(temp,next);
          try{if(!File.ReadAllBytes(path).SequenceEqual(original))throw new IOException("hosts изменён другой программой: "+path);File.Replace(temp,path,null);return;}
          catch(IOException error){if(!SharingViolation(error))throw;}
          // DNS readers may allow writes but deny atomic replacement (FILE_SHARE_DELETE).
          // Hold one handle, reject competing writers, and compare again before writing.
          using(var file=new FileStream(path,FileMode.Open,FileAccess.ReadWrite,FileShare.Read)){
            if(file.Length!=original.Length)throw new IOException("hosts изменён другой программой: "+path);
            var current=new byte[original.Length];int offset=0,count;
            while(offset<current.Length&&(count=file.Read(current,offset,current.Length-offset))>0)offset+=count;
            if(offset!=current.Length||!current.SequenceEqual(original))throw new IOException("hosts изменён другой программой: "+path);
            try{file.Position=0;file.Write(next,0,next.Length);file.SetLength(next.Length);file.Flush(true);}
            catch{file.Position=0;file.Write(original,0,original.Length);file.SetLength(original.Length);file.Flush(true);throw;}
          }
          return;
      }
      finally{if(File.Exists(temp))File.Delete(temp);}
    }
public static void Apply(string root,string entries,byte[] expected=null,bool makeBackup=true){
      string path=Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.System),"drivers","etc","hosts");
      if((File.GetAttributes(path)&(FileAttributes.ReparsePoint|FileAttributes.ReadOnly))!=0)throw new IOException("hosts защищён или перенаправлен: "+path+". Автоматически снимать защиту не будем.");
      var original=ReadHostsFile(path);if(expected!=null&&!original.SequenceEqual(expected))throw new IOException("hosts изменён другой программой. Обновите записи: "+path);var next=Compose(original,entries);if(original.SequenceEqual(next))return;
      if(makeBackup){string backups=Path.Combine(root,"hosts-backups");Directory.CreateDirectory(backups);File.WriteAllBytes(Path.Combine(backups,"hosts-"+DateTime.UtcNow.ToString("yyyyMMdd-HHmmss")+"-"+Guid.NewGuid().ToString("N")+".bak"),original);}
      ReplaceHostsFile(path,original,next);DnsFlushResolverCache();
    }
  }
  public sealed partial class MainForm {
    async Task<bool> OfferMetaHosts(System.Threading.CancellationToken token){
      if(!await ConfirmChange("Instagram больше не проверяется перебором стратегий Zapret. Можно посмотреть рекомендации hosts для Meta и отдельно подтвердить их применение. Другие записи hosts сохранятся; это не гарантия работы медиа.","Посмотреть рекомендации hosts"))return false;
      token.ThrowIfCancellationRequested();string entries=HostsRepair.Parse(await core.DownloadTextAsync(HostsRepair.Source));token.ThrowIfCancellationRequested();
      var done=new TaskCompletionSource<bool>();var veil=new GlassBackdrop(blueDashboard){Dock=DockStyle.Fill};var popup=new GlassPopupPanel{Size=new Size(Math.Min(800,ClientSize.Width-40),Math.Min(540,ClientSize.Height-40))};veil.Controls.Add(popup);
      Action layout=()=>popup.Location=new Point((veil.Width-popup.Width)/2,(veil.Height-popup.Height)/2);veil.Resize+=(s,e)=>layout();
      Add(popup,L("Meta · рекомендации hosts",18,true),24,20);var info=L("Будет добавлен только блок MCRF. Оригинал сохранится в резервной копии.\nIP могут устареть; после применения проверьте медиа в браузере. TLS остаётся включён.",10,false,Muted);info.AutoSize=false;info.SetBounds(24,70,popup.Width-48,64);popup.Controls.Add(info);
      var field=new GlassFieldPanel{Location=new Point(24,146),Size=new Size(popup.Width-48,popup.Height-220)};popup.Controls.Add(field);var preview=new GlassLogBox{Name="setupMetaHostsPreview",ReadOnly=true,BorderStyle=BorderStyle.None,BackColor=GlassFieldPanel.InputColor,ForeColor=Ink,Font=Font,Text=entries};field.Controls.Add(preview);preview.InstallRails(field);
      bool applying=false;var apply=B("Применить hosts",async(s,e)=>{if(applying)return;if(!IsAdministrator()){if(await ConfirmChange("Для hosts нужны права Windows. Ничего не изменено; приложение перезапустится с запросом разрешения.","Получить права и перезапустить","Доступ Windows"))RequestElevation("");return;}applying=true;((Button)s).Enabled=false;try{token.ThrowIfCancellationRequested();await Task.Run(()=>HostsRepair.Apply(store.Root,entries));done.TrySetResult(true);veil.Dispose();}catch(Exception ex){GlassNotice.Show(this,ex.Message,"hosts");}finally{applying=false;if(!((Control)s).IsDisposed)((Control)s).Enabled=true;}},true);apply.SetBounds(24,popup.Height-56,240,40);popup.Controls.Add(apply);
      var cancel=B("Не менять hosts",(s,e)=>{if(!applying)veil.Dispose();});cancel.SetBounds(popup.Width-230,popup.Height-56,206,40);popup.Controls.Add(cancel);veil.Disposed+=(s,e)=>done.TrySetResult(false);Controls.Add(veil);veil.BringToFront();layout();
      using(token.Register(()=>{if(!veil.IsDisposed)veil.BeginInvoke(new Action(()=>{if(!applying&&!veil.IsDisposed)veil.Dispose();}));})){return await done.Task;}
    }
    void HostsBlueLegacy(){
      var box=Box();box.Dock=DockStyle.Fill;content.Controls.Add(box);
      var info=L("Instagram / Facebook и Rutor: рекомендации hosts из Zapret-Manager.\nНе заменяет VPN или Zapret и не гарантирует работу. В браузере со своим DoH hosts может не применяться.",11,false,Muted);info.AutoSize=false;info.SetBounds(22,18,box.Width-44,82);box.Controls.Add(info);
      RepositoryLink(box,"hostsSource","Источник рекомендаций","https://github.com/StressOzz/Zapret-Manager",22,107,box.Width-44);
      var services=new GlassPicker{Name="hostsServices",MultiSelect=true,Location=new Point(22,148),Width=330};services.SetItems(new[]{"Instagram / Facebook","Rutor"});services.SetChecked(0,true);box.Controls.Add(services);var tracker=L("RuTracker: в источнике нет блока hosts. Защиту от ботов IP-подменой не подтверждаем.",10,false,Muted);tracker.AutoSize=false;tracker.SetBounds(370,148,box.Width-392,42);tracker.Anchor=AnchorStyles.Top|AnchorStyles.Left|AnchorStyles.Right;box.Controls.Add(tracker);
      var host=new GlassFieldPanel{Location=new Point(22,203),Size=new Size(box.Width-44,box.Height-294),Anchor=AnchorStyles.Top|AnchorStyles.Bottom|AnchorStyles.Left|AnchorStyles.Right};box.Controls.Add(host);
      var preview=new GlassLogBox{Name="hostsPreview",ReadOnly=true,BorderStyle=BorderStyle.None,Font=Font,BackColor=GlassFieldPanel.InputColor,ForeColor=GlassInk.White};host.Controls.Add(preview);preview.InstallRails(host);string entries=null;Button apply=null;services.CheckedItemsChanged+=(s,e)=>{entries=null;preview.Clear();if(apply!=null)apply.Enabled=false;};
      var fetch=B("Загрузить рекомендации",async(s,e)=>{var button=(Button)s;button.Enabled=false;services.Enabled=false;apply.Enabled=false;entries=null;try{if(services.SelectedIndices.Length==0)throw new InvalidOperationException("Выберите сервисы.");var source=await core.DownloadTextAsync(HostsRepair.Source);entries=String.Join("\r\n",services.SelectedIndices.Select(i=>HostsRepair.Parse(source,i==0?"instagram":"rutor")));preview.Text=entries;apply.Enabled=true;Toast("При нескольких IP выбран первый из рекомендаций. Проверьте записи перед применением.");}catch(Exception ex){GlassNotice.Show(this,ex.Message,"hosts");}finally{if(!button.IsDisposed)button.Enabled=true;if(!services.IsDisposed)services.Enabled=true;}});fetch.SetBounds(22,box.Height-62,248,40);fetch.Anchor=AnchorStyles.Bottom|AnchorStyles.Left;box.Controls.Add(fetch);
      apply=B("Применить hosts",async(s,e)=>{if(entries==null)return;if(!IsAdministrator()){if(await ConfirmChange("Для изменения hosts нужны права Windows. Записи пока не изменены.","Получить права и перезапустить","Нужны права Windows"))RequestElevation("");return;}if(!await ConfirmChange("Будут добавлены только показанные записи в отдельный блок MCRF. Предыдущий блок MCRF заменится. Создадим резервную копию; остальные записи сохранятся. TLS-проверка не отключается.","Добавить записи в hosts"))return;try{await Task.Run(()=>HostsRepair.Apply(store.Root,entries));Toast("Записи добавлены. Перезапустите браузер и проверьте медиа.");}catch(Exception ex){GlassNotice.Show(this,ex.Message,"hosts");}},true);apply.Enabled=false;apply.SetBounds(284,box.Height-62,198,40);apply.Anchor=AnchorStyles.Bottom|AnchorStyles.Left;box.Controls.Add(apply);
      var remove=B("Убрать записи MCRF",async(s,e)=>{if(!IsAdministrator()){if(await ConfirmChange("Для изменения hosts нужны права Windows. Записи пока не изменены.","Получить права и перезапустить","Нужны права Windows"))RequestElevation("");return;}if(!await ConfirmChange("Удалим только блок hosts, добавленный MCRF. Другие записи hosts останутся.","Удалить блок MCRF"))return;try{await Task.Run(()=>HostsRepair.Apply(store.Root,null));Toast("Блок MCRF удалён; остальные записи сохранены.");}catch(Exception ex){GlassNotice.Show(this,ex.Message,"hosts");}});remove.SetBounds(496,box.Height-62,220,40);remove.Anchor=AnchorStyles.Bottom|AnchorStyles.Left;box.Controls.Add(remove);
    }
  }
}
