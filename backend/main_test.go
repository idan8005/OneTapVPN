package main

import (
	"bytes"
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

var testKey = bytes.Repeat([]byte("k"), 32)
func newTestAPI(t *testing.T) *api {
	t.Helper()
	return &api{adminToken:[]byte(strings.Repeat("a",32)), signingKey:testKey,
		store:serverStore{filepath.Join(t.TempDir(),"catalog.enc"),testKey},
		now:func() time.Time{return time.Date(2026,11,21,12,0,0,0,time.UTC)}}
}
func TestTokenSignatureTamperAndExpiry(t *testing.T) {
	now:=time.Date(2026,11,21,12,0,0,0,time.UTC)
	token,err:=signToken(testKey,Claims{"user-1","premium",now.Add(time.Hour).Unix()}); if err!=nil{t.Fatal(err)}
	claims,err:=verifyToken(testKey,token,now); if err!=nil||claims.Subject!="user-1"||claims.Plan!="premium"{t.Fatalf("valid token rejected: %+v %v",claims,err)}
	if _,err:=verifyToken(testKey,token+"x",now);err==nil{t.Fatal("tampered token accepted")}
	if _,err:=verifyToken(testKey,token,now.Add(2*time.Hour));err==nil{t.Fatal("expired token accepted")}
}
func TestEncryptedCatalogRoundTripAndWrongKey(t *testing.T) {
	store:=serverStore{filepath.Join(t.TempDir(),"catalog.enc"),testKey}
	want:=[]Server{{ID:"eu-1",Name:"Europe",Country:"Germany",Region:"EU",Host:"vpn.example.net",Port:443,Protocol:"vless-reality",Config:"vless://secret",Enabled:true}}
	if err:=store.write(want);err!=nil{t.Fatal(err)}
	got,err:=store.read();if err!=nil||len(got)!=1||got[0].Config!=want[0].Config{t.Fatalf("round trip failed: %+v %v",got,err)}
	wrong:=serverStore{store.path,bytes.Repeat([]byte("z"),32)}
	if _,err:=wrong.read();err==nil{t.Fatal("wrong encryption key accepted")}
}
func TestFreeCatalogNeverReceivesPremiumNodes(t *testing.T) {
	a:=newTestAPI(t)
	if err:=a.store.write([]Server{{ID:"eu-1",Name:"Europe",Country:"Germany",Region:"EU",Host:"vpn.example.net",Port:443,Protocol:"vless-reality",Config:"vless://secret",Enabled:true}});err!=nil{t.Fatal(err)}
	token,_:=signToken(a.signingKey,Claims{"free-user","free",a.now().Add(time.Hour).Unix()})
	req:=httptest.NewRequest(http.MethodGet,"/api/v1/catalog",nil);req.Header.Set("Authorization","Bearer "+token)
	rec:=httptest.NewRecorder();a.routes().ServeHTTP(rec,req)
	var out catalogResponse;if err:=json.Unmarshal(rec.Body.Bytes(),&out);err!=nil{t.Fatal(err)}
	if rec.Code!=http.StatusOK||len(out.PremiumServers)!=0||!out.PublicDiscovery{t.Fatalf("free catalog leaked premium node: %d %+v",rec.Code,out)}
}
func TestPremiumCatalogReturnsEnabledServersOnly(t *testing.T) {
	a:=newTestAPI(t)
	nodes:=[]Server{
		{ID:"eu-1",Name:"Europe 1",Country:"Germany",Region:"EU",Host:"vpn.example.net",Port:443,Protocol:"vless-reality",Config:"vless://secret",Enabled:true},
		{ID:"eu-2",Name:"Europe 2",Country:"Germany",Region:"EU",Host:"vpn2.example.net",Port:443,Protocol:"vless-reality",Config:"vless://disabled",Enabled:false},
	}
	if err:=a.store.write(nodes);err!=nil{t.Fatal(err)}
	token,_:=signToken(a.signingKey,Claims{"paid-user","premium",a.now().Add(time.Hour).Unix()})
	req:=httptest.NewRequest(http.MethodGet,"/api/v1/catalog",nil);req.Header.Set("Authorization","Bearer "+token)
	rec:=httptest.NewRecorder();a.routes().ServeHTTP(rec,req)
	var out catalogResponse;if err:=json.Unmarshal(rec.Body.Bytes(),&out);err!=nil{t.Fatal(err)}
	if rec.Code!=http.StatusOK||len(out.PremiumServers)!=1||out.PremiumServers[0].ID!="eu-1"{t.Fatalf("unexpected response: %d %+v",rec.Code,out)}
}
func TestAdminAuthenticationAndWireGuardRejection(t *testing.T) {
	a:=newTestAPI(t)
	body,_:=json.Marshal(serverRequest{Servers:[]Server{{ID:"x1",Name:"Node",Country:"Germany",Region:"EU",Host:"vpn.example.net",Port:443,Protocol:"wireguard",Config:"secret",Enabled:true}}})
	req:=httptest.NewRequest(http.MethodPut,"/admin/v1/servers",bytes.NewReader(body))
	rec:=httptest.NewRecorder();a.routes().ServeHTTP(rec,req)
	if rec.Code!=http.StatusUnauthorized{t.Fatalf("unauthorized admin got %d",rec.Code)}
	req=httptest.NewRequest(http.MethodPut,"/admin/v1/servers",bytes.NewReader(body));req.Header.Set("Authorization","Bearer "+string(a.adminToken))
	rec=httptest.NewRecorder();a.routes().ServeHTTP(rec,req)
	if rec.Code!=http.StatusBadRequest{t.Fatalf("WireGuard not rejected: %d %s",rec.Code,rec.Body.String())}
}
func TestAdminTokenIssue(t *testing.T) {
	a:=newTestAPI(t)
	req:=httptest.NewRequest(http.MethodPost,"/admin/v1/tokens",strings.NewReader("{\"userId\":\"user-2\",\"plan\":\"premium\",\"ttlSeconds\":3600}"))
	req.Header.Set("Authorization","Bearer "+string(a.adminToken))
	rec:=httptest.NewRecorder();a.routes().ServeHTTP(rec,req)
	if rec.Code!=http.StatusCreated{t.Fatalf("token issue failed: %d %s",rec.Code,rec.Body.String())}
	var out struct{AccessToken string `json:"accessToken"`}
	if err:=json.Unmarshal(rec.Body.Bytes(),&out);err!=nil{t.Fatal(err)}
	claims,err:=verifyToken(a.signingKey,out.AccessToken,a.now())
	if err!=nil||claims.Subject!="user-2"||claims.Plan!="premium"{t.Fatalf("issued token invalid: %+v %v",claims,err)}
}
func TestServerValidationRejectsInvalidHostAndConfig(t *testing.T) {
	s:=Server{ID:"node1",Name:"Node",Country:"Germany",Region:"EU",Host:"https://evil.example",Port:443,Protocol:"openvpn",Config:"profile",Enabled:true}
	if err:=validateServer(s);err==nil{t.Fatal("URL-like host accepted")}
	s.Host="";if err:=validateServer(s);err==nil{t.Fatal("empty host accepted")}
	s.Host="vpn.example.net";s.Config=" ";if err:=validateServer(s);err==nil{t.Fatal("empty config accepted")}
}
