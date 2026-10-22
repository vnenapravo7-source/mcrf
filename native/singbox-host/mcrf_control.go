package main

import (
 "bufio"
 "os"
 "sync"
)

// Private inherited stdin, never a globally reachable control endpoint.
var mcrfStdinControl bool
var mcrfStop = make(chan struct{})
var mcrfControlOnce sync.Once

func init() { commandRun.Flags().BoolVar(&mcrfStdinControl, "mcrf-stdin-control", false, "Stop owned service on private stdin STOP or EOF") }
func startMcrfControl() {
 if !mcrfStdinControl { return }
 mcrfControlOnce.Do(func(){go func(){
  scanner:=bufio.NewScanner(os.Stdin)
  scanner.Buffer(make([]byte,64),1024)
  for scanner.Scan(){if scanner.Text()=="STOP"{break}}
  close(mcrfStop)
 }()})
}
