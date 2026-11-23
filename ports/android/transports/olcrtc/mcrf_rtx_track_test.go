package jitsi

import (
	"net"
	"testing"
	"time"

	"github.com/pion/rtp"
	"github.com/pion/webrtc/v4"
)

func TestMcrfOlcRtxBeforePrimaryVideo(t *testing.T) {
	var settings webrtc.SettingEngine
	settings.SetIncludeLoopbackCandidate(true)
	settings.SetIPFilter(func(address net.IP) bool { return address.IsLoopback() })
	media := &webrtc.MediaEngine{}
	for _, codec := range []webrtc.RTPCodecParameters{
		{RTPCodecCapability: webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeVP8, ClockRate: 90000}, PayloadType: 96},
		{RTPCodecCapability: webrtc.RTPCodecCapability{MimeType: webrtc.MimeTypeRTX, ClockRate: 90000, SDPFmtpLine: "apt=96"}, PayloadType: 97},
	} {
		if err := media.RegisterCodec(codec, webrtc.RTPCodecTypeVideo); err != nil {
			t.Fatal(err)
		}
	}
	api := webrtc.NewAPI(webrtc.WithSettingEngine(settings), webrtc.WithMediaEngine(media))
	sender, err := api.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = sender.Close() })
	receiver, err := api.NewPeerConnection(webrtc.Configuration{})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { _ = receiver.Close() })

	add := func(mime, name string) *webrtc.TrackLocalStaticRTP {
		t.Helper()
		codec := webrtc.RTPCodecCapability{MimeType: mime, ClockRate: 90000}
		if mime == webrtc.MimeTypeRTX {
			codec.SDPFmtpLine = "apt=96"
		}
		track, err := webrtc.NewTrackLocalStaticRTP(codec, name, "owned")
		if err != nil {
			t.Fatal(err)
		}
		if _, err = sender.AddTrack(track); err != nil {
			t.Fatal(err)
		}
		return track
	}
	rtx := add(webrtc.MimeTypeRTX, "rtx")
	primary := add(webrtc.MimeTypeVP8, "primary")

	var session Session
	dispatched := make(chan string, 2)
	seen := make(chan string, 2)
	session.SetVideoTrackHandler(func(track *webrtc.TrackRemote, _ *webrtc.RTPReceiver) {
		dispatched <- track.Codec().MimeType
	})
	receiver.OnTrack(func(track *webrtc.TrackRemote, r *webrtc.RTPReceiver) {
		session.handleRemoteTrack(track, r)
		seen <- track.Codec().MimeType
	})

	offer, err := sender.CreateOffer(nil)
	if err != nil {
		t.Fatal(err)
	}
	applyLocal(t, sender, offer)
	if err = receiver.SetRemoteDescription(*sender.LocalDescription()); err != nil {
		t.Fatal(err)
	}
	answer, err := receiver.CreateAnswer(nil)
	if err != nil {
		t.Fatal(err)
	}
	applyLocal(t, receiver, answer)
	if err = sender.SetRemoteDescription(*receiver.LocalDescription()); err != nil {
		t.Fatal(err)
	}
	writeUntilSeen := func(track *webrtc.TrackLocalStaticRTP, mime string) {
		t.Helper()
		deadline := time.After(5 * time.Second)
		tick := time.NewTicker(20 * time.Millisecond)
		defer tick.Stop()
		for seq := uint16(1); ; seq++ {
			select {
			case got := <-seen:
				if got != mime {
					t.Fatalf("OnTrack order = %q, want %q", got, mime)
				}
				return
			case <-deadline:
				t.Fatalf("no OnTrack callback for %s", mime)
			case <-tick.C:
				if err := track.WriteRTP(&rtp.Packet{Header: rtp.Header{Version: 2, Marker: true, SequenceNumber: seq, Timestamp: uint32(seq) * 3000}, Payload: []byte{0x10, 0x00, 0x01}}); err != nil {
					t.Fatal(err)
				}
			}
		}
	}
	writeUntilSeen(rtx, webrtc.MimeTypeRTX)
	writeUntilSeen(primary, webrtc.MimeTypeVP8)
	select {
	case got := <-dispatched:
		if got != webrtc.MimeTypeVP8 {
			t.Fatalf("dispatched %s, want primary VP8", got)
		}
	default:
		t.Fatal("primary track was not dispatched")
	}
}

func applyLocal(t *testing.T, pc *webrtc.PeerConnection, desc webrtc.SessionDescription) {
	t.Helper()
	gathered := webrtc.GatheringCompletePromise(pc)
	if err := pc.SetLocalDescription(desc); err != nil {
		t.Fatal(err)
	}
	select {
	case <-gathered:
	case <-time.After(5 * time.Second):
		t.Fatal("ICE gathering timed out")
	}
}
