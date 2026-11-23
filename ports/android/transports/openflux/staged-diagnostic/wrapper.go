package main
import (
 "fmt"
 "github.com/p1neappleXpress/OpenFlux/mcrfdiag"
)
// operation returns only after run's deferred StopProxy. Panic intentionally emits no snapshot.
func mcrfDiagnosticOperation(operation func() error, emit func(string)) error {
 recorder:=mcrfdiag.Begin()
 err:=operation()
 emit(recorder.Finish())
 return err
}
func mcrfRunDiagnostic(path string) error {
 return mcrfDiagnosticOperation(func()error{return run(path)},func(line string){fmt.Println(line)})
}
