-keep class org.steer.core.SteerCore { *; }
# Same parent-first JSON boundary contract as main; no method inlining/removal
# or field/class renaming across host/module copies. Actual R8 proof still due.
-keep class org.mcrf.steerprep.json.** { *; }
-keep interface org.steer.core.SteerCore$SocketProtector { *; }
-keep interface org.steer.core.SteerCore$FlowRouter { *; }
-keep interface org.steer.core.SteerCore$PortFlowRouter { *; }
-keepclassmembers class * implements org.steer.core.SteerCore$PortFlowRouter { public boolean portFlowsEnabled(); public long routeFlowPort(int,int,int); }
-keep interface org.steer.core.SteerCore$DualStackFlowRouter { *; }
-keep interface org.steer.core.SteerCore$ProxyFlowObserver { *; }
-keep interface org.steer.core.SteerCore$OwnedFlowRouter { *; }
-keepclassmembers class * implements org.steer.core.SteerCore$OwnedFlowRouter { public boolean ownerFlowsEnabled(); public byte[] routeOwnedFlow(byte[]); }
-keepclassmembers class * implements org.steer.core.SteerCore$FlowRouter { public long routeFlow(int); }
-keepclassmembers class * implements org.steer.core.SteerCore$DualStackFlowRouter { public boolean ipv6FlowsEnabled(); public byte[] routeFlow6(byte[]); }
-keepclassmembers class * implements org.steer.core.SteerCore$ProxyFlowObserver { public boolean observesProxyFlows(); public void proxyFlowResult(byte[],int,int,boolean); }
-keepclassmembers class * implements org.steer.core.SteerCore$SocketProtector { public boolean protectSocket(int); }
