# Installed entry is discovered by signed metadata, never by a static host reference.
# Parent SPI is delivered/kept by :primary-spi; do not copy it into the module.
-keep class org.mcrf.engines.singbox.SingBoxEngine {
    public <init>(android.content.Context);
    public *;
}
# The actual runtime AAR's proguard.txt also keeps go.** and io.nekohasekai.**.
# Preserve those upstream rules when composing the separate primary APK: JNI and
# gomobile proxy callbacks require their exact class/method names.
