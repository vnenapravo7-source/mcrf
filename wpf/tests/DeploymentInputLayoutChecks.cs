using System;
using System.Linq;
using System.Reflection;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
class DeploymentInputLayoutChecks {
 static readonly BindingFlags F=BindingFlags.Public|BindingFlags.NonPublic|BindingFlags.Static|BindingFlags.Instance;
 static int checks;
 static void Check(bool ok,string label){if(!ok)throw new Exception(label);checks++;Console.WriteLine("PASS "+label);}
 static TextBox[] Fields(ScrollViewer editor){return ((StackPanel)editor.Content).Children.Cast<DockPanel>().Select(row=>row.Children.OfType<TextBox>().Single()).ToArray();}
 static bool Paste(TextBox field,string value){var data=new DataObject(DataFormats.UnicodeText,value);var paste=new DataObjectPastingEventArgs(data,false,DataFormats.UnicodeText);field.RaiseEvent(paste);return paste.CommandCancelled;}
 [STAThread]static int Main(){try{
  var assembly=typeof(NetworkCore).Assembly;assembly.GetType("Mcrf.Wpf.ManagedDependencies").GetMethod("Ensure",F).Invoke(null,null);
  var app=new Application();assembly.GetType("Mcrf.Wpf.Program").GetMethod("LoadTheme",F).Invoke(null,new object[]{app});
  var type=assembly.GetType("Mcrf.Wpf.MainWindow");var main=Activator.CreateInstance(type,new object[]{new string[0]});
  var calls=Enumerable.Range(1,33).Select(i=>"https://vk.ru/call/join/"+new string((char)('A'+i%26),20)+i).ToArray();
  foreach(string separator in new[]{"\r\n","\n"," ",";\r\n"}){
   object[] args={null,null};var editor=(ScrollViewer)type.GetMethod("VkCallFields",F).Invoke(main,args);var read=(Func<string[]>)args[0];
   Check(Paste(Fields(editor).Single(),String.Join(separator,calls.Take(6))),"batch paste intercepted before single-line truncation");
   Check(Fields(editor).Length==6&&read().SequenceEqual(calls.Take(6)),"six original links retained in separate fields");
  }
  object[] editorArgs={null,null};var inputs=(ScrollViewer)type.GetMethod("VkCallFields",F).Invoke(main,editorArgs);var replace=(Action<string[]>)editorArgs[1];
  replace(new[]{calls[0],calls[1]});var selected=Fields(inputs)[1];selected.SelectAll();Check(Paste(selected,String.Join("\n",calls.Skip(2).Take(3)))&&Fields(inputs).Select(t=>t.Text).SequenceEqual(new[]{calls[0],calls[2],calls[3],calls[4]}),"paste into selected existing row preserves surrounding rows");
  replace(new[]{calls[0]});Fields(inputs)[0].SelectAll();Check(Paste(Fields(inputs)[0],String.Join("\n",calls.Take(32)))&&Fields(inputs).Length==32,"32-call paste accepted");
  var before=Fields(inputs).Select(t=>t.Text).ToArray();Fields(inputs)[0].SelectAll();Check(Paste(Fields(inputs)[0],String.Join("\n",calls.Take(2)))&&Fields(inputs).Select(t=>t.Text).SequenceEqual(before),"overflow cancels paste without losing existing data");
  replace(new[]{calls[0]});Fields(inputs)[0].SelectAll();Check(!Paste(Fields(inputs)[0],calls[1]),"single-link paste left to normal editor behavior");
  var recipes=(Array)assembly.GetType("Mcrf.Wpf.DeployRecipe").GetField("All",F).GetValue(null);
  foreach(var recipe in recipes)foreach(bool existing in new[]{false,true}){
   var picker=new ComboBox{ItemsSource=recipes,SelectedItem=recipe};object[] args={picker,null,existing};var section=(StackPanel)type.GetMethod("DeploymentProfiles",F).Invoke(main,args);
   section.Measure(new Size(900,5000));section.Arrange(new Rect(0,0,900,section.DesiredSize.Height));section.UpdateLayout();
   var name=(TextBox)((StackPanel)section.Children[0]).Children[1];var toggle=(CheckBox)section.Children[1];var group=section.Children.Cast<UIElement>().Skip(2).First(e=>e.Visibility==Visibility.Visible);
   double bottom=name.TranslatePoint(new Point(0,name.ActualHeight),section).Y;double top=toggle.TranslatePoint(new Point(0,0),section).Y;
   Check(top-bottom>=14&&group.TranslatePoint(new Point(0,0),section).Y-toggle.TranslatePoint(new Point(0,toggle.ActualHeight),section).Y>=16,"name/toggle/protocol spacing for "+recipe+" existing="+existing);
  }
  Console.WriteLine("DEPLOYMENT_INPUT_LAYOUT_PASS "+checks);app.Shutdown();return 0;
 }catch(Exception error){Console.WriteLine("DEPLOYMENT_INPUT_LAYOUT_FAIL "+error);return 1;}}
}
