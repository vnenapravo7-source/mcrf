using System;
using System.Threading.Tasks;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  Func<Task> pendingSimpleRecipe;
  Action pauseSimpleRecipeSave;
  async Task<bool> FlushPendingRecipe(){
   if(pauseSimpleRecipeSave!=null)pauseSimpleRecipeSave();
   var commit=pendingSimpleRecipe;pendingSimpleRecipe=null;
   if(commit==null)return true;
   bool saved=false;
   await Run(async()=>{await commit();saved=true;},()=>pendingSimpleRecipe=commit);
   return saved&&!closing&&!closeRequested;
  }
  async void NavigateWithPendingRecipe(string name){
   if(await FlushPendingRecipe())Page(name);
  }
  async void NavigateHistoryWithPendingRecipe(int offset){if(await FlushPendingRecipe())NavigateHistory(offset);}
 }
}
