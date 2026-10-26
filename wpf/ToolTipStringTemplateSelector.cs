using System.Windows;
using System.Windows.Controls;
using System.Windows.Data;
namespace Mcrf.Wpf {
 public sealed class ToolTipStringTemplateSelector:DataTemplateSelector {
  readonly DataTemplate textTemplate;
  public ToolTipStringTemplateSelector(){var text=new FrameworkElementFactory(typeof(TextBlock));text.SetBinding(TextBlock.TextProperty,new Binding());text.SetValue(TextBlock.TextWrappingProperty,TextWrapping.Wrap);textTemplate=new DataTemplate{VisualTree=text};}
  public override DataTemplate SelectTemplate(object item,DependencyObject container){return item is string?textTemplate:null;}
 }
}
