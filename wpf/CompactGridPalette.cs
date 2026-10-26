using System;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Media;
using System.Windows.Threading;

namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  void ApplyCompactGridPalette(DataGrid table){
   bool rules=table.Name=="compactRulesTable";if(rules){table.UseLayoutRounding=true;table.SnapsToDevicePixels=true;table.GridLinesVisibility=DataGridGridLinesVisibility.None;table.ClipToBounds=true;}
   table.Foreground=Brush(CompactPrimary);table.Background=Brushes.Transparent;table.RowBackground=Brushes.Transparent;table.AlternatingRowBackground=Brush(AlphaColor(CompactAccentColor,18));if(rules){table.RowBackground=Brush(CompactLight?"#F5F8FA":"#141B24");table.AlternatingRowBackground=Brush(CompactLight?"#EDF3F5":"#19232A");}table.HorizontalGridLinesBrush=Brush(CompactOutline);table.BorderBrush=Brush(CompactOutline);
   var header=new Style(typeof(DataGridColumnHeader),(Style)FindResource(typeof(DataGridColumnHeader)));header.Setters.Add(new Setter(Control.BackgroundProperty,Brush(CompactSurface)));header.Setters.Add(new Setter(Control.ForegroundProperty,Brush(CompactPrimary)));table.ColumnHeaderStyle=header;
   var rows=new Style(typeof(DataGridRow));rows.Setters.Add(new Setter(Control.ForegroundProperty,Brush(CompactPrimary)));var selection=new Trigger{Property=DataGridRow.IsSelectedProperty,Value=true};selection.Setters.Add(new Setter(Control.BackgroundProperty,Brush(rules?(CompactLight?"#D0E6E3":"#214540"):AlphaColor(CompactAccentColor,65))));selection.Setters.Add(new Setter(Control.ForegroundProperty,Brush(CompactPrimary)));rows.Triggers.Add(selection);table.RowStyle=rows;
   var cells=new Style(typeof(DataGridCell),(Style)FindResource(typeof(DataGridCell)));cells.Setters.Add(new Setter(Control.ForegroundProperty,Brush(CompactPrimary)));var cellTemplate=new ControlTemplate(typeof(DataGridCell));var cellBorder=new FrameworkElementFactory(typeof(Border));cellBorder.SetBinding(Border.BackgroundProperty,new System.Windows.Data.Binding("Background"){RelativeSource=new System.Windows.Data.RelativeSource(System.Windows.Data.RelativeSourceMode.TemplatedParent)});cellBorder.SetValue(Border.PaddingProperty,new Thickness(rules?3:5,3,rules?3:5,3));var presenter=new FrameworkElementFactory(typeof(ContentPresenter));presenter.SetValue(FrameworkElement.VerticalAlignmentProperty,VerticalAlignment.Center);cellBorder.AppendChild(presenter);cellTemplate.VisualTree=cellBorder;cells.Setters.Add(new Setter(Control.TemplateProperty,cellTemplate));var selectedCell=new Trigger{Property=DataGridCell.IsSelectedProperty,Value=true};selectedCell.Setters.Add(new Setter(Control.BackgroundProperty,rules?Brushes.Transparent:Brush(AlphaColor(CompactAccentColor,65))));selectedCell.Setters.Add(new Setter(Control.ForegroundProperty,Brush(CompactPrimary)));cells.Triggers.Add(selectedCell);table.CellStyle=cells;
   if(!table.Resources.Contains("CompactPaletteRows")){table.Resources["CompactPaletteRows"]=true;table.LoadingRow+=(s,e)=>{var row=e.Row;Dispatcher.BeginInvoke(new Action(()=>{if(row.IsLoaded)ApplyCompactControlPalette(row);}),DispatcherPriority.Loaded);};}
  }
 }
}
