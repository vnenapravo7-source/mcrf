using System;
using System.Threading.Tasks;
using SplifyWin;
namespace Mcrf.Wpf {
 internal enum ServerSelectionResult { Cancelled, AlreadyActive, Switched, Reconfigured }
 // Runtime boundary keeps the transaction testable without launching a tunnel.
 internal sealed class ServerSelectionRuntime {
  internal Func<bool> Running;
  internal Func<string> Active;
  internal Func<string,bool> CanSelect;
  internal Func<string,Task<bool>> Select;
  internal static ServerSelectionRuntime For(NetworkCore core){return new ServerSelectionRuntime{
   Running=()=>core.Running,Active=()=>core.ActiveServerId,CanSelect=core.CanSelectActiveServer,
   Select=id=>Task.Run(()=>core.SelectActiveServer(id))
  };}
 }
}
