package org.steer.core;

import android.content.Context;
import org.json.*;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** SOURCE ONLY per-worker trusted asset + pinned private Geo resolver.
 * All reads/compilation share ONE bounded budget and immutable source caches.
 * Each preparation gets its own manifest-bound view; no mutable "current bank"
 * that another validate/start could replace. No downloader or capability. */
public final class WorkerRuleSets {
    private final Context host;
    private final SrsAddressSet.Budget budget=new SrsAddressSet.Budget(128L*1024*1024);
    private final PackagedRuleSets packaged;
    private final Map<String,Bank> banks=new LinkedHashMap<>();
    private final Map<String,SrsAddressSet> matches=new HashMap<>();
    private final Map<String,NativeFlowMatch> flowMatches=new HashMap<>();
    private static final class Identity {
        final String kind,code;
        Identity(JSONObject row)throws JSONException{kind=row.getString("kind");code=row.getString("code");}
    }
    private final class Bank {
        final String text,hash;
        final PinnedGeoRuleSets geo;
        final Map<Integer,Identity> identities=new HashMap<>();
        Bank(String text,String hash)throws IOException{
            this.text=text;this.hash=hash;
            try{
                JSONArray manifest=new JSONArray(text);
                // PinnedGeoRuleSets independently checks exact fields/types,
                // IDs, private host context, names and duplicate selectors.
                geo=manifest.length()==0?null:new PinnedGeoRuleSets(host,manifest,budget);
                for(int i=0;i<manifest.length();i++){
                    JSONObject row=manifest.getJSONObject(i);identities.put(row.getInt("selector"),new Identity(row));
                }
            }catch(JSONException invalid){throw new IOException("Invalid bound Geo manifest",invalid);}
        }
        SrsAddressSet load(String reference)throws IOException{
            if(reference==null||!reference.matches("mcrf-geo-(?:0|[1-9][0-9]?)"))throw new IOException("Canonical Geo reference required");
            int index=Integer.parseInt(reference.substring(9));Identity identity=identities.get(index);
            if(geo==null||identity==null)throw new IOException("Geo reference absent from pinned manifest");
            return geo.load(index,identity.kind,identity.code);
        }
    }
    public WorkerRuleSets(Context context){
        if(context==null||context.getApplicationContext()==null)throw new IllegalArgumentException("Actual worker host context required");
        host=context.getApplicationContext();packaged=new PackagedRuleSets(host,budget);
    }
    private static void offUi()throws IOException{
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IOException("Worker rule preparation must be off UI");
    }
    private static String hash(String text)throws IOException{
        try{
            StringBuilder value=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8)))
                value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();
        }catch(NoSuchAlgorithmException impossible){throw new IOException("SHA-256 unavailable",impossible);}
    }
    public synchronized McrfPreparation.RuleSets bind(JSONArray manifest)throws IOException{
        offUi();String text=manifest==null?"[]":manifest.toString();
        if(text.length()>65536||manifest!=null&&manifest.length()>64)throw new IOException("Geo manifest bound");
        String key=hash(text);Bank cached=banks.get(key);
        if(cached==null){
            if(banks.size()>=8)throw new IOException("Worker Geo snapshot capacity");
            budget.claim(2048L+text.length()*8L);cached=new Bank(text,key);banks.put(key,cached);
        }else if(!cached.text.equals(text))throw new IOException("Geo manifest identity collision");
        final Bank bank=cached;
        return new McrfPreparation.RuleSets(){
            @Override public SrsAddressSet load(String id)throws IOException{
                synchronized(WorkerRuleSets.this){offUi();return packaged.load(id);}
            }
            @Override public boolean matchesManifest(JSONArray value){return value!=null&&bank.text.equals(value.toString());}
            @Override public SrsAddressSet compileNative(JSONObject value)throws IOException{
                synchronized(WorkerRuleSets.this){
                    offUi();if(value==null)throw new IOException("Native match required");String source=value.toString();
                    if(source.length()>262144)throw new IOException("Native match source bound");
                    String id=hash(bank.hash+"\n"+source);SrsAddressSet previous=matches.get(id);if(previous!=null)return previous;
                    if(matches.size()+flowMatches.size()>=1024)throw new IOException("Worker native match cache capacity");
                    budget.claim(320);SrsAddressSet result=NativeAddressMatch.compileText(source,bank::load,budget);matches.put(id,result);return result;
                }
            }
            @Override public NativeFlowMatch compileNativeFlow(JSONObject value)throws IOException{
                synchronized(WorkerRuleSets.this){
                    offUi();if(value==null)throw new IOException("Original flow condition required");String source=value.toString();
                    if(source.length()>262144)throw new IOException("Native flow source bound");
                    String id=hash(bank.hash+"\nflow\n"+source);NativeFlowMatch previous=flowMatches.get(id);if(previous!=null)return previous;
                    if(matches.size()+flowMatches.size()>=1024)throw new IOException("Worker native match cache capacity");
                    budget.claim(320);NativeFlowMatch result=NativeFlowMatch.compileText(source,bank::load,budget);flowMatches.put(id,result);return result;
                }
            }
        };
    }
    @Override public String toString(){return "WorkerRuleSets(redacted)";}
}
