using System;
using System.Linq;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {public sealed partial class MainWindow {
 UIElement CompactBsSiteFields(){
  var panel=new StackPanel{Margin=new Thickness(0,8,0,0)};var saved=BsDetectionSites.Load(session.State.AutoBsSites);var inputs=new ComboBox[3];
  for(int i=0;i<3;i++){var input=new ComboBox{Name="compactBsSite"+(i+1),IsEditable=true,IsTextSearchEnabled=false,ItemsSource=new[]{saved[i]}.Concat(BsDetectionSites.Presets).Distinct().ToArray(),Text=saved[i],MinWidth=0};inputs[i]=input;panel.Children.Add(Field(i<2?"Прямой доступ · сайт "+(i+1):"Через VPN · сайт 3",input));}
  var feedback=Text("",11);panel.Children.Add(feedback);var save=Button("Сохранить сайты",async()=>{try{var sites=inputs.Select(x=>BsDetectionSites.Normalize(x.Text)).ToArray();await CompactPersist(()=>session.State.AutoBsSites=sites,()=>{});var actual=BsDetectionSites.Load(session.State.AutoBsSites);if(actual.SequenceEqual(sites)){lastBsDetection=DateTime.MinValue;feedback.Text="Сайты сохранены.";}}catch(Exception ex){feedback.Text=FailureExplanations.UserMessage(JournalStyle.Redact(ex.Message));}});save.Name="compactSaveBsSites";panel.Children.Add(save);return panel;
 }
}}
