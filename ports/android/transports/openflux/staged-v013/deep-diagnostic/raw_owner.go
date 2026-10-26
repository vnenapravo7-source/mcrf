// SPDX-License-Identifier: GPL-3.0-or-later
// UNWIRED transport overlay; requires exact source hook recipe. No global owner.
package transport

import "github.com/p1neappleXpress/OpenFlux/mcrfdiag"

// Bind before provider Start. A reused provider object cannot acquire another
// run, even after Stop. Late callbacks still observe only the original owner.
// Group/wrapper propagation is a separate, still-unimplemented requirement.
func (base *BaseTransport) McrfBindDiagnostic(owner *mcrfdiag.Run) bool {
    if base == nil || owner == nil || base.IsRunning() { return false }
    current := base.mcrfDiagnostic.Load()
    return current == owner || current == nil && base.mcrfDiagnostic.CompareAndSwap(nil, owner)
}

// Snapshot only; a DocSession captures this pointer once, including rotations.
func (base *BaseTransport) McrfDiagnosticOwner() *mcrfdiag.Run {
    if base == nil { return nil }
    return base.mcrfDiagnostic.Load()
}
