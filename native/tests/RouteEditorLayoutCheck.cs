using System;
using System.IO;
using System.Drawing;
using System.Linq;
using System.Reflection;
using System.Windows.Forms;
using SplifyWin;
class RouteEditorLayoutCheck {
 static readonly BindingFlags Flags=BindingFlags.Instance|BindingFlags.NonPublic;
 static int count;
 static void Check(bool value,string message){if(!value)throw new Exception(message);count++;Console.WriteLine("PASS "+message);}
 [STAThread]static int Main(string[] args){try{
  Application.SetUnhandledExceptionMode(UnhandledExceptionMode.ThrowException);
  string root=Path.GetFullPath(args[0]);Directory.CreateDirectory(root);Environment.SetEnvironmentVariable("MCRF_DATA_DIR",root);Environment.SetEnvironmentVariable("SPLIFY_WIN_DATA_DIR",root);Environment.SetEnvironmentVariable("MCRF_TEST_NO_STARTUP_SETUP","1");Environment.SetEnvironmentVariable("MCRF_TEST_NO_STARTUP_UPDATES","1");
  using(var form=new MainForm(false,false)){form.Show();Application.DoEvents();var state=(ClientState)typeof(MainForm).GetField("state",Flags).GetValue(form);state.AdvancedMode=true;UiTheme.Simple=true;var profile=state.ZapretProfiles.First();var route=new RouteList{Name="Wardogs arhekog · точечный",Text=String.Join("\n",Enumerable.Range(0,18).Select(i=>"server"+i+".wardogs.example.com")),Target="zapret",ZapretProfileId=profile.Id,MatchMode="addresses",OriginalEngine=true};
   typeof(MainForm).GetMethod("EditRoute",Flags).Invoke(form,new object[]{route});Application.DoEvents();var overlay=(Panel)typeof(MainForm).GetField("popupOverlay",Flags).GetValue(form);var editor=overlay.Controls.OfType<RouteDialog>().Single();
   foreach(var size in new[]{new Size(1400,900),new Size(1050,680),new Size(1366,929)}){form.Size=size;Application.DoEvents();Check(editor.Left>=32&&editor.Top>=32&&overlay.Width-editor.Right>=32&&overlay.Height-editor.Bottom>=32,"outer margins at "+size);Check(editor.Controls.Cast<Control>().Where(c=>c.Visible).All(c=>c.Left>=20&&c.Right<=editor.Width-20&&c.Top>=16&&c.Bottom<=editor.Height-20),"controls remain inside padded dialog at "+size);var input=editor.Controls.Find("routeEntries",true).Single();Check(input.Left>=12&&input.Right<=input.Parent.Width-12&&input.Bottom<=input.Parent.Height-10,"input padding follows field resize at "+size);if(size.Height>800)Check(input.Parent.Height>=300&&input.Parent.Width>=900,"large address field at "+size);using(var image=new Bitmap(editor.Width,editor.Height)){editor.DrawToBitmap(image,new Rectangle(Point.Empty,editor.Size));image.Save(Path.Combine(root,"editor-"+size.Width+"x"+size.Height+".png"));}}
   editor.Close();Application.DoEvents();Check((string)typeof(MainForm).GetField("page",Flags).GetValue(form)=="routes"&&!form.Controls.OfType<Panel>().Any(p=>p.Controls.OfType<RouteDialog>().Any()),"closing editor replaces overlay with routes");
   for(int i=0;i<4;i++){typeof(MainForm).GetMethod("EditRoute",Flags).Invoke(form,new object[]{route});Application.DoEvents();overlay=(Panel)typeof(MainForm).GetField("popupOverlay",Flags).GetValue(form);overlay.Controls.OfType<RouteDialog>().Single().Close();Application.DoEvents();Check((string)typeof(MainForm).GetField("page",Flags).GetValue(form)=="routes","repeat close completes repaint "+i);}
   typeof(MainForm).GetMethod("ShowPage",Flags).Invoke(form,new object[]{"game-templates"});Application.DoEvents();overlay=(Panel)typeof(MainForm).GetField("popupOverlay",Flags).GetValue(form);var templateGrid=overlay.Controls.Find("gameTemplatesTable",true).OfType<DataGridView>().Single();Check(templateGrid.Columns[1].HeaderText=="Адресов"&&templateGrid.Rows.Cast<DataGridViewRow>().Select(r=>Convert.ToInt32(r.Cells[1].Value)).SequenceEqual(new[]{149,7,4}),"template UI counts all addresses");using(var image=new Bitmap(overlay.Width,overlay.Height)){overlay.DrawToBitmap(image,new Rectangle(Point.Empty,overlay.Size));image.Save(Path.Combine(root,"templates.png"));}
   using(var list=new UpdateListView()){form.Controls.Add(list);list.SetBounds(0,0,875,285);list.Reload(Enumerable.Range(0,12).Select(i=>new UpdateItem{Id="partial"+i,Name="Компонент "+i,Kind="optional",Group="Движки",Current="1",Version="2",Status="Актуально"}).ToArray());using(var image=new Bitmap(list.Width,list.Height)){list.DrawToBitmap(image,list.ClientRectangle);Check(image.GetPixel(20,list.Height-20).ToArgb()!=list.BackColor.ToArgb(),"updates paint partial bottom row instead of blank area");}typeof(UpdateListView).GetMethod("OnMouseDown",Flags).Invoke(list,new object[]{new MouseEventArgs(MouseButtons.Left,1,60,list.Height-20,0)});Check(list.SelectedIndex==list.FirstVisibleRow+(list.Height-42)/62,"partial bottom update row can be selected");}
   form.Close();
  }Console.WriteLine("OK "+count+" checks");return 0;
 }catch(Exception ex){Console.WriteLine(ex);return 1;}}
}
