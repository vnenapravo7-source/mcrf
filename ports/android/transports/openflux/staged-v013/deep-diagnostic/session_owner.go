// SPDX-License-Identifier: GPL-3.0-or-later
// Future disposable fresh transport overlay; requires session-hook-recipe.json.
// UNWIRED, not compiled/copied/producer-bound. Never copy into mcrfdiag package.
package transport

import (
    "errors"
    "github.com/p1neappleXpress/OpenFlux/mcrfdiag"
)

// Owner is installed on a new unpublished Session, before AddTransport/start.
// No setter exists on a running Session or retained encryption subscription.
// Default NewSession remains unobserved; nil marks are noops, not health proof.
func McrfNewDiagnosticSession(parameters PeerParameters, exit bool, owner *mcrfdiag.Run) (*Session, error) {
    if owner == nil { return nil, errors.New("diagnostic owner required") }
    session, err := NewSession(parameters, exit)
    if err != nil { return nil, err }
    session.mcrfDiagnostic = owner
    return session, nil
}

// Existing upstream unit paths can construct a link without encryption. Keep
// those unobserved, never turn a diagnostic hook into a nil-pointer failure.
func mcrfSessionDiagnostic(link *transportLink) *mcrfdiag.Run {
    if link == nil || link.encrypted == nil { return nil }
    return link.encrypted.mcrfSessionDiagnostic.Load()
}
func mcrfClassicDiagnostic(link *transportLink) *mcrfdiag.Run {
    if link == nil || link.classicEnc == nil { return nil }
    return link.classicEnc.mcrfClassicDiagnostic.Load()
}
