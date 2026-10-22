using System;
using System.IO;
using System.Threading;
class FixtureCore {
 static int Main(string[] args){string file=args[args.Length-1];var stop=new ManualResetEvent(false);Console.CancelKeyPress+=(s,e)=>{e.Cancel=true;File.WriteAllText(file+".closed","graceful");stop.Set();};Console.WriteLine("FIXTURE_READY");Console.Out.Flush();stop.WaitOne();return 0;}
}
