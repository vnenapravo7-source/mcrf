using System;
namespace Mcrf.Wpf {
 public sealed partial class MainWindow {
  internal Func<AndroidConnectionScene> ConnectionSceneForTests;
  AndroidConnectionScene TryCreateConnectionScene(out bool unavailable){
   unavailable=false;try{var scene=ConnectionSceneForTests==null?new AndroidConnectionScene(root):ConnectionSceneForTests();unavailable=scene==null;return scene;}
   catch(Exception error){if(error is OutOfMemoryException||error is StackOverflowException||error is AccessViolationException)throw;unavailable=true;return null;}
  }
 }
}
