using System;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  Button DeploymentFormAction(string label,Func<Task> action,TextBlock feedback){Button button=null;button=Button(label,async()=>{if(busy||closing||closeRequested)return;button.IsEnabled=false;feedback.Visibility=Visibility.Collapsed;try{await Run(action,()=>{feedback.Text=status.Text;feedback.Visibility=Visibility.Visible;status.Text="";});}finally{if(!closing)button.IsEnabled=true;}});return button;}
 }
}
