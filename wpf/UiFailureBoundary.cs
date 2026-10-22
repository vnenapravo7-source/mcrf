using System;
namespace Mcrf.Wpf {
 // WPF can dispatch another layout error while a modal message pumps messages.
 // Detach the damaged view before presenting anything and never nest dialogs.
 public sealed class UiFailureBoundary {
  bool handling;
  public bool Handle(Exception error,Action<Exception> recover,Action<Exception> report){if(handling)return false;handling=true;try{recover(error);report(error);return true;}finally{handling=false;}}
  public static bool IsItemsFailure(Exception error){for(var e=error;e!=null;e=e.InnerException)if(e.Message.IndexOf("ItemsControl",StringComparison.OrdinalIgnoreCase)>=0&&e.Message.IndexOf("inconsistent",StringComparison.OrdinalIgnoreCase)>=0)return true;return false;}
 }
 public sealed partial class MainWindow {
  bool uiRecoveryPending;
  void CompleteUiRecovery(){if(!uiRecoveryPending||closing||busy)return;uiRecoveryPending=false;Page(current);}
  internal void RecoverUiFailure(Exception error){if(!UiFailureBoundary.IsItemsFailure(error))return;body.Children.Clear();uiRecoveryPending=true;Dispatcher.BeginInvoke(System.Windows.Threading.DispatcherPriority.ContextIdle,new Action(CompleteUiRecovery));}
 }
}
