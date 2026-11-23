package mcrfdiag
import("encoding/json";"strings";"testing")
func TestV2SchemaAndV1Isolation(t *testing.T){
 r:=Begin();r.Mark(DemuxEmpty);line:=r.Finish();var v1 map[string]bool
 if !strings.HasPrefix(line,Prefix)||json.Unmarshal([]byte(strings.TrimPrefix(line,Prefix)),&v1)!=nil||len(v1)!=15||strings.Contains(line,"demux_"){t.Fatal("V1 broadened")}
 r=BeginV2();for e:=Event(0);e<v2EventCount;e++{r.Mark(e)};line=r.Finish();var v2 map[string]bool
 if !strings.HasPrefix(line,PrefixV2)||json.Unmarshal([]byte(strings.TrimPrefix(line,PrefixV2)),&v2)!=nil||len(v2)!=26{t.Fatal("V2 exact schema")};for _,v:=range v2{if !v{t.Fatal("lost flag")}}
 old:=BeginV2();next:=BeginV2();old.Finish();old.Mark(DemuxEmpty);if Current()!=next{t.Fatal("old finish detached new run")};if strings.Contains(next.Finish(),":true"){t.Fatal("captured old pointer contaminated run")}
}
