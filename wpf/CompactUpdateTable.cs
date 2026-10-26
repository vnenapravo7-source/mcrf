using System;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {public sealed partial class MainWindow {
 UIElement CompactUpdateTableRow(UpdateItem item){
  var row=new Grid{Margin=new Thickness(0,6,0,6)};row.ColumnDefinitions.Add(new ColumnDefinition());row.ColumnDefinitions.Add(new ColumnDefinition{Width=new GridLength(88)});row.ColumnDefinitions.Add(new ColumnDefinition{Width=new GridLength(52)});
  var label=new StackPanel{Margin=new Thickness(0,0,6,0)};label.Children.Add(new TextBlock{Text=item.Name,FontSize=12,TextWrapping=TextWrapping.Wrap,Foreground=Brush(CompactPrimary)});label.Children.Add(new TextBlock{Text=!String.IsNullOrEmpty(item.AppliedVersion)?"Скачано · "+item.AppliedVersion:!String.IsNullOrEmpty(item.Current)&&!item.Current.StartsWith("Не установ",StringComparison.OrdinalIgnoreCase)?"Установлено · "+item.Current:"Не установлено",FontSize=10,TextWrapping=TextWrapping.Wrap,Foreground=Brush(CompactSecondary)});row.Children.Add(label);
  var state=new TextBlock{Text=!String.IsNullOrEmpty(item.AppliedVersion)?"Готово к установке":item.Available?"Доступно обновление":item.DisplayStatus,FontSize=10,TextWrapping=TextWrapping.Wrap,Foreground=Brush(CompactPrimary),VerticalAlignment=VerticalAlignment.Center,Margin=new Thickness(0,0,6,0)};Grid.SetColumn(state,1);row.Children.Add(state);long size=item.Asset!=null?item.Asset.size:item.Package!=null?item.Package.Size:0;var amount=new TextBlock{Text=size>0?(size/1048576d).ToString("0.#")+" МБ":"—",FontSize=10,Foreground=Brush(CompactSecondary),VerticalAlignment=VerticalAlignment.Center,TextAlignment=TextAlignment.Right};Grid.SetColumn(amount,2);row.Children.Add(amount);return row;
 }
}}
