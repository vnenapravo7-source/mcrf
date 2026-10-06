using System;
using System.Drawing;
using System.Runtime.InteropServices;
using System.Reflection;
using System.Windows.Forms;
namespace SplifyWin {
 public static class TrayPopupNative {
  [DllImport("user32.dll")]static extern bool SetForegroundWindow(IntPtr window);
  [DllImport("user32.dll")]static extern bool ShowWindow(IntPtr window,int command);
  [DllImport("user32.dll")]static extern bool PostMessage(IntPtr window,uint message,IntPtr wParam,IntPtr lParam);
  public static Point Anchor(Point cursor,Size menu,Rectangle work){return new Point(Math.Max(work.Left,Math.Min(cursor.X,work.Right-menu.Width)),Math.Max(work.Top,Math.Min(cursor.Y,work.Bottom-menu.Height)));}
  public static Point AboveIcon(Point cursor,Size menu,Rectangle work){return Anchor(new Point(cursor.X-menu.Width/2,cursor.Y-menu.Height-8),menu,work);}
  [StructLayout(LayoutKind.Sequential)]struct IconIdentifier{public uint Size;public IntPtr Window;public uint Id;public Guid Guid;}
  [StructLayout(LayoutKind.Sequential)]struct IconRect{public int Left,Top,Right,Bottom;}
  [DllImport("shell32.dll")]static extern int Shell_NotifyIconGetRect(ref IconIdentifier icon,out IconRect rect);
  public static Point IconAnchor(NotifyIcon icon,Point fallback){try{var flags=BindingFlags.Instance|BindingFlags.NonPublic;var window=typeof(NotifyIcon).GetField("window",flags).GetValue(icon) as NativeWindow;var id=Convert.ToUInt32(typeof(NotifyIcon).GetField("id",flags).GetValue(icon));if(window==null)return fallback;var identifier=new IconIdentifier{Size=(uint)Marshal.SizeOf(typeof(IconIdentifier)),Window=window.Handle,Id=id};IconRect rect;if(Shell_NotifyIconGetRect(ref identifier,out rect)==0&&rect.Right>rect.Left&&rect.Bottom>rect.Top)return new Point((rect.Left+rect.Right)/2,rect.Top);}catch{}return fallback;}
  internal static void Foreground(IntPtr window){SetForegroundWindow(window);}
  internal static void Restore(IntPtr window,bool maximized){ShowWindow(window,maximized?3:9);SetForegroundWindow(window);}
  internal static void AfterClose(IntPtr window){PostMessage(window,0,IntPtr.Zero,IntPtr.Zero);}
 }
}
