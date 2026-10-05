using System;
using System.ComponentModel;
using System.Diagnostics;
using System.Runtime.InteropServices;
using Microsoft.Win32.SafeHandles;

namespace SplifyWin {
  // Only our own freshly spawned engines are assigned. No enumeration or killing of other clients.
  public static class OwnedJob {
    [StructLayout(LayoutKind.Sequential)]struct BasicLimits {
      public long ProcessTime,JobTime;public uint Flags;public UIntPtr MinWorkingSet,MaxWorkingSet;public uint ActiveProcesses;public UIntPtr Affinity;public uint Priority,Scheduling;
    }
    [StructLayout(LayoutKind.Sequential)]struct IoCounters {public ulong ReadOperations,WriteOperations,OtherOperations,ReadBytes,WriteBytes,OtherBytes;}
    [StructLayout(LayoutKind.Sequential)]struct ExtendedLimits {
      public BasicLimits Basic;public IoCounters Io;public UIntPtr ProcessMemory,JobMemory,PeakProcessMemory,PeakJobMemory;
    }
    [DllImport("kernel32.dll",CharSet=CharSet.Unicode,SetLastError=true)]static extern IntPtr CreateJobObject(IntPtr attributes,string name);
    [DllImport("kernel32.dll",SetLastError=true)]static extern bool SetInformationJobObject(SafeFileHandle job,int type,IntPtr data,uint size);
    [DllImport("kernel32.dll",SetLastError=true)]static extern bool AssignProcessToJobObject(SafeFileHandle job,IntPtr process);
    static readonly object sync=new object();static SafeFileHandle job;
    static void Ensure(){
      if(job!=null)return;var handle=CreateJobObject(IntPtr.Zero,null);if(handle==IntPtr.Zero)throw new Win32Exception(Marshal.GetLastWin32Error());
      var candidate=new SafeFileHandle(handle,true);var limits=new ExtendedLimits();limits.Basic.Flags=0x2000;int size=Marshal.SizeOf(limits);var data=Marshal.AllocHGlobal(size);
      try{Marshal.StructureToPtr(limits,data,false);if(!SetInformationJobObject(candidate,9,data,(uint)size))throw new Win32Exception(Marshal.GetLastWin32Error());job=candidate;}catch{candidate.Dispose();throw;}finally{Marshal.FreeHGlobal(data);}
    }
    public static void Attach(Process owned){
      if(owned==null)throw new InvalidOperationException("Не удалось создать процесс движка.");
      try{if(owned.HasExited)return;lock(sync){Ensure();if(!AssignProcessToJobObject(job,owned.Handle)){int error=Marshal.GetLastWin32Error();if(owned.HasExited)return;throw new Win32Exception(error,"Не удалось включить защиту от оставшихся процессов движка.");}}}
      catch{try{if(!owned.HasExited)owned.Kill();}catch{}throw;}
    }
    public static Process Start(ProcessStartInfo info){var owned=Process.Start(info);Attach(owned);return owned;}
  }
}
