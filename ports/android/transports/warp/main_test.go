package main
import("context";"testing";"time")
func TestMcrfRejectInputsBeforeNetwork(t *testing.T){
    for _,input:=range []request{{Operation:"shell",Protocol:"wg",Country:"any"},{Operation:"scan",Protocol:"masque",Country:"any",Account:"12345678901234567890123456789012"},{Operation:"scan",Protocol:"wg",Country:"RU;curl x",Account:"12345678901234567890123456789012"},{Operation:"scan",Protocol:"awg",Country:"foreign",Account:""}}{if validate(input)==nil{t.Fatal("Invalid request accepted")}}
    if validate(request{Operation:"register",Protocol:"auto",Country:"foreign"})!=nil{t.Fatal("Registration options refused")}
}
func TestMcrfCancelledScan(t *testing.T){
    ctx,cancel:=context.WithCancel(context.Background());cancel();start:=time.Now()
    _,err:=run(ctx,request{Operation:"scan",Protocol:"auto",Country:"foreign",Account:"12345678901234567890123456789012"},nil)
    if err==nil||time.Since(start)>time.Second{t.Fatal("Cancelled scan started network work")}
}
