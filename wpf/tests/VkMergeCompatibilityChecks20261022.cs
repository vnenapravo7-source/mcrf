using System;
using System.Linq;
using SplifyWin;
class VkMergeCompatibilityChecks {
 static int Main(){string valid=new string('A',43);var original=new[]{"abc_def",new string('B',42)+"/",valid};var merged=VkTurnSettings.Merge(original,new[]{"https://vk.ru/call/join/"+valid},32);if(merged.Length!=3||merged[0]!="abc_def"||merged[1]!=new string('B',42)+"_")return 1;Console.WriteLine("PASS supplements preserve original parsed QWDTT and legacy base64 hashes");bool rejected=false;try{VkTurnSettings.Merge(original,new[]{"invalid manual call"},32);}catch(FormatException){rejected=true;}if(!rejected)return 1;Console.WriteLine("PASS manual supplements still require validated VK calls");return 0;}
}
