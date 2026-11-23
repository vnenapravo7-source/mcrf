package main
import("fmt";"github.com/p1neappleXpress/OpenFlux/mcrfdiag")
func mcrfDiagnosticOperationV3(operation func()error,emit func(string))error{
 r:=mcrfdiag.BeginV3();err:=operation();emit(r.Finish());return err
}
func mcrfRunDiagnosticV3(path string)error{return mcrfDiagnosticOperationV3(func()error{return run(path)},func(line string){fmt.Println(line)})}
