using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;
using System.Web.Script.Serialization;
namespace SplifyWin {
  public sealed class GameProfileFile {public int Version{get;set;}public List<RouteList> Profiles{get;set;}}
  public static class GameProfiles {
    static readonly JavaScriptSerializer Json=new JavaScriptSerializer{MaxJsonLength=2000000};
    public static bool IsGame(RouteList route){return route!=null&&(route.Name??"").StartsWith("Игра · ")||route!=null&&(route.Name??"").StartsWith("Запись · ");}
    static RouteList Clean(RouteList route){
      if(!IsGame(route)||(route.Target!="proxy"&&route.Target!="direct")||route.Text==null||route.Text.Length>100000||route.Name.Length>150)throw new InvalidDataException("Можно обмениваться только игровыми профилями «напрямую» / «VPN».");
      if(route.Text.Contains("://")||route.Text.IndexOf("path:",StringComparison.OrdinalIgnoreCase)>=0)throw new InvalidDataException("В профиле обнаружена ссылка или путь к файлу. Используйте имена процессов, домены и IP.");
      var parsed=RouteCompiler.Parse(route.Text);if(parsed["process_name"].Count==0||parsed["process_name"].Any(x=>String.IsNullOrWhiteSpace(x)||x.Length>150||x.Contains("/")||x.Contains("\\")||x.Contains(":")||x.Contains("*")||!x.EndsWith(".exe",StringComparison.OrdinalIgnoreCase))||parsed["process_path"].Count>0||parsed["ip_cidr"].Any(x=>x.EndsWith("/0")))throw new InvalidDataException("Нужны имена процессов игры (.exe); глобальные сети и локальные пути запрещены.");
      if(route.MatchMode!="apps"&&route.MatchMode!="in-apps")throw new InvalidDataException("Игровой профиль должен ограничиваться процессами игры.");
      var clean=new RouteList{Name=route.Name,Text=route.Text,Target=route.Target,MatchMode=route.MatchMode,Enabled=route.Enabled};RouteCompiler.Compile(clean,clean.Target);return clean;
    }
    public static string Export(IEnumerable<RouteList> routes){var list=routes.Where(IsGame).Select(Clean).ToList();if(list.Count==0||list.Count>100)throw new InvalidDataException("Выберите от 1 до 100 игровых профилей.");return Json.Serialize(new GameProfileFile{Version=1,Profiles=list});}
    public static RouteList[] Import(string text){if(text==null||text.Length>2000000)throw new InvalidDataException("Профиль слишком большой.");var file=Json.Deserialize<GameProfileFile>(text);if(file==null||file.Version!=1||file.Profiles==null||file.Profiles.Count==0||file.Profiles.Count>100)throw new InvalidDataException("Неизвестный формат игровых профилей.");return file.Profiles.Select(Clean).ToArray();}
    public static void Save(string path,IEnumerable<RouteList> routes){File.WriteAllText(path,Export(routes),new UTF8Encoding(false));}
  }
}
