using System.Windows;
using System.Windows.Controls;
using System.Windows.Media;

namespace Mcrf.Wpf {
 partial class MainWindow {
  void ApplyCompactPickerPalette(ComboBox picker) {
   picker.Resources["PickerHover"] = Brush(CompactLight ? "#E1ECEC" : "#31434A");
   picker.Resources["PickerSelected"] = Brush(CompactLight ? "#C9E3E1" : "#354F55");
   var style = new Style(typeof(ComboBoxItem), (Style)FindResource(typeof(ComboBoxItem)));
   style.Setters.Add(new Setter(Control.ForegroundProperty, Brush(CompactPrimary)));
   style.Setters.Add(new Setter(Control.HorizontalContentAlignmentProperty, HorizontalAlignment.Stretch));
   picker.ItemContainerStyle = style;
  }
 }
}
