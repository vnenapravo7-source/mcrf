using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Net;
using System.Collections.Generic;
using System.Security.Cryptography;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;
using System.Web;
using System.Web.Script.Serialization;
namespace SplifyWin {
 public sealed class VkLoginToken {public string Value;public DateTime Expires;}
 public static class VkCalls {
  public const string Redirect="https://oauth.vk.ru/blank.html";
  public static string NewState(){var bytes=new byte[32];using(var random=RandomNumberGenerator.Create())random.GetBytes(bytes);return Convert.ToBase64String(bytes).TrimEnd('=').Replace('+','-').Replace('/','_');}
  public static string LoginUrl(string state){return "https://oauth.vk.ru/authorize?client_id=54670800&redirect_uri="+Uri.EscapeDataString(Redirect)+"&display=mobile&response_type=token&v=5.199&state="+Uri.EscapeDataString(state);}
  public static bool AllowedPage(string raw){Uri u;return Uri.TryCreate(raw,UriKind.Absolute,out u)&&u.Scheme=="https"&&u.Port==443&&u.UserInfo.Length==0&&(u.Host=="vk.ru"||u.Host.EndsWith(".vk.ru")||u.Host=="vk.com"||u.Host.EndsWith(".vk.com"));}
  public static bool Callback(string raw){Uri u;return Uri.TryCreate(raw,UriKind.Absolute,out u)&&u.GetLeftPart(UriPartial.Path)==Redirect&&u.UserInfo.Length==0&&u.Port==443;}
  public static VkLoginToken ParseToken(string raw,string state){Uri u;if(!Callback(raw)||!Uri.TryCreate(raw,UriKind.Absolute,out u)||u.Query.Length>0||raw.Length>16384)throw new FormatException("VK вернул неподходящий адрес входа.");var q=HttpUtility.ParseQueryString(u.Fragment.TrimStart('#'));if(q.AllKeys.Any(k=>k==null||q.GetValues(k).Length!=1))throw new FormatException("Повторяющиеся параметры ответа VK.");string actual=q["state"]??"";int difference=actual.Length^state.Length;for(int i=0;i<Math.Min(actual.Length,state.Length);i++)difference|=actual[i]^state[i];int seconds;string token=q["access_token"];if(difference!=0||q["error"]!=null||!Regex.IsMatch(token??"", @"^[A-Za-z0-9._~+/-]{1,8192}=*$")||!Regex.IsMatch(q["user_id"]??"", @"^[1-9][0-9]{0,18}$")||!Int32.TryParse(q["expires_in"],out seconds)||seconds<0||seconds>31536000)throw new FormatException("Ответ VK не прошёл проверку. Звонок не создавался.");return new VkLoginToken{Value=token,Expires=DateTime.UtcNow.AddSeconds(seconds==0?86400:Math.Min(seconds,86400))};}
  public static string CallHash(string raw){Uri u;string hash=raw.Trim();if(Uri.TryCreate(hash,UriKind.Absolute,out u)){if(!AllowedPage(hash)||!u.AbsolutePath.StartsWith("/call/join/"))throw new FormatException("Нужна ссылка звонка VK вида https://vk.ru/call/join/…");hash=u.AbsolutePath.Substring("/call/join/".Length);}if(!Regex.IsMatch(hash,@"^[A-Za-z0-9_=-]{16,2048}$"))throw new FormatException("Некорректная ссылка или хеш звонка VK.");return hash;}
  public static bool ValidProof(string value){return Regex.IsMatch(value??"",@"^[A-Za-z0-9._~+/=-]{1,16384}$");}
  public static string[] CallLinks(string raw){var calls=Regex.Split(raw??"",@"[\s,;]+").Where(v=>!String.IsNullOrWhiteSpace(v)).Select(v=>"https://vk.ru/call/join/"+CallHash(v)).Distinct().ToArray();if(calls.Length==0||calls.Length>32)throw new FormatException("Добавьте от 1 до 32 ссылок звонков VK.");return calls;}
  public static async Task<string[]> CreateBatch(int count,Func<Task<string>> create,Action<string[]> progress,CancellationToken cancellation){
   if(count<1||count>6)throw new ArgumentOutOfRangeException("count");var calls=new List<string>();
   try{for(int i=0;i<count;i++){cancellation.ThrowIfCancellationRequested();string link="https://vk.ru/call/join/"+CallHash(await create());if(calls.Contains(link))throw new InvalidOperationException("VK вернул повторную ссылку; создание остановлено.");calls.Add(link);if(progress!=null)progress(calls.ToArray());if(i+1<count)await Task.Delay(500,cancellation);}return calls.ToArray();}
   catch(Exception ex){if(calls.Count==0)throw;throw new InvalidOperationException("Создано звонков: "+calls.Count+" из "+count+". Полученные ссылки сохранены в поле; автоматического повтора нет. "+(ex is OperationCanceledException?"Создание отменено.":ex.Message));}
  }
  public static async Task<string> CreateCall(VkLoginToken token,Func<string,Task<string>> manualCaptcha,CancellationToken cancellation){
   if(token==null||DateTime.UtcNow>=token.Expires)throw new InvalidOperationException("Войдите в VK ещё раз.");
   var fields=new Dictionary<string,string>{{"access_token",token.Value},{"v","5.199"}};var response=await Post(fields,cancellation);
   object error;if(response.TryGetValue("error",out error)){var detail=error as Dictionary<string,object>;int code=detail!=null&&detail.ContainsKey("error_code")?Convert.ToInt32(detail["error_code"]):-1;
    if(code==14&&detail.ContainsKey("redirect_uri")&&manualCaptcha!=null){string url=Convert.ToString(detail["redirect_uri"]);if(!AllowedPage(url))throw new InvalidOperationException("VK вернул неподходящий адрес капчи.");string proof=await manualCaptcha(url);if(!ValidProof(proof))throw new OperationCanceledException();fields["captcha_sid"]=Convert.ToString(detail.ContainsKey("captcha_sid")?detail["captcha_sid"]:"");fields["success_token"]=proof;fields["captcha_key"]="";response=await Post(fields,cancellation);}
    if(response.TryGetValue("error",out error)){detail=error as Dictionary<string,object>;code=detail!=null&&detail.ContainsKey("error_code")?Convert.ToInt32(detail["error_code"]):-1;throw new InvalidOperationException("VK не выдал ссылку звонка (код "+code+"). Автоматический повтор не выполнялся; можно вставить готовую ссылку.");}
   }
   object value;if(!response.TryGetValue("response",out value)||!(value is Dictionary<string,object>))throw new IOException("VK не подтвердил создание звонка. Повторный запрос не отправлялся.");var data=(Dictionary<string,object>)value;foreach(string key in new[]{"join_link","vk_join_link","link"})if(data.ContainsKey(key)){string link=Convert.ToString(data[key]);try{return "https://vk.ru/call/join/"+CallHash(link);}catch(FormatException){}}
   throw new IOException("VK создал звонок, но не вернул подходящую ссылку. Проверьте звонки VK; повтор не отправлялся.");
  }
  static Task<Dictionary<string,object>> Post(Dictionary<string,string> fields,CancellationToken token){return Task.Run(()=>{var request=(HttpWebRequest)WebRequest.Create("https://api.vk.ru/method/calls.start");request.Method="POST";request.AllowAutoRedirect=false;request.Timeout=request.ReadWriteTimeout=20000;request.UserAgent="MCRF";request.ContentType="application/x-www-form-urlencoded";byte[] bytes=Encoding.UTF8.GetBytes(String.Join("&",fields.Select(p=>Uri.EscapeDataString(p.Key)+"="+Uri.EscapeDataString(p.Value))));using(token.Register(()=>request.Abort())){using(var stream=request.GetRequestStream())stream.Write(bytes,0,bytes.Length);try{using(var response=request.GetResponse())using(var reader=new StreamReader(response.GetResponseStream())){string text=reader.ReadToEnd();if(text.Length>1048576)throw new IOException("Слишком большой ответ VK.");return new JavaScriptSerializer().Deserialize<Dictionary<string,object>>(text);}}catch(WebException){token.ThrowIfCancellationRequested();throw new IOException("Запрос VK не подтверждён. Автоматический повтор не выполнялся, чтобы не создавать лишние звонки.");}}},token);}
 }
}
