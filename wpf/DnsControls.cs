using System;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  static void ValidateDns(ExitDnsSettings dns){if(dns==null||dns.Provider=="Общий DNS")return;DnsOptions.Build(new ClientState{DnsProvider=dns.Provider,DnsProtocol=dns.Protocol,DnsCustom=dns.Custom});}
  UIElement GlobalDnsRow(){var outer=new StackPanel();var row=new Grid{Margin=new Thickness(0,10,0,0)};foreach(var width in new[]{GridLength.Auto,new GridLength(170),new GridLength(150),new GridLength(1,GridUnitType.Star),GridLength.Auto})row.ColumnDefinitions.Add(new ColumnDefinition{Width=width});var label=Text("Общий DNS");label.VerticalAlignment=VerticalAlignment.Center;label.Margin=new Thickness(0,0,20,0);row.Children.Add(label);var provider=new ComboBox{ItemsSource=DnsOptions.Providers,SelectedItem=session.State.DnsProvider,Margin=new Thickness(0,0,12,0)};var protocol=new ComboBox{ItemsSource=DnsOptions.Protocols(session.State.DnsProvider),SelectedItem=session.State.DnsProtocol,Margin=new Thickness(0,0,12,0)};var custom=new TextBox{Text=session.State.DnsCustom,Visibility=session.State.DnsProvider=="Свой"?Visibility.Visible:Visibility.Collapsed};provider.SelectionChanged+=(s,e)=>{protocol.ItemsSource=DnsOptions.Protocols(Convert.ToString(provider.SelectedItem));protocol.SelectedIndex=0;custom.Visibility=Convert.ToString(provider.SelectedItem)=="Свой"?Visibility.Visible:Visibility.Collapsed;};Grid.SetColumn(provider,1);row.Children.Add(provider);Grid.SetColumn(protocol,2);row.Children.Add(protocol);var apply=AsyncButton("Применить DNS",async()=>{var dns=new ExitDnsSettings{Provider=Convert.ToString(provider.SelectedItem),Protocol=Convert.ToString(protocol.SelectedItem),Custom=custom.Text.Trim()};ValidateDns(dns);await session.ChangeConfiguration(()=>{session.State.DnsProvider=dns.Provider;session.State.DnsProtocol=dns.Protocol;session.State.DnsCustom=dns.Custom;});status.Text="Общий DNS сохранён.";});Accent(apply);Grid.SetColumn(apply,4);row.Children.Add(apply);outer.Children.Add(row);outer.Children.Add(custom);return outer;}
 }
}

