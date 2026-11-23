package warpscout

import("context";"fmt";"sync";"testing")
func TestMcrfBudgetRequiresDurableKnownForeignNonDme(t *testing.T){
    ctx:=McrfCandidateBudget(context.Background(),2)
    opts:=options{dropCountries:[]string{"RU"},dropColos:[]string{"DME"}}
    run:=protoRun{kindAWG,"awg"}
    good:=endpointResult{endpoint:"1.1.1.1:2408",ok:true,durable:true,measured:true,exit:metaResult{colo:"AMS",coloISO:"NL"}}
    for _,change:=range []func(*endpointResult){func(r *endpointResult){r.durable=false},func(r *endpointResult){r.loss=.1},func(r *endpointResult){r.measured=false},func(r *endpointResult){r.exit.coloISO="RU"},func(r *endpointResult){r.exit.colo="DME"},func(r *endpointResult){r.exit.coloISO=""},func(r *endpointResult){r.exit.colo=""}}{
        bad:=good;change(&bad);mcrfAcceptCandidate(ctx,run,bad,opts)
    }
    if mcrfScanComplete(ctx){t.Fatal("unverified endpoints consumed budget")}
    mcrfAcceptCandidate(ctx,run,good,opts);mcrfAcceptCandidate(ctx,run,good,opts)
    if mcrfScanComplete(ctx){t.Fatal("same endpoint counted twice")}
    good.endpoint="2.2.2.2:2408";mcrfAcceptCandidate(ctx,run,good,opts)
    if !mcrfScanComplete(ctx){t.Fatal("valid candidates did not end pending work")}
    if mcrfScanComplete(McrfCandidateBudget(context.Background(),2)){t.Fatal("budget leaked into another scan")}
    if mcrfScanComplete(context.Background()){t.Fatal("normal upstream scan was limited")}
}
func TestMcrfBudgetConcurrentAndCancelled(t *testing.T){
    ctx:=McrfCandidateBudget(context.Background(),8);var group sync.WaitGroup
    for i:=0;i<32;i++{group.Add(1);go func(i int){defer group.Done();mcrfAcceptCandidate(ctx,protoRun{kindWG,"wg"},endpointResult{endpoint:fmt.Sprint(i),ok:true,durable:true,measured:true,exit:metaResult{colo:"AMS",coloISO:"NL"}},options{});_ = mcrfScanComplete(ctx)}(i)}
    group.Wait();if !mcrfScanComplete(ctx){t.Fatal("concurrent budget failed")}
    stopped,cancel:=context.WithCancel(context.Background());cancel();if !mcrfScanComplete(stopped){t.Fatal("cancelled work started another probe")}
}
