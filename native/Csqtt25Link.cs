using System;
using System.Runtime.InteropServices;
using System.Security.Cryptography;
using System.Text;

namespace SplifyWin {
  // The panel's public fixed-key envelope identifies the 2.5 protocol; it is
  // not subscription encryption or proof of a trusted issuer. Authenticate
  // the GCM tag before parsing anything, and never expose payloads in errors.
  public static class Csqtt25Link {
    const string Prefix="csqtt://";
    public static bool IsSealed(string link){return link!=null&&link.StartsWith(Prefix,StringComparison.OrdinalIgnoreCase)&&!link.Substring(Prefix.Length).StartsWith("connect?",StringComparison.OrdinalIgnoreCase);}
    public static string Seal(string link){
      if(link==null||!link.StartsWith(Prefix+"connect?v=2&",StringComparison.Ordinal)||link.Length>16000||link.IndexOfAny(new[]{'\r','\n','\0','#'})>=0)throw new FormatException("Некорректные данные подключения CSQTT 2.5");
      // Parse before sealing so an unrelated or malformed panel result cannot
      // become an apparently valid encrypted connection. The result uses the
      // panel's public fixed-key format, not private subscription encryption.
      TurnProfile.Parse(link);
      byte[] key=null,plain=null,mac=null,cipher=null;
      IntPtr algorithm=IntPtr.Zero,nativeKey=IntPtr.Zero,nonce=IntPtr.Zero,tag=IntPtr.Zero;
      try{
        plain=Encoding.UTF8.GetBytes(link.Substring(Prefix.Length));
        using(var sha=SHA256.Create())key=sha.ComputeHash(Encoding.UTF8.GetBytes("csqtt-chiper-key"));
        using(var hmac=new HMACSHA256(key))mac=hmac.ComputeHash(plain);
        Check(BCryptOpenAlgorithmProvider(out algorithm,"AES",null,0));
        var mode=Encoding.Unicode.GetBytes("ChainingModeGCM\0");Check(BCryptSetProperty(algorithm,"ChainingMode",mode,mode.Length,0));
        Check(BCryptGenerateSymmetricKey(algorithm,out nativeKey,IntPtr.Zero,0,key,key.Length,0));
        nonce=Marshal.AllocHGlobal(12);Marshal.Copy(mac,0,nonce,12);tag=Marshal.AllocHGlobal(16);
        cipher=new byte[plain.Length+28];Buffer.BlockCopy(mac,0,cipher,0,12);var encrypted=new byte[plain.Length];
        try{var info=new AuthInfo{Size=Marshal.SizeOf(typeof(AuthInfo)),Version=1,Nonce=nonce,NonceBytes=12,Tag=tag,TagBytes=16};int written;
          Check(BCryptEncrypt(nativeKey,plain,plain.Length,ref info,IntPtr.Zero,0,encrypted,encrypted.Length,out written,0));
          if(written!=encrypted.Length)throw new CryptographicException("Unexpected CSQTT sealed size");
          Buffer.BlockCopy(encrypted,0,cipher,12,encrypted.Length);Marshal.Copy(tag,cipher,cipher.Length-16,16);
          return Prefix+Convert.ToBase64String(cipher).TrimEnd('=').Replace('+','-').Replace('/','_');
        }finally{Array.Clear(encrypted,0,encrypted.Length);}
      }finally{
        if(key!=null)Array.Clear(key,0,key.Length);if(plain!=null)Array.Clear(plain,0,plain.Length);if(mac!=null)Array.Clear(mac,0,mac.Length);if(cipher!=null)Array.Clear(cipher,0,cipher.Length);
        if(nonce!=IntPtr.Zero)Marshal.FreeHGlobal(nonce);if(tag!=IntPtr.Zero)Marshal.FreeHGlobal(tag);
        if(nativeKey!=IntPtr.Zero)BCryptDestroyKey(nativeKey);if(algorithm!=IntPtr.Zero)BCryptCloseAlgorithmProvider(algorithm,0);
      }
    }
    public static string Open(string link){
      if(!IsSealed(link)||link.Length>32768)throw new FormatException("Некорректная ссылка CSQTT 2.5");
      byte[] bytes=null,key=null,plain=null;
      try{
        string encoded=link.Substring(Prefix.Length);
        if(encoded.Length<38||encoded.Length%4==1)throw new FormatException();
        foreach(char c in encoded)if(!(c>='a'&&c<='z'||c>='A'&&c<='Z'||c>='0'&&c<='9'||c=='-'||c=='_'))throw new FormatException();
        bytes=Convert.FromBase64String(encoded.Replace('-','+').Replace('_','/').PadRight((encoded.Length+3)/4*4,'='));
        if(bytes.Length<=28)throw new FormatException();
        using(var sha=SHA256.Create())key=sha.ComputeHash(Encoding.UTF8.GetBytes("csqtt-chiper-key"));
        plain=Decrypt(key,bytes);
        string payload=new UTF8Encoding(false,true).GetString(plain);
        if(!payload.StartsWith("connect?v=2&",StringComparison.Ordinal)||payload.IndexOfAny(new[]{'\r','\n','\0','#'})>=0)throw new FormatException();
        return Prefix+payload;
      }catch(FormatException){throw new FormatException("Некорректная или повреждённая ссылка CSQTT 2.5");}
      catch(CryptographicException){throw new FormatException("Некорректная или повреждённая ссылка CSQTT 2.5");}
      catch(DecoderFallbackException){throw new FormatException("Некорректная или повреждённая ссылка CSQTT 2.5");}
      finally{if(bytes!=null)Array.Clear(bytes,0,bytes.Length);if(key!=null)Array.Clear(key,0,key.Length);if(plain!=null)Array.Clear(plain,0,plain.Length);}
    }
    [StructLayout(LayoutKind.Sequential)]
    struct AuthInfo {public int Size,Version;public IntPtr Nonce;public int NonceBytes;public IntPtr Auth;public int AuthBytes;public IntPtr Tag;public int TagBytes;public IntPtr Mac;public int MacBytes,AadBytes;public ulong DataBytes;public int Flags;}
    [DllImport("bcrypt.dll",CharSet=CharSet.Unicode)]static extern int BCryptOpenAlgorithmProvider(out IntPtr handle,string algorithm,string implementation,int flags);
    [DllImport("bcrypt.dll",CharSet=CharSet.Unicode)]static extern int BCryptSetProperty(IntPtr handle,string name,byte[] value,int size,int flags);
    [DllImport("bcrypt.dll")]static extern int BCryptGenerateSymmetricKey(IntPtr algorithm,out IntPtr key,IntPtr keyObject,int keyObjectBytes,byte[] secret,int secretBytes,int flags);
    [DllImport("bcrypt.dll")]static extern int BCryptDecrypt(IntPtr key,byte[] input,int inputBytes,ref AuthInfo info,IntPtr iv,int ivBytes,byte[] output,int outputBytes,out int written,int flags);
    [DllImport("bcrypt.dll")]static extern int BCryptEncrypt(IntPtr key,byte[] input,int inputBytes,ref AuthInfo info,IntPtr iv,int ivBytes,byte[] output,int outputBytes,out int written,int flags);
    [DllImport("bcrypt.dll")]static extern int BCryptDestroyKey(IntPtr key);
    [DllImport("bcrypt.dll")]static extern int BCryptCloseAlgorithmProvider(IntPtr algorithm,int flags);
    static void Check(int result){if(result!=0)throw new CryptographicException("CSQTT envelope authentication failed");}
    static byte[] Decrypt(byte[] secret,byte[] sealedBytes){
      IntPtr algorithm=IntPtr.Zero,key=IntPtr.Zero,nonce=IntPtr.Zero,tag=IntPtr.Zero;byte[] cipher=null,plain=null;
      try{
        Check(BCryptOpenAlgorithmProvider(out algorithm,"AES",null,0));
        var mode=Encoding.Unicode.GetBytes("ChainingModeGCM\0");Check(BCryptSetProperty(algorithm,"ChainingMode",mode,mode.Length,0));
        Check(BCryptGenerateSymmetricKey(algorithm,out key,IntPtr.Zero,0,secret,secret.Length,0));
        nonce=Marshal.AllocHGlobal(12);Marshal.Copy(sealedBytes,0,nonce,12);
        tag=Marshal.AllocHGlobal(16);Marshal.Copy(sealedBytes,sealedBytes.Length-16,tag,16);
        cipher=new byte[sealedBytes.Length-28];Buffer.BlockCopy(sealedBytes,12,cipher,0,cipher.Length);plain=new byte[cipher.Length];
        var info=new AuthInfo{Size=Marshal.SizeOf(typeof(AuthInfo)),Version=1,Nonce=nonce,NonceBytes=12,Tag=tag,TagBytes=16};int written;
        Check(BCryptDecrypt(key,cipher,cipher.Length,ref info,IntPtr.Zero,0,plain,plain.Length,out written,0));
        if(written!=plain.Length)throw new CryptographicException("Unexpected CSQTT payload size");
        var result=plain;plain=null;return result;
      }finally{
        if(plain!=null)Array.Clear(plain,0,plain.Length);if(cipher!=null)Array.Clear(cipher,0,cipher.Length);
        if(nonce!=IntPtr.Zero)Marshal.FreeHGlobal(nonce);if(tag!=IntPtr.Zero)Marshal.FreeHGlobal(tag);
        if(key!=IntPtr.Zero)BCryptDestroyKey(key);if(algorithm!=IntPtr.Zero)BCryptCloseAlgorithmProvider(algorithm,0);
      }
    }
  }
}
