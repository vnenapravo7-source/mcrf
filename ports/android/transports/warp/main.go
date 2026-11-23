// Private-file adapter around the upstream mobile scan/registration backend.
package main

import(
    "bufio";"context";"encoding/json";"errors";"flag";"fmt";"io";"os";"path/filepath";"sort";"time"
    "github.com/vernette/warpscout/core"
    "github.com/vernette/warpscout/internal/warpscout"
)
type request struct {Operation string `json:"operation"`;Account string `json:"account"`;Protocol string `json:"protocol"`;Country string `json:"country"`;ExcludeDME bool `json:"excludeDme"`;Extended bool `json:"extended"`}
type candidate struct {Config string `json:"config"`;Result core.EndpointResult `json:"result"`;Protocol string `json:"protocol"`}
type result struct {Account string `json:"account,omitempty"`;Candidates []candidate `json:"candidates,omitempty"`;Error string `json:"error,omitempty"`}
func validate(input request)error {
    if input.Operation!="register"&&input.Operation!="scan"{return errors.New("invalid_operation")}
    if input.Protocol!="wg"&&input.Protocol!="awg"&&input.Protocol!="auto"{return errors.New("invalid_protocol")}
    if input.Country!="any"&&input.Country!="foreign"&&(len(input.Country)!=2||input.Country[0]<'A'||input.Country[0]>'Z'||input.Country[1]<'A'||input.Country[1]>'Z'){return errors.New("invalid_country")}
    if input.Operation=="scan"&&(len(input.Account)<32||len(input.Account)>32768){return errors.New("invalid_account")}
    return nil
}
func run(ctx context.Context,input request,progress core.EventSink)(result,error) {
    if err:=validate(input);err!=nil{return result{},err}
    engine:=core.New(warpscout.NewMobileBackend())
    if input.Operation=="register" {
        account,err:=engine.Register(ctx,core.RegisterOptions{Relay:"none",TimeoutSec:4,Fresh:false,IPv6:false},progress)
        return result{Account:account.RawJSON},err
    }
    protocols:=[]string{input.Protocol};if input.Protocol=="auto"{protocols=[]string{"awg","wg"}}
    account:=core.Account{RawJSON:input.Account};all:=[]candidate{}
    for _,protocol:=range protocols {
        if ctx.Err()!=nil{return result{},ctx.Err()}
        options:=core.ScanOptions{Protocol:core.Protocol(protocol),TimeoutSec:2,Jobs:6,SamplePerSubnet:2,TunnelPingCount:10,MTU:1280,Port:2408,BestBy:"ping"}
        if input.Extended{options.Port=0;options.SamplePerSubnet=3}
        if protocol=="awg" {chain,err:=warpscout.McrfQuicI1();if err!=nil{return result{},err};options.AWGI1=chain}
        if input.Country=="foreign"{options.ExcludeCountries=[]string{"RU"}}else if input.Country!="any"{options.IncludeCountries=[]string{input.Country}}
        if input.ExcludeDME{options.ExcludeNodes=[]string{"DME"}}
        budget:=2;if input.Extended{budget=8}
        report,err:=engine.Scan(warpscout.McrfCandidateBudget(ctx,budget),account,options,progress)
        if err!=nil {if ctx.Err()!=nil{return result{},ctx.Err()};continue}
        sort.SliceStable(report.Results,func(i,j int)bool{return report.Results[i].TunnelPingMS<report.Results[j].TunnelPingMS})
        format:=core.ConfigWireGuard;if protocol=="awg"{format=core.ConfigAmneziaWG}
        for _,endpoint:=range report.Results {
            if !endpoint.Working||endpoint.LossPercent!=0 {continue}
            text,err:=engine.RenderConfig(account,endpoint,options,format);if err!=nil{continue}
            all=append(all,candidate{Config:text,Result:endpoint,Protocol:protocol});if len(all)>=budget{break}
        }
        if len(all)>0{break}
    }
    if len(all)==0{return result{},errors.New("no_endpoints")}
    return result{Candidates:all},nil
}
func main() {
    inputPath:=flag.String("request","","Private request file");flag.Parse()
    if *inputPath==""||flag.NArg()!=0{os.Exit(2)}
    bytes,err:=os.ReadFile(*inputPath);if err!=nil||len(bytes)>65536{os.Exit(2)}
    var input request;if json.Unmarshal(bytes,&input)!=nil||validate(input)!=nil{os.Exit(2)}
    if err=warpscout.McrfBindNetwork();err!=nil{os.Exit(3)}
    output:=os.Stdout
    // Silence backend diagnostics. Progress is a bounded structured enum, never raw logs.
    null,err:=os.OpenFile(os.DevNull,os.O_WRONLY,0);if err!=nil{os.Exit(1)};defer null.Close();os.Stdout=null;os.Stderr=null
    ctx,cancel:=context.WithTimeout(context.Background(),5*time.Minute);defer cancel()
    go func(){scanner:=bufio.NewScanner(os.Stdin);scanner.Buffer(make([]byte,128),1024);for scanner.Scan(){if scanner.Text()=="STOP"{break}};cancel()}()
    progress:=func(event core.ProgressEvent){data,_:=json.Marshal(struct{Phase string `json:"phase"`;Completed int `json:"completed"`;Total int `json:"total"`}{event.Phase,event.Completed,event.Total});fmt.Fprintf(output,"MCRF_PROGRESS %s\n",data)}
    value,err:=run(ctx,input,progress)
    if err!=nil{value=result{Error:"operation_failed"};if ctx.Err()!=nil{value.Error="cancelled"}else if typed,ok:=err.(*core.CoreError);ok{value.Error=typed.Code}else if err.Error()=="no_endpoints"{value.Error="no_endpoints"}}
    data,encodeError:=json.Marshal(value);if encodeError!=nil||len(data)>1024*1024{os.Exit(1)}
    file,err:=os.OpenFile(filepath.Join(filepath.Dir(*inputPath),"result.json"),os.O_WRONLY|os.O_CREATE|os.O_EXCL,0600);if err!=nil{os.Exit(1)}
    _,err=file.Write(data);if err==nil{err=file.Sync()};file.Close();if err!=nil{os.Exit(1)}
    io.WriteString(output,"MCRF_COMPLETE\n")
}
