package mcrfdiag
import("encoding/json";"strings";"testing")
func TestV3ExactBoundedSchema(t *testing.T){for _,version:=range []int{1,2,3}{var r *Run;switch version{case 1:r=Begin();case 2:r=BeginV2();case 3:r=BeginV3()};for e:=Event(0);e<v3EventCount;e++{r.Mark(e)};line:=r.Finish();prefix:=map[int]string{1:Prefix,2:PrefixV2,3:PrefixV3}[version];var flags map[string]bool;if !strings.HasPrefix(line,prefix)||json.Unmarshal([]byte(strings.TrimPrefix(line,prefix)),&flags)!=nil||len(flags)!=map[int]int{1:15,2:26,3:37}[version]{t.Fatal("version schema changed")};if len(line)>1024{t.Fatal("size bound")};for _,v:=range flags{if !v{t.Fatal("missing event")}}}
 r:=BeginV3();line:=r.Finish();if len(line)>1024||strings.Contains(line,":true")||r.Finish()!=""{t.Fatal("false schema/freeze")};t.Logf("Fixed all-false snapshot bytes: %d",len(line));for _,c:=range line{if c>127{t.Fatal("nonASCII")}}
}
