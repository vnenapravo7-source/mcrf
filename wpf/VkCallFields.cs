using System;
using System.Linq;
using System.Collections.Generic;
using System.Text.RegularExpressions;
using System.Windows;
using System.Windows.Controls;
using SplifyWin;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  UIElement VkCallFields(out Func<string[]> read,out Action<string[]> replace){
   var rows=new StackPanel{Name="deployVkCallRows"};var fields=new List<TextBox>();bool editing=false;
   var viewport=new ScrollViewer{Name="deployVkCallsScroll",Content=rows,MaxHeight=108,VerticalScrollBarVisibility=ScrollBarVisibility.Auto,HorizontalScrollBarVisibility=ScrollBarVisibility.Disabled};
   Func<TextBox,string,bool> split=null;Func<int,string,TextBox> add=null;
   split=(input,text)=>{var parts=Regex.Split(text,@"[\s,;]+").Where(v=>!String.IsNullOrWhiteSpace(v)).ToArray();if(parts.Length<2)return false;if(fields.Count+parts.Length-1>32){status.Text="Не больше 32 звонков. Уберите лишние ссылки.";return true;}editing=true;try{input.Text=parts[0];input.CaretIndex=input.Text.Length;int position=fields.IndexOf(input)+1;foreach(string link in parts.Skip(1))add(position++,link);}finally{editing=false;}return true;};
   add=(index,text)=>{
    if(fields.Count>=32)throw new InvalidOperationException("Не больше 32 звонков в одном подключении.");
    var row=new DockPanel{Margin=new Thickness(0,0,0,6)};var input=new TextBox{Name="deployVkCall",Text=text,Height=32,Margin=new Thickness(0),ToolTip="Ссылка https://vk.ru/call/join/… или хеш звонка. Несколько ссылок при вставке станут отдельными полями."};
    var plus=IconButton("plus","Добавить ссылку звонка VK",()=>{var next=add(fields.IndexOf(input)+1,"");next.Focus();next.BringIntoView();return System.Threading.Tasks.Task.FromResult(0);});plus.Name="deployAddVkCall";plus.Margin=new Thickness(8,0,0,0);DockPanel.SetDock(plus,Dock.Right);row.Children.Add(plus);row.Children.Add(input);fields.Insert(index,input);rows.Children.Insert(index,row);for(int i=0;i<rows.Children.Count;i++)((FrameworkElement)rows.Children[i]).Margin=new Thickness(0,0,0,i+1==rows.Children.Count?0:6);
    input.TextChanged+=(sender,args)=>{if(!editing)split(input,input.Text);};
    // Single-line WPF TextBox truncates clipboard text at the first newline
    // before TextChanged. Read the original paste payload before that happens.
    DataObject.AddPastingHandler(input,(sender,args)=>{if(editing)return;string format=args.DataObject.GetDataPresent(DataFormats.UnicodeText)?DataFormats.UnicodeText:DataFormats.Text;if(!args.DataObject.GetDataPresent(format))return;var pasted=args.DataObject.GetData(format) as string;if(pasted==null)return;string candidate=input.Text.Remove(input.SelectionStart,input.SelectionLength).Insert(input.SelectionStart,pasted);if(split(input,candidate))args.CancelCommand();});
    return input;
   };
   add(0,"");read=()=>VkCalls.CallLinks(String.Join("\n",fields.Select(f=>f.Text)));
   replace=calls=>{if(calls==null||calls.Length==0)return;if(calls.Length>32)throw new FormatException("Не больше 32 звонков.");editing=true;try{while(fields.Count<calls.Length)add(fields.Count,"");while(fields.Count>calls.Length){fields.RemoveAt(fields.Count-1);rows.Children.RemoveAt(rows.Children.Count-1);}for(int i=0;i<calls.Length;i++)fields[i].Text=calls[i];}finally{editing=false;}};
   return viewport;
  }
 }
}
