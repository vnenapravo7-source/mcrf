using System;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
namespace Mcrf.Wpf {public sealed partial class MainWindow {
 static readonly int[] SubscriptionHours={0,1,3,6,12,24,72,168};
 DateTime lastSubscriptionAttempt=DateTime.UtcNow;
 async Task PollSubscriptionUpdates(){if(diagnostic||session==null||busy||closing||closeRequested||!SubscriptionHours.Contains(session.State.SubscriptionUpdateHours)||session.State.SubscriptionUpdateHours==0)return;if(DateTime.UtcNow-lastSubscriptionAttempt<TimeSpan.FromHours(session.State.SubscriptionUpdateHours))return;lastSubscriptionAttempt=DateTime.UtcNow;if(!(session.State.SubscriptionUrls??new System.Collections.Generic.List<string>()).Concat(new[]{session.State.SubscriptionUrl}).Any(x=>!String.IsNullOrWhiteSpace(x)))return;await Run(()=>RefreshSubscriptions(true),()=>{});}
 UIElement CompactSubscriptionSettings(){var panel=new StackPanel();var picker=new ComboBox{Name="subscriptionInterval",ItemsSource=new[]{"Вручную","Раз в час","Раз в 3 часа","Раз в 6 часов","Раз в 12 часов","Раз в день","Раз в 3 дня","Раз в неделю"},SelectedIndex=Math.Max(0,Array.IndexOf(SubscriptionHours,session.State.SubscriptionUpdateHours))};panel.Children.Add(Field("Обновлять подписки",picker));picker.SelectionChanged+=async(s,e)=>{if(picker.SelectedIndex<0||busy)return;int previous=session.State.SubscriptionUpdateHours,next=SubscriptionHours[picker.SelectedIndex];await CompactPersist(()=>session.State.SubscriptionUpdateHours=next,()=>picker.SelectedIndex=Math.Max(0,Array.IndexOf(SubscriptionHours,previous)));lastSubscriptionAttempt=DateTime.UtcNow;};var update=AsyncButton("Обновить сейчас",UpdateSubscriptions);update.Name="subscriptionRefreshNow";panel.Children.Add(update);return panel;}
}}
