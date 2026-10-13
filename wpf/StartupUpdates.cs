using System;
using System.Threading.Tasks;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  async void CheckStartupUpdates(){try{var snapshot=session.ConfigurationSnapshot();await Task.Run(()=>session.Updates.Check(snapshot));RecordEvent("Обновления: "+session.Updates.Activity);if(!closing&&current=="Обновления"){body.Children.Clear();UpdatesPage();}}catch(Exception ex){if(!closing&&current=="Обновления")status.Text=ex.Message;}}
 }
}
