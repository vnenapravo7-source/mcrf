package main
import("fmt";"github.com/p1neappleXpress/OpenFlux/mcrfdiag")
func mcrfDiagnosticOperationV2(operation func()error,emit func(string))error{
 recorder:=mcrfdiag.BeginV2();err:=operation();emit(recorder.Finish());return err
}
func mcrfRunDiagnosticV2(path string)error{
 return mcrfDiagnosticOperationV2(func()error{return run(path)},func(line string){fmt.Println(line)})
}
