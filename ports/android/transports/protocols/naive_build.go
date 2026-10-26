//go:build with_naive_outbound && cgo && !with_purego

// SPDX-License-Identifier: GPL-3.0-or-later
package main

import _ "github.com/sagernet/cronet-go/all"

// No alternative definition: accidentally omitting genuine statically linked
// Cronet support must fail the producer, not advertise an HTTP approximation.
func requireNaiveBuild() {}
