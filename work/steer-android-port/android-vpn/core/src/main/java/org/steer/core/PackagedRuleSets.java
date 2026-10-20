package org.steer.core;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.*;

/** Only signed host APK assets; never user paths/downloads, guessed tags or an
 * embedded native core. Cache belongs to this one private engine worker. */
public final class PackagedRuleSets implements McrfPreparation.RuleSets {
    private final Context host;
    private final Map<String,JSONObject> catalogue=new HashMap<>();
    private final Map<String,SrsAddressSet> loaded=new HashMap<>();
    private final SrsAddressSet.Budget budget;
    public PackagedRuleSets(Context context){
        this(context,new SrsAddressSet.Budget(128L*1024*1024));
    }
    PackagedRuleSets(Context context,SrsAddressSet.Budget sharedBudget){
        if(context==null||context.getApplicationContext()==null||sharedBudget==null)throw new IllegalArgumentException("Actual host APK context and budget required");
        host=context.getApplicationContext();
        budget=sharedBudget;
    }
    private static byte[] bounded(InputStream input,int max)throws IOException{
        ByteArrayOutputStream output=new ByteArrayOutputStream();byte[] part=new byte[8192];
        for(int got;(got=input.read(part))!=-1;){if(got==0)throw new IOException("Asset read stalled");if(output.size()>max-got)throw new IOException("Asset too large");output.write(part,0,got);}
        return output.toByteArray();
    }
    @Override public synchronized SrsAddressSet load(String id)throws IOException{
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IOException("Rule-set materialization must be off UI");
        if(id==null||!id.matches("[0-9a-f]{16}"))throw new IOException("Invalid packaged rule-set ID");
        SrsAddressSet previous=loaded.get(id);if(previous!=null)return previous;
        try{
            if(catalogue.isEmpty()){
                JSONObject root;try(InputStream input=host.getAssets().open("presets/catalogue.json")){root=new JSONObject(new String(bounded(input,256*1024),StandardCharsets.UTF_8));}
                if(!Integer.valueOf(1).equals(root.opt("version")))throw new IOException("Unknown packaged catalogue");
                JSONArray entries=root.getJSONArray("presets");if(entries.length()!=68)throw new IOException("Packaged catalogue count");
                Map<String,JSONObject> candidate=new HashMap<>();
                for(int at=0;at<entries.length();at++){
                    JSONObject row=entries.getJSONObject(at);String key=row.getString("id");Object bytes=row.opt("bytes");
                    if(!key.matches("[0-9a-f]{16}")||!row.getString("sha256").matches("[0-9a-f]{64}")||
                        !(bytes instanceof Integer||bytes instanceof Long)||((Number)bytes).longValue()<1||((Number)bytes).longValue()>8*1024*1024||candidate.put(key,row)!=null)
                        throw new IOException("Invalid packaged catalogue entry");
                }
                catalogue.putAll(candidate);
            }
            JSONObject row=catalogue.get(id);if(row==null)throw new IOException("Packaged rule-set unavailable");
            byte[] packed;try(InputStream input=host.getAssets().open("presets/"+id+".srs")){packed=bounded(input,8*1024*1024);}
            StringBuilder digest=new StringBuilder();for(byte value:MessageDigest.getInstance("SHA-256").digest(packed))digest.append(String.format(Locale.ROOT,"%02x",value&255));
            if(packed.length!=row.getLong("bytes")||!digest.toString().equals(row.getString("sha256")))throw new IOException("Packaged rule-set integrity mismatch");
            SrsAddressSet result=SrsAddressSet.read(packed,budget);
            if(result.hasDomains()!=(row.getLong("domains")>0)||result.hasIps()!=(row.getLong("ips")>0))throw new IOException("Packaged rule-set kind mismatch");
            loaded.put(id,result);return result;
        }catch(JSONException|GeneralSecurityException invalid){throw new IOException("Invalid packaged rule-set catalogue");}
    }
    @Override public String toString(){return "PackagedRuleSets(redacted)";}
}
