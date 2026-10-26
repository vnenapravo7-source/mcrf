using System;
using System.Linq;
using System.Collections.Generic;
using System.Web;
using SplifyWin;
namespace Mcrf.Wpf {public sealed partial class MainWindow {
 void NormalizeNewQwdttImports(IEnumerable<ServerNode> nodes){foreach(var node in nodes.Where(x=>x.Protocol=="qwdtt")){if(session.State.Servers.Any(x=>x.Protocol=="qwdtt"&&x.Link==node.Link))continue;Uri uri;if(!Uri.TryCreate(node.Link.Replace("qwdtt:config","qwdtt://config"),UriKind.Absolute,out uri)||HttpUtility.ParseQueryString(uri.Query)["mode"]!=null)continue;int fragment=node.Link.IndexOf('#');string tail=fragment<0?"":node.Link.Substring(fragment),head=fragment<0?node.Link:node.Link.Substring(0,fragment);node.Link=head+(head.Contains("?")?"&":"?")+"mode=raw"+tail;}}
}}
