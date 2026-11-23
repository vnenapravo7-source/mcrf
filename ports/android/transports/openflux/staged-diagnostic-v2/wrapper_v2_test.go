package main
import("errors";"strings";"testing";"github.com/p1neappleXpress/OpenFlux/mcrfdiag")
func TestV2StopAndCrash(t *testing.T){stopped:=false;emits:=0;want:=errors.New("fixture")
 err:=mcrfDiagnosticOperationV2(func()error{defer func(){stopped=true;mcrfdiag.Current().Mark(mcrfdiag.DemuxEmpty)}();return want},func(line string){emits++;if !stopped||!strings.HasPrefix(line,mcrfdiag.PrefixV2){t.Fatal("ordering/schema")}});if err!=want||emits!=1{t.Fatal("error/lifecycle")}
 func(){defer func(){recover()}();mcrfDiagnosticOperationV2(func()error{panic("fixture")},func(string){emits++})}();if emits!=1{t.Fatal("crash reported")};mcrfdiag.Current().Finish()
}
