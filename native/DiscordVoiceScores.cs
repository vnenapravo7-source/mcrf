using System;
using System.Collections.Generic;
using System.Linq;
namespace SplifyWin {
 public static class DiscordVoiceScores {
public static void Configure(ZapretSettings settings,ZapretCheckRow row){var voice=row.Services.FirstOrDefault(s=>s.Id=="discord-voice");settings.DiscordVoiceEnabled=voice!=null&&voice.Access==ServiceAccess.Available;settings.DiscordInterfaceStrategy=voice==null||String.IsNullOrEmpty(voice.InterfaceStrategyId)?row.StrategyId:voice.InterfaceStrategyId;}
  public static readonly ZapretService Service=new ZapretService{Id="discord-voice",Name="Discord: голос",Group="Основные",Domains=ZapretChecks.Services.First(s=>s.Id=="discord").Domains,Urls=new string[0]};
  public static void Apply(ZapretCheckReport report,IEnumerable<Dictionary<string,object>> scores){
   foreach(var score in scores){var row=report.Rows.FirstOrDefault(r=>r.StrategyId==Convert.ToString(score["StrategyId"]));if(row==null)continue;string result=Convert.ToString(score["Result"]);row.Services.RemoveAll(s=>s.Id==Service.Id);row.Services.Add(new ZapretServiceResult{Id=Service.Id,InterfaceStrategyId=score.ContainsKey("InterfaceStrategyId")?Convert.ToString(score["InterfaceStrategyId"]):"",Access=result=="user-confirmed"?ServiceAccess.Available:result=="user-unavailable"?ServiceAccess.Unavailable:ServiceAccess.Unknown,PingQuality=score.ContainsKey("PingQuality")?Convert.ToString(score["PingQuality"]):"",Detail=score.ContainsKey("Detail")?Convert.ToString(score["Detail"]):"Слышимость и качество соединения отмечены пользователем; это не измерение пинга программой."});}
  }
 }
}
