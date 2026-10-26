using System.Linq;
using System.Windows;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  Window CurrentDialogHost(){Window host=detachedWorkspaceWindow??this;while(true){var visible=host.OwnedWindows.Cast<Window>().Where(w=>w.IsVisible).ToArray();var child=visible.LastOrDefault(w=>w.IsActive)??visible.LastOrDefault();if(child==null)return host;host=child;}}
 }
}
