using System;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {public sealed partial class MainWindow {
 UIElement VkCallFields(out Func<string[]> read,out Action<string[]> replace){
  var source=new TextBox{Name="deployVkCall"};var view=VkCallRows(source,32);
  read=()=>VkCalls.CallLinks(source.Text);replace=calls=>{if(calls==null)return;var values=calls.Select(VkCalls.CallHash).Distinct(StringComparer.Ordinal).Select(x=>"https://vk.ru/call/join/"+x).ToArray();if(values.Length>32)throw new FormatException("Не больше 32 звонков.");source.Text=String.Join("\n",values);};return view;
 }
}}
