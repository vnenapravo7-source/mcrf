package org.steer.core;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Bounded lexical expansion only. Every term still needs policy validation;
 * hosts-file prefix addresses are NOT destination routing conditions. */
public final class McrfRuleLines {
    private McrfRuleLines() {}
    public static List<String> expand(String text, boolean model2) {
        if(text==null||text.length()>4*1024*1024||text.getBytes(StandardCharsets.UTF_8).length>4*1024*1024)
            throw new IllegalArgumentException("Routing text exceeds 4 MiB");
        List<String> result=new ArrayList<>();
        try(BufferedReader reader=new BufferedReader(new StringReader(text))){
            for(String raw;(raw=reader.readLine())!=null;){
                int comment=raw.indexOf('#');
                String value=(comment<0?raw:raw.substring(0,comment)).trim();
                if(value.startsWith("\uFEFF"))value=value.substring(1);
                if(value.isEmpty()||value.startsWith("!")||value.startsWith("["))continue;
                // app/pkg stay intact, as in RouteRules; CIDRs are not hosts prefixes.
                int split=-1;
                if(model2&&!value.contains(","))for(int i=0;i<value.length();i++)
                    if(value.charAt(i)==' '||value.charAt(i)=='\t'){split=i;break;}
                if(split>0&&literal(value.substring(0,split))){
                    int at=split;
                    while(at<value.length()){
                        while(at<value.length()&&(value.charAt(at)==' '||value.charAt(at)=='\t'))at++;
                        int end=at;while(end<value.length()&&value.charAt(end)!=' '&&value.charAt(end)!='\t')end++;
                        if(end>at){
                            String term=value.substring(at,end),lower=term.toLowerCase(java.util.Locale.ROOT);
                            if(lower.startsWith("app:")||lower.startsWith("pkg:")||lower.startsWith("path:"))
                                throw new IllegalArgumentException("Hosts rows cannot introduce application conditions");
                            add(result,term);
                        }
                        at=end;
                    }
                }else add(result,value);
            }
        }catch(IOException impossible){throw new IllegalStateException("String reader failure",impossible);}
        return Collections.unmodifiableList(result);
    }
    private static void add(List<String> values,String value){
        if(values.size()>=4096)throw new IllegalArgumentException("Routing text expands beyond 4096 terms");
        values.add(value);
    }
    private static boolean literal(String value){
        if(value.indexOf('/')>=0)return false;
        try{
            if(value.indexOf(':')>=0){Ipv6Routes.address(value);return true;}
            if(!value.matches("[0-9]{1,3}(?:\\.[0-9]{1,3}){3}"))return false;
            Ipv4Routes.parse(value+"/32");return true;
        }catch(IllegalArgumentException invalid){return false;}
    }
}
