package mcrfdiag
const (
 SessHeaderInvalid Event = v2EventCount+iota
 SessDirectionInvalid
 SessDecryptFailed
 SessDecryptOK
 SessBatchInvalid
 SessEnvelopeInvalid
 SessRoleInvalid
 SessHelloAccepted
 SessHelloRejected
 SessCallbackMissing
 SessReplayRejected
 v3EventCount
)
const PrefixV3="MCRF_PROVIDER_STAGE_V3 "
var namesV3=[]string{"sess_hdr_bad","sess_dir_bad","sess_dec_fail","sess_dec_ok","sess_batch_bad","sess_env_bad","sess_role_bad","sess_hello_ok","sess_hello_bad","sess_cb_missing","sess_replay"}
func BeginV3()*Run{r:=&Run{version:3};current.Store(r);return r}
