package mailru
import("strings";"testing";"net/http";"net/http/httptest";"github.com/gorilla/websocket";"github.com/p1neappleXpress/OpenFlux/mcrfdiag";"github.com/p1neappleXpress/OpenFlux/transport")
func TestMcrfDiagnosticAck(t *testing.T){
 r:=mcrfdiag.Begin();srv:=httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter,q *http.Request){c,e:=(&websocket.Upgrader{CheckOrigin:func(*http.Request)bool{return true}}).Upgrade(w,q,nil);if e==nil{defer c.Close();c.ReadMessage()}}));defer srv.Close()
 conn,_,err:=websocket.DefaultDialer.Dial("ws"+strings.TrimPrefix(srv.URL,"http"),nil);if err!=nil{t.Fatal(err)}
 session:=&DocSession{Conn:conn,ready:make(chan struct{}),diag:r};tr:=&MailruDocsTransport{};tr.handleMessage(session,[]byte(`42["message",{"type":"authChanges"}]`));conn.Close();tr.handleMessage(session,[]byte(`42["message",{"type":"authChanges"}]`));mcrfDiagnosticFallback(session)
 line:=r.Finish();for _,key:=range []string{"mailru_auth_changes_seen","mailru_ack_write_ok","mailru_ack_write_failed","mailru_fallback_ready"}{if !strings.Contains(line,`"`+key+`":true`){t.Fatal("missing ACK hook",key)}}
}
func TestMcrfDiagnosticChatEcho(t *testing.T){r:=mcrfdiag.Begin();session:=&DocSession{diag:r};session.Info.EditorUserID="own";tr:=NewMailruDocsTransport("",transport.TransportConfig{});tr.handleMessage(session,[]byte(`42["message",{"type":"message","messages":[{"message":"OFX1YWJj","useridoriginal":"own"}]}]`));line:=r.Finish();if strings.Contains(line,`"mailru_peer_chat_decoded":true`){t.Fatal("echo counted")}
 r=mcrfdiag.Begin();session.diag=r;tr.handleMessage(session,[]byte(`42["message",{"type":"message","messages":[{"message":"OFX1YWJj","useridoriginal":"peer"}]}]`));if !strings.Contains(r.Finish(),`"mailru_peer_chat_decoded":true`){t.Fatal("peer omitted")}}
