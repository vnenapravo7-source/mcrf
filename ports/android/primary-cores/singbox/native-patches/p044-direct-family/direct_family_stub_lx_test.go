//go:build !with_mcrf_direct_family

package route

import (
	"github.com/sagernet/sing-box/adapter"
	"testing"
)

type mcrfStubFamilyOutbound struct{ adapter.Outbound }

func TestMcrfDirectFamilyStubPreservesOriginal(t *testing.T) {
	var router *Router
	outbound := &mcrfStubFamilyOutbound{}
	if router.mcrfDirectFamilyDialer(outbound, nil, adapter.InboundContext{}) != outbound {
		t.Fatal("disabled feature changed original dialer")
	}
}
