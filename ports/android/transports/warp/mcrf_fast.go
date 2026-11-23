package warpscout

import (
    "context"
    "sync"
)

// A per-scan budget: never cancels active tunnels or weakens their durability checks.
// Only this Android CLI enables it; the normal upstream backend remains exhaustive.
type mcrfBudgetKey struct{}
type mcrfBudget struct { mu sync.Mutex; limit int; accepted map[string]struct{} }
func McrfCandidateBudget(ctx context.Context, limit int) context.Context {
    if limit < 1 || limit > 8 { panic("invalid candidate budget") }
    return context.WithValue(ctx,mcrfBudgetKey{},&mcrfBudget{limit:limit,accepted:make(map[string]struct{})})
}
func mcrfScanComplete(ctx context.Context) bool {
    if ctx.Err()!=nil {return true}
    budget,_:=ctx.Value(mcrfBudgetKey{}).(*mcrfBudget)
    if budget==nil{return false}
    budget.mu.Lock();defer budget.mu.Unlock()
    return len(budget.accepted)>=budget.limit
}
func mcrfAcceptCandidate(ctx context.Context, run protoRun, result endpointResult, opts options) {
    budget,_:=ctx.Value(mcrfBudgetKey{}).(*mcrfBudget)
    if budget==nil || !result.ok || !result.durable || !result.measured || result.loss!=0 || result.endpoint=="" || result.exit.coloISO=="" || result.exit.colo=="" {return}
    // Reuse the SAME country/node filters as the final report, not bare UDP reachability.
    if len(applyFilters(phaseResult{run,[]endpointResult{result}},opts).results)!=1{return}
    budget.mu.Lock();defer budget.mu.Unlock()
    budget.accepted[result.endpoint]=struct{}{}
}
