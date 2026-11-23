package main
import("encoding/json";"fmt";"os";"github.com/sagernet/sing/common/domain")
func main(){
 patterns:=[]string{"||example.org^","|example.org^","example","||ads.*^","*","||a*b.example^","|a*b^","EXAMPLE","||EXAMPLE.org^","|prefix","prefix.","prefix..","a*b*c","||*example*^","тест","||пример.рф^",""}
 names:=[]string{"example.org","sub.example.org","badexample.org","example.org.evil","myexamplekeyword.invalid","a.b.example","ab.example","acb.example","aZZb","aZZbZZc","prefix","prefixx","prefix.example","abc-prefix.foo","prefix..foo","EXAMPLE.org","example.ORG","тест.пример","под.пример.рф","badпример.рф","","a\nb"}
 rows:=[]map[string]any{}
 for _,pattern:=range patterns{matcher:=domain.NewAdGuardMatcher([]string{pattern});recovered:=matcher.Dump();if len(recovered)!=1{panic("bad fixture")};for _,name:=range names{rows=append(rows,map[string]any{"pattern":recovered[0],"original":pattern,"domain":name,"matches":matcher.Match(name)})}}
 data,err:=json.MarshalIndent(rows,"","  ");if err!=nil{panic(err)};if err=os.WriteFile("adguard-reference.json",data,0600);err!=nil{panic(err)};fmt.Println("adguard cases",len(rows))
}
