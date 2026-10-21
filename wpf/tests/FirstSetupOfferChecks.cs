using System;
using System.Reflection;
using SplifyWin;
class FirstSetupOfferChecks {
 static int count;static readonly BindingFlags F=BindingFlags.Static|BindingFlags.NonPublic;
 static void Check(bool ok,string text){if(!ok)throw new Exception(text);count++;Console.WriteLine("PASS "+text);}
 static int Main(){try{
  var type=typeof(NetworkCore).Assembly.GetType("Mcrf.Wpf.MainWindow");var offer=type.GetMethod("ShouldOfferFirstSetup",F);var ready=type.GetMethod("FirstSetupWindowReady",F);
  Func<bool,bool,bool> should=(seen,diagnostic)=>(bool)offer.Invoke(null,new object[]{seen,diagnostic});Func<bool,bool,bool,bool,bool,bool> can=(busy,visible,active,modal,closing)=>(bool)ready.Invoke(null,new object[]{busy,visible,active,modal,closing});
  Check(should(false,false),"fresh normal profile offers setup");Check(!should(true,false),"existing handled profile is not reset by a new executable");Check(!should(false,true),"diagnostic profiles do not trigger first-run dialogs");
  Check(!can(true,true,true,false,false),"busy window waits instead of losing the setup offer");Check(can(false,true,true,false,false),"offer becomes eligible after busy operation completes");Check(!can(false,true,true,true,false),"existing modal must finish first");Check(!can(false,false,false,false,false),"hidden window waits");Check(!can(false,true,false,false,false),"background window waits for user focus");Check(!can(false,true,true,false,true),"closing window cannot prompt");
  Check(!ClientState.Initial().SetupSeen,"actual new client state is eligible for first-run setup");Console.WriteLine("FIRST_SETUP_OFFER_PASS "+count);return 0;
 }catch(Exception error){Console.WriteLine("FIRST_SETUP_OFFER_FAIL "+error);return 1;}}
}
