# gomobile's JNI and generated proxy classes are addressed by native names/reflection.
-keep class go.** { *; }
-keep class io.nekohasekai.libbox.** { *; }
-keepclasseswithmembernames,includedescriptorclasses class * { native <methods>; }
-keepattributes Signature,InnerClasses,EnclosingMethod,RuntimeVisibleAnnotations
# Parent-loaded ABI shared with separately installed primary modules. Keep its
# names and methods stable even before a particular optional core is installed.
-keep class org.mcrf.engine.spi.v1.** { *; }
-keep interface org.mcrf.engine.spi.v1.** { *; }

# Parent-first modules may resolve the host guard/shape and their nested public
# fields. Preserve identity AND methods without allowoptimization: the guard
# contractVersion call must not be folded into a constant by R8.
-keep class org.mcrf.steerprep.json.** { *; }
# Optional packet-module ABI is parent-loaded too; module APK has compileOnly SPI.
-keep class org.mcrf.engine.protocol.v1.** { *; }
-keep interface org.mcrf.engine.protocol.v1.** { *; }
-keep class org.mcrf.engine.protocol.dns.v1.** { *; }
-keep interface org.mcrf.engine.protocol.dns.v1.** { *; }
-keep class org.mcrf.engine.protocol.pipe.v1.** { *; }
-keep interface org.mcrf.engine.protocol.pipe.v1.** { *; }
# JSch selects JCE primitives, key exchanges and auth handlers by configured name.
# Optional desktop agents/loggers and BC/GSS providers are not bundled on Android.
-keep class com.jcraft.jsch.DHEC256 { *; }
-keep class com.jcraft.jsch.DHEC384 { *; }
-keep class com.jcraft.jsch.DHEC521 { *; }
-keep class com.jcraft.jsch.DHG14256 { *; }
-keep class com.jcraft.jsch.jce.SignatureECDSA256 { *; }
-keep class com.jcraft.jsch.jce.SignatureECDSA384 { *; }
-keep class com.jcraft.jsch.jce.SignatureECDSA521 { *; }
-keep class com.jcraft.jsch.jce.SignatureRSASHA512 { *; }
-keep class com.jcraft.jsch.jce.SignatureRSASHA256 { *; }
-keep class com.jcraft.jsch.jce.AES128CTR { *; }
-keep class com.jcraft.jsch.jce.AES256CTR { *; }
-keep class com.jcraft.jsch.jce.HMACSHA256ETM { *; }
-keep class com.jcraft.jsch.jce.HMACSHA512ETM { *; }
-keep class com.jcraft.jsch.jce.HMACSHA256 { *; }
-keep class com.jcraft.jsch.jce.HMACSHA512 { *; }
-keep class com.jcraft.jsch.jce.Random { *; }
-keep class com.jcraft.jsch.jce.SHA256 { *; }
-keep class com.jcraft.jsch.jce.SHA384 { *; }
-keep class com.jcraft.jsch.jce.SHA512 { *; }
-keep class com.jcraft.jsch.jce.ECDHN { *; }
-keep class com.jcraft.jsch.jce.DH { *; }
-keep class com.jcraft.jsch.UserAuthNone { *; }
-keep class com.jcraft.jsch.UserAuthPassword { *; }
-keep class com.jcraft.jsch.UserAuthPublicKey { *; }
-keep class com.jcraft.jsch.jce.MD5 { *; }
-keep class com.jcraft.jsch.jce.SHA1 { *; }
-keep class com.jcraft.jsch.jce.AES128CBC { *; }
-keep class com.jcraft.jsch.jce.AES192CBC { *; }
-keep class com.jcraft.jsch.jce.AES256CBC { *; }
-keep class com.jcraft.jsch.jce.TripleDESCBC { *; }
-keep class com.jcraft.jsch.jce.PBKDF { *; }
-keep class com.jcraft.jsch.jce.PBKDF2HMACSHA1 { *; }
-keep class com.jcraft.jsch.jce.PBKDF2HMACSHA256 { *; }
-keep class com.jcraft.jsch.jce.PBKDF2HMACSHA384 { *; }
-keep class com.jcraft.jsch.jce.PBKDF2HMACSHA512 { *; }
-keep class com.jcraft.jsch.jbcrypt.JBCrypt { *; }
-keep class com.jcraft.jsch.jce.KeyPairGenRSA { *; }
-keep class com.jcraft.jsch.jce.KeyPairGenECDSA { *; }
