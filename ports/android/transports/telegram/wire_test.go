package mtproto

import("bytes";"crypto/aes";"crypto/cipher";"encoding/binary";"testing")

// Match TDLib TcpTransport.cpp, not the upstream tests' formerly truncated CTR.
func TestMcrfFullObfuscatedHeader(t *testing.T) {
    for _,dc:=range []int16{1,2,3,4,5,-4,203} {
        header:=make([]byte,64);for i:=range header {header[i]=byte(i+8)}
        binary.LittleEndian.PutUint32(header[56:],0xeeeeeeee)
        binary.LittleEndian.PutUint16(header[60:],uint16(dc))
        block,_:=aes.NewCipher(header[8:40]);stream:=cipher.NewCTR(block,header[40:56])
        encrypted:=make([]byte,64);stream.XORKeyStream(encrypted,header)
        copy(header[56:],encrypted[56:])
        original:=append([]byte(nil),header...)
        got:=ExtractDCFromInit(header)
        want:=int(dc);if want<0{want=-want}
        if !got.Valid || got.DC!=want || got.IsMedia!=(dc<0) {t.Fatalf("Header mismatch for %d: %+v",dc,got)}
        patched,ok:=PatchInitDC(header,2)
        after:=ExtractDCFromInit(patched)
        if !ok || !after.Valid || after.DC!=2 || after.IsMedia || !bytes.Equal(original[:60],patched[:60]) || !bytes.Equal(original[62:],patched[62:]) {t.Fatal("DC patch changed transport or nonce")}
    }
}
