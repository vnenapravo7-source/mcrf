package org.steer.core;

import android.content.Context;
import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** SOURCE-ONLY private host-cache binding, not a downloader or capability.
 * Caller must project the same immutable selector snapshot into this manifest.
 * No arbitrary path, fallback filename, cache refresh or provider URL is accepted.
 * Host worker owns its actual application Context; no cross-UID provider needed. */
public final class PinnedGeoRuleSets {
    private static final class Entry {
        final String file,sha,kind,code;final long bytes;
        Entry(JSONObject value)throws JSONException,IOException{
            if(value.length()!=6)throw new IOException("Exact Geo cache descriptor required");
            Set<String> known=new HashSet<>(Arrays.asList("selector","file","sha256","bytes","kind","code"));
            for(Iterator<String> it=value.keys();it.hasNext();)if(!known.contains(it.next()))throw new IOException("Unknown Geo cache descriptor field");
            for(String key:Arrays.asList("file","sha256","kind","code"))if(!(value.opt(key) instanceof String))throw new IOException("Geo cache descriptor string required");
            file=value.getString("file");sha=value.getString("sha256");kind=value.getString("kind");code=value.getString("code");
            Object count=value.opt("bytes");if(!(count instanceof Integer||count instanceof Long))throw new IOException("Geo cache byte count required");
            bytes=((Number)count).longValue();
            if(!file.matches("[0-9a-f]{64}\\.json")||!sha.matches("[0-9a-f]{64}")||bytes<1||bytes>8L*1024*1024||
                !Arrays.asList("geoip","geosite").contains(kind)||!code.matches("[a-zA-Z0-9_-]{1,64}"))throw new IOException("Invalid Geo cache identity/bound");
        }
    }
    private final Path root;
    private final Map<Integer,Entry> entries=new HashMap<>();
    private final Map<String,SrsAddressSet> loaded=new HashMap<>();
    private final SrsAddressSet.Budget budget;
    public PinnedGeoRuleSets(Context context,JSONArray manifest,SrsAddressSet.Budget budget)throws IOException{
        if(context==null||budget==null||manifest==null||manifest.length()<1||manifest.length()>64||
            !"org.mcrf.client".equals(context.getPackageName())||context.getApplicationInfo().uid!=android.os.Process.myUid())
            throw new IOException("Actual private host Context and bounded Geo manifest required");
        this.budget=budget;
        File files=context.getFilesDir().getCanonicalFile(),directory=new File(files,"mcrf-geodata");
        if(Files.isSymbolicLink(directory.toPath())||!directory.isDirectory()||!directory.getCanonicalFile().getParentFile().equals(files))
            throw new IOException("Ordinary existing private Geo cache required");
        root=directory.getCanonicalFile().toPath();
        try{
            for(int at=0;at<manifest.length();at++){
                JSONObject value=manifest.getJSONObject(at);Object id=value.opt("selector");
                if(!(id instanceof Integer||id instanceof Long)||((Number)id).longValue()<0||((Number)id).longValue()>63)throw new IOException("Geo selector identity required");
                int index=((Number)id).intValue();Entry entry=new Entry(value);
                if(entries.put(index,entry)!=null)throw new IOException("Duplicate Geo selector identity");
            }
        }catch(JSONException invalid){throw new IOException("Invalid Geo manifest shape",invalid);}
    }
    public synchronized SrsAddressSet load(int selector,String kind,String code)throws IOException{
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper())throw new IOException("Geo materialization must be off UI");
        Entry entry=entries.get(selector);
        if(entry==null||!entry.kind.equals(kind)||!entry.code.equals(code))throw new IOException("Unbound/stale Geo selector");
        SrsAddressSet cached=loaded.get(entry.sha);
        if(cached!=null){requireKind(cached,entry.kind);return cached;}
        Path path=root.resolve(entry.file);
        if(!path.getParent().equals(root)||Files.isSymbolicLink(root)||Files.isSymbolicLink(path)||!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)||Files.size(path)!=entry.bytes)
            throw new IOException("Pinned ordinary Geo cache file required");
        budget.claim(entry.bytes*32L+1024); // UTF-8/JSON peak plus input copies, BEFORE read/parse
        byte[] bytes=new byte[(int)entry.bytes];
        try(InputStream input=Files.newInputStream(path,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS)){
            int at=0;
            while(at<bytes.length){int got=input.read(bytes,at,bytes.length-at);if(got<=0)throw new IOException("Short/stalled Geo cache");at+=got;}
            if(input.read()!=-1)throw new IOException("Geo cache grew beyond pinned bound");
        }
        try{
            StringBuilder sha=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))sha.append(String.format(Locale.ROOT,"%02x",b&255));
            if(!sha.toString().equals(entry.sha))throw new IOException("Geo cache SHA mismatch; no refresh/fallback");
        }catch(NoSuchAlgorithmException impossible){throw new IOException("SHA-256 unavailable",impossible);}
        String text=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();
        SrsAddressSet result=SrsAddressSet.fromGeoJson(GeoSourceJson.parse(text),budget);
        requireKind(result,entry.kind);
        loaded.put(entry.sha,result);return result;
    }
    private static void requireKind(SrsAddressSet result,String kind)throws IOException{
        if(kind.equals("geoip")?(!result.hasIps()||result.hasDomains()):(!result.hasDomains()||result.hasIps()))throw new IOException("Geo category kind mismatch");
    }
    @Override public String toString(){return "PinnedGeoRuleSets(redacted)";}
}
