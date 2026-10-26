using System;
using System.IO;
using System.Text;
using System.Threading;
using System.Threading.Tasks;
using System.Windows;
namespace Mcrf.Wpf {
 internal sealed class CompactImportServices {
  internal Func<Window,string> ChooseFile;
  internal Func<string,CancellationToken,Task<string>> LoadFile;
  internal Func<string,Task<bool>> Import;
 }
 internal static class ImportFileSource {
  internal const int MaxBytes=4*1024*1024;
  internal static string ErrorMessage(Exception error){
   if(error is FileNotFoundException||error is DirectoryNotFoundException)return "Файл не найден. Выберите его заново.";
   if(error is UnauthorizedAccessException||error is System.Security.SecurityException)return "Нет доступа к файлу. Проверьте права или выберите другой файл.";
   if(error is FormatException)return error.Message;
   if(error is IOException)return "Не удалось прочитать файл. Возможно, он занят или недоступен. Повторите попытку.";
   return "Не удалось открыть файл. Повторите выбор.";
  }
  internal static string ValidateText(string text){
   if(String.IsNullOrWhiteSpace(text))throw new FormatException("Нет текста подключений. Выберите непустой текстовый файл.");
   if(text.Length>MaxBytes)throw new FormatException("Конфигурация слишком большая. Максимум — 4 МиБ.");
   try{if(new UTF8Encoding(false,true).GetByteCount(text)>MaxBytes)throw new FormatException("Конфигурация слишком большая. Максимум — 4 МиБ.");}
   catch(EncoderFallbackException ex){throw new FormatException("Текст содержит повреждённые символы Unicode.",ex);}
   if(text.IndexOf('\0')>=0)throw new FormatException("Данные содержат нулевые символы. Нужна текстовая конфигурация.");
   return text;
  }
  internal static Task<string> ReadAsync(string path,CancellationToken token){return Task.Run(()=>{
   token.ThrowIfCancellationRequested();
   try{
    using(var stream=new FileStream(path,FileMode.Open,FileAccess.Read,FileShare.Read)){
     if(stream.Length>MaxBytes)throw new FormatException("Файл слишком большой. Максимум — 4 МиБ.");
     using(var output=new MemoryStream()){
      var buffer=new byte[8192];
      try{
       int read;while((read=stream.Read(buffer,0,buffer.Length))>0){token.ThrowIfCancellationRequested();if(output.Length>MaxBytes-read)throw new FormatException("Файл слишком большой. Максимум — 4 МиБ.");output.Write(buffer,0,read);}
       token.ThrowIfCancellationRequested();byte[] bytes=output.GetBuffer();int length=(int)output.Length,skip=0;Encoding encoding=new UTF8Encoding(false,true);
       if(length>=4&&bytes[0]==255&&bytes[1]==254&&bytes[2]==0&&bytes[3]==0){encoding=new UTF32Encoding(false,false,true);skip=4;}
       else if(length>=4&&bytes[0]==0&&bytes[1]==0&&bytes[2]==254&&bytes[3]==255){encoding=new UTF32Encoding(true,false,true);skip=4;}
       else if(length>=3&&bytes[0]==239&&bytes[1]==187&&bytes[2]==191)skip=3;
       else if(length>=2&&bytes[0]==255&&bytes[1]==254){encoding=new UnicodeEncoding(false,false,true);skip=2;}
       else if(length>=2&&bytes[0]==254&&bytes[1]==255){encoding=new UnicodeEncoding(true,false,true);skip=2;}
       // Decode the whole bounded byte sequence, including the final decoder flush.
       return ValidateText(encoding.GetString(bytes,skip,length-skip));
      }finally{Array.Clear(buffer,0,buffer.Length);var bytes=output.GetBuffer();Array.Clear(bytes,0,bytes.Length);}
     }
    }
   }catch(DecoderFallbackException ex){throw new FormatException("Не удалось прочитать кодировку. Используйте UTF-8 или Unicode с BOM.",ex);}
  },token);}
 }
}
