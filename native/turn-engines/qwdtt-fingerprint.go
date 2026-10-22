// SPDX-License-Identifier: GPL-3.0-or-later
package main
import (
 "encoding/json"
 "flag"
 "fmt"
 "os"
 "github.com/bogdanfinn/tls-client/profiles"
)
var mcrfFingerprint=flag.String("fingerprint","chrome","MCRF_TURN_OPTIONS_V1: chrome/firefox/safari")
func mcrfSetFingerprint(){
 switch *mcrfFingerprint {case "chrome":case "firefox":vkCallsProfile=Profile{UserAgent:"Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:148.0) Gecko/20100101 Firefox/148.0"};case "safari":vkCallsProfile=Profile{UserAgent:"Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Safari/605.1.15"};default:fmt.Fprintln(os.Stderr,"MCRF_TURN_OPTIONS_V1: unsupported fingerprint");os.Exit(2)}
}
func mcrfTLSProfile()profiles.ClientProfile{switch *mcrfFingerprint{case "firefox":return profiles.Firefox_148;case "safari":return profiles.Safari_16_0;default:return profiles.Chrome_146}}
func mcrfValidateOnly(values map[string]interface{}) {if os.Getenv("MCRF_VALIDATE_ONLY")=="1"{encoded,_:=json.Marshal(values);fmt.Printf("MCRF_CONFIG_PARSED|%s\n",encoded);os.Exit(0)}}
