using System;
using System.Linq;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  DataGrid CheckTable(ZapretCheckReport report){var table=Table(new ResultRow[0],"Стратегия|Name");table.Columns[0].Width=240;foreach(var service in ZapretChecks.Services.Where(s=>(report.ServiceIds??(report.Settings==null?null:report.Settings.CheckServices)??new System.Collections.Generic.List<string>()).Contains(s.Id))){string id=service.Id;var column=CellColumn(service.CheckName,1,o=>{var row=((ResultRow)o).Source;var value=(row.Services??new System.Collections.Generic.List<ZapretServiceResult>()).FirstOrDefault(s=>s.Id==id);var text=CellText(value==null?"—":value.Access==ServiceAccess.Available?(value.Milliseconds>0?value.Milliseconds+" мс":"Доступен"):value.Access==ServiceAccess.Unavailable?"Ошибка":"Не подтверждён");text.Foreground=Brush(value==null?(CompactLight?"#52616E":"#8F9FB9"):value.Access==ServiceAccess.Available?(CompactLight?"#08664F":"#6BFAD8"):value.Access==ServiceAccess.Unavailable?(CompactLight?"#A51E3C":"#FF8CA1"):(CompactLight?"#805500":"#FFC972"));if(value!=null)text.ToolTip=JournalStyle.Redact(value.Detail);return text;});column.Width=150;table.Columns.Add(column);}table.Columns.Add(new DataGridTextColumn{Header="Состояние",Binding=new System.Windows.Data.Binding("State"),Width=170});return table;}
 }
}
