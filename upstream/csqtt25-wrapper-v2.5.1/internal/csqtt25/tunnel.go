package csqtt25

import (
	"errors"
	"fmt"
	"net/netip"
	"regexp"
	"strconv"
	"strings"
)

// swg-panel's client permits a fifth field after the TUNCONF prefix (an
// optional policy blob / workers / padding). The legacy bridge accepted only
// four. Never execute or apply remote app/domain policies: MCRF owns routing.
var tunnelFrame=regexp.MustCompile(`^TUNCONF:([^:]+):(.+):([0-9]{1,5}):([^:\r\n]{1,128})(?::([^\r\n]*))?$`)

func NormalizeTunnel(raw string)(string,error){
	invalid:=errors.New("invalid CSQTT 2.5 tunnel frame: expected address, DNS, UDP port, revision and optional policy")
	if len(raw)>65536||strings.ContainsAny(raw,"\r\n\x00"){return "",invalid}
	parts:=tunnelFrame.FindStringSubmatch(strings.TrimSpace(raw));if parts==nil{return "",invalid}
	address,err:=netip.ParseAddr(strings.TrimSpace(parts[1]));if err!=nil||!address.Is4()||address.IsUnspecified(){return "",errors.New("invalid CSQTT 2.5 tunnel client IPv4 address")}
	port,err:=strconv.Atoi(parts[3]);if err!=nil||port>65535{return "",errors.New("invalid CSQTT 2.5 tunnel UDP port")}
	if strings.TrimSpace(parts[4])=="" {return "",errors.New("invalid CSQTT 2.5 tunnel revision")}
	var dns []string
	for _,item:=range strings.Split(parts[2],","){
		ip,err:=netip.ParseAddr(strings.Trim(strings.TrimSpace(item),"[]"));if err!=nil||ip.IsUnspecified(){return "",errors.New("invalid CSQTT 2.5 tunnel DNS address")}
		dns=append(dns,ip.String())
	}
	if len(dns)==0||len(dns)>16{return "",errors.New("invalid CSQTT 2.5 tunnel DNS count")}
	return fmt.Sprintf("TUNCONF:%s:%s:%d:%s",address,strings.Join(dns,","),port,parts[4]),nil
}
