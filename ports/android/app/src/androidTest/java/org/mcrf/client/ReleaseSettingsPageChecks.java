package org.mcrf.client;

import android.app.Activity;
import android.app.Instrumentation;
import android.content.Intent;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import java.util.concurrent.atomic.AtomicReference;

/** Framework-only checks against minified code. Does not edit settings or copy credentials. */
final class ReleaseSettingsPageChecks {
    private static String settingsDigest(Instrumentation test)throws Exception {
        java.io.File root=test.getTargetContext().getFilesDir().getCanonicalFile();
        java.io.File pointer=new java.io.File(root,"active-profile.txt");
        if(pointer.exists()){
            check(pointer.length()<=128&&!java.nio.file.Files.isSymbolicLink(pointer.toPath()),"Invalid settings generation pointer");
            String generation=new String(java.nio.file.Files.readAllBytes(pointer.toPath()),java.nio.charset.StandardCharsets.US_ASCII);
            check(generation.matches("mcrf-store-[0-9a-f-]{36}"),"Invalid settings generation");
            java.io.File directory=new java.io.File(root,generation);
            check(root.equals(directory.getCanonicalFile().getParentFile())&&!java.nio.file.Files.isSymbolicLink(directory.toPath()),"Invalid settings directory");root=directory;
        }
        java.io.File settings=new java.io.File(root,"preferences.enc");
        if(!settings.exists())return "absent";
        check(settings.length()<=4*1024*1024+64&&!java.nio.file.Files.isSymbolicLink(settings.toPath()),"Invalid encrypted settings file");
        return java.util.Arrays.toString(java.security.MessageDigest.getInstance("SHA-256").digest(java.nio.file.Files.readAllBytes(settings.toPath())));
    }
    private static void check(boolean value,String label) {if(!value)throw new IllegalStateException(label);}
    private static void main(Instrumentation test,Runnable action)throws Exception {
        AtomicReference<Throwable> failure=new AtomicReference<>();
        test.runOnMainSync(()->{try{action.run();}catch(Throwable error){failure.set(error);}});
        if(failure.get()!=null)throw new Exception(failure.get().getMessage(),failure.get());
    }
    private static boolean retired(View node) {
        if(node instanceof Button) {
            String title=((Button)node).getText().toString();
            if(title.contains("WebDAV")||title.contains("Подписки · параметры"))return true;
        }
        if(node instanceof ViewGroup)for(int i=0;i<((ViewGroup)node).getChildCount();i++)if(retired(((ViewGroup)node).getChildAt(i)))return true;
        return false;
    }
    private static void click(Instrumentation test,View root,String tag)throws Exception {
        main(test,()->{View target=root.findViewWithTag(tag);check(target!=null,"Missing action: "+tag);check(target.performClick(),"Action not handled: "+tag);});
        test.waitForIdleSync();SystemClock.sleep(180);
    }
    private static void awaitTag(Instrumentation test,View root,String tag)throws Exception {
        long deadline=SystemClock.elapsedRealtime()+8000;
        while(SystemClock.elapsedRealtime()<deadline){
            java.util.concurrent.atomic.AtomicBoolean present=new java.util.concurrent.atomic.AtomicBoolean();
            main(test,()->present.set(root.findViewWithTag(tag)!=null));
            if(present.get()){test.waitForIdleSync();return;}
            SystemClock.sleep(40);
        }
        throw new IllegalStateException("Page did not become ready: "+tag);
    }
    private static int headings(View node) {
        int count=node.isAccessibilityHeading()?1:0;
        if(node instanceof ViewGroup)for(int i=0;i<((ViewGroup)node).getChildCount();i++)count+=headings(((ViewGroup)node).getChildAt(i));
        return count;
    }
    private static void labelFits(Button button,int maxLines,String name) {
        check(button!=null&&button.getLayout()!=null,"Missing measured action: "+name);
        android.text.Layout layout=button.getLayout();
        check(layout.getLineCount()<=maxLines,"Action label wraps too far: "+name);
        int width=button.getWidth()-button.getCompoundPaddingLeft()-button.getCompoundPaddingRight();
        for(int line=0;line<layout.getLineCount();line++){
            check(layout.getEllipsisCount(line)==0&&layout.getLineWidth(line)<=width+1,"Action label clipped: "+name);
        }
    }
    private static void navigation(View root,String selected) {
        ViewGroup nav=root.findViewWithTag("main-navigation");check(nav!=null&&nav.getChildCount()==4,"Bottom navigation must have four items");
        int height=-1,width=-1,chosen=0;
        for(int i=0;i<nav.getChildCount();i++) {
            Button item=(Button)nav.getChildAt(i);check(item.getLayout()!=null&&item.getLayout().getLineCount()==1,"Bottom label wraps: "+item.getContentDescription());
            check(item.getLayout().getEllipsisCount(0)==0,"Bottom label is truncated: "+item.getContentDescription());
            check(!item.getText().toString().contains("\n"),"Bottom label contains a forced newline");
            if(height<0){height=item.getHeight();width=item.getWidth();}else check(item.getHeight()==height&&Math.abs(item.getWidth()-width)<=1,"Bottom selection geometry differs");
            check(item.isSelected()==selected.contentEquals(item.getContentDescription()),"Wrong bottom selected state");if(item.isSelected())chosen++;
        }
        check(chosen==1,"Bottom selection is not unique");
    }
    private static void compactCaptcha(View root,float density,int vpnModeHeight) {
        ViewGroup choice=root.findViewWithTag("captcha-mode-switch");check(choice!=null&&choice.getChildCount()==2,"CAPTCHA must have exactly two choices");
        String[] labels={"Авто","Вручную"};int chosen=0;
        for(int i=0;i<2;i++) {
            Button item=(Button)choice.getChildAt(i);
            check(labels[i].contentEquals(item.getText()),"Technical CAPTCHA mode leaked into user label");
            check(item.getHeight()>=48*density-1,"Compact CAPTCHA touch target below48dp");
            check(item.getTop()==choice.getChildAt(0).getTop(),"CAPTCHA choices must be horizontal");
            check(("mode-rendered:"+labels[i]).equals(item.getTag()),"Compact CAPTCHA label did not fit");
            android.view.accessibility.AccessibilityNodeInfo info=item.createAccessibilityNodeInfo();
            check(info.isCheckable()&&info.isChecked()==item.isSelected(),"CAPTCHA choice lacks accessible checked state");
            if(item.isSelected())chosen++;
        }
        check(chosen==1,"CAPTCHA selected state must be unique");
        check(vpnModeHeight>0&&choice.getHeight()<vpnModeHeight,"CAPTCHA switch must be smaller than VPN/Proxy");
        android.graphics.Rect visible=new android.graphics.Rect();check(choice.getGlobalVisibleRect(visible)&&visible.width()==choice.getWidth(),"CAPTCHA switch clipped at screen edge");
    }
    private static void preview(Instrumentation test,View root,String name,StringBuilder report) {
        check(root.getWidth()>0&&root.getHeight()>0,"App preview has no bounds");
        android.graphics.Bitmap bitmap=android.graphics.Bitmap.createBitmap(root.getWidth(),root.getHeight(),android.graphics.Bitmap.Config.ARGB_8888);
        try {
            root.draw(new android.graphics.Canvas(bitmap));
            java.io.File output=new java.io.File(test.getTargetContext().getExternalCacheDir(),name);
            try(java.io.OutputStream stream=new java.io.FileOutputStream(output)){check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream),"App preview encoding failed");}
            report.append("MCRF_ONLY_PREVIEW=").append(output.getAbsolutePath()).append('\n');
        }catch(java.io.IOException error){throw new IllegalStateException("App preview could not be saved",error);}
        finally{bitmap.recycle();}
    }
    static String run(Instrumentation test)throws Exception {
        Activity activity=test.startActivitySync(new Intent().setClassName("org.mcrf.client","org.mcrf.client.MainActivity")
            .putExtra("test_no_prompts",true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        StringBuilder report=new StringBuilder();
        try {
            test.waitForIdleSync();View root=activity.getWindow().getDecorView();
            // Catalogue decrypt/load is asynchronous, especially on a cold debug install.
            // Wait for the actual ready UI, never treat its loading placeholder as missing WARP.
            awaitTag(test,root,"home-servers");
            main(test,()->check(root.findViewWithTag("warp-open")!=null,"Home WARP action missing"));
            main(test,()->{navigation(root,"VPN");check(root.findViewWithTag("captcha-mode-switch")==null&&root.findViewWithTag("home-captcha-settings")==null&&root.findViewWithTag("auto-policy")==null,"Whitelist controls remain on VPN");});
            java.util.concurrent.atomic.AtomicInteger vpnModeHeight=new java.util.concurrent.atomic.AtomicInteger();
            main(test,()->vpnModeHeight.set(root.findViewWithTag("home-mode-switch").getHeight()));
            click(test,root,"warp-open");
            awaitTag(test,root,"warp-country");
            main(test,()->{
                android.widget.Spinner countries=root.findViewWithTag("warp-country");android.widget.CheckBox all=root.findViewWithTag("warp-all-countries");
                check(countries!=null&&all!=null,"WARP country selector/all-countries control missing");
                android.graphics.drawable.Drawable popup=countries.getPopupBackground();
                check(popup instanceof android.graphics.drawable.GradientDrawable,"WARP selector popup must use the app surface");
                android.content.res.ColorStateList fill=((android.graphics.drawable.GradientDrawable)popup).getColor();
                check(fill!=null&&android.graphics.Color.alpha(fill.getDefaultColor())==255,"WARP selector popup must hide underlying text");
                check(all.isChecked()?countries.getCount()>2:countries.getCount()==2,"Unexpected WARP country choices");
                preview(test,root,"mcrf-warp-refined.png",report);
            });
            click(test,root,"nav-Главная");
            click(test,root,"nav-Белые списки");
            main(test,()->{preview(test,root,"mcrf-compact-captcha.png",report);compactCaptcha(root,activity.getResources().getDisplayMetrics().density,vpnModeHeight.get());});
            main(test,()->{
                check(root.findViewWithTag("whitelist-connection-control")!=null,"Whitelist connection control missing");
                check(root.findViewWithTag("nav-Автодеплой")==null,"Old deployment tab remains");navigation(root,"Белые списки");
                ViewGroup nav=root.findViewWithTag("main-navigation");check("Белые списки".contentEquals(nav.getChildAt(1).getContentDescription()),"Whitelists must be the second tab");
                ViewGroup actions=root.findViewWithTag("whitelist-actions");check(actions!=null&&actions.getChildCount()==4,"Whitelist settings must fit one four-action row");
                check(root.findViewWithTag("whitelist-settings-Настройки серверов")==null,"Separate connection settings shortcut remains");
                for(int index=0;index<actions.getChildCount();index++){Button action=(Button)actions.getChildAt(index);check(action.getHeight()>=48*activity.getResources().getDisplayMetrics().density,"Compact action lost touch target");labelFits(action,1,"Whitelist compact action");}
            });
            for(String child:new String[]{"Капча VK","Аккаунт и звонки VK","Настройки OpenFlux"}){
                click(test,root,"whitelist-settings-"+child);
                main(test,()->check(root.findViewWithTag("screen-"+child)!=null,"Whitelist child missing: "+child));
                click(test,root,"settings-back");
                main(test,()->check(root.findViewWithTag("screen-Белые списки")!=null&&root.findViewWithTag("screen-Настройки")==null,"Whitelist child back returned to Settings: "+child));
            }
            click(test,root,"nav-Главная");
            report.append("PASS single-line equal-size bottom navigation and unique Whitelists selection\n");
            click(test,root,"settings-action");
            main(test,()->{check(root.findViewWithTag("screen-Настройки")!=null,"Settings root missing");check(!retired(root),"Retired options still visible");
                check(root.findViewWithTag("copy-proxy-socks5")==null,"Proxy controls duplicated at settings root");check(root.findViewWithTag("dns-settings")==null,"VPN DNS duplicated at settings root");});
            main(test,()->{
                View deploy=root.findViewWithTag("settings-page-Автодеплой");check(deploy!=null,"Autodeploy setting missing");
                check(((ViewGroup)deploy.getParent()).indexOfChild(deploy)==2,"Autodeploy must be third Settings entry");
                android.widget.EditText search=root.findViewWithTag("settings-search");check(search!=null,"General settings search missing");
                search.setText("csqtt 2.5");
                View servers=root.findViewWithTag("settings-page-Настройки серверов");
                check(servers!=null&&servers.getVisibility()==View.VISIBLE&&deploy.getVisibility()==View.GONE,"New CSQTT settings are not searchable");
                search.setText("капча");
                View captcha=root.findViewWithTag("settings-page-Капча VK");check(captcha!=null&&captcha.getVisibility()==View.VISIBLE,"CAPTCHA settings are not searchable");
                search.setText("");check(deploy.getVisibility()==View.VISIBLE,"Clearing settings search lost an entry");
            });
            click(test,root,"settings-page-Автодеплой");
            main(test,()->{check(root.findViewWithTag("screen-Автодеплой")!=null,"Autodeploy settings page missing");check(root.findViewWithTag("settings-back")!=null,"Autodeploy Settings back missing");preview(test,root,"mcrf-refined-autodeploy.png",report);});
            click(test,root,"settings-back");
            report.append("PASS settings root excludes retired sections and duplicate proxy controls\n");
            String settingsBaseline=settingsDigest(test);
            click(test,root,"settings-page-Оформление");awaitTag(test,root,"random-appearance");
            main(test,()->{
                labelFits(root.findViewWithTag("random-appearance"),2,"Random appearance");
                labelFits(root.findViewWithTag("theme-colors-save"),1,"Apply appearance");
            });
            click(test,root,"settings-back");
            report.append("PASS appearance Random/Apply labels are fully visible without draft commit\n");
            for(String page:new String[]{"DNS и домены","Капча VK","Подключение и работа в фоне","VPN и локальный прокси","Раздача VPN другим устройствам","Дополнительные настройки TLS","Дополнительное ядро Xray","Базы GeoIP и GeoSite","Аккаунт и звонки VK","Обновления","Расширенные настройки"}) {
                click(test,root,"settings-page-"+page);
                main(test,()->{check(root.findViewWithTag("screen-"+page)!=null,"Not a real settings page: "+page);
                    check(root.findViewWithTag("settings-page-"+page)==null,"Settings editor opened over root");
                    check(root.findViewWithTag("edge-vpn")!=null&&root.findViewWithTag("edge-telegram")!=null,"Side toggles missing: "+page);
                    if(!page.equals("Базы GeoIP и GeoSite")&&!page.equals("Аккаунт и звонки VK")&&!page.equals("Обновления"))check(headings(root)>=2,"Settings page lacks meaningful section headings: "+page);
                    for(String retiredKey:new String[]{"ntpServer","ntpPort","ntpInterval","clashSecret","clashPort","apiSecret","apiPort"})check(root.findViewWithTag("setting."+retiredKey)==null,"Retired setting still editable: "+retiredKey);
                    check(root.findViewWithTag("expert-body-dns")==null,"Retired custom DNS JSON editor remains");
                    check((root.findViewWithTag("setting.streamWindow")!=null)==page.equals("Расширенные настройки"),"QUIC settings not isolated on Advanced page");
                    check((root.findViewWithTag("setting.customInbounds")!=null)==page.equals("Расширенные настройки"),"Inbound JSON not isolated on Advanced page");
                    for(String key:new String[]{"privateRanges","dnsPort","mtu","address4","address6","randomPort","httpProxy","httpBypass","certificateStore","insecure","muxDefault","muxProtocol","muxConcurrency","fragmentDefault","fingerprint"})check((root.findViewWithTag("setting."+key)!=null)==page.equals("Расширенные настройки"),"Approved setting not isolated on Advanced page: "+key);
                    check(root.findViewWithTag("setting.bypassDirectIps")==null,"Retired direct TUN switch remains visible");
                    for(String key:new String[]{"shareLan","lanPort"})check((root.findViewWithTag("setting."+key)!=null)==page.equals("Раздача VPN другим устройствам"),"Sharing settings duplicated outside dedicated page: "+key);
                    if(page.equals("Раздача VPN другим устройствам"))check(root.findViewWithTag("lan-sharing-page")!=null&&root.findViewWithTag("lan-sharing-copy-addresses")!=null&&root.findViewWithTag("lan-sharing-copy-password")!=null,"Dedicated sharing page incomplete");
                    for(String key:new String[]{"xrayMuxDefault","xrayMuxConcurrency","fragmentSize","fragmentSleep","mixedCaseSniDefault"})check((root.findViewWithTag("setting."+key)!=null)==page.equals("Расширенные настройки"),"Approved module setting not isolated on Advanced page: "+key);
                    for(String key:new String[]{"remoteNoAAAA","directNoAAAA","fakeIp","answers","capacity","optimistic","timeout"})check((root.findViewWithTag("dns-setting."+key)!=null)==page.equals("Расширенные настройки"),"Approved DNS setting not isolated on Advanced page: "+key);
                    if(page.equals("VPN и локальный прокси")) {
                        check(root.findViewWithTag("setting.dnsPort")==null,"Local DNS duplicated on VPN page");
                        Button socks=root.findViewWithTag("copy-proxy-socks5"),http=root.findViewWithTag("copy-proxy-http");
                        check(socks!=null&&http!=null&&socks.getParent()==http.getParent(),"Proxy links must share one row");
                        check(socks.getCompoundDrawables()[0]!=null&&http.getCompoundDrawables()[0]!=null,"Copy icons missing");
                        check(Math.abs(socks.getWidth()-http.getWidth())<=1&&http.getLeft()>socks.getRight(),"Copy buttons are unequal or glued");
                    }
                    if(page.equals("Обновления"))check(root.findViewWithTag("update-row")!=null&&root.findViewWithTag("openflux-updates")==null,"OpenFlux settings must live under Whitelists");
                    if(page.equals("DNS и домены")){check(root.findViewWithTag("dns-settings")!=null,"VPN DNS missing from the single DNS page");check(root.findViewWithTag("setting.dnsPort")==null,"Technical local DNS port remains on normal DNS page");}
                    if(page.equals("Подключение и работа в фоне"))check(root.findViewWithTag("background-settings")!=null&&root.findViewWithTag("notification-settings")!=null,"Background/notification access missing");
                });
                report.append("PASS standalone page, side toggles and back: ").append(page).append('\n');
                click(test,root,"settings-back");
                main(test,()->check(root.findViewWithTag("screen-Настройки")!=null,"Back did not return to settings"));
            }
            check(settingsBaseline.equals(settingsDigest(test)),"Settings navigation rewrote encrypted settings");
            click(test,root,"settings-back");
            main(test,()->check(root.findViewWithTag("screen-Главная")!=null,"Settings lost originating section"));
            report.append("PASS WARP home action and settings return to original section\n");
            main(test,()->preview(test,root,"mcrf-refined-home.png",report));
            return report.toString();
        }finally{test.runOnMainSync(activity::finish);}
    }
}
