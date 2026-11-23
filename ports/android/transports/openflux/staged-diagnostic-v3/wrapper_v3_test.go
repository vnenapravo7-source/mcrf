package main
import("strings";"testing";"github.com/p1neappleXpress/OpenFlux/mcrfdiag")
func TestV3StopAndCrash(t *testing.T){stopped:=false;emits:=0;mcrfDiagnosticOperationV3(func()error{defer func(){stopped=true}();return nil},func(line string){emits++;if !stopped||!strings.HasPrefix(line,mcrfdiag.PrefixV3){t.Fatal("before stop")}});func(){defer func(){recover()}();mcrfDiagnosticOperationV3(func()error{panic("fixture")},func(string){emits++})}();if emits!=1{t.Fatal("duplicate/crash snapshot")};mcrfdiag.Current().Finish()}
