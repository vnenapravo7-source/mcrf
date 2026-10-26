using System;
using System.Linq;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Controls;
using System.Windows.Controls.Primitives;
using System.Windows.Input;
using System.Windows.Media;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  async Task DeleteRoute(RouteList route){if(!Confirm("Удалить список «"+route.Name+"»?","Маршрутизация"))return;await ChangeSavedRoute(route.Id,current=>session.State.Lists.Remove(current));}
  internal static void ReorderRoute(System.Collections.Generic.List<RouteList> routes,RouteList moving,RouteList destination,bool after){if(moving==null||destination==null)return;moving=routes.FirstOrDefault(r=>r.Id==moving.Id);destination=routes.FirstOrDefault(r=>r.Id==destination.Id);int target=routes.IndexOf(destination);if(moving==null||target<0||moving==destination)return;int source=routes.IndexOf(moving);if(source<0)return;routes.RemoveAt(source);target=routes.IndexOf(destination)+(after?1:0);routes.Insert(target,moving);}
  static T VisualAncestor<T>(DependencyObject child)where T:DependencyObject{while(child!=null){var found=child as T;if(found!=null)return found;child=child is Visual||child is System.Windows.Media.Media3D.Visual3D?VisualTreeHelper.GetParent(child):LogicalTreeHelper.GetParent(child);}return null;}
  void InstallRouteDrag(DataGrid table){
   table.AllowDrop=true;Point start=new Point();RouteList pending=null;bool dragging=false;
   table.PreviewMouseLeftButtonDown+=(s,e)=>{var source=e.OriginalSource as DependencyObject;if(VisualAncestor<ButtonBase>(source)!=null||VisualAncestor<ComboBox>(source)!=null||VisualAncestor<TextBox>(source)!=null){pending=null;return;}var row=VisualAncestor<DataGridRow>(source);pending=row==null?null:row.Item as RouteList;start=e.GetPosition(table);};
   table.PreviewMouseMove+=(s,e)=>{if(dragging||pending==null||e.LeftButton!=MouseButtonState.Pressed||busy)return;Point next=e.GetPosition(table);if(Math.Abs(next.X-start.X)<SystemParameters.MinimumHorizontalDragDistance&&Math.Abs(next.Y-start.Y)<SystemParameters.MinimumVerticalDragDistance)return;dragging=true;try{DragDrop.DoDragDrop(table,new DataObject("MCRF.Route",pending),DragDropEffects.Move);}finally{pending=null;dragging=false;}};
   table.DragOver+=(s,e)=>{e.Effects=!busy&&e.Data.GetDataPresent("MCRF.Route")?DragDropEffects.Move:DragDropEffects.None;e.Handled=true;var position=e.GetPosition(table);var scroll=FindScrollViewer(table);if(scroll!=null){if(position.Y<42)scroll.LineUp();else if(position.Y>table.ActualHeight-32)scroll.LineDown();}};
   table.Drop+=async(s,e)=>{if(!e.Data.GetDataPresent("MCRF.Route")||busy)return;var moving=e.Data.GetData("MCRF.Route") as RouteList;var row=VisualAncestor<DataGridRow>(e.OriginalSource as DependencyObject);var target=row==null?null:row.Item as RouteList;if(target==null)return;bool after=e.GetPosition(row).Y>row.ActualHeight/2;e.Handled=true;await Run(()=>RouteChange(()=>ReorderRoute(session.State.Lists,moving,target,after)));};
  }
  static ScrollViewer FindScrollViewer(DependencyObject parent){for(int i=0;i<VisualTreeHelper.GetChildrenCount(parent);i++){var child=VisualTreeHelper.GetChild(parent,i);var found=child as ScrollViewer??FindScrollViewer(child);if(found!=null)return found;}return null;}
 }
}
