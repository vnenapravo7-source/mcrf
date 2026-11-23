package main
import("encoding/json";"os";"strings";"github.com/sagernet/sing/common/domain")
type Case struct{Kind string `json:"kind"`; Pattern string `json:"pattern"`; Host string `json:"host"`; Match bool `json:"match"`}
func main(){var rows []Case
patterns:=[]string{"example.org",".example.org","example.org.","EXAMPLE.ORG",".",".example.","example."}
hosts:=[]string{"example.org","www.example.org","EXAMPLE.ORG","EXAMPLE.ORG.","notexample.org","example.org.attacker.test","localhost",""}
for _,kind:=range []string{"domain","domain_suffix","domain_keyword"}{for _,p:=range patterns{for _,host:=range hosts{
 h:=strings.ToLower(host);matched:=false
 if h!=""{switch kind{case "domain":matched=domain.NewMatcher([]string{p},nil,false).Match(h);case "domain_suffix":matched=domain.NewMatcher(nil,[]string{p},false).Match(h);case "domain_keyword":matched=strings.Contains(h,p)}}
 rows=append(rows,Case{kind,p,host,matched})
}}};json.NewEncoder(os.Stdout).Encode(rows)}
