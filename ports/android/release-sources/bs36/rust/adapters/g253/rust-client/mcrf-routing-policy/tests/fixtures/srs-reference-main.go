package main

import (
 "bytes"
 "encoding/base64"
 "encoding/json"
 "fmt"
 "os"
 "github.com/sagernet/sing-box/common/srs"
 "github.com/sagernet/sing-box/option"
)
func check(err error){if err!=nil{panic(err)}}
func expected(rule option.HeadlessRule) map[string]any {
 data,err:=json.Marshal(rule);check(err);var value map[string]any;check(json.Unmarshal(data,&value))
 if len(rule.DefaultOptions.AdGuardDomain)>0{value["adguard_domain"]=rule.DefaultOptions.AdGuardDomain}
 if rule.Type=="logical"{children:=[]map[string]any{};for _,child:=range rule.LogicalOptions.Rules{children=append(children,expected(child))};value["rules"]=children}
 return value
}
func main(){
 rows:=[]map[string]any{}
 cases:=[]struct{name string;minimum uint8;source string}{
  {"domains-and-ranges",1,`{"version":3,"rules":[{"domain":["exact.example","тест.пример"],"domain_suffix":["example.org",".only-sub.example","legacy.example"],"domain_keyword":["needle"],"domain_regex":["^owned[0-9]+\\.invalid$"],"source_ip_cidr":["192.0.2.0/25","192.0.2.128/25"],"ip_cidr":["198.51.100.9/32","2001:db8::/126"],"network":["tcp","udp"],"source_port":[1,65535],"port":[53,443],"port_range":["8000:8080"],"source_port_range":["10:20"],"query_type":[1,28],"package_name":["org.example.owned"],"wifi_ssid":["owned"],"wifi_bssid":["00:11:22:33:44:55"],"invert":true}]}`},
  {"logical",1,`{"version":3,"rules":[{"type":"logical","mode":"or","invert":true,"rules":[{"domain_suffix":["one.invalid"]},{"type":"logical","mode":"and","rules":[{"port":[80]},{"domain_keyword":["owned"],"invert":true}]}]}]}`},
  {"full-ip-spaces",1,`{"version":3,"rules":[{"ip_cidr":["0.0.0.0/0","::/0"]}]}`},
  {"underlay",3,`{"version":3,"rules":[{"network_type":["wifi","cellular","ethernet","other"],"network_is_expensive":true,"network_is_constrained":true}]}`},
  {"interface-addresses",4,`{"version":4,"rules":[{"network_interface_address":{"wifi":["192.0.2.7/24"],"ethernet":["2001:db8::1/64"]},"default_interface_address":["198.51.100.2/32"]}]}`},
  {"package-regex",5,`{"version":5,"rules":[{"package_name_regex":["^org\\.example\\."],"process_path_regex":["^/owned/"]}]}`},
 }
 for _,entry:=range cases{for version:=entry.minimum;version<=5;version++{
  var source option.PlainRuleSetCompat;check(json.Unmarshal([]byte(entry.source),&source));var binary bytes.Buffer;check(srs.Write(&binary,source.Options,version));decoded,err:=srs.Read(bytes.NewReader(binary.Bytes()),true);check(err)
  rules:=[]map[string]any{};for _,rule:=range decoded.Options.Rules{rules=append(rules,expected(rule))}
  rows=append(rows,map[string]any{"name":fmt.Sprintf("%s-v%d",entry.name,version),"binary":base64.StdEncoding.EncodeToString(binary.Bytes()),"expected":map[string]any{"version":version,"rules":rules}})
 }}
 for version:=uint8(2);version<=5;version++{rule:=option.HeadlessRule{Type:"default",DefaultOptions:option.DefaultHeadlessRule{AdGuardDomain:[]string{"||example.org^","|exact.example^","keyword","||ads.*"}}};var binary bytes.Buffer;check(srs.Write(&binary,option.PlainRuleSet{Rules:[]option.HeadlessRule{rule}},version));decoded,err:=srs.Read(bytes.NewReader(binary.Bytes()),true);check(err);rows=append(rows,map[string]any{"name":fmt.Sprintf("adguard-v%d",version),"binary":base64.StdEncoding.EncodeToString(binary.Bytes()),"expected":map[string]any{"version":version,"rules":[]map[string]any{expected(decoded.Options.Rules[0])}}})}
 data,err:=json.MarshalIndent(rows,"","  ");check(err);check(os.WriteFile("reference.json",data,0600));fmt.Println("reference cases",len(rows))
}
