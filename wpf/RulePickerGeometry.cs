using System;
using System.Windows;
namespace Mcrf.Wpf {public sealed partial class MainWindow {
 void CenterRuleDialog(Window dialog){if(ActualWidth<=0||ActualHeight<=0)return;dialog.Left=Left+(ActualWidth-dialog.ActualWidth)/2;dialog.Top=Top+(ActualHeight-dialog.ActualHeight)/2;}
 Window LargeRulePicker(string title,Window owner){var dialog=Dialog(title,800,760);dialog.Owner=owner;dialog.Width=Math.Min(800,Math.Max(320,ActualWidth-24));dialog.Height=Math.Min(760,Math.Max(400,ActualHeight-24));dialog.WindowStartupLocation=WindowStartupLocation.Manual;dialog.Left=Left+(ActualWidth-dialog.Width)/2;dialog.Top=Top+(ActualHeight-dialog.Height)/2;dialog.Loaded+=(s,e)=>CenterRuleDialog(dialog);return dialog;}
}}
