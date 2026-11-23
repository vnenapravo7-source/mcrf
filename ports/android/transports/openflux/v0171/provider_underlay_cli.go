package main

import "github.com/p1neappleXpress/OpenFlux/netbind"

func init() {
	configurePhysicalNetwork = netbind.McrfConfigurePhysicalNetwork
}
