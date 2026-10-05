using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Web.Script.Serialization;

namespace SplifyWin {
  // A signed media address is short-lived. Never log it or persist it in reports.
  internal sealed class VideoProbe : IDisposable {
    readonly object sync=new object();readonly HashSet<BrowserVideoProbe> active=new HashSet<BrowserVideoProbe>();bool disposed;
    readonly SemaphoreSlim isolated=new SemaphoreSlim(2);
    readonly string proxy,root;
    string selectedShorts=ShortsTarget;
    internal VideoProbe(string executable,string options){root=Path.GetDirectoryName(executable);var match=System.Text.RegularExpressions.Regex.Match(options,@"--proxy\s+socks5h?://127\.0\.0\.1:(\d+)");proxy=match.Success?"socks5://127.0.0.1:"+match.Groups[1].Value:null;VideoBrowserLibraries.Install(root);}
    internal const string PornhubTarget="https://rt.pornhub.com/";
    internal const string Target="https://www.youtube.com/watch?v=jNQXAC9IVRw";
    internal const string ShortsTarget="https://www.youtube.com/results?search_query=%23shorts";
    internal static bool Allowed(string address){Uri uri;return Uri.TryCreate(address,UriKind.Absolute,out uri)&&uri.Scheme=="https"&&uri.Port==443&&uri.UserInfo.Length==0&&uri.Fragment.Length==0&&(uri.Host.EndsWith(".googlevideo.com",StringComparison.OrdinalIgnoreCase)||uri.Host=="googlevideo.com");}
    internal static string PlayerJson(string text){
      int marker=text.IndexOf("ytInitialPlayerResponse",StringComparison.Ordinal);if(marker<0)return null;
      int start=text.IndexOf('{',marker);if(start<0||start-marker>160)return null;int depth=0;bool quoted=false,escape=false;
      for(int i=start;i<text.Length;i++){char c=text[i];if(quoted){if(escape)escape=false;else if(c=='\\')escape=true;else if(c=='\"')quoted=false;}else if(c=='\"')quoted=true;else if(c=='{')depth++;else if(c=='}'&&--depth==0)return text.Substring(start,i-start+1);}return null;
    }
    internal static string Extract(string text){
      string json=text.TrimStart().StartsWith("{",StringComparison.Ordinal)?text:PlayerJson(text);if(json==null)return null;var root=new JavaScriptSerializer{MaxJsonLength=2097152}.DeserializeObject(json) as Dictionary<string,object>;object value;
      if(root==null||!root.TryGetValue("streamingData",out value))return null;var data=value as Dictionary<string,object>;if(data==null)return null;
      // Prefer a combined MP4; do not guess signatures or disable TLS checks.
      foreach(string key in new[]{"formats","adaptiveFormats"}){if(!data.TryGetValue(key,out value))continue;var items=value as IEnumerable;if(items==null)continue;foreach(object item in items){var format=item as Dictionary<string,object>;object url,mime;if(format!=null&&format.TryGetValue("url",out url)&&format.TryGetValue("mimeType",out mime)&&Convert.ToString(mime).StartsWith("video/",StringComparison.OrdinalIgnoreCase)&&Allowed(Convert.ToString(url)))return Convert.ToString(url);}}
      return data.TryGetValue("hlsManifestUrl",out value)&&Allowed(Convert.ToString(value))?Convert.ToString(value):null;
    }
    internal static bool VideoBytes(byte[] body){
      if(body==null||body.Length<65536)return false;
      string box=Encoding.ASCII.GetString(body,4,4);if(box=="ftyp"||box=="styp"||box=="moof")return true;
      if(body[0]==0x1a&&body[1]==0x45&&body[2]==0xdf&&body[3]==0xa3)return true;
      return body[0]==0x47&&body[188]==0x47&&body[376]==0x47;
    }
    internal static string Segment(string playlist,string source){
      if(!playlist.TrimStart().StartsWith("#EXTM3U",StringComparison.Ordinal))return null;
      foreach(string line in playlist.Split('\n')){string candidate=line.Trim();if(candidate.Length==0||candidate.StartsWith("#"))continue;Uri uri;if(Uri.TryCreate(new Uri(source),candidate,out uri)&&Allowed(uri.AbsoluteUri))return uri.AbsoluteUri;}return null;
    }
    internal Task<ZapretEndpointResult> Run(CancellationToken token){return RunMediaFor(Target,token);}
    internal async Task<ZapretEndpointResult> RunMediaFor(string target,CancellationToken token){
      var normal=await RunSingle(target,token);if(target!=Target||normal.Access!=ServiceAccess.Available)return normal;
      var shorts=await RunSingle(selectedShorts,token);if(shorts.Access==ServiceAccess.Available&&shorts.Url.StartsWith("https://www.youtube.com/shorts/",StringComparison.Ordinal))selectedShorts=shorts.Url;shorts.Url=Target;shorts.Milliseconds+=normal.Milliseconds;
      shorts.Detail=shorts.Access==ServiceAccess.Available?"Обычное видео и случайный Shorts воспроизводятся: время и кадры растут.":"Обычное видео работает. Shorts: "+shorts.Detail;
      return shorts;
    }
    internal async Task<ZapretEndpointResult> RunSingle(string target,CancellationToken token){
      await isolated.WaitAsync(token);BrowserVideoProbe fresh=null;try{lock(sync){if(disposed)throw new ObjectDisposedException("VideoProbe");fresh=new BrowserVideoProbe(proxy,target);active.Add(fresh);}return await fresh.Run(token);}finally{if(fresh!=null){lock(sync)active.Remove(fresh);fresh.Dispose();}isolated.Release();}
    }
    public void Dispose(){lock(sync){disposed=true;foreach(var browser in active)browser.Dispose();}}
  }
}

