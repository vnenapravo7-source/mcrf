using System;
using System.IO;
using System.Linq;
using System.Text;
using System.Threading.Tasks;
using System.Threading;
using System.Collections.Generic;
using System.Web.Script.Serialization;
namespace Mcrf.Wpf {
 internal static class DeploymentOutput {
  internal const string Prefix="MCRF_RESULT|";internal const int ResultLineLimit=700000,LogLineLimit=65536,DecodedLimit=525000;
  internal static string ReadLine(TextReader reader,bool stdout){
   var text=new StringBuilder();bool possibleResult=stdout;int value;
   while((value=reader.Read())>=0){char ch=(char)value;if(ch=='\n')return text.ToString();if(ch=='\r'){if(reader.Peek()=='\n')reader.Read();return text.ToString();}
    int index=text.Length;if(index<Prefix.Length&&ch!=Prefix[index])possibleResult=false;int maximum=possibleResult?ResultLineLimit:LogLineLimit;
    if(index>=maximum)throw new IOException("Вывод установщика превышает допустимый размер строки.");text.Append(ch);
   }return text.Length==0?null:text.ToString();
  }
  internal static DeployResult ReadStdout(TextReader reader,Action<string> log){
   DeployResult result=null;bool seen=false;string line;
   while((line=ReadLine(reader,true))!=null){if(!line.StartsWith(Prefix,StringComparison.Ordinal)){log(line);continue;}
    if(seen)throw new IOException("Установщик вернул повторное подтверждение результата.");seen=true;
    if(line.Length<=Prefix.Length)throw new IOException("Установщик вернул пустой результат.");
    try{byte[] bytes=Convert.FromBase64String(line.Substring(Prefix.Length));if(bytes.Length>DecodedLimit)throw new FormatException();string json=new UTF8Encoding(false,true).GetString(bytes);result=new JavaScriptSerializer{MaxJsonLength=DecodedLimit}.Deserialize<DeployResult>(json);if(result==null)throw new FormatException();}
    catch(Exception ex){if(!(ex is FormatException)&&!(ex is ArgumentException)&&!(ex is InvalidOperationException)&&!(ex is OverflowException)&&!(ex is InvalidCastException))throw;throw new IOException("Установщик вернул повреждённый результат.");}
   }
   if(!seen)throw new IOException("Нет подтверждения результата установки.");return result;
  }
  internal static void ReadStderr(TextReader reader,Action<string> log){string line;while((line=ReadLine(reader,false))!=null){if(line.StartsWith(Prefix,StringComparison.Ordinal))throw new IOException("Результат установщика получен в потоке ошибок.");log(line);}}
  internal static void ThrowIfFaulted(Task output,Task errors,Action stop){
   var failed=new[]{output,errors}.Where(t=>t.IsFaulted||t.IsCanceled).ToArray();if(failed.Length==0)return;var causes=new List<Exception>();foreach(var task in failed){if(task.IsFaulted)causes.AddRange(task.Exception.Flatten().InnerExceptions);else causes.Add(new TaskCanceledException(task));}
   try{stop();}catch(Exception ex){causes.Add(ex);}throw new IOException("Не удалось проверить вывод установщика. Уже выполненные изменения сервера не откатываются автоматически.",new AggregateException(causes));
  }
  internal static void CompleteReaders(Task output,Task errors,Action stop){try{Task.WaitAll(output,errors);}catch(AggregateException){ThrowIfFaulted(output,errors,stop);throw;}}
  internal static void Poll(Task output,Task errors,CancellationToken cancellation,Action stop){if(cancellation.IsCancellationRequested){stop();cancellation.ThrowIfCancellationRequested();}ThrowIfFaulted(output,errors,stop);}
 }
}
