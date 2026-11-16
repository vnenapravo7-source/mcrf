package org.mcrf.steerprep.json;

import java.util.HashSet;
import java.util.Set;

/** Shared host/worker boundary, BEFORE permissive or recursive JSONObject parsing.
 * Syntax and allocation limits only: not feature admission or cache provenance.
 * No payload, key, credential, or parser excerpt enters an exception message. */
public final class SteerSnapshotJson {
    private SteerSnapshotJson() {}
    /** Method, NOT an inlined constant: module parent-first loading may find
     * the host copy. Any incompatible guard change must bump this contract. */
    public static int contractVersion(){return 1;}
    private static final int MAX_BYTES=4*1024*1024, MAX_DEPTH=48, MAX_VALUES=262144;
    public static final class Rejected extends IllegalArgumentException {
        public final String code;
        private Rejected(String value){super(value);code=value;}
    }
    private static Rejected bad(){return new Rejected("SNAPSHOT_JSON");}
    public static void requireObject(String text){
        if(text==null)throw bad();
        // Bound BEFORE UTF-8 allocation/hash/JSONObject, including multibyte text.
        if(text.length()>MAX_BYTES)throw new Rejected("SNAPSHOT_SIZE");
        long bytes=0;
        for(int at=0;at<text.length();at++){
            char ch=text.charAt(at);
            if(Character.isHighSurrogate(ch)){
                if(++at>=text.length()||!Character.isLowSurrogate(text.charAt(at)))throw bad();
                bytes+=4;
            }else if(Character.isLowSurrogate(ch))throw bad();
            else bytes+=ch<128?1:ch<2048?2:3;
            if(bytes>MAX_BYTES)throw new Rejected("SNAPSHOT_SIZE");
        }
        Reader reader=new Reader(text);reader.space();if(reader.peek()!='{')throw bad();
        reader.value(0);reader.space();if(reader.at!=text.length())throw bad();
    }
    private static final class Reader {
        final String text;int at,values,keys;
        Reader(String value){text=value;}
        int peek(){return at<text.length()?text.charAt(at):-1;}
        void space(){while(at<text.length()&&" \t\r\n".indexOf(text.charAt(at))>=0)at++;}
        boolean eat(char ch){space();if(peek()!=ch)return false;at++;return true;}
        void take(char ch){if(!eat(ch))throw bad();}
        void value(int depth){
            if(++values>MAX_VALUES)throw new Rejected("SNAPSHOT_COMPLEXITY");
            space();int ch=peek();
            if(ch=='{'||ch=='['){
                if(depth>=MAX_DEPTH)throw new Rejected("SNAPSHOT_DEPTH");
                at++;
                if(ch=='{'){
                    Set<String> names=new HashSet<>();if(eat('}'))return;
                    do{
                        space();String key=string(true);
                        if(++keys>65536||names.size()>=256)throw new Rejected("SNAPSHOT_COMPLEXITY");
                        if(!names.add(key))throw bad();
                        take(':');value(depth+1);if(eat('}'))return;take(',');
                    }while(true);
                }
                if(eat(']'))return;int entries=0;
                do{
                    if(++entries>100000)throw new Rejected("SNAPSHOT_COMPLEXITY");
                    value(depth+1);if(eat(']'))return;take(',');
                }while(true);
            }
            if(ch=='"'){string(false);return;}
            if(ch=='t'){literal("true");return;}if(ch=='f'){literal("false");return;}if(ch=='n'){literal("null");return;}
            if(ch=='-'||digit(ch)){number();return;}throw bad();
        }
        void literal(String word){if(!text.startsWith(word,at))throw bad();at+=word.length();}
        static boolean digit(int ch){return ch>='0'&&ch<='9';}
        void number(){
            int begin=at;if(peek()=='-')at++;
            if(peek()=='0')at++;
            else{if(peek()<'1'||peek()>'9')throw bad();while(digit(peek()))at++;}
            if(peek()=='.'){at++;if(!digit(peek()))throw bad();while(digit(peek()))at++;}
            if(peek()=='e'||peek()=='E'){
                at++;if(peek()=='+'||peek()=='-')at++;if(!digit(peek()))throw bad();while(digit(peek()))at++;
            }
            if(at-begin>128)throw new Rejected("SNAPSHOT_COMPLEXITY");
        }
        char hex(){
            if(at>text.length()-4)throw bad();int value=0;
            for(int i=0;i<4;i++){
                char ch=text.charAt(at++);int n=ch>='0'&&ch<='9'?ch-'0':ch>='a'&&ch<='f'?ch-'a'+10:ch>='A'&&ch<='F'?ch-'A'+10:-1;
                if(n<0)throw bad();value=(value<<4)|n;
            }return (char)value;
        }
        String string(boolean key){
            if(peek()!='"')throw bad();at++;StringBuilder decoded=key?new StringBuilder():null;
            while(at<text.length()){
                char ch=text.charAt(at++);if(ch=='"')return key?decoded.toString():null;
                if(ch<32)throw bad();
                if(ch=='\\'){
                    if(at>=text.length())throw bad();ch=text.charAt(at++);
                    switch(ch){
                        case '"':case '\\':case '/':break;
                        case 'b':ch='\b';break;case 'f':ch='\f';break;case 'n':ch='\n';break;case 'r':ch='\r';break;case 't':ch='\t';break;
                        case 'u':
                            ch=hex();
                            if(Character.isHighSurrogate(ch)){
                                if(at>text.length()-2||text.charAt(at++)!='\\'||text.charAt(at++)!='u')throw bad();
                                char low=hex();if(!Character.isLowSurrogate(low))throw bad();
                                if(key)decoded.append(ch);ch=low;
                            }else if(Character.isLowSurrogate(ch))throw bad();
                            break;
                        default:throw bad();
                    }
                }else if(Character.isHighSurrogate(ch)){
                    if(at>=text.length()||!Character.isLowSurrogate(text.charAt(at)))throw bad();
                    if(key)decoded.append(ch);ch=text.charAt(at++);
                }else if(Character.isLowSurrogate(ch))throw bad();
                if(key){decoded.append(ch);if(decoded.length()>256)throw new Rejected("SNAPSHOT_COMPLEXITY");}
            }
            throw bad();
        }
    }
}
