package mcrfdiag
import("encoding/json";"strings";"sync";"testing")
func TestResetFreezePrivacy(t *testing.T){
 old:=Begin();old.Mark(AckOK);line:=old.Finish();if old.Finish()!=""{t.Fatal("duplicate snapshot")}
 var flags map[string]bool;if json.Unmarshal([]byte(strings.TrimPrefix(line,Prefix)),&flags)!=nil||len(flags)!=int(eventCount){t.Fatal("schema")}
 for _,name:=range names{if _,ok:=flags[name];!ok{t.Fatal("missing flag")}}
 if !flags["mailru_ack_write_ok"]||flags["classic_decrypt_ok"]{t.Fatal("false flags")}
 next:=Begin();old.Mark(ClassicDecryptOK);if strings.Contains(next.Finish(),":true"){t.Fatal("late previous-run event leaked")}
 if Current()!=nil{t.Fatal("run retained")}
}
func TestConcurrentFixedEvents(t *testing.T){r:=Begin();var wg sync.WaitGroup;for i:=Event(0);i<eventCount;i++{wg.Add(1);go func(e Event){defer wg.Done();for n:=0;n<50;n++{r.Mark(e)}}(i)};wg.Wait();var flags map[string]bool;json.Unmarshal([]byte(strings.TrimPrefix(r.Finish(),Prefix)),&flags);for _,v:=range flags{if !v{t.Fatal("lost atomic event")}}}
func TestDisabled(t *testing.T){var r *Run;r.Mark(AckOK);if r.Finish()!=""{t.Fatal("default emits")}}
