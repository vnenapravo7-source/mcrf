// SPDX-License-Identifier: GPL-3.0-or-later
package mobile
import "github.com/p1neappleXpress/OpenFlux/transport"
func mcrf016ConfigureHandshake(s *transport.Session,specs []sessionSpec)bool{
 for _,spec:=range specs{if spec.Type=="fluxfast"{return s.McrfAndroidFluxFastHandshake()}};return false
}
