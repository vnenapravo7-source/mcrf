using System;
using System.Collections.Generic;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  internal static string RouteScope(RouteList route){
   var excluded=new List<string>();if(route.ExcludeRussia)excluded.Add("РФ (GeoIP / Geosite)");if(route.ExcludeTorrents)excluded.Add("торренты");if(!String.IsNullOrWhiteSpace(route.ExcludedText))excluded.Add("свои исключения");
   if(excluded.Count==0)return "";
   if(route.Target!="proxy"&&!WarpOutputs.IsTarget(route.Target))return "Исключения РФ / торрентов применяются только к выходу VPN или WARP.";
   return "Напрямую: "+String.Join(", ",excluded)+". Остальные записи — через выбранный выход.";
  }
  FrameworkElement RouteNameCell(RouteList route){
   var panel=new StackPanel{Margin=new Thickness(0,3,0,3),VerticalAlignment=VerticalAlignment.Center};var name=CellText(route.Name);name.Foreground=Brush(route.Name.Contains("Популярные сервисы")?"#FFDD74":"#F0F8FF");panel.Children.Add(name);
   string scope=RouteScope(route);panel.ToolTip=route.Name+(scope.Length>0?"\n"+scope:"");
   if(scope.Length>0){var hint=CellText(route.ExcludeRussia?"РФ → напрямую":"Есть прямые исключения");hint.FontSize=11;hint.Foreground=Brush("#6BFAD8");panel.Children.Add(hint);}return panel;
  }
 }
}
