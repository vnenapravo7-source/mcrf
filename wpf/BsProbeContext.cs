using SplifyWin;
namespace Mcrf.Wpf {
 internal sealed class BsProbeContext {
  readonly ClientState origin;readonly int generation;readonly bool active;readonly string selected;internal bool BsActive{get{return active;}}
  internal BsProbeContext(ClientState state,int startedGeneration){origin=state;generation=startedGeneration;active=state.AutoBsActive;selected=state.SelectedServerId;}
  internal bool CanApply(ClientState state,int currentGeneration,bool running,bool starting,bool busy,bool stopping){return !stopping&&!busy&&running&&!starting&&object.ReferenceEquals(state,origin)&&state.AutoDetectBs&&state.VpnEnabled&&state.Mode=="tun"&&state.AutoBsActive==active&&state.SelectedServerId==selected&&currentGeneration==generation;}
 }
}
