package mcrfdiag
const (
 RawCallbackMissing Event = eventCount+iota
 DemuxEmpty
 DemuxSession
 DemuxClassic
 LayerCallbackMissing
 CodecUnknown
 CodecDecodeFailed
 CodecNoPayload
 ClassicHeaderInvalid
 ClassicDirectionInvalid
 ClassicReplayRejected
 v2EventCount
)
const PrefixV2="MCRF_PROVIDER_STAGE_V2 "
var namesV2=[]string{"raw_callback_missing","demux_empty","demux_session","demux_classic","layer_callback_missing","codec_unknown","codec_decode_failed","codec_no_payload","classic_header_invalid","classic_direction_invalid","classic_replay_rejected"}
func BeginV2()*Run{r:=&Run{version:2};current.Store(r);return r}
