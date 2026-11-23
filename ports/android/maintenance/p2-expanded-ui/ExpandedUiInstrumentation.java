package org.mcrf.expandedui;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import org.json.JSONObject;
import java.io.File;
import java.nio.file.Files;
import java.lang.reflect.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/** New task57 widget acceptance only. Isolated encrypted records; no SSH/import/install action. */
public final class ExpandedUiInstrumentation extends Instrumentation {
    private String phase="deploy-names";
    private boolean allowNetworkScreen;
    private Activity activity;
    private Dialog dialog;
    private Object backend, ui, store;
    private ClassLoader loader;
    private int passed;
    private boolean allowVpnConsent;
    private String privateSocket;
    private final StringBuilder report = new StringBuilder();
    private final Map<String,Map<String,?>> originalPrefs=new HashMap<>();
    private final Map<File,byte[]> originalFiles=new LinkedHashMap<>();
    private String originalPointer;
    @Override public void onCreate(Bundle args) { super.onCreate(args);if(args!=null){phase=args.getString("phase","deploy-names");privateSocket=args.getString("privateSocket","");allowNetworkScreen=args.getString("allowNetworkScreen","").equals("true");allowVpnConsent=args.getString("allowVpnConsent","").equals("true");} start(); }
    private Class<?> cls(String name) throws Exception { return Class.forName("org.mcrf.client."+name,true,loader); }
    private Object call(Object target,String name,Object... args) throws Exception {
        for(Method m:target.getClass().getMethods()) {
            if(m.getName().equals(name)&&m.getParameterCount()==args.length) {
                try { return m.invoke(target,args); }
                catch(InvocationTargetException e) { throw new Exception(name+": "+e.getCause(),e.getCause()); }
            }
        }
        throw new NoSuchMethodException(name);
    }
    private void main(Runnable action) throws Exception {
        AtomicReference<Throwable> error=new AtomicReference<>();
        runOnMainSync(()->{try{action.run();}catch(Throwable e){error.set(e);}});
        if(error.get()!=null)throw new Exception("UI: "+error.get(),error.get());
    }
    private void check(boolean value,String label) throws Exception {
        if(!value)throw new Exception(label);
        passed++;report.append("PASS ").append(label).append('\n');
    }
    private List<View> views(View root) {
        List<View> result=new ArrayList<>();result.add(root);
        if(root instanceof ViewGroup)for(int i=0;i<((ViewGroup)root).getChildCount();i++)result.addAll(views(((ViewGroup)root).getChildAt(i)));
        return result;
    }
    private Button button(String label) {
        for(View view:views(dialog.getWindow().getDecorView()))if(view instanceof Button&&((Button)view).getText().toString().equals(label))return (Button)view;
        throw new IllegalStateException("Missing button "+label);
    }
    private EditText nameField() {
        for(View view:views(dialog.getWindow().getDecorView()))if(view instanceof EditText)return (EditText)view;
        throw new IllegalStateException("Missing name editor");
    }
    private JSONObject workspace() throws Exception { return (JSONObject)call(store,"workspace"); }
    private Object function(String kind,java.util.function.Function<Object[],Object> action) throws Exception {
        Class<?> type=Class.forName("kotlin.jvm.functions."+kind,true,loader);
        return Proxy.newProxyInstance(loader,new Class<?>[]{type},(proxy,method,args)->method.getName().equals("invoke")?action.apply(args):null);
    }
    private void update(String section,JSONObject changes) throws Exception {
        call(store,"update",section,function("Function1",args->{JSONObject value=(JSONObject)args[0];for(Iterator<String> it=changes.keys();it.hasNext();){String key=it.next();try{value.put(key,changes.get(key));}catch(Exception e){throw new RuntimeException(e);}}return null;}));
    }
    private void initializeActivity(Context base,boolean hasProfiles) throws Exception {
        activity=startActivitySync(new Intent(base,cls("MainActivity")).putExtra("test_no_prompts",true).putExtra("test_direct",hasProfiles).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        long deadline=SystemClock.uptimeMillis()+10000;
        while((Boolean)field(activity,"loading")&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(40);
        if((Boolean)field(activity,"loading"))throw new Exception("Main UI initialization did not finish");
        main(()->{try{Object chooser=field(activity,"primaryChooser");if(chooser instanceof Dialog)((Dialog)chooser).dismiss();}catch(Exception e){throw new RuntimeException(e);}});
    }
    private AccessibilityNodeInfo node(String label) throws Exception {
        long deadline=SystemClock.uptimeMillis()+5000;
        while(SystemClock.uptimeMillis()<deadline){
            AccessibilityNodeInfo root=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES).getRootInActiveWindow();
            if(root!=null)for(AccessibilityNodeInfo item:root.findAccessibilityNodeInfosByText(label))if(label.contentEquals(item.getText()==null?"":item.getText())&&"org.mcrf.client".contentEquals(item.getPackageName()))return item;
            SystemClock.sleep(50);
        }
        throw new Exception("Missing own UI label: "+label);
    }
    private void clickLocal(String label) {clickIn(dialog.getWindow().getDecorView(),label);}
    private void clickIn(View root,String label) {
        for(View v:views(root))if(v instanceof TextView&&label.contentEquals(((TextView)v).getText())){
            View target=v;while(!target.isClickable()&&target.getParent() instanceof View)target=(View)target.getParent();
            if(target.isClickable()){target.performClick();return;}
        }
        throw new AssertionError("Missing clickable local label: "+label);
    }
    private void deployQuickUi(Context base,boolean hasProfiles) throws Exception {
        initializeActivity(base,hasProfiles);
        String host="192.0.2.41";
        JSONObject values=new JSONObject().put("host",host).put("user","root").put("sshPort","22").put("sshAuth","password").put("sshPassword","fixture-only");
        String id=(String)call(store,"newDeployment","swg","Quick UI fixture",values,"");
        String other=(String)call(store,"newDeployment","swg","Other VPS fixture",new JSONObject(values.toString()).put("host","192.0.2.42").put("sshPassword","other-fixture"),"");
        String operation=UUID.randomUUID().toString(),key=host+":22/swg/"+operation;
        JSONObject record=new JSONObject().put("operation_id",operation).put("recipe_id","swg").put("created_at",System.currentTimeMillis()).put("deployment_id",id).put("deployment_name","Quick UI fixture")
            .put("panel_url","https://"+host+":8443/panel/").put("username","fixture").put("password","fixture-only").put("certificate_sha256",String.join("",Collections.nCopies(64,"a"))).put("ready",true)
            .put("connections",new org.json.JSONArray())
            .put("transport_template",new JSONObject().put("host",host).put("user","root").put("sshPort","22").put("panelPort","8443").put("domain","").put("options",new JSONObject().put("create",false)));
        update("deployResults",new JSONObject().put(key,record));
        String sibling="";
        if(phase.equals("deploy-accordion-ui")){
            sibling=(String)call(store,"newDeployment","3xui","Same VPS second panel",new JSONObject(values.toString()),"");
            String op=UUID.randomUUID().toString();
            JSONObject second=new JSONObject(record.toString()).put("operation_id",op).put("recipe_id","3xui").put("deployment_id",sibling)
                .put("deployment_name","Same VPS second panel").put("panel_url","https://"+host+":8444/panel/");
            second.getJSONObject("transport_template").put("panelPort","8444");
            update("deployResults",new JSONObject().put(host+":22/3xui/"+op,second));
        }
        JSONObject inventory=(JSONObject)call(store,"inventory");
        String serverName=inventory.getJSONObject("servers").getJSONObject(inventory.getJSONObject("servers").keys().next()).getString("name");
        String chosenPanel=inventory.getJSONObject("panels").keys().next();
        if(phase.equals("deploy-accordion-ui"))for(Iterator<String> it=inventory.getJSONObject("panels").keys();it.hasNext();){String candidate=it.next();if(inventory.getJSONObject("panels").getJSONObject(candidate).getString("recipe").equals("swg")){chosenPanel=candidate;break;}}
        String panelName=inventory.getJSONObject("panels").getJSONObject(chosenPanel).getString("name");
        main(()->{try{
            call(activity,"navigate","Автодеплой");
            Object previous=field(activity,"deployUi");if(previous!=null)call(previous,"detach");
            View old=activity.getWindow().getDecorView().findViewWithTag("androidDeploy.browser");
            if(old==null)throw new AssertionError("Normal autodeploy page missing");
            ViewGroup parent=(ViewGroup)old.getParent();int index=parent.indexOfChild(old);ViewGroup.LayoutParams layout=old.getLayoutParams();
            ui=cls("AndroidDeployUi").getConstructor(Activity.class,cls("AndroidDeployStore"),cls("AndroidDeployBackend"),Class.forName("kotlin.jvm.functions.Function1",true,loader)).newInstance(activity,store,backend,function("Function1",args->{throw new AssertionError("No import allowed");}));
            parent.removeView(old);parent.addView((View)call(ui,"buildPage"),index,layout);
            Field owner=activity.getClass().getDeclaredField("deployUi");owner.setAccessible(true);owner.set(activity,ui);
        }catch(Exception e){throw new RuntimeException(e);}});
        waitForIdleSync();
        if(phase.equals("deploy-accordion-ui")){
            deployAccordionUi(base,serverName,panelName,id,sibling,other);return;
        }
        long headings=views(activity.getWindow().getDecorView()).stream().filter(v->v instanceof TextView&&"Автодеплой".contentEquals(((TextView)v).getText())&&v.isShown()).count();
        if(!phase.endsWith("-rest"))check(headings==1,"normal autodeploy page has one heading and one shared back path");
        main(()->{View root=activity.getWindow().getDecorView();clickIn(root,"Серверы и панели");clickIn(root,serverName);clickIn(root,panelName);});
        int drafts=workspace().getJSONObject("drafts").length();
        main(()->clickIn(activity.getWindow().getDecorView(),"Добавить подключение"));
        if(!phase.endsWith("-rest"))check(node("QWDTT").isVisibleToUser()&&node("CSQTT v2.1.9 · SWG").isVisibleToUser(),"existing panel add opens short protocol choice, not server access editor");
        selectDialogRow("WDTT");waitForIdleSync();
        await(()->{AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();return root!=null&&(!root.findAccessibilityNodeInfosByText("Новое подключение").isEmpty()||!root.findAccessibilityNodeInfosByText("Нужен доступ SSH").isEmpty());},5);
        capture(base,"deploy-quick-dialog");
        AccessibilityNodeInfo active=getUiAutomation().getRootInActiveWindow();
        check(active!=null&&!active.findAccessibilityNodeInfosByText("Новое подключение").isEmpty()&&active.findAccessibilityNodeInfosByText("SSH-пароль").isEmpty(),"quick connection dialog contains protocol fields without SSH wall");
        selectDialogRow("Отмена");
        check(workspace().getJSONObject("drafts").length()==drafts&&!(Boolean)call(call(backend,"getState"),"getRunning"),"canceling quick add creates no draft or remote operation");
        main(()->{clickIn(activity.getWindow().getDecorView(),"Настройки сервера");EditText password=activity.getWindow().getDecorView().findViewWithTag("androidDeploy.serverSettings.password");if(password==null||!password.getText().toString().equals("fixture-only"))throw new AssertionError("Wrong saved SSH identity");password.setText("updated-fixture");activity.getWindow().getDecorView().findViewWithTag("androidDeploy.serverSettings.save").performClick();});
        JSONObject after=workspace().getJSONObject("drafts");
        check(after.getJSONObject(id).getJSONObject("values").getString("sshPassword").equals("updated-fixture")&&after.getJSONObject(other).getJSONObject("values").getString("sshPassword").equals("other-fixture"),"server settings save only the matching VPS credentials");
        main(()->activity.getWindow().getDecorView().findViewWithTag("androidDeploy.back").performClick());
        check(node("Добавить подключение").isVisibleToUser(),"normal page back returns from server settings to panel instead of leaving section");
        android.content.ClipboardManager clipboard=base.getSystemService(android.content.ClipboardManager.class);
        final android.content.ClipData[] saved={null};main(()->saved[0]=clipboard.getPrimaryClip());
        try{
            main(()->{try{dialog=(Dialog)call(cls("AndroidDeployPanelAccessUi").getField("INSTANCE").get(null),"show",activity,record);}catch(Exception e){throw new RuntimeException(e);}});
            waitForIdleSync();
            main(()->{View root=dialog.getWindow().getDecorView();TextView password=root.findViewWithTag("androidDeploy.panelAccess.value.Пароль");if(password==null||!password.getText().toString().equals("••••••••"))throw new AssertionError("Panel password is visible");long copies=views(root).stream().filter(v->v.getTag()!=null&&v.getTag().toString().startsWith("androidDeploy.panelAccess.copy.")).count();if(copies!=4)throw new AssertionError("Expected four consistent copy targets");});
            check(true,"panel access has four shared-style copy rows and masked password");
            capture(base,"deploy-panel-access-new");
            main(()->dialog.getWindow().getDecorView().findViewWithTag("androidDeploy.panelAccess.copy.Пароль").performClick());
            final boolean[] copied={false};main(()->{android.content.ClipData clip=clipboard.getPrimaryClip();copied[0]=clip!=null&&"fixture-only".contentEquals(clip.getItemAt(0).getText())&&clip.getDescription().getExtras()!=null&&clip.getDescription().getExtras().getBoolean("android.content.extra.IS_SENSITIVE");});
            check(copied[0],"panel password copy preserves value and sensitive clipboard flag");
        }finally{main(()->{if(dialog!=null){dialog.dismiss();dialog=null;}if(saved[0]==null)clipboard.clearPrimaryClip();else clipboard.setPrimaryClip(saved[0]);});}
        Object quick=cls("AndroidDeployQuickConnection").getField("INSTANCE").get(null);
        List<?> choices=(List<?>)call(quick,"choices","openflux");
        check(choices.size()==7,"OpenFlux exposes all seven existing-panel connection choices");
        capture(base,"deploy-panel-new");
    }
    private boolean hasVisibleText(String label){
        return views(activity.getWindow().getDecorView()).stream().anyMatch(v->v instanceof TextView&&v.isShown()&&label.contentEquals(((TextView)v).getText()));
    }
    private JSONObject managementRequest(Context base,Object target,JSONObject saved,String action,JSONObject params,boolean mutate,Object trust) throws Exception {
        Object controller=cls("ServerManagementController").getField("INSTANCE").get(null);
        Object kind=Arrays.stream(cls("ServerManagementRequest$Action").getEnumConstants()).filter(v->((Enum<?>)v).name().equals(action)).findFirst().get();
        java.util.concurrent.CountDownLatch done=new java.util.concurrent.CountDownLatch(1);
        AtomicReference<JSONObject> result=new AtomicReference<>();AtomicReference<String> error=new AtomicReference<>();
        Object complete=function("Function2",args->{result.set((JSONObject)args[0]);error.set((String)args[1]);done.countDown();return null;});
        String id=(String)(mutate?call(controller,"mutate",base,store,target,saved,kind,params,true,complete,trust):call(controller,"read",base,store,target,saved,kind,complete,trust));
        if(id==null)throw new Exception("Owned management lease unavailable");
        if(!done.await(90,TimeUnit.SECONDS)){
            call(controller,"cancel",id);done.await(20,TimeUnit.SECONDS);throw new Exception("Bounded management callback timeout");
        }
        if(error.get()!=null||result.get()==null)throw new Exception("Management "+action+": "+error.get());
        return result.get();
    }
    private void managementLive(Context base,boolean hasProfiles,JSONObject payload) throws Exception {
        initializeActivity(base,hasProfiles);
        JSONObject saved=payload.getJSONObject("saved");JSONObject template=saved.getJSONObject("transport_template");
        if(!template.getString("host").equals("144.31.159.50")||!template.getString("sshPort").equals("22")||!template.getString("panelPort").equals("18445"))throw new Exception("Only owned SWG test endpoint allowed");
        org.json.JSONArray fingerprints=payload.getJSONArray("knownFingerprints");
        Set<String> expected=new HashSet<>();for(int i=0;i<fingerprints.length();i++){String value=fingerprints.getString(i);if(!value.matches("SHA256:[A-Za-z0-9+/]{43}"))throw new Exception("Invalid known SSH fingerprint");expected.add(value);}
        if(expected.isEmpty())throw new Exception("No previously verified SSH identity");
        Object trust=function("Function2",args->{if(!"144.31.159.50:22".equals(args[0])||!expected.contains(args[1]))throw new AssertionError("Owned SSH host identity changed");return true;});
        Object target=cls("AndroidDeployTarget").getConstructor(String.class,String.class,int.class,String.class,String.class,String.class,int.class,String.class,String.class,String.class,JSONObject.class,boolean.class,String.class,String.class,String.class)
            .newInstance("144.31.159.50","root",22,payload.getString("sshPassword"),"","",18445,saved.getString("username"),saved.getString("password"),"",new JSONObject().put("create",false),true,UUID.randomUUID().toString(),"","");
        JSONObject status=managementRequest(base,target,saved,"STATUS",new JSONObject(),false,trust);
        check(status.has("version")&&status.has("rx_bytes_per_sec"),"actual Android SSH controller reads SWG status through pinned host and panel certificate");
        JSONObject clients=managementRequest(base,target,saved,"CLIENTS",new JSONObject(),false,trust);
        check(clients.has("clients"),"actual Android SSH controller reads existing panel clients without generic no-response error");
        JSONObject interfaces=managementRequest(base,target,saved,"INTERFACES",new JSONObject(),false,trust);
        check(interfaces.has("interfaces"),"actual Android SSH controller reads restart candidates without inventing unavailable interfaces");
        JSONObject created=managementRequest(base,target,saved,"ADD_CLIENT",new JSONObject().put("name","MCRF P2 disposable "+UUID.randomUUID().toString().substring(0,8)),true,trust);
        String client=created.getString("client_id");
        check(client.matches("[A-Za-z0-9_-]{1,128}"),"actual Android management creates only a new disposable test client with confirmed response");
        JSONObject disabled=managementRequest(base,target,saved,"DISABLE_CLIENT",new JSONObject().put("client_id",client),true,trust);
        check(client.equals(disabled.getString("client_id")),"actual Android management disables its own disposable client without touching existing clients");
    }
    private void deployAccordionUi(Context base,String serverName,String panelName,String id,String sibling,String other) throws Exception {
        main(()->clickIn(activity.getWindow().getDecorView(),"Серверы и панели"));
        check(!hasVisibleText(panelName)&&!hasVisibleText("Настройки сервера"),"server panels and shared settings start collapsed inline");
        main(()->clickIn(activity.getWindow().getDecorView(),serverName));
        check(hasVisibleText(panelName)&&hasVisibleText("Настройки сервера"),"server expands panel buttons and one shared settings row on the same page");
        capture(base,"deploy-server-accordion");
        main(()->clickIn(activity.getWindow().getDecorView(),panelName));
        check(hasVisibleText("Добавить подключение")&&!hasVisibleText("Настройки сервера"),"panel opens a separate action page without duplicate SSH settings");
        main(()->clickIn(activity.getWindow().getDecorView(),"Добавить подключение"));
        check(node("CSQTT v2.1.9 · SWG").isVisibleToUser()&&node("CSQTT v2.5 · SWG").isVisibleToUser(),"both real CSQTT generations remain transparent selectable choices");
        selectDialogRow("CSQTT v2.5 · SWG");waitForIdleSync();
        await(()->{AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();return root!=null&&!root.findAccessibilityNodeInfosByText("Новое подключение").isEmpty();},5);
        AccessibilityNodeInfo popup=getUiAutomation().getRootInActiveWindow();
        check(popup!=null&&popup.findAccessibilityNodeInfosByText("WireGuard").isEmpty()&&popup.findAccessibilityNodeInfosByText("SSH-пароль").isEmpty(),"CSQTT2.5 quick editor excludes unrelated WireGuard and SSH fields");
        selectDialogRow("Отмена");
        main(()->activity.getWindow().getDecorView().findViewWithTag("androidDeploy.back").performClick());
        check(hasVisibleText(panelName)&&hasVisibleText("Настройки сервера"),"panel back returns to the expanded server without an intermediate list window");
        main(()->{clickIn(activity.getWindow().getDecorView(),"Настройки сервера");EditText password=activity.getWindow().getDecorView().findViewWithTag("androidDeploy.serverSettings.password");if(password==null)throw new AssertionError("Shared SSH editor missing");password.setText("shared-updated-fixture");activity.getWindow().getDecorView().findViewWithTag("androidDeploy.serverSettings.save").performClick();});
        JSONObject drafts=workspace().getJSONObject("drafts");
        check(drafts.getJSONObject(id).getJSONObject("values").getString("sshPassword").equals("shared-updated-fixture")&&drafts.getJSONObject(sibling).getJSONObject("values").getString("sshPassword").equals("shared-updated-fixture")&&drafts.getJSONObject(other).getJSONObject("values").getString("sshPassword").equals("other-fixture"),"shared settings atomically update both same-VPS panels and preserve other VPS credentials");
    }
    private void historicalModules(Context base) throws Exception {
        for(String kind:new String[]{"csqtt20","csqtt215"}){
            String expected=kind.equals("csqtt20")?"2.0.0":"2.1.5";
            File wrapper=(File)cls("NativeCarrier").getMethod("executable",Context.class,String.class).invoke(null,base,kind);
            android.content.pm.ApplicationInfo application=base.getPackageManager().getApplicationInfo("org.mcrf.engines."+kind,android.content.pm.PackageManager.GET_META_DATA);
            check(wrapper.isFile()&&wrapper.canExecute()&&wrapper.getParentFile().getCanonicalPath().equals(new File(application.nativeLibraryDir).getCanonicalPath()),"installed "+kind+" resolves its own signed generation metadata and dedicated wrapper without legacy substitution");
            File transport=new File(wrapper.getParentFile(),"libcsqtt_transport.so");
            java.lang.Process child=new ProcessBuilder(transport.getAbsolutePath(),"--mcrf-native-validation-noop").redirectErrorStream(true).start();
            String output;
            try{
                if(!child.waitFor(10,TimeUnit.SECONDS))throw new Exception("Bounded native version check timed out");
                byte[] bytes=new byte[512];int used=0,count;
                while(used<bytes.length&&(count=child.getInputStream().read(bytes,used,bytes.length-used))>0)used+=count;
                output=new String(bytes,0,used,java.nio.charset.StandardCharsets.UTF_8).trim();
                check(child.exitValue()==2&&output.contains("unexpected argument")&&output.contains("--mcrf-native-validation-noop"),"actual Android native transport for "+kind+" executes its argument parser before network or credentials; generation "+expected+" remains source-bound");
            }finally{child.destroy();if(child.isAlive()){child.destroyForcibly();child.waitFor(5,TimeUnit.SECONDS);}}
        }
    }
    private void csqttImportVersions(Context base,boolean hasProfiles) throws Exception {
        initializeActivity(base,hasProfiles);
        String hash=java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        String one="csqtt://connect?v=2&host=fixture.invalid&peer=46000&password=fixture-only&hashes="+hash;
        String two=one.replace("peer=46000","peer=46001");
        Object importer=cls("EngineImportUi").getConstructor(cls("MainActivity")).newInstance(activity);
        try {
            main(()->{try{call(importer,"show",one+"\n"+two,true);}catch(Exception e){throw new RuntimeException(e);}});
            Object state=call(importer,"getState");long deadline=SystemClock.uptimeMillis()+10000;
            while(!"csqtt-version".equals(call(state,"getPhase"))&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(50);
            if(!phase.equals("csqtt-import-versions-rest"))check("csqtt-version".equals(call(state,"getPhase"))&&((List<?>)call(state,"getCsqttRequired")).size()==2,"two ambiguous v2 links require two explicit version choices before any engine lookup");
            dialog=(Dialog)field(importer,"dialog");
            String firstId=(String)field(((List<?>)call(state,"getCsqttRequired")).get(0),"id");
            deadline=SystemClock.uptimeMillis()+5000;
            while(dialog.getWindow().getDecorView().findViewWithTag("import-csqtt-version."+firstId)==null&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(50);
            main(()->{View root=dialog.getWindow().getDecorView();int count=0;boolean explanation=false;for(View v:views(root)){
                if(v.getTag() instanceof String&&((String)v.getTag()).startsWith("import-csqtt-version."))count++;
                if(v instanceof TextView&&((TextView)v).getText().toString().contains("v=2"))explanation=true;
            }if(count!=2||!explanation)throw new AssertionError("Missing independent choices or explanation of link-format version");});
            if(!phase.equals("csqtt-import-versions-rest"))check(true,"version resolver explains uncertainty and renders an independent shared-style picker per server");
            String id=firstId;
            main(()->dialog.getWindow().getDecorView().findViewWithTag("import-csqtt-version."+id).performClick());
            selectDialogRow("CSQTT v2.0.0 · Original");
            await(()->"2.0".equals(((Map<?,?>)call(state,"getCsqttChoices")).get(id)),5);
            check("2.0".equals(((Map<?,?>)call(state,"getCsqttChoices")).get(id)),"visible version label selects the exact historical engine family instead of a cosmetic alias");
            main(()->((AlertDialog)dialog).getButton(AlertDialog.BUTTON_POSITIVE).performClick());
            deadline=SystemClock.uptimeMillis()+5000;while(!"csqtt-version".equals(call(state,"getPhase"))&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(50);
            check("csqtt-version".equals(call(state,"getPhase"))&&call(state,"getPlan")==null&&((List<?>)call(state,"getAssets")).isEmpty(),"unresolved second server prevents catalogue writes, engine offer for the wrong family and downloads");
        }finally{
            main(()->{try{Object window=field(importer,"dialog");if(window instanceof AlertDialog)((AlertDialog)window).getButton(AlertDialog.BUTTON_NEGATIVE).performClick();}catch(Exception e){throw new RuntimeException(e);}});
            dialog=null;
        }
    }
    private void deployBrowser(Context base,boolean hasProfiles) throws Exception {
        initializeActivity(base,hasProfiles);
        String host="192.0.2.41",id=(String)call(store,"newDeployment","swg","Browser fixture",new JSONObject().put("host",host).put("user","root").put("sshPort","22").put("sshPassword","fixture-only"),"");
        String operation=UUID.randomUUID().toString(),key=host+":22/swg/"+operation;
        JSONObject record=new JSONObject().put("operation_id",operation).put("recipe_id","swg").put("created_at",System.currentTimeMillis()).put("deployment_id",id).put("deployment_name","Browser fixture")
            .put("panel_url","https://"+host+":8443/panel/").put("username","fixture").put("password","fixture-only").put("certificate_sha256",String.join("",Collections.nCopies(64,"a"))).put("ready",true)
            .put("connections",new org.json.JSONArray().put("vless://00000000-0000-0000-0000-000000000001@192.0.2.41:1080#BrowserConnection"))
            .put("transport_template",new JSONObject().put("host",host).put("user","root").put("sshPort","22").put("panelPort","8443").put("domain","").put("options",new JSONObject().put("create",false)));
        update("deployResults",new JSONObject().put(key,record));
        JSONObject inventory=(JSONObject)call(store,"inventory");
        JSONObject server=inventory.getJSONObject("servers").getJSONObject(inventory.getJSONObject("servers").keys().next());
        JSONObject panel=inventory.getJSONObject("panels").getJSONObject(inventory.getJSONObject("panels").keys().next());
        String serverName=server.getString("name"),panelName=panel.getString("name");
        ui=cls("AndroidDeployUi").getConstructor(Activity.class,cls("AndroidDeployStore"),cls("AndroidDeployBackend"),Class.forName("kotlin.jvm.functions.Function1",true,loader)).newInstance(activity,store,backend,function("Function1",args->{throw new AssertionError("No import allowed in browser fixture");}));
        main(()->{try{dialog=new Dialog(activity);dialog.setContentView((View)call(ui,"buildPage"));dialog.show();}catch(Exception e){throw new RuntimeException(e);}});
        if(phase.equals("deploy-browser-capture")){
            capture(base,"deploy-home");
            main(()->{clickLocal("Серверы и панели");clickLocal(serverName);});capture(base,"deploy-server");
            main(()->clickLocal(panelName));capture(base,"deploy-panel");
            main(()->clickLocal("Подключения"));capture(base,"deploy-connections");
            main(()->{try{call(ui,"back");clickLocal("Управление");dialog.getWindow().getDecorView().findViewWithTag("serverManagement.addClient").performClick();}catch(Exception e){throw new RuntimeException(e);}});
            capture(base,"deploy-client");selectDialogRow("Отмена");return;
        }
        main(()->{for(View v:views(dialog.getWindow().getDecorView()))if(v instanceof EditText)throw new AssertionError("Home must not be an always-open editor");clickLocal("Серверы и панели");});
        check(true,"autodeploy home opens a separate server section without inline editors");
        main(()->{clickLocal(serverName);clickLocal(panelName);});
        check(dialog.getWindow().getDecorView().findViewWithTag("androidDeploy.form")==null,"server and panel destinations contain no deployment form");
        main(()->clickLocal("Подключения"));
        main(()->{for(View v:views(dialog.getWindow().getDecorView()))if(v instanceof TextView&&((TextView)v).getText().toString().contains("vless://"))throw new AssertionError("Full URI wall returned");});
        check(true,"saved connections render compact names and actions without plaintext URI wall");
        main(()->{try{if(!(Boolean)call(ui,"back"))throw new AssertionError("Missing panel back navigation");clickLocal("Управление");}catch(Exception e){throw new RuntimeException(e);}});
        main(()->{View root=dialog.getWindow().getDecorView();EditText password=root.findViewWithTag("serverManagement.password");if(password==null||!password.getText().toString().equals("fixture-only"))throw new AssertionError("Wrong server credentials");root.findViewWithTag("serverManagement.addClient").performClick();});
        check(node("Новый клиент").isVisibleToUser()&&node("Имя клиента").isVisibleToUser(),"new client opens a separate themed named-field dialog without sending a request");
        selectDialogRow("Отмена");
        main(()->{try{call(ui,"back");clickLocal("Добавить подключение");}catch(Exception e){throw new RuntimeException(e);}});
        View form=dialog.getWindow().getDecorView().findViewWithTag("androidDeploy.form");
        check(form!=null&&!(Boolean)call(call(backend,"getState"),"getRunning"),"existing panel adds a separate form without SSH or panel reinstall");
        Object browser=field(ui,"browserUi");
        main(()->{try{call(browser,"progress",call(backend,"getState"));}catch(Exception e){throw new RuntimeException(e);}});
        check(dialog.getWindow().getDecorView().findViewWithTag("androidDeploy.form")==form,"progress leaves the same editor instance attached without scroll-jump rebuild");
    }
    private void remaining(Context base,Context isolated,boolean hasProfiles) throws Exception {
        initializeActivity(base,hasProfiles);
        if(!phase.equals("interval-timer")) {
        main(()->{View edge=activity.getWindow().getDecorView().findViewWithTag("edge-rule-groups");if(!(edge instanceof Button)||((Button)edge).getText().length()!=0||edge.getLayoutParams().width>(int)(40*base.getResources().getDisplayMetrics().density))throw new AssertionError("Side handle must be narrow icon-only");});
        check(true,"new narrow routes icon has no Rules text; active-group accessibility label retained");
        // New inventory/management views only; do not replay the five accepted named-editor cases.
        for(int i=1;i<=2;i++){
            String host="192.0.2."+(10+i),name="Inventory "+i,id=(String)call(store,"newDeployment","swg",name,new JSONObject().put("host",host).put("user","root").put("sshPort","22").put("sshPassword","inventory-fixture-"+i),"");
            String operation=UUID.randomUUID().toString(),key=host+":22/swg/"+operation;
            JSONObject record=new JSONObject().put("operation_id",operation).put("recipe_id","swg").put("created_at",System.currentTimeMillis()).put("deployment_id",id).put("deployment_name",name)
                    .put("panel_url","https://"+host+":8443/panel/").put("username","fixture").put("password","panel-fixture").put("certificate_sha256",String.join("",Collections.nCopies(64,"a"))).put("ready",true).put("connections",new org.json.JSONArray())
                    .put("transport_template",new JSONObject().put("host",host).put("user","root").put("sshPort","22").put("panelPort","8443").put("domain","").put("options",new JSONObject().put("create",false)));
            update("deployResults",new JSONObject().put(key,record));
        }
        Object importer=function("Function1",args->{throw new AssertionError("No import in inventory fixture");});
        ui=cls("AndroidDeployUi").getConstructor(Activity.class,cls("AndroidDeployStore"),cls("AndroidDeployBackend"),Class.forName("kotlin.jvm.functions.Function1",true,loader)).newInstance(activity,store,backend,importer);
        main(()->{try{dialog=new Dialog(activity);dialog.setContentView((View)call(ui,"buildPage"));dialog.show();button("Серверы: 2 · Панели: 2").performClick();}catch(Exception e){throw new RuntimeException(e);}});
        check(((JSONObject)call(store,"inventory")).getJSONObject("servers").length()==2,"real server/panel overview renders both independent VPS records");
        for(int which=0;which<2;which++){
            final int index=which;
            main(()->{List<Button> buttons=new ArrayList<>();for(View v:views(dialog.getWindow().getDecorView()))if(v instanceof Button&&((Button)v).getText().toString().equals("Управление сервером"))buttons.add((Button)v);buttons.get(index).performClick();});
            main(()->{View root=dialog.getWindow().getDecorView();String endpoint="";for(View v:views(root))if(v instanceof TextView&&((TextView)v).getText().toString().startsWith("root@192.0.2."))endpoint=((TextView)v).getText().toString();EditText password=root.findViewWithTag("serverManagement.password");String expected=endpoint.contains("192.0.2.11:")?"inventory-fixture-1":endpoint.contains("192.0.2.12:")?"inventory-fixture-2":"missing";if(password==null||!expected.equals(password.getText().toString()))throw new AssertionError("Management credentials crossed instances");});
        }
        check(true,"both management buttons open credentials belonging to the displayed VPS only");
        main(()->{dialog.getWindow().getDecorView().findViewWithTag("androidDeploy.addTransport").performClick();});
        check(workspace().getJSONObject("drafts").length()==3&&!(Boolean)call(call(backend,"getState"),"getRunning"),"existing-panel add-transport opens independent editor without SSH or reinstall");
        main(()->{try{call(ui,"detach");dialog.dismiss();dialog=null;ui=null;}catch(Exception e){throw new RuntimeException(e);}});
        Object picker=cls("RouteServerPicker").getConstructor(cls("MainActivity"),JSONObject.class).newInstance(activity,new JSONObject());
        Button pickerView=(Button)call(picker,"getView");
        main(()->{dialog=new Dialog(activity);dialog.setContentView(pickerView);dialog.show();pickerView.performClick();});
        AccessibilityNodeInfo choose=node("Выбрать"),cancel=node("Отмена");android.graphics.Rect a=new android.graphics.Rect(),b=new android.graphics.Rect();choose.getBoundsInScreen(a);cancel.getBoundsInScreen(b);
        check(choose.isVisibleToUser()&&cancel.isVisibleToUser()&&a.bottom<=base.getResources().getDisplayMetrics().heightPixels&&b.bottom<=base.getResources().getDisplayMetrics().heightPixels,"large server list keeps Choose and Cancel visible inside screen");
        check(node("Текущий сервер подключения").isVisibleToUser()&&node("Выбрать группу подписки…").isVisibleToUser(),"reset and subscription-group actions remain reachable above scrolling server list");
        selectDialogRow("Отмена");main(()->dialog.dismiss());dialog=null;
        }
        final View[] intervalRow={null};
        main(()->{try{Method method=cls("MainActivity").getDeclaredMethod("autoBsChoice");method.setAccessible(true);intervalRow[0]=(View)method.invoke(activity);dialog=new Dialog(activity);dialog.setContentView(intervalRow[0]);dialog.show();}catch(Exception e){throw new RuntimeException(e);}});
        await(()->intervalRow[0].getWidth()>0&&intervalRow[0].isLaidOut(),5);
        main(()->intervalRow[0].findViewWithTag("auto-bs-interval").performClick());
        AccessibilityNodeInfo option=node("15 с");android.graphics.Rect optionBounds=new android.graphics.Rect();option.getBoundsInScreen(optionBounds);
        check(optionBounds.width()<base.getResources().getDisplayMetrics().widthPixels*.8,"interval choices use compact anchored popup instead of full-screen menu");
        selectDialogRow("15 с");
        main(()->{Button interval=intervalRow[0].findViewWithTag("auto-bs-interval");if(!interval.getText().toString().equals("15 с ▾"))throw new AssertionError("Interval did not update");});
        check(!base.getSharedPreferences("MainActivity",0).getBoolean("autoDetectBs",false),"restyled interval selection does not turn on auto-detection");
        main(()->dialog.dismiss());dialog=null;
        // Real Handler scheduling; null snapshot deliberately forbids all Internet/provider work.
        android.content.SharedPreferences prefs=isolated.getSharedPreferences("MainActivity",0);
        java.util.concurrent.atomic.AtomicInteger ticks=new java.util.concurrent.atomic.AtomicInteger();List<Long> times=Collections.synchronizedList(new ArrayList<>());
        Object snapshot=function("Function0",args->{ticks.incrementAndGet();times.add(SystemClock.elapsedRealtime());return null;});
        Object forbidden=function("Function1",args->{throw new AssertionError("No network/switch operation allowed in timer fixture");});
        final Object[] monitor={null};long began=SystemClock.elapsedRealtime();
        main(()->{try{prefs.edit().putBoolean("autoDetectBs",true).putInt("bsDetectionInterval",15).commit();monitor[0]=cls("BsDetectionMonitor").getConstructors()[0].newInstance(isolated,snapshot,forbidden,forbidden);}catch(Exception e){throw new RuntimeException(e);}});
        try{
            long deadline=began+19000;while(ticks.get()==0&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);
            check(ticks.get()==1&&times.get(0)-began>=14500&&times.get(0)-began<19000,"real Android detector timer fires at configured 15 seconds");
            long changed=SystemClock.elapsedRealtime();main(()->prefs.edit().putInt("bsDetectionInterval",30).commit());
            deadline=changed+34000;while(ticks.get()<2&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(100);
            check(ticks.get()==2&&times.get(1)-changed>=29500&&times.get(1)-changed<34000,"changing interval rearms same detector at 30 seconds without old 15-second tick");
            main(()->prefs.edit().putBoolean("autoDetectBs",false).commit());
            check(!((Handler)field(monitor[0],"main")).hasCallbacks((Runnable)field(monitor[0],"tick")),"disabled detector removes pending periodic callback");
        }finally{if(monitor[0]!=null)main(()->{try{call(monitor[0],"close");}catch(Exception e){throw new RuntimeException(e);}});}
    }
    private void lateUi(Context base,boolean hasProfiles,boolean resume)throws Exception {
        initializeActivity(base,hasProfiles);
        final ExecutorService[] editorWrites={null};
        if(resume)main(()->{try{
            JSONObject raw=new JSONObject().put("type","csqtt").put("server","fixture.invalid").put("server_port",56000).put("password","fixture-only").put("hashes",new org.json.JSONArray().put("fixture-call-0123456789abcdef")).put("workers",18);
            Object parsed=call(cls("SwgProfile").getField("INSTANCE").get(null),"fromJson","design-capture",raw);
            Object editor=cls("ConnectionEditor").getConstructor(cls("MainActivity"),parsed.getClass()).newInstance(activity,parsed);
            dialog=(Dialog)call(editor,"show");
            View option=views(dialog.getWindow().getDecorView()).stream().filter(v->v.getClass().getSimpleName().equals("CarrierOption")).findFirst().get();
            editorWrites[0]=(ExecutorService)field(editor,"worker");
        }catch(Exception error){throw new RuntimeException(error);}});
        if(!resume)main(()->{
            try {
                View root=activity.getWindow().getDecorView();
                JSONObject raw=new JSONObject().put("type","csqtt").put("server","fixture.invalid").put("server_port",56000).put("password","fixture-only").put("hashes",new org.json.JSONArray().put("fixture-call-0123456789abcdef")).put("workers",18);
                Object parsed=call(cls("SwgProfile").getField("INSTANCE").get(null),"fromJson","late-ui-fixture",raw);
                Object editor=cls("ConnectionEditor").getConstructor(cls("MainActivity"),parsed.getClass()).newInstance(activity,parsed);
                dialog=(Dialog)call(editor,"show");
                View form=dialog.getWindow().getDecorView(),address=form.findViewWithTag("carrier-address-fields");
                form.findViewWithTag("carrier-address").performClick();
                EditText override=form.findViewWithTag("carrier-workers-override");
                check(override==null&&!views(form).stream().anyMatch(v->v instanceof TextView&&((TextView)v).getText().toString().contains("Пусто — значение из VK TURN")),"server editor has no stream field or obsolete inheritance hint");
                check(!views(form).stream().anyMatch(v->v instanceof TextView&&((TextView)v).getText().toString().contains("смене вышек")),"travel warning is absent inside server editor");
                View option=views(form).stream().filter(v->v.getClass().getSimpleName().equals("CarrierOption")).findFirst().orElseThrow(()->new IllegalStateException("Carrier option missing"));
                check(((LinearLayout)option).getOrientation()==LinearLayout.HORIZONTAL&&((View)field(option,"select")).getBackground() instanceof android.graphics.drawable.RippleDrawable,"dropdown is one inline row with a borderless themed value");
                int previous=(Integer)call(option,"getSelectedItemPosition");((View)field(option,"select")).performClick();
                android.widget.PopupWindow choices=(android.widget.PopupWindow)field(option,"choicesDialog");
                long infoCount=views(choices.getContentView()).stream().filter(v->v.getContentDescription()!=null&&v.getContentDescription().toString().startsWith("Справка:")).count();
                check(infoCount==0&&(Integer)call(option,"getSelectedItemPosition")==previous,"dropdown values have no help icons; opening keeps current selection");
                editorWrites[0]=(ExecutorService)field(editor,"worker");
            }catch(Exception e){throw new RuntimeException(e);}
        });
        if(editorWrites[0]!=null){
            waitForIdleSync();SystemClock.sleep(500);
            if(resume){main(()->{try{
                View option=views(dialog.getWindow().getDecorView()).stream().filter(v->v.getClass().getSimpleName().equals("CarrierOption")).findFirst().get();
                ((View)field(option,"select")).performClick();
                if(phase.equals("style-final")){
                    View value=(View)field(option,"select");
                    android.graphics.drawable.RippleDrawable ripple=(android.graphics.drawable.RippleDrawable)value.getBackground();
                    check(ripple.getDrawable(0).getClass().getSimpleName().equals("DropdownValueDecoration"),"value uses a thin underline, not a filled capsule");
                    android.widget.PopupWindow popup=(android.widget.PopupWindow)field(option,"choicesDialog");
                    View content=popup.getContentView();
                    boolean noIcons=views(content).stream().noneMatch(v->v.getContentDescription()!=null&&v.getContentDescription().toString().startsWith("Справка:"));
                    boolean marked=views(content).stream().anyMatch(v->v instanceof TextView&&((TextView)v).getText().toString().contains("✓"));
                    check(noIcons&&marked&&((View)field(option,"info")).getContentDescription().toString().startsWith("Справка:"),"choices have no i, selected choice is marked and setting help remains");
                }
            }catch(Exception error){throw new RuntimeException(error);}});waitForIdleSync();SystemClock.sleep(250);}
            android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();
            if(shot==null)throw new Exception("Design screenshot unavailable");
            File file=new File(getTargetContext().getCacheDir(),"p2-dropdown-design.png");
            try(java.io.FileOutputStream output=new java.io.FileOutputStream(file)){if(!shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output))throw new Exception("Cannot save design screenshot");}finally{shot.recycle();}
            report.append("SCREENSHOT ").append(file.getAbsolutePath()).append('\n');
            main(()->{dialog.dismiss();dialog=null;});
            await(()->editorWrites[0].isShutdown()&&editorWrites[0].isTerminated(),10);
        }
        if(phase.equals("design-capture")||phase.equals("style-final"))return;
        android.content.ClipboardManager clipboard=base.getSystemService(android.content.ClipboardManager.class);
        final android.content.ClipData[] saved={null};
        main(()->saved[0]=clipboard.getPrimaryClip());
        try {
            main(()->{try{
                Object noSave=function("Function0",args->null);
                View slots=(View)cls("VkCallSlots").getConstructors()[0].newInstance(activity,Arrays.asList("old-first","keep-second"),6,noSave);
                dialog=new Dialog(activity);dialog.setContentView(slots);dialog.show();
                EditText first=(EditText)views(slots).stream().filter(v->v instanceof EditText).findFirst().orElseThrow(()->new IllegalStateException("First call slot missing"));
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("P2 owned fixture","new-first\nnew-second"));first.onTextContextMenuItem(android.R.id.paste);
                View delete=views(slots).stream().filter(v->v.getContentDescription()!=null&&v.getContentDescription().toString().equals("Удалить звонок 1")).findFirst().orElseThrow(()->new IllegalStateException("Delete action missing"));
                check(call(slots,"getValues").equals(Arrays.asList("new-first","new-second","keep-second"))&&((android.view.ViewGroup.MarginLayoutParams)delete.getLayoutParams()).getMarginStart()>=8*base.getResources().getDisplayMetrics().density,"actual paste expands calls, retains other slots and leaves an eight-dp delete gap");
                dialog.dismiss();dialog=null;
                View slider=(View)cls("StreamCountSlider").getConstructors()[0].newInstance(activity,Arrays.asList(9,18,27,36),18,function("Function1",args->null));
                slider.setEnabled(false);SeekBar bar=(SeekBar)views(slider).stream().filter(v->v instanceof SeekBar).findFirst().orElseThrow(()->new IllegalStateException("Stream bar missing"));
                boolean disabled=!bar.isEnabled();slider.setEnabled(true);call(slider,"setValue",27);
                check(disabled&&bar.isEnabled()&&bar.getProgress()==2,"global stream bar uses discrete marks and actually disables its touch control");
                Button retention=(Button)call(cls("LogRetentionUi").getField("INSTANCE").get(null),"button",activity);
                check("logs-retention".equals(retention.getTag())&&retention.getContentDescription().toString().contains("хранения"),"log retention selector is a labelled theme-aware control");
                Object panel=field(activity,"miniature");
                try {
                    call(panel,"updateConnection",true,true,false,true,false,true);
                    View panelView=(View)panel;Switch vpn=panelView.findViewWithTag("edge-vpn"),bs=panelView.findViewWithTag("edge-whitelists");
                    boolean correct=bs.isChecked()&&!vpn.isChecked();call(panel,"updateConnection",true,true,false,true,true,false);
                    check(correct&&vpn.isChecked()&&!bs.isChecked(),"actual sidebar indicators follow BS and VPN destination in both directions");
                }finally {call(panel,"updateConnection",false,false,true,true,false,null);}
            }catch(Exception e){throw new RuntimeException(e);}});
        }finally {main(()->{if(saved[0]==null)clipboard.clearPrimaryClip();else clipboard.setPrimaryClip(saved[0]);});}
    }
    private void capture(Context base,String name) throws Exception {
        waitForIdleSync();SystemClock.sleep(500); // Let dialog transitions reach a drawn frame.
        android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();if(shot==null)throw new Exception("Missing own screenshot");
        File file=new File(base.getCacheDir(),"p2-"+name+".png");
        try(java.io.FileOutputStream stream=new java.io.FileOutputStream(file)){if(!shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))throw new Exception("Cannot save screenshot");}finally{shot.recycle();}
        report.append("SCREENSHOT ").append(file.getAbsolutePath()).append('\n');
    }
    /** Only newly changed controls; do not replay the twelve accepted refresh cases. */
    private void batteryDesign(Context base,boolean hasProfiles) throws Exception {
        initializeActivity(base,hasProfiles);
        main(()->{try{call(activity,"navigate","Главная");}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        main(()->{try{
            View root=(View)field(activity,"page"),tolerance=root.findViewWithTag("auto-tolerance"),policy=root.findViewWithTag("auto-bs-selection-policy");
            check(tolerance instanceof Button&&tolerance.getParent()==policy.getParent()&&((Button)tolerance).getText().toString().contains("мс"),"deviation is a compact dropdown in the same auto-selection row");
            tolerance.performClick();
        }catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        node("75 мс").performAction(AccessibilityNodeInfo.ACTION_CLICK);waitForIdleSync();
        check(base.getSharedPreferences("MainActivity",0).getInt("tolerance",-1)==75,"deviation popup persists a step-fifteen choice");
        main(()->{try{((View)field(activity,"page")).findViewWithTag("auto-bs-selection-policy").performClick();}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        node("Выкл").performAction(AccessibilityNodeInfo.ACTION_CLICK);waitForIdleSync();
        check(base.getSharedPreferences("MainActivity",0).getString("bsDetectionSelection","").equals("off"),"power option switches automatic server choice off");
        main(()->{try{
            call(activity,"navigate","Белые списки");
            View root=(View)field(activity,"page");
            check(views(root).stream().noneMatch(v->v instanceof TextView&&Arrays.asList("БС","Нажмите, чтобы подключиться").contains(((TextView)v).getText().toString())),"BS page has neither redundant heading nor idle connect hint");
            Object noSave=function("Function0",args->null);
            View slots=(View)cls("VkCallSlots").getConstructors()[0].newInstance(activity,Arrays.asList("call-fixture"),6,noSave);
            dialog=new Dialog(activity);dialog.setContentView(slots);dialog.show();
        }catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();capture(base,"hash-style");
        main(()->{try{
            View slots=views(dialog.getWindow().getDecorView()).stream().filter(v->v.getClass().getSimpleName().equals("VkCallSlots")).findFirst().get();
            ViewGroup rows=(ViewGroup)field(slots,"rows"),row=(ViewGroup)rows.getChildAt(0);EditText hash=(EditText)row.getChildAt(0);View copy=row.getChildAt(1),remove=row.getChildAt(2);
            boolean sameClass=copy.getClass()==remove.getClass(),sameBackground=copy.getBackground().getClass()==remove.getBackground().getClass();
            boolean clear=hash.getBackground() instanceof android.graphics.drawable.GradientDrawable&&((android.graphics.drawable.GradientDrawable)hash.getBackground()).getColor().getDefaultColor()==android.graphics.Color.TRANSPARENT;
            report.append("HASH_STYLE class=").append(sameClass).append(" background=").append(sameBackground).append(" clear=").append(clear).append(" tint=").append(hash.getBackgroundTintList()).append('\n');
            check(sameClass&&sameBackground&&clear&&hash.getBackgroundTintList()==null,"copy/delete styling and untinted transparent hash field");
            dialog.dismiss();dialog=null;
            JSONObject raw=new JSONObject().put("type","csqtt").put("server","fixture.invalid").put("server_port",56000).put("password","fixture-only").put("hashes",new org.json.JSONArray().put(android.util.Base64.encodeToString(new byte[32],11))).put("workers",18);
            Object parsed=call(cls("SwgProfile").getField("INSTANCE").get(null),"fromJson","align-fixture",raw);
            dialog=(Dialog)call(cls("ConnectionEditor").getConstructor(cls("MainActivity"),parsed.getClass()).newInstance(activity,parsed),"show");
        }catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();capture(base,"option-alignment");
        main(()->{try{
            List<View> options=new ArrayList<>();for(View v:views(dialog.getWindow().getDecorView()))if(v.getClass().getSimpleName().equals("CarrierOption"))options.add(v);
            boolean aligned=options.size()>=2;Integer column=null;
            for(View v:options){View choice=(View)field(v,"select");int[] position=new int[2];choice.getLocationInWindow(position);if(column==null)column=position[0];else aligned&=Math.abs(column-position[0])<=1;aligned&=choice.getHeight()==v.getHeight()&&((Button)choice).getGravity()==Gravity.CENTER;}
            check(aligned,"all BS info rows share a centered selected-value column");dialog.dismiss();dialog=null;
            View settings=(View)call(cls("NotificationTrafficUi").getField("INSTANCE").get(null),"view",activity);
            Switch enabled=settings.findViewWithTag("notification-traffic-enabled");enabled.setChecked(false);
            boolean disabled=!base.getSharedPreferences("MainActivity",0).getBoolean("notificationTrafficEnabled",true);
            enabled.setChecked(true);
            View edge=(View)call(cls("EdgePanelAppearance").getField("INSTANCE").get(null),"view",activity);
            check(disabled&&settings.findViewWithTag("notificationTrafficForegroundSeconds")!=null&&settings.findViewWithTag("notificationTrafficBackgroundSeconds")!=null&&views(edge).stream().filter(v->v instanceof Switch).count()==5,"Appearance exposes notification intervals/disable and five sidebar visibility choices");
        }catch(Exception e){throw new RuntimeException(e);}});
    }
    private void notificationCadence(Context base,Context isolated,boolean hasProfiles) throws Exception {
        if(!allowNetworkScreen)throw new Exception("Explicit screen authority required");
        initializeActivity(base,hasProfiles);
        SharedPreferences prefs=isolated.getSharedPreferences("MainActivity",0);
        java.util.concurrent.atomic.AtomicInteger samples=new java.util.concurrent.atomic.AtomicInteger();
        ExecutorService executor=java.util.concurrent.Executors.newSingleThreadExecutor();Object[] sampler={null};
        Object presence=cls("TelemetryCadence").getField("INSTANCE").get(null);
        try{
            main(()->{try{sampler[0]=cls("NotificationTrafficSampler").getConstructors()[0].newInstance(isolated,new Handler(Looper.getMainLooper()),executor,function("Function0",a->true),function("Function0",a->{samples.incrementAndGet();return null;}),function("Function0",a->null));}catch(Exception e){throw new RuntimeException(e);}});
            await(()->samples.get()>=1,5);int first=samples.get();SystemClock.sleep(3500);
            check(samples.get()==first+1,"open app notification sampler uses default three-second cadence");
            main(()->{try{call(presence,"paused",activity);}catch(Exception e){throw new RuntimeException(e);}});
            int background=samples.get();SystemClock.sleep(3500);boolean quiet=samples.get()==background;await(()->samples.get()>background,9);
            check(quiet&&samples.get()==background+1,"background sampler uses ten seconds rather than foreground cadence");
            stage("SCREEN_OFF_WAIT");await(()->!base.getSystemService(PowerManager.class).isInteractive(),10);SystemClock.sleep(400);int off=samples.get();SystemClock.sleep(3500);
            check(samples.get()==off,"physical screen-off stops counter polling");
            stage("SCREEN_ON_WAIT");await(()->base.getSystemService(PowerManager.class).isInteractive(),10);await(()->samples.get()>off,2);
            check(samples.get()==off+1,"physical wake requests a fresh counter sample immediately");
            main(()->prefs.edit().putBoolean("notificationTrafficEnabled",false).apply());SystemClock.sleep(400);int disabled=samples.get();SystemClock.sleep(3500);
            check(samples.get()==disabled,"disabled notification counters do not poll");
        }finally{
            main(()->{try{if(sampler[0]!=null)call(sampler[0],"close");call(presence,"resumed",activity);}catch(Exception e){throw new RuntimeException(e);}});
            executor.shutdownNow();if(!executor.awaitTermination(5,TimeUnit.SECONDS))throw new Exception("Counter fixture worker still live");
        }
    }
    private void alignedCapture(Context base,boolean hasProfiles) throws Exception {
        initializeActivity(base,hasProfiles);
        main(()->{try{
            JSONObject raw=new JSONObject().put("type","csqtt").put("server","fixture.invalid").put("server_port",56000).put("password","fixture-only").put("hashes",new org.json.JSONArray().put(android.util.Base64.encodeToString(new byte[32],11))).put("workers",18);
            Object parsed=call(cls("SwgProfile").getField("INSTANCE").get(null),"fromJson","capture-fixture",raw);
            dialog=(Dialog)call(cls("ConnectionEditor").getConstructor(cls("MainActivity"),parsed.getClass()).newInstance(activity,parsed),"show");
        }catch(Exception e){throw new RuntimeException(e);}});
        capture(base,"aligned-editor");
    }
    /** Fresh follow-up only: redesigned hierarchy, folds, new deviation and RU-app latency. */
    private void deployCallControls(Context base,boolean hasProfiles) throws Exception {
        initializeActivity(base,hasProfiles);
        org.json.JSONArray calls=new org.json.JSONArray();for(int i=0;i<7;i++){byte[] bytes=new byte[32];bytes[0]=(byte)i;calls.put("https://vk.ru/call/join/"+android.util.Base64.encodeToString(bytes,11));}
        JSONObject options=new JSONObject().put("relay_kind","wdtt").put("call_count",33).put("vk_calls",calls);
        String id=(String)call(store,"newDeployment","swg","Expanded calls",new JSONObject().put("options",options),"");
        Object importer=function("Function1",a->{throw new AssertionError("No import/deploy in widget check");});
        ui=cls("AndroidDeployUi").getConstructor(Activity.class,cls("AndroidDeployStore"),cls("AndroidDeployBackend"),Class.forName("kotlin.jvm.functions.Function1",true,loader)).newInstance(activity,store,backend,importer);
        main(()->{try{dialog=new Dialog(activity);dialog.setContentView((View)call(ui,"deploy","swg"));dialog.show();}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        main(()->{try{
            View area=dialog.getWindow().getDecorView().findViewWithTag("androidDeploy.swg.wdtt.fields");
            Spinner count=(Spinner)views(area).stream().filter(v->v instanceof Spinner).findFirst().get();
            check("33 звонков".equals(count.getSelectedItem().toString())&&count.getAdapter().getItem(count.getAdapter().getCount()-1).toString().equals("50 звонков"),"deploy count retains33 and offers50 without six-call clamp");
            int index=-1;for(int i=0;i<count.getAdapter().getCount();i++)if("50 звонков".equals(count.getAdapter().getItem(i).toString()))index=i;count.setSelection(index);
        }catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        main(()->{try{
            JSONObject workspace=(JSONObject)call(store,"workspace"),values=workspace.getJSONObject("drafts").getJSONObject(id).getJSONObject("values");
            org.json.JSONArray profiles=values.getJSONObject("options").getJSONArray("swg_profiles");JSONObject wdtt=profiles.getJSONObject(1);
            check(wdtt.getInt("call_count")==50&&wdtt.getJSONArray("vk_calls").length()==7,"saved deploy draft keeps all manual calls independently of requested generation count");dialog.dismiss();dialog=null;
        }catch(Exception e){throw new RuntimeException(e);}});
    }
    private void dropdownRegressions(Context base,boolean hasProfiles) throws Exception {
        initializeActivity(base,hasProfiles);
        if(!phase.equals("dropdown-regressions-rest")){
        main(()->{try{call(activity,"navigate","Главная");}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        main(()->{try{
            LinearLayout row=activity.getWindow().getDecorView().findViewWithTag("auto-selection-control");
            TextView label=(TextView)row.getChildAt(1);
            check(label.getPaint().measureText(label.getText().toString())<=label.getWidth()-label.getPaddingLeft()-label.getPaddingRight()&&row.getChildAt(row.getChildCount()-1).getRight()<=row.getWidth(),"full Auto-selection label and all controls fit without clipping");
            Object page=cls("RoutingPage").getConstructor(cls("MainActivity"),cls("RoutingStore")).newInstance(activity,field(activity,"routing"));
            JSONObject rule=new JSONObject().put("id","owned-dropdown-regression").put("name","Owned route").put("text","example.org\n10.0.0.0/8").put("mode","addresses").put("target","proxy").put("enabled",true);
            dialog=(Dialog)call(page,"edit",rule);
        }catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        capture(base,"dropdown-route");
        main(()->{try{
            View root=dialog.getWindow().getDecorView();List<View> editors=new ArrayList<>();for(View v:views(root))if(v instanceof EditText)editors.add(v);
            EditText name=(EditText)editors.get(0),addresses=(EditText)editors.get(1);
            check((name.getGravity()&Gravity.VERTICAL_GRAVITY_MASK)==Gravity.CENTER_VERTICAL,"route name is vertically centered");
            TextView count=root.findViewWithTag("route-address-count");
            check(root.findViewWithTag("route-large-preview")==null&&addresses.getVisibility()==View.GONE&&"Записей: 2".contentEquals(count.getText()),"route has record count instead of address preview");
            root.findViewWithTag("route-expand-text").performClick();
            check("example.org\n10.0.0.0/8".contentEquals(addresses.getText())&&addresses.getVisibility()==View.VISIBLE,"explicit edit opens the complete unchanged address list");
            View form=root.findViewWithTag("route-editor-form");View target=views(form).stream().filter(v->v.getClass().getSimpleName().equals("CarrierOption")).reduce((a,b)->b).get();
            call(target,"setSelection",1);target.requestRectangleOnScreen(new android.graphics.Rect(0,0,target.getWidth(),target.getHeight()),true);
        }catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        for(String next:Arrays.asList("VPN / прокси","Блокировать","Напрямую")){
            main(()->{View form=dialog.getWindow().getDecorView().findViewWithTag("route-editor-form");LinearLayout target=(LinearLayout)views(form).stream().filter(v->v.getClass().getSimpleName().equals("CarrierOption")).reduce((a,b)->b).get();target.getChildAt(target.getChildCount()-1).performClick();});waitForIdleSync();
            if(next.equals("VPN / прокси"))capture(base,"dropdown-directions");
            AccessibilityNodeInfo option=node(next);android.graphics.Rect bounds=new android.graphics.Rect();option.getBoundsInScreen(bounds);
            if(bounds.isEmpty()||!option.isVisibleToUser())throw new AssertionError("Direction option not visible: "+next);
            option.performAction(AccessibilityNodeInfo.ACTION_CLICK);waitForIdleSync();
        }
        check(true,"Direct can switch visibly to VPN, block and back without closing route editor");
        }
        main(()->{try{
            if(dialog!=null)dialog.dismiss();dialog=null;
            Object editor=cls("DnsEditor").getConstructor(cls("MainActivity"),JSONObject.class).newInstance(activity,call(cls("DnsConfig").getField("INSTANCE").get(null),"defaults"));
            View body=(View)field(editor,"view");dialog=new AlertDialog.Builder(activity).setTitle("Owned DNS fixture").setView(body).setNegativeButton("Cancel",null).show();
        }catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        // Like a human tap, open only after the dialog owns a measured window token.
        main(()->{View body=dialog.getWindow().getDecorView();Spinner provider=(Spinner)views(body).stream().filter(v->v instanceof Spinner).findFirst().get();if(!provider.isAttachedToWindow()||provider.getWidth()==0)throw new AssertionError("DNS anchor not laid out");provider.performClick();});waitForIdleSync();
        capture(base,"dropdown-dns");
        main(()->{try{
            Spinner provider=(Spinner)views(dialog.getWindow().getDecorView()).stream().filter(v->v instanceof Spinner).findFirst().get();
            PopupWindow popup=(PopupWindow)field(provider,"choices");View content=popup.getContentView();android.graphics.Rect visible=new android.graphics.Rect();
            check(popup.isShowing()&&content.getGlobalVisibleRect(visible)&&!visible.isEmpty()&&views(content).stream().anyMatch(v->v instanceof TextView&&"Cloudflare".contentEquals(((TextView)v).getText()))&&views(content).stream().noneMatch(v->v instanceof TextView&&((TextView)v).getText().toString().contains("✓")),"DNS uses common popup without text checkmark");
        }catch(Exception e){throw new RuntimeException(e);}});
        // Dismiss only our popup; activity/dialog belong to this fixture.
        sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);waitForIdleSync();
        main(()->{try{
            dialog.dismiss();dialog=null;
            Object catalogue=field(activity,"catalogue");List<?> entries=(List<?>)call(catalogue,"entries");
            Object bs=entries.stream().filter(e->{try{return e.getClass().getField("carrier").get(e)!=null;}catch(Exception x){throw new RuntimeException(x);}}).findFirst().orElseThrow(()->new IllegalStateException("Existing BS entry required"));
            Object vpn=entries.stream().filter(e->{try{return e.getClass().getField("carrier").get(e)==null;}catch(Exception x){throw new RuntimeException(x);}}).findFirst().get();
            base.getSharedPreferences("MainActivity",0).edit().putString("bsLastManualBs",(String)bs.getClass().getField("id").get(bs)).commit();Field selectedField=activity.getClass().getDeclaredField("selected");selectedField.setAccessible(true);selectedField.set(activity,vpn.getClass().getField("id").get(vpn));
            Method method=Arrays.stream(activity.getClass().getDeclaredMethods()).filter(m->m.getName().equals("captchaChoice")).findFirst().get();method.setAccessible(true);
            View row=(View)method.invoke(activity,new Object[]{null});View choice=row.findViewWithTag("captcha-mode-switch");
            check(views(choice).stream().filter(v->v instanceof Button).allMatch(View::isEnabled),"CAPTCHA choice stays available while disconnected with VPN selected and remembered BS");
        }catch(Exception e){throw new RuntimeException(e);}});
    }
    private void latestRedesign(Context base,boolean hasProfiles) throws Exception {
        initializeActivity(base,hasProfiles);
        main(()->{try{
            View root=activity.getWindow().getDecorView();Button bs=root.findViewWithTag("nav-Белые списки");
            check(bs!=null&&"БС".contentEquals(bs.getText()),"BS section has a short visible label and stable navigation identity");
            call(activity,"navigate","Главная");
        }catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        main(()->{try{
            View root=(View)field(activity,"page");LinearLayout row=root.findViewWithTag("auto-selection-control");
            boolean aligned=row.getGravity()==Gravity.CENTER;for(int i=0;i<row.getChildCount();i++){View child=row.getChildAt(i);aligned&=Math.abs(child.getTop()+child.getHeight()/2-row.getHeight()/2)<=1;}
            Button help=root.findViewWithTag("auto-bs-selection-help");
            check(aligned&&help.getText().length()==0,"entire automatic-selection row is centered and help uses a vector icon");
            String explanation=(String)cls("BsAutoSelectionPolicy").getField("HELP").get(null);
            check(explanation.contains("\n\n")&&explanation.contains("потоковому")&&explanation.contains("15–150"),"help has logical paragraphs, range and streaming disruption warning");
            root.findViewWithTag("auto-tolerance").performClick();
        }catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        node("При недоступности").performAction(AccessibilityNodeInfo.ACTION_CLICK);waitForIdleSync();
        check(base.getSharedPreferences("MainActivity",0).getBoolean("autoSelectionFailureOnly",false),"first deviation choice enables true failure-only policy");
        main(()->{try{
            Object slots=cls("VkCallSlots").getConstructors()[0].newInstance(activity,Arrays.asList("fixture"),6,function("Function0",a->null));
            View rows=(View)field(slots,"rows"),fold=((View)slots).findViewWithTag("manual-hashes-fold");boolean hidden=rows.getVisibility()==View.GONE;fold.performClick();
            check(hidden&&rows.getVisibility()==View.VISIBLE,"shared manual hashes start collapsed and expand without losing values");
            call(activity,"navigate","Оформление");
        }catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        main(()->{try{
            View root=(View)field(activity,"page");View item=root.findViewWithTag("settings-page-Минимализм");
            check(item!=null&&views(item).stream().anyMatch(v->v instanceof TextView&&"Скрыть разделы".contentEquals(((TextView)v).getText()))&&views(item).stream().noneMatch(v->v instanceof TextView&&((TextView)v).getText().toString().contains("Выбор функций")),"Appearance rename has no old Minimalism subtitle");
            Object routing=field(activity,"routing");Object page=cls("RoutingPage").getConstructor(cls("MainActivity"),cls("RoutingStore")).newInstance(activity,routing);
            JSONObject rule=new JSONObject().put("id","owned-redesign").put("name","Owned redesign").put("text","example.org").put("mode","addresses").put("target","proxy").put("enabled",true);
            dialog=(Dialog)call(page,"edit",rule);
        }catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();capture(base,"route-redesign");
        main(()->{try{
            View root=dialog.getWindow().getDecorView();View form=root.findViewWithTag("route-editor-form"),advanced=root.findViewWithTag("route-editor-advanced");
            check(form!=null&&advanced.getVisibility()==View.GONE&&root.findViewWithTag("route-server-picker")!=null,"route editor uses grouped form, collapsed advanced controls and retained bottom server choice");dialog.dismiss();dialog=null;
            call(activity,"navigate","Маршрутизация");
        }catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();await(()->activity.getWindow().getDecorView().findViewWithTag("routing-quick-actions")!=null,10);capture(base,"routes-redesign");
        main(()->{try{
            check(activity.getWindow().getDecorView().findViewWithTag("routing-quick-actions")!=null,"routes page groups preset and RU-app quick actions");
            JSONObject raw=new JSONObject().put("type","csqtt").put("server","fixture.invalid").put("server_port",56000).put("password","fixture-only").put("hashes",new org.json.JSONArray().put(android.util.Base64.encodeToString(new byte[32],11))).put("workers",18);
            Object parsed=call(cls("SwgProfile").getField("INSTANCE").get(null),"fromJson","redesign-fixture",raw);
            dialog=(Dialog)call(cls("ConnectionEditor").getConstructor(cls("MainActivity"),parsed.getClass()).newInstance(activity,parsed),"show");
        }catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();capture(base,"server-redesign");
        main(()->{try{
            View root=dialog.getWindow().getDecorView(),form=root.findViewWithTag("connection-editor-form");View slots=views(root).stream().filter(v->v.getClass().getSimpleName().equals("VkCallSlots")).findFirst().get();
            check(form!=null&&form.getBackground()==null&&((View)field(slots,"rows")).getVisibility()==View.GONE,"server editor has one surface and collapsed hashes");dialog.dismiss();dialog=null;
            call(activity,"navigate","Аккаунт и звонки VK");
        }catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();capture(base,"vk-redesign");
        main(()->{try{
            View root=(View)field(activity,"page"),account=root.findViewWithTag("vk-turn-account-calls");View slots=views(account).stream().filter(v->v.getClass().getSimpleName().equals("VkCallSlots")).findFirst().get();
            check(((View)field(slots,"rows")).getVisibility()==View.GONE&&root.findViewWithTag("vk-turn-transport-settings")!=null,"Vk Turn redesign retains account, global streams and collapsed shared hashes");
        }catch(Exception e){throw new RuntimeException(e);}});
        java.util.concurrent.atomic.AtomicLong loaded=new java.util.concurrent.atomic.AtomicLong();long opened=SystemClock.uptimeMillis();
        main(()->{try{
            Object picker=cls("RoutingDefaultsRussianAppsUi").getField("INSTANCE").get(null);
            call(picker,"show",activity,field(activity,"routing"),function("Function1",a->{loaded.set(SystemClock.uptimeMillis());return null;}));
            dialog=(Dialog)((java.lang.ref.WeakReference<?>)field(picker,"current")).get();
            check(dialog!=null&&dialog.isShowing()&&SystemClock.uptimeMillis()-opened<500,"RU-app shows its cancellable window immediately");
        }catch(Exception e){throw new RuntimeException(e);}});
        await(()->loaded.get()>0,10);report.append("RU_APP_READY_MS ").append(loaded.get()-opened).append('\n');
        main(()->{dialog.dismiss();dialog=null;});
    }
    private String digest(File file) throws Exception {
        if(Files.isSymbolicLink(file.toPath()))throw new Exception("Linked recovery target");
        byte[] hash=java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath()));StringBuilder value=new StringBuilder();for(byte b:hash)value.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return value.toString();
    }
    /** Exact failed-budget receipt recovery only; not a reusable rollback grant. */
    private void budgetRecovery(Context base) throws Exception {
        Object service=cls("McrfVpnService").getDeclaredField("currentService");((Field)service).setAccessible(true);
        if(((Field)service).get(null)!=null)throw new Exception("Live service; recovery refused");
        File root=base.getFilesDir(),pointer=new File(root,"active-profile.txt"),xml=new File(base.getApplicationInfo().dataDir,"shared_prefs/MainActivity.xml");
        File recovery=new File(base.getCacheDir(),"p2-recovery-a3c97102-edc8-487f-a009-46184d114609");
        String generation="mcrf-store-7cf75074-6da7-4e22-b06d-f115148cf4ae";
        JSONObject record=new JSONObject(new String(Files.readAllBytes(new File(recovery,"state.json").toPath()),java.nio.charset.StandardCharsets.UTF_8));
        if(record.getBoolean("pointerPresent")||!generation.equals(record.getString("temporaryGeneration"))||!generation.equals(new String(Files.readAllBytes(pointer.toPath()),java.nio.charset.StandardCharsets.US_ASCII)))throw new Exception("Wrong failed-budget generation");
        String[] names={"connections.enc","preferences.enc","profile.enc","routing.enc"};
        String[] hashes={"5ee97d12daa797ccafbe982a401c4965835752e726914dd0b165f8a3c287b707","058a48d7454828baa8857daa0c50f21f7508c5a73279c1a2a72718fa2b903abe","6575b8b2502fad9f302ae0cad1dd38586ef8a311e1933135588d8496c06fd3f7","d526418102f08910e25292d7be0036dbdc2443565b8bdf9ba620be0105e8130d"};
        for(int i=0;i<names.length;i++)if(!hashes[i].equals(digest(new File(root,names[i]))))throw new Exception("Original encrypted store changed; no rollback");
        File saved=new File(recovery,"prefs.xml");String original="c8df54064a51e3c3726daecf1c5e34d7f7c99b152db2297318213d2a890f9bae";
        if(!original.equals(digest(saved))||!"3e81e7be76c654e5a57e39184b027c416d640fa26aa4822ba9e4eaa77b5ff1a9".equals(digest(xml))||!generation.equals(base.getSharedPreferences("MainActivity",0).getString("restoreAppliedGeneration","")))throw new Exception("Recovery baseline/live preference guard changed");
        android.util.AtomicFile atomic=new android.util.AtomicFile(xml);java.io.FileOutputStream stream=atomic.startWrite();try{stream.write(Files.readAllBytes(saved.toPath()));atomic.finishWrite(stream);}catch(Throwable e){atomic.failWrite(stream);throw e;}
        if(!original.equals(digest(xml)))throw new Exception("Restored preference hash mismatch");
        if(Files.isSymbolicLink(pointer.toPath())||!pointer.delete())throw new Exception("Cannot retire exact temporary pointer");
        File folder=new File(root,generation);for(File f:Objects.requireNonNull(folder.listFiles()))if(Files.isSymbolicLink(f.toPath())||!Arrays.asList(names).contains(f.getName()))throw new Exception("Unexpected test-generation child; preserve");
        removeOwned(root,folder);removeOwned(base.getCacheDir(),recovery);
        report.append("RECOVERY exact original encrypted stores and preference bytes restored; only owned temporary generation/cache removed\n");
    }
    private void refreshDesign(Context base,boolean hasProfiles) throws Exception {
        initializeActivity(base,hasProfiles);
        for(String page:Arrays.asList("Главная","Белые списки","Логи","Маршрутизация","Оформление")){
            if(phase.equals("refresh-design-rest")&&(page.equals("Главная")||page.equals("Белые списки")))continue;
            main(()->{try{call(activity,"navigate",page);}catch(Exception e){throw new RuntimeException(e);}});
            waitForIdleSync();
            main(()->{try{
                Object before=field(activity,"page");ScrollView scroll=(ScrollView)field(activity,"activeScroll");if(scroll!=null)scroll.scrollTo(0,120);int position=scroll==null?0:scroll.getScrollY();
                call(activity,"navigate",page.equals("Главная")?"VPN":page);
                check(before==field(activity,"page")&&scroll==field(activity,"activeScroll")&&(scroll==null||scroll.getScrollY()==position),"same-tab no redraw: "+page);
            }catch(Exception e){throw new RuntimeException(e);}});
        }
        final ScrollView[] oldScroll={null};final int[] oldY={0};final java.util.concurrent.atomic.AtomicInteger firstY=new java.util.concurrent.atomic.AtomicInteger(-1);
        final ViewTreeObserver.OnDrawListener[] observer={null};final View[] shell={null};
        main(()->{try{
            Object before=field(activity,"page");
            activity.getWindow().getDecorView().findViewWithTag("theme-colors-save").performClick();
            check(before==field(activity,"page")&&!(Boolean)call(activity,"working"),"unchanged appearance Apply keeps the current hierarchy");
            oldScroll[0]=(ScrollView)field(activity,"activeScroll");oldScroll[0].scrollTo(0,400);oldY[0]=oldScroll[0].getScrollY();
            if(oldY[0]<=0)throw new Exception("Appearance must be scrollable for first-frame test");
            View color=activity.getWindow().getDecorView().findViewWithTag("themeDarkPrimary");
            call(color,"setValue",call(color,"getValue").equals("#527EA1")?"#5280A2":"#527EA1");
            shell[0]=(View)field(activity,"shell");
            observer[0]=()->{try{ScrollView current=(ScrollView)field(activity,"activeScroll");if(current!=oldScroll[0])firstY.compareAndSet(-1,current.getScrollY());}catch(Exception e){throw new RuntimeException(e);}};
            shell[0].getViewTreeObserver().addOnDrawListener(observer[0]);
            activity.getWindow().getDecorView().findViewWithTag("theme-colors-save").performClick();
        }catch(Exception e){throw new RuntimeException(e);}});
        try{await(()->firstY.get()>=0,8);check(firstY.get()==oldY[0],"changed appearance restores scroll before first drawn frame");}
        finally{main(()->shell[0].getViewTreeObserver().removeOnDrawListener(observer[0]));}
        main(()->{try{call(activity,"navigate","Главная");}catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        main(()->{try{
            View root=(View)field(activity,"page");
            check(root.findViewWithTag("unified-auto-selection")!=null&&root.findViewWithTag("auto-options")==null&&views(root).stream().noneMatch(v->v instanceof TextView&&"Проверка сети".contentEquals(((TextView)v).getText())),"one unified auto-selection block replaces duplicate network/auto controls");
            SharedPreferences prefs=base.getSharedPreferences("MainActivity",0);int interval=prefs.getInt("bsDetectionInterval",60);boolean enabled=prefs.getBoolean("autoDetectBs",false);
            ((EditText)root.findViewWithTag("auto-tolerance")).setText("75");
            check(prefs.getInt("tolerance",-1)==75&&prefs.getInt("bsDetectionInterval",60)==interval&&prefs.getBoolean("autoDetectBs",false)==enabled,"unified deviation persists without changing detector state or interval");
            JSONObject raw=new JSONObject().put("type","csqtt").put("server","fixture.invalid").put("server_port",56000).put("password","fixture-only").put("hashes",new org.json.JSONArray().put(android.util.Base64.encodeToString(new byte[32],android.util.Base64.URL_SAFE|android.util.Base64.NO_WRAP|android.util.Base64.NO_PADDING))).put("workers",18);
            Object parsed=call(cls("SwgProfile").getField("INSTANCE").get(null),"fromJson","design-fixture",raw);
            Object editor=cls("ConnectionEditor").getConstructor(cls("MainActivity"),parsed.getClass()).newInstance(activity,parsed);
            dialog=(Dialog)call(editor,"show");
        }catch(Exception e){throw new RuntimeException(e);}});waitForIdleSync();
        main(()->{try{
            View root=dialog.getWindow().getDecorView();
            View option=views(root).stream().filter(v->v.getClass().getSimpleName().equals("CarrierOption")).findFirst().get();
            LinearLayout optionRow=(LinearLayout)option;View label=(View)field(option,"label"),select=(View)field(option,"select");
            check(optionRow.getChildAt(0)==field(option,"info")&&optionRow.getChildAt(1)==label&&select.getWidth()>0&&select.getLeft()>=label.getRight(),"BS setting help precedes the label and selected value remains visible");
            ViewGroup form=(ViewGroup)option.getParent();View first=form.getChildAt(0);
            check(first instanceof ViewGroup&&((ViewGroup)first).getChildAt(0) instanceof TextView&&"Название".contentEquals(((TextView)((ViewGroup)first).getChildAt(0)).getText()),"server editor begins at name without the accent protocol header");
            View slots=views(root).stream().filter(v->v.getClass().getSimpleName().equals("VkCallSlots")).findFirst().get();
            View counter=(View)field(slots,"counter"),rows=(View)field(slots,"rows");View add=((ViewGroup)slots).getChildAt(((ViewGroup)slots).getChildCount()-1);
            check(((ViewGroup.MarginLayoutParams)counter.getLayoutParams()).bottomMargin>0&&((ViewGroup.MarginLayoutParams)add.getLayoutParams()).topMargin>0,"shared server and VK TURN slots have explicit counter and Add gaps");
            ViewGroup hashRow=(ViewGroup)((ViewGroup)rows).getChildAt(0);EditText hash=(EditText)hashRow.getChildAt(0);View copy=hashRow.getChildAt(1),remove=hashRow.getChildAt(2);
            check(copy.getClass()==remove.getClass()&&copy.getBackground().getClass()==remove.getBackground().getClass()&&((android.graphics.drawable.GradientDrawable)hash.getBackground()).getColor().getDefaultColor()==android.graphics.Color.TRANSPARENT,"copy and delete share icon styling; hash input has no contrasting fill");
        }catch(Exception e){throw new RuntimeException(e);}});
        android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();if(shot==null)throw new Exception("Missing owned design capture");
        File file=new File(base.getCacheDir(),"p2-refresh-editor.png");
        try(java.io.FileOutputStream output=new java.io.FileOutputStream(file)){if(!shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output))throw new Exception("Cannot save design capture");}finally{shot.recycle();}
        report.append("SCREENSHOT ").append(file.getAbsolutePath()).append('\n');
        main(()->{dialog.dismiss();dialog=null;});
    }
    private void hashUi(Context base,boolean hasProfiles) throws Exception {
        initializeActivity(base,hasProfiles);
        android.content.ClipboardManager clipboard=base.getSystemService(android.content.ClipboardManager.class);
        final android.content.ClipData[] original={null};final Object[] slots={null};
        final List<String> incoming=new ArrayList<>();for(int i=1;i<=9;i++)incoming.add(String.join("",Collections.nCopies(42,"A"))+i);
        main(()->original[0]=clipboard.getPrimaryClip());
        try {
            main(()->{try{
                if(!phase.endsWith("-rest")){
                slots[0]=cls("VkCallSlots").getConstructors()[0].newInstance(activity,Arrays.asList("old-first","keep-second"),6,function("Function0",args->null));
                dialog=new Dialog(activity);dialog.setContentView((View)slots[0]);dialog.show();
                EditText first=(EditText)views((View)slots[0]).stream().filter(v->v instanceof EditText).findFirst().orElseThrow(IllegalStateException::new);
                String payload=String.join("\n",incoming)+"\nhttps://vk.com/call/join/"+incoming.get(0);
                clipboard.setPrimaryClip(android.content.ClipData.newPlainText("Owned call fixture",payload));first.onTextContextMenuItem(android.R.id.paste);
                List<?> values=(List<?>)call(slots[0],"getValues");
                check(values.size()==10&&values.get(0).equals(incoming.get(0))&&values.get(9).equals("keep-second"),"actual clipboard paste accepts nine calls, dedupes URL/hash identity and retains other manual slots");
                check(views((View)slots[0]).stream().anyMatch(v->v instanceof TextView&&((TextView)v).getText().toString().equals("Ручных хэшей: 10")),"manual counter reflects unique calls");
                View copy=views((View)slots[0]).stream().filter(v->v.getContentDescription()!=null&&"Скопировать звонок 1".contentEquals(v.getContentDescription())).findFirst().orElseThrow(IllegalStateException::new);
                copy.performClick();
                check(clipboard.getPrimaryClip()!=null&&incoming.get(0).contentEquals(clipboard.getPrimaryClip().getItemAt(0).coerceToText(activity)),"server call copy action copies the full value");
                call(slots[0],"append",Arrays.asList(incoming.get(0),"append-last"));values=(List<?>)call(slots[0],"getValues");
                check(values.size()==11&&values.get(10).equals("append-last")&&values.get(9).equals("keep-second"),"generated-list append keeps existing order and removes duplicates");
                View copyView=copy;
                check(copyView.getLayoutParams().width>=48*base.getResources().getDisplayMetrics().density&&((ViewGroup.MarginLayoutParams)copyView.getLayoutParams()).getMarginStart()>0,"copy action retains its touch target and field gap");
                dialog.dismiss();dialog=null;
                }
                SharedPreferences prefs=base.getSharedPreferences("MainActivity",0);prefs.edit().putBoolean("autoDetectBs",false).putInt("bsDetectionInterval",45).putString("bsDetectionSelection","fastest").commit();
                Method factory=activity.getClass().getDeclaredMethod("autoBsChoice");factory.setAccessible(true);
                View control=(View)factory.invoke(activity);dialog=new Dialog(activity);dialog.setContentView(control);dialog.show();
                View policy=control.findViewWithTag("auto-bs-selection-policy"),help=control.findViewWithTag("auto-bs-selection-help");
                if(!phase.endsWith("-rest"))check(policy instanceof Button&&help!=null&&((Button)policy).getCompoundDrawablesRelative()[0]!=null,"detector selection has a monochrome policy icon and separate help action");
            }catch(Exception error){throw new RuntimeException(error);}});
            waitForIdleSync();
            if(!phase.equals("hash-help-rest")){
            await(()->{final boolean[] ready={false};main(()->{View policy=dialog.getWindow().getDecorView().findViewWithTag("auto-bs-selection-policy");ready[0]=policy.isLaidOut()&&policy.getWidth()>0&&policy.getHeight()>0;});return ready[0];},5);
            main(()->dialog.getWindow().getDecorView().findViewWithTag("auto-bs-selection-policy").performClick());
            selectDialogRow("Последний выбранный");waitForIdleSync();
            SharedPreferences prefs=base.getSharedPreferences("MainActivity",0);
            check(prefs.getString("bsDetectionSelection","").equals("recent")&&!prefs.getBoolean("autoDetectBs",true)&&prefs.getInt("bsDetectionInterval",0)==45,"selecting recent policy preserves disabled detector and interval");
            }
            main(()->dialog.getWindow().getDecorView().findViewWithTag("auto-bs-selection-help").performClick());waitForIdleSync();
            await(()->{AccessibilityNodeInfo root=getUiAutomation().getRootInActiveWindow();return root!=null&&!root.findAccessibilityNodeInfosByText("Интервал задаёт").isEmpty();},5);
            check(true,"policy help explains icons, protocol priority and both switch directions");
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        }finally {
            main(()->{if(dialog!=null){dialog.dismiss();dialog=null;}if(original[0]==null)clipboard.clearPrimaryClip();else clipboard.setPrimaryClip(original[0]);});
        }
    }
    private void routePicker(Context base,boolean hasProfiles) throws Exception {
        initializeActivity(base,hasProfiles);
        final Object[] picker={null};final Button[] value={null};final Dialog[] choices={null};
        main(()->{try{
            picker[0]=cls("RouteServerPicker").getConstructor(cls("MainActivity"),JSONObject.class).newInstance(activity,new JSONObject().put("target","proxy"));
            value[0]=(Button)call(picker[0],"getView");dialog=new Dialog(activity);dialog.setContentView(value[0]);dialog.show();
            float density=base.getResources().getDisplayMetrics().density;
            check(value[0].getPaddingStart()>=16*density&&value[0].getPaddingTop()>=14*density,"route current-server control has internal text padding");
            value[0].performClick();
        }catch(Exception e){throw new RuntimeException(e);}});
        await(()->{main(()->{try{choices[0]=(Dialog)field(picker[0],"choicesDialog");}catch(Exception e){throw new RuntimeException(e);}});return choices[0]!=null&&choices[0].isShowing();},15);
        main(()->{try{
            View root=choices[0].getWindow().getDecorView();ListView list=root.findViewWithTag("route-server-options");
            check(root.findViewWithTag("route-fastest-vpn")!=null&&root.findViewWithTag("route-fastest-bs")!=null,"route picker exposes fastest VPN and fastest BS shortcuts together");
            for(int i=0;i<list.getAdapter().getCount();i++){
                CheckedTextView row=(CheckedTextView)list.getAdapter().getView(i,null,list);
                if(row.getCompoundDrawablesRelative()[0]==null||row.getCompoundDrawablePadding()==0||row.getPaddingTop()==0)throw new IllegalStateException("Missing route protocol icon or row spacing");
            }
            check(list.getAdapter().getCount()>0,"every server option has a leading protocol icon and spaced text");
            root.findViewWithTag("route-fastest-vpn").performClick();
            ((android.app.AlertDialog)choices[0]).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            JSONObject rule=new JSONObject().put("target","proxy");call(picker[0],"write",rule);
            Object catalogue=cls("Connections").getConstructor(Context.class).newInstance(base);call(catalogue,"load");List<?> entries=(List<?>)call(catalogue,"entries");
            Object companion=cls("RouteServerPlan").getField("Companion").get(null);
            List<?> expected=(List<?>)call(companion,"fastestEntries",entries,false);
            List<?> ids=(List<?>)call(companion,"ids",rule);
            Set<String> expectedIds=new HashSet<>();for(Object e:expected)expectedIds.add((String)e.getClass().getField("id").get(e));
            check(!expectedIds.isEmpty()&&expectedIds.equals(new HashSet<>(ids)),"fastest VPN shortcut writes only the current VPN pool into the unsaved rule");
            value[0].performClick();
        }catch(Exception e){throw new RuntimeException(e);}});
        await(()->{main(()->{try{choices[0]=(Dialog)field(picker[0],"choicesDialog");}catch(Exception e){throw new RuntimeException(e);}});return choices[0]!=null&&choices[0].isShowing();},15);
        main(()->{try{
            View root=choices[0].getWindow().getDecorView();root.findViewWithTag("route-fastest-bs").performClick();
            ((android.app.AlertDialog)choices[0]).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick();
            JSONObject rule=new JSONObject().put("target","proxy");call(picker[0],"write",rule);
            Object catalogue=cls("Connections").getConstructor(Context.class).newInstance(base);call(catalogue,"load");
            Object companion=cls("RouteServerPlan").getField("Companion").get(null);
            List<?> expected=(List<?>)call(companion,"fastestEntries",call(catalogue,"entries"),true);
            Set<String> expectedIds=new HashSet<>();for(Object e:expected)expectedIds.add((String)e.getClass().getField("id").get(e));
            check(!expectedIds.isEmpty()&&expectedIds.equals(new HashSet<>((List<?>)call(companion,"ids",rule))),"fastest BS shortcut writes only the current BS pool into the unsaved rule");
            check(!rule.has("serverId")&&rule.getJSONArray("serverIds").length()>1,"fastest shortcut preserves the multi-server URL-test route contract");
        }catch(Exception e){throw new RuntimeException(e);}});
    }
    private void stage(String name) { Bundle status=new Bundle();status.putString("stage",name);sendStatus(1,status); }
    private void menus(Context base,boolean hasProfiles)throws Exception {
        initializeActivity(base,hasProfiles);
        for(String name:new String[]{"groups","logs"}){
            main(()->{try{
                View control;
                if(name.equals("groups"))control=(View)call(cls("RoutingGroupsUi").getField("INSTANCE").get(null),"row",activity,call(field(activity,"routing"),"snapshot"));
                else control=(View)call(cls("LogRetentionUi").getField("INSTANCE").get(null),"button",activity);
                dialog=new Dialog(activity);dialog.setContentView(control);dialog.show();
            }catch(Exception error){throw new RuntimeException(error);}});
            waitForIdleSync();SystemClock.sleep(400);
            main(()->{
                View root=dialog.getWindow().getDecorView();
                root.findViewWithTag(name.equals("groups")?"routing-group-picker":"logs-retention").performClick();
            });
            waitForIdleSync();SystemClock.sleep(250);
            android.graphics.Bitmap shot=getUiAutomation().takeScreenshot();if(shot==null)throw new Exception("Menu screenshot unavailable");
            File file=new File(getTargetContext().getCacheDir(),"p2-"+name+"-menu.png");
            try(java.io.FileOutputStream stream=new java.io.FileOutputStream(file)){if(!shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))throw new Exception("Menu screenshot save failed");}finally{shot.recycle();}
            report.append("SCREENSHOT ").append(file.getAbsolutePath()).append('\n');
            sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);main(()->{dialog.dismiss();dialog=null;});
        }
    }
    private void await(java.util.concurrent.Callable<Boolean> condition,int seconds) throws Exception {
        long deadline=SystemClock.elapsedRealtime()+seconds*1000L;
        while(!condition.call()&&SystemClock.elapsedRealtime()<deadline)SystemClock.sleep(80);
        if(!condition.call())throw new Exception("Environment transition timed out");
    }
    private void environment(Context base,Context isolated) throws Exception {
        if(!allowNetworkScreen)throw new Exception("Explicit network/screen authority required");
        final Object[] wifi={null},lease={null};
        Object settings=cls("BsWifiSettings").getConstructor(boolean.class,boolean.class,List.class).newInstance(true,false,Collections.emptyList());
        Object changed=function("Function0",args->null);
        try {
            main(()->{try{wifi[0]=cls("BsWifiMonitor").getConstructors()[0].newInstance(isolated,settings,changed);call(wifi[0],"start");}catch(Exception e){throw new RuntimeException(e);}});
            stage("WIFI_OFF_WAIT");
            await(()->!(Boolean)call(call(wifi[0],"getSnapshot"),"getWifi"),18);
            Object off=call(wifi[0],"getSnapshot");
            check(!(Boolean)call(settings,"shouldPause",false,call(off,"getSsids")),"real Wi-Fi loss clears physical pause decision without reading location");
            stage("WIFI_ON_WAIT");
            await(()->(Boolean)call(call(wifi[0],"getSnapshot"),"getWifi"),25);
            Object on=call(wifi[0],"getSnapshot");
            check((Boolean)call(settings,"shouldPause",true,call(on,"getSsids")),"physical Wi-Fi callback returns opt-in BS pause state");
            main(()->{try{call(wifi[0],"close");}catch(Exception e){throw new RuntimeException(e);}});
            check(!(Boolean)field(wifi[0],"registered")&&!(Boolean)field(wifi[0],"permissionsRegistered")&&!(Boolean)field(wifi[0],"receiverRegistered"),"Wi-Fi monitor retires its network/permission/location registrations");
            Object powerSettings=cls("BsScreenOffSettings").getConstructor(String.class).newInstance("stable");
            Object alive=function("Function0",args->true);
            main(()->{try{lease[0]=cls("BsScreenOffLease").getConstructors()[0].newInstance(isolated,powerSettings,alive);call(lease[0],"start");}catch(Exception e){throw new RuntimeException(e);}});
            android.os.PowerManager power=(android.os.PowerManager)base.getSystemService(Context.POWER_SERVICE);
            await(()->power.isInteractive(),5);
            check(!((android.os.PowerManager.WakeLock)field(lease[0],"lock")).isHeld(),"stable BS lease does not hold CPU while screen is on");
            stage("SCREEN_OFF_WAIT");
            await(()->!power.isInteractive()&&((android.os.PowerManager.WakeLock)field(lease[0],"lock")).isHeld(),12);
            check(true,"actual screen-off broadcast acquires bounded CPU lease for the owned BS-session fixture");
            main(()->{try{call(lease[0],"close");}catch(Exception e){throw new RuntimeException(e);}});
            check(!((android.os.PowerManager.WakeLock)field(lease[0],"lock")).isHeld()&&!(Boolean)field(lease[0],"registered")&&!((Handler)field(lease[0],"handler")).hasCallbacks((Runnable)field(lease[0],"refresh")),"STOP-equivalent lease close releases CPU and removes receiver/renewal");
            stage("SCREEN_ON_WAIT");await(power::isInteractive,12);
        }finally{
            main(()->{try{if(wifi[0]!=null)call(wifi[0],"close");if(lease[0]!=null)call(lease[0],"close");}catch(Exception e){throw new RuntimeException(e);}});
        }
    }
    private Object field(Object value,String name) throws Exception {
        Field f=value.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(value);
    }
    private Set<String> preferenceNames(Context c) {
        Set<String> names=new HashSet<>();File[] files=new File(c.getApplicationInfo().dataDir,"shared_prefs").listFiles();
        if(files!=null)for(File file:files)if(file.getName().endsWith(".xml"))names.add(file.getName().substring(0,file.getName().length()-4));
        return names;
    }
    private void checkpoint(Context c) throws Exception {
        Set<String> names=preferenceNames(c);
        for(String name:names)originalPrefs.put(name,new HashMap<>(c.getSharedPreferences(name,0).getAll()));
        if(c.getSharedPreferences("MainActivity",0).getBoolean("wantedRunning",false))throw new Exception("Reconnect requested; do not launch UI fixture");
        File pointer=new File(c.getFilesDir(),"active-profile.txt");
        originalPointer=pointer.exists()?new String(Files.readAllBytes(pointer.toPath()),java.nio.charset.StandardCharsets.US_ASCII):null;
        List<File> roots=new ArrayList<>();roots.add(c.getFilesDir());
        if(originalPointer!=null){if(!originalPointer.matches("mcrf-store-[0-9a-f-]{36}"))throw new Exception("Unknown store generation");roots.add(new File(c.getFilesDir(),originalPointer));}
        for(File root:roots)for(String name:new String[]{"profile.enc","connections.enc","routing.enc","preferences.enc","active-profile.txt"})for(String suffix:new String[]{"",".bak",".new"}){
            File file=new File(root,name+suffix);if(Files.isSymbolicLink(file.toPath()))throw new Exception("Unexpected symlink");
            originalFiles.put(file,file.exists()?Files.readAllBytes(file.toPath()):null);
        }
    }
    private void restore(Context c) throws Exception {
        if(activity!=null){
            long deadline=SystemClock.uptimeMillis()+4000;
            while(!activity.isDestroyed()&&SystemClock.uptimeMillis()<deadline)SystemClock.sleep(40);
            if(!activity.isDestroyed())throw new Exception("Activity retirement pending; preserve state");
            for(String worker:new String[]{"worker","probeWorker","presentationWorker","startupAccessWorker"}){
                ExecutorService executor=(ExecutorService)field(activity,worker);
                if(!executor.awaitTermination(3,TimeUnit.SECONDS))throw new Exception("Owned UI worker not retired: "+worker);
            }
        }
        File pointer=new File(c.getFilesDir(),"active-profile.txt");
        String now=pointer.exists()?new String(Files.readAllBytes(pointer.toPath()),java.nio.charset.StandardCharsets.US_ASCII):null;
        if(!Objects.equals(now,originalPointer))throw new Exception("Store generation changed; no blind rollback");
        for(Map.Entry<File,byte[]> row:originalFiles.entrySet()){
            if(row.getValue()==null){if(row.getKey().exists()&&!row.getKey().delete())throw new Exception("Cannot remove test-created setting");}
            else if(!row.getKey().exists()||!Arrays.equals(Files.readAllBytes(row.getKey().toPath()),row.getValue()))Files.write(row.getKey().toPath(),row.getValue());
        }
        for(String name:preferenceNames(c))if(!originalPrefs.containsKey(name))c.deleteSharedPreferences(name);
        for(Map.Entry<String,Map<String,?>> row:originalPrefs.entrySet()){
            android.content.SharedPreferences prefs=c.getSharedPreferences(row.getKey(),0);
            if(prefs.getAll().equals(row.getValue()))continue;
            android.content.SharedPreferences.Editor edit=prefs.edit().clear();
            for(Map.Entry<String,?> entry:row.getValue().entrySet()){
                String key=entry.getKey();Object value=entry.getValue();
                if(value instanceof String)edit.putString(key,(String)value);
                else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
                else if(value instanceof Integer)edit.putInt(key,(Integer)value);
                else if(value instanceof Long)edit.putLong(key,(Long)value);
                else if(value instanceof Float)edit.putFloat(key,(Float)value);
                else if(value instanceof Set)edit.putStringSet(key,(Set<String>)value);
                else throw new Exception("Unsupported preference type");
            }
            if(!edit.commit()||!prefs.getAll().equals(row.getValue()))throw new Exception("Preferences restoration failed");
        }
    }
    private void removeOwned(File root,File file) throws Exception {
        if(!file.getCanonicalPath().equals(root.getCanonicalPath())&&!file.getCanonicalPath().startsWith(root.getCanonicalPath()+File.separator))throw new Exception("Fixture path escaped");
        File[] children=file.listFiles();if(children!=null)for(File child:children)removeOwned(root,child);
        if(file.exists()&&!file.delete())throw new Exception("Fixture cleanup failed");
    }
    private void show(String id) throws Exception {
        call(store,"selectDeployment","swg",id);
        main(()->{try{
            if(ui!=null)call(ui,"detach");if(dialog!=null)dialog.dismiss();
            Class<?> function=Class.forName("kotlin.jvm.functions.Function1",true,loader);
            Object importer=Proxy.newProxyInstance(loader,new Class<?>[]{function},(proxy,m,args)->{
                if(m.getName().equals("invoke"))throw new AssertionError("No import allowed");
                if(m.getName().equals("toString"))return "no-import";
                return null;
            });
            ui=cls("AndroidDeployUi").getConstructor(Activity.class,cls("AndroidDeployStore"),cls("AndroidDeployBackend"),function)
                    .newInstance(activity,store,backend,importer);
            dialog=new Dialog(activity);dialog.setContentView((View)call(ui,"deploy","swg"));dialog.show();
        }catch(Exception e){throw new RuntimeException(e);}});
    }
    private void selectDialogRow(String label) throws Exception {
        UiAutomation automation=getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        android.accessibilityservice.AccessibilityServiceInfo info=automation.getServiceInfo();
        info.flags|=android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        automation.setServiceInfo(info);
        long deadline=SystemClock.uptimeMillis()+4000;
        while(SystemClock.uptimeMillis()<deadline) {
            List<AccessibilityNodeInfo> roots=new ArrayList<>();
            AccessibilityNodeInfo active=automation.getRootInActiveWindow();if(active!=null)roots.add(active);
            for(android.view.accessibility.AccessibilityWindowInfo window:automation.getWindows()){AccessibilityNodeInfo root=window.getRoot();if(root!=null)roots.add(root);}
            for(AccessibilityNodeInfo root:roots) {
                List<AccessibilityNodeInfo> matches=root.findAccessibilityNodeInfosByText(label);
                for(AccessibilityNodeInfo node:matches) {
                    if(!label.contentEquals(node.getText()==null?"":node.getText())&&!label.contentEquals(node.getContentDescription()==null?"":node.getContentDescription()))continue;
                    if(!"org.mcrf.client".contentEquals(node.getPackageName()))continue;
                    for(int i=0;node!=null&&i<4;i++,node=node.getParent())if(node.isClickable()) {
                        if(node.performAction(AccessibilityNodeInfo.ACTION_CLICK))return;
                    }
                }
            }
            SystemClock.sleep(60);
        }
        android.graphics.Bitmap shot=automation.takeScreenshot();
        if(shot!=null){
            File file=new File(getTargetContext().getCacheDir(),"p2-policy-popup-failure.png");
            try(java.io.FileOutputStream stream=new java.io.FileOutputStream(file)){shot.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream);}finally{shot.recycle();}
            report.append("SCREENSHOT ").append(file.getAbsolutePath()).append('\n');
        }
        throw new Exception("Named owned-app popup row not selectable: "+label);
    }
    @Override public void onStart() {
        Bundle result=new Bundle();Context base=getTargetContext();loader=base.getClassLoader();
        String prefix="p2-ui-"+UUID.randomUUID()+"-";
        Set<String> preferences=Collections.synchronizedSet(new HashSet<>());
        File temporary=new File(base.getCacheDir(),prefix);int code=Activity.RESULT_CANCELED;
        try {
            if(phase.equals("budget-recovery")){budgetRecovery(base);code=Activity.RESULT_OK;return;}
            if(phase.equals("carrier-inspect")){
                Class.forName("org.mcrf.client.P2ExpandedLive",true,getClass().getClassLoader()).getMethod("inspect",Instrumentation.class,Context.class,java.util.function.Consumer.class)
                    .invoke(null,this,base,(java.util.function.Consumer<String>)(line->{report.append("DIAGNOSTIC ").append(line).append('\n');}));
                code=Activity.RESULT_OK;return;
            }
            if(phase.equals("recovery-report")){
                Object catalogue=cls("ConnectionCatalogue").getConstructor(String.class).newInstance(call(cls("ProfileStore").getConstructor(Context.class,String.class).newInstance(base,"connections.enc"),"read"));
                for(Object server:(List<?>)call(catalogue,"entries"))report.append("ID ").append(server.getClass().getField("id").get(server)).append('\n');
                code=Activity.RESULT_OK;return;
            }
            Object profiles=cls("ProfileStore").getConstructor(Context.class).newInstance(base);
            boolean hasProfiles=!((String)call(profiles,"read")).isEmpty();
            checkpoint(base);
            if(!temporary.mkdir())throw new Exception("Cannot create private fixture directory");
            Context isolated=new ContextWrapper(base) {
                @Override public Context getApplicationContext(){return this;}
                @Override public File getFilesDir(){return temporary;}
                @Override public android.content.SharedPreferences getSharedPreferences(String name,int mode){
                    preferences.add(prefix+name);return base.getSharedPreferences(prefix+name,mode);
                }
            };
            store=cls("AndroidDeployStore").getConstructor(Context.class).newInstance(isolated);
            // Kotlin's constructor default creates VkRelayAccount from this isolated store only.
            for(Constructor<?> ctor:cls("AndroidDeployBackend").getConstructors())if(ctor.getParameterCount()==4)
                backend=ctor.newInstance(store,null,2,null);
            if(backend==null)throw new Exception("Missing isolated backend constructor");
            if(phase.equals("historical-modules")){historicalModules(base);code=Activity.RESULT_OK;return;}
            if(phase.equals("historical-traffic-20")||phase.equals("historical-traffic-215")||phase.equals("historical-traffic-215-rest")||phase.equals("modern-traffic-25")||phase.equals("legacy-traffic-219")||phase.equals("management-live")){
                if(privateSocket==null||!privateSocket.matches("mcrf-historical-[a-f0-9]{32}"))throw new Exception("Owned private pipe required");
                try(android.net.LocalServerSocket server=new android.net.LocalServerSocket(privateSocket)){
                    java.util.Timer deadline=new java.util.Timer(true);deadline.schedule(new java.util.TimerTask(){public void run(){try{server.close();}catch(Exception ignored){}}},45000);
                    stage("HISTORICAL_PIPE_WAIT");
                    try(android.net.LocalSocket client=server.accept()){
                        deadline.cancel();int uid=client.getPeerCredentials().getUid();if(uid!=2000&&uid!=0)throw new Exception("Only owned ADB pipe permitted");client.setSoTimeout(30000);
                        java.io.DataInputStream input=new java.io.DataInputStream(client.getInputStream());int size=input.readInt();if(size<20||size>65536)throw new Exception("Bounded private profile required");
                        byte[] bytes=new byte[size];input.readFully(bytes);JSONObject profile=new JSONObject(new String(bytes,java.nio.charset.StandardCharsets.UTF_8));Arrays.fill(bytes,(byte)0);
                        if(phase.equals("management-live")){managementLive(base,hasProfiles,profile);profile=null;code=Activity.RESULT_OK;return;}
                        if(!profile.getString("csqtt_generation").equals(phase.equals("legacy-traffic-219")?"2.1.9":phase.equals("modern-traffic-25")?"2.5":phase.endsWith("-20")?"2.0":"2.1.5"))throw new Exception("Phase/profile family mismatch");
                        java.util.concurrent.atomic.AtomicInteger delivered=new java.util.concurrent.atomic.AtomicInteger();
                        Class.forName("org.mcrf.client.P2ExpandedLive",true,getClass().getClassLoader()).getMethod("historicalTraffic",Instrumentation.class,Context.class,JSONObject.class,java.util.function.Consumer.class)
                            .invoke(null,this,base,profile,(java.util.function.Consumer<String>)(label->{if(phase.equals("historical-traffic-215-rest")&&delivered.getAndIncrement()==0)return;passed++;report.append("PASS ").append(label).append('\n');Bundle progress=new Bundle();progress.putInt("checks",passed);progress.putString("check",label);sendStatus(1,progress);}));
                        profile=null;
                    }finally{deadline.cancel();}
                }
                code=Activity.RESULT_OK;return;
            }
            if(phase.equals("deploy-quick-ui")||phase.equals("deploy-quick-ui-rest")||phase.equals("deploy-accordion-ui")){deployQuickUi(base,hasProfiles);code=Activity.RESULT_OK;return;}
            if(phase.equals("csqtt-import-versions")||phase.equals("csqtt-import-versions-rest")){csqttImportVersions(base,hasProfiles);code=Activity.RESULT_OK;return;}
            if(phase.equals("deploy-browser")||phase.equals("deploy-browser-capture")){deployBrowser(base,hasProfiles);code=Activity.RESULT_OK;return;}
            if(phase.equals("remaining-ui")||phase.equals("interval-timer")){remaining(base,isolated,hasProfiles);code=Activity.RESULT_OK;return;}
            if(phase.equals("environment")){environment(base,isolated);code=Activity.RESULT_OK;return;}
            if(phase.equals("menu-capture")){menus(base,hasProfiles);code=Activity.RESULT_OK;return;}
            if(phase.equals("refresh-design")||phase.equals("refresh-design-rest")){refreshDesign(base,hasProfiles);code=Activity.RESULT_OK;return;}
            if(phase.equals("battery-design")){batteryDesign(base,hasProfiles);code=Activity.RESULT_OK;return;}
            if(phase.equals("latest-redesign")){latestRedesign(base,hasProfiles);code=Activity.RESULT_OK;return;}
            if(phase.equals("deploy-call-controls")){deployCallControls(base,hasProfiles);code=Activity.RESULT_OK;return;}
            if(phase.equals("dropdown-regressions")||phase.equals("dropdown-regressions-rest")){dropdownRegressions(base,hasProfiles);code=Activity.RESULT_OK;return;}
            if(phase.equals("aligned-capture")){alignedCapture(base,hasProfiles);code=Activity.RESULT_OK;return;}
            if(phase.equals("notification-cadence")){notificationCadence(base,isolated,hasProfiles);code=Activity.RESULT_OK;return;}
            if(phase.equals("hash-ui")||phase.equals("hash-policy-rest")||phase.equals("hash-help-rest")){hashUi(base,hasProfiles);code=Activity.RESULT_OK;return;}
            if(phase.equals("route-picker")){routePicker(base,hasProfiles);code=Activity.RESULT_OK;return;}
            if(phase.equals("late-ui")||phase.equals("late-ui-rest")||phase.equals("design-capture")||phase.equals("style-final")){lateUi(base,hasProfiles,!phase.equals("late-ui"));code=Activity.RESULT_OK;return;}
            if(phase.equals("active-idle-budget")||phase.equals("active-budget")||phase.equals("route-live")||phase.equals("route-steer")||phase.equals("bs-live")||phase.equals("bs-traffic")||phase.equals("wdtt-traffic")){
                if((phase.equals("active-budget")||phase.equals("active-idle-budget"))&&!allowNetworkScreen)throw new Exception("Explicit screen authority required");
                if(phase.equals("route-steer")&&!allowNetworkScreen)throw new Exception("Explicit temporary Steer DNS consent required");
                Intent permission=android.net.VpnService.prepare(base);
                if(permission!=null&&allowVpnConsent){
                    initializeActivity(base,hasProfiles);
                    main(()->activity.startActivityForResult(permission,19857));stage("VPN_CONSENT_WAIT");await(()->android.net.VpnService.prepare(base)==null,60);
                    main(()->activity.finish());await(()->activity.isDestroyed(),5);
                }
                Class.forName("org.mcrf.client.P2ExpandedLive",true,getClass().getClassLoader())
                    .getMethod(phase.equals("active-idle-budget")?"idleBudget":phase.equals("active-budget")?"budget":phase.equals("bs-live")?"bs":phase.equals("wdtt-traffic")?"wdttTraffic":phase.equals("bs-traffic")?"traffic":phase.equals("route-steer")?"steer":"run",Instrumentation.class,Context.class,java.util.function.Consumer.class)
                    .invoke(null,this,base,(java.util.function.Consumer<String>)(label->{passed++;report.append("PASS ").append(label).append('\n');Bundle progress=new Bundle();progress.putInt("checks",passed);progress.putString("check",label);sendStatus(1,progress);}));
                code=Activity.RESULT_OK;return;
            }
            String a=(String)call(store,"newDeployment","swg","P2 fixture A",new JSONObject().put("host","192.0.2.1").put("sshPassword","fixture-A").put("options",new JSONObject().put("create",false)),"");
            String b=(String)call(store,"newDeployment","swg","P2 fixture B",new JSONObject().put("host","192.0.2.2").put("sshPassword","fixture-B").put("options",new JSONObject().put("create",false)),"");
            // Empty first install must NOT enable test_direct (that flag intentionally starts a TUN fixture).
            activity=startActivitySync(new Intent(base,cls("MainActivity")).putExtra("test_no_prompts",true).putExtra("test_direct",hasProfiles).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            long loadingDeadline=SystemClock.uptimeMillis()+10000;
            while((Boolean)field(activity,"loading")&&SystemClock.uptimeMillis()<loadingDeadline)SystemClock.sleep(40);
            if((Boolean)field(activity,"loading"))throw new Exception("Main UI initialization did not finish");
            main(()->{try{Object chooser=field(activity,"primaryChooser");if(chooser instanceof Dialog)((Dialog)chooser).dismiss();}catch(Exception e){throw new RuntimeException(e);}});
            show(a);
            main(()->{if(!nameField().getText().toString().equals("P2 fixture A"))throw new AssertionError("Wrong selected editor");button("Выбрать сохранённую установку").performClick();});
            selectDialogRow("P2 fixture B · 192.0.2.2");
            main(()->{if(!nameField().getText().toString().equals("P2 fixture B"))throw new AssertionError("Picker did not reopen B");});
            check(workspace().getJSONObject("selected").getString("swg").equals(b),"actual named picker switches same-protocol VPS A to B");
            main(()->nameField().setText("P2 renamed B"));
            JSONObject drafts=workspace().getJSONObject("drafts");
            check(drafts.getJSONObject(b).getString("name").equals("P2 renamed B")&&drafts.getJSONObject(a).getString("name").equals("P2 fixture A"),"name editor changes only selected named instance");
            main(()->button("Новая установка SWG Panel").performClick());
            JSONObject current=workspace();String fresh=current.getJSONObject("selected").getString("swg");
            JSONObject values=current.getJSONObject("drafts").getJSONObject(fresh).getJSONObject("values");
            check(!fresh.equals(a)&&!fresh.equals(b)&&current.getJSONObject("drafts").length()==3&&values.optString("host").isEmpty()&&values.optString("sshPassword").isEmpty(),"new same-protocol installation is independent and copies no VPS credentials");
            show(a);
            main(()->{if(!nameField().getText().toString().equals("P2 fixture A"))throw new AssertionError("A lost after new draft");});
            check(workspace().getJSONObject("drafts").getJSONObject(a).getJSONObject("values").getString("sshPassword").equals("fixture-A"),"reopen original installation preserves its own credentials");
            check(!(Boolean)call(call(backend,"getState"),"getRunning")&&((JSONObject)call(store,"get","deployResults")).length()==0,"widget actions start no SSH/deploy/provider/import operation");
            code=Activity.RESULT_OK;
        } catch(Throwable e) {
            Throwable failure=e;for(int depth=0;depth<8&&failure.getCause()!=null;depth++)failure=failure.getCause();
            String detail=failure.toString();try{detail=(String)cls("ClientEvents").getMethod("redact",String.class).invoke(null,detail);}catch(Exception ignored){detail=failure.getClass().getSimpleName();}
            report.append("FAIL ").append(detail).append('\n');
            for(StackTraceElement frame:failure.getStackTrace())if(frame.getClassName().startsWith("org.mcrf."))report.append("  ").append(frame).append('\n');
        }
        finally {
            try {main(()->{try{if(ui!=null)call(ui,"detach");}catch(Exception e){throw new RuntimeException(e);}finally{if(dialog!=null)dialog.dismiss();if(activity!=null)activity.finish();}});if(backend!=null)call(backend,"close");}
            catch(Exception e){report.append("CLEANUP FAIL ").append(e);code=Activity.RESULT_CANCELED;}
            try {if(!originalFiles.isEmpty()){restore(base);result.putBoolean("settingsRestored",true);}}
            catch(Exception e){report.append("RESTORE FAIL ").append(e);code=Activity.RESULT_CANCELED;}
            for(String name:preferences)base.deleteSharedPreferences(name);
            try {if(temporary.exists())removeOwned(temporary,temporary);}catch(Exception e){report.append("FILE CLEANUP FAIL ").append(e);code=Activity.RESULT_CANCELED;}
            result.putInt("passed",passed);result.putString("report",report.toString());
            finish(code,result);
        }
    }
}
