package main

import (
	"crypto/aes"
	"crypto/cipher"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"encoding/base64"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"time"
)

const (
	maxBodyBytes = 1 << 20
	maxConfigLen = 64 << 10
)

var (
	idPattern = regexp.MustCompile("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$")
	protocolPattern = regexp.MustCompile("^(vless-reality|openvpn)$")
	hostnamePattern = regexp.MustCompile("(?i)^(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?)(?:\\.(?:[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?))*$")
)

type Server struct {
	ID string `json:"id"`
	Name string `json:"name"`
	Country string `json:"country"`
	Region string `json:"region"`
	Host string `json:"host"`
	Port int `json:"port"`
	Protocol string `json:"protocol"`
	Config string `json:"config"`
	Enabled bool `json:"enabled"`
	MaxUsers int `json:"maxUsers,omitempty"`
	Priority int `json:"priority,omitempty"`
	UpdatedAt string `json:"updatedAt,omitempty"`
}

type Claims struct {
	Subject string `json:"sub"`
	Plan string `json:"plan"`
	Expires int64 `json:"exp"`
}

type serverStore struct { path string; key []byte }
type api struct { adminToken, signingKey []byte; store serverStore; now func() time.Time }
type tokenRequest struct { UserID string `json:"userId"`; Plan string `json:"plan"`; TTLSeconds int64 `json:"ttlSeconds"` }
type serverRequest struct { Servers []Server `json:"servers"` }
type catalogResponse struct {
	Plan string `json:"plan"`
	GeneratedAt string `json:"generatedAt"`
	PublicDiscovery bool `json:"publicDiscovery"`
	PremiumServers []Server `json:"premiumServers"`
}

func main() {
	adminToken, signingKey := os.Getenv("RAAHAT_ADMIN_TOKEN"), os.Getenv("RAAHAT_TOKEN_SIGNING_KEY")
	if len(adminToken) < 32 || len(signingKey) < 32 {
		log.Fatal("RAAHAT_ADMIN_TOKEN and RAAHAT_TOKEN_SIGNING_KEY must each contain at least 32 characters")
	}
	encryptionKey, err := base64.StdEncoding.DecodeString(os.Getenv("RAAHAT_DATA_ENCRYPTION_KEY"))
	if err != nil || len(encryptionKey) != 32 {
		log.Fatal("RAAHAT_DATA_ENCRYPTION_KEY must be a base64-encoded 32-byte key")
	}
	dataPath := os.Getenv("RAAHAT_DATA_FILE")
	if dataPath == "" { dataPath = "./data/premium-catalog.enc" }
	addr := os.Getenv("RAAHAT_LISTEN_ADDR")
	if addr == "" { addr = ":8080" }
	a := &api{[]byte(adminToken), []byte(signingKey), serverStore{dataPath, encryptionKey}, time.Now}
	server := &http.Server{
		Addr: addr, Handler: securityHeaders(a.routes()), ReadHeaderTimeout: 5*time.Second,
		ReadTimeout: 10*time.Second, WriteTimeout: 15*time.Second, IdleTimeout: 60*time.Second,
		MaxHeaderBytes: 16 << 10,
	}
	log.Printf("Raahat VPN control plane listening on %s", addr)
	if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) { log.Fatal(err) }
}

func (a *api) routes() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /healthz", a.health)
	mux.HandleFunc("GET /api/v1/catalog", a.catalog)
	mux.HandleFunc("PUT /admin/v1/servers", a.replaceServers)
	mux.HandleFunc("POST /admin/v1/tokens", a.issueToken)
	mux.HandleFunc("/", func(w http.ResponseWriter, _ *http.Request) {
		writeError(w, http.StatusNotFound, "not_found", "The requested endpoint does not exist.")
	})
	return mux
}

func securityHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Referrer-Policy", "no-referrer")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Content-Security-Policy", "default-src 'none'; frame-ancestors 'none'")
		if r.TLS != nil { w.Header().Set("Strict-Transport-Security", "max-age=31536000; includeSubDomains") }
		next.ServeHTTP(w, r)
	})
}

func (a *api) health(w http.ResponseWriter, _ *http.Request) {
	writeJSON(w, http.StatusOK, map[string]string{"status":"ok", "service":"raahat-control-plane"})
}

func (a *api) catalog(w http.ResponseWriter, r *http.Request) {
	claims, ok := a.authenticateUser(r)
	if !ok { writeError(w, http.StatusUnauthorized, "unauthorized", "A valid Raahat access token is required."); return }
	out := catalogResponse{Plan:claims.Plan, GeneratedAt:a.now().UTC().Format(time.RFC3339), PublicDiscovery:true, PremiumServers:[]Server{}}
	if claims.Plan == "premium" {
		servers, err := a.store.read()
		if err != nil { writeError(w, http.StatusServiceUnavailable, "catalog_unavailable", "The managed server catalogue is temporarily unavailable."); return }
		for _, server := range servers { if server.Enabled { out.PremiumServers = append(out.PremiumServers, server) } }
	}
	writeJSON(w, http.StatusOK, out)
}

func (a *api) replaceServers(w http.ResponseWriter, r *http.Request) {
	if !a.authenticateAdmin(r) { writeError(w, http.StatusUnauthorized, "unauthorized", "Administrator authentication is required."); return }
	var request serverRequest
	if err := decodeJSON(w, r, &request); err != nil { writeError(w, http.StatusBadRequest, "invalid_json", "The server list is not valid JSON."); return }
	if len(request.Servers) > 500 { writeError(w, http.StatusBadRequest, "too_many_servers", "At most 500 managed servers can be stored."); return }
	seen := make(map[string]bool, len(request.Servers))
	for i := range request.Servers {
		server := &request.Servers[i]
		if err := validateServer(*server); err != nil { writeError(w, http.StatusBadRequest, "invalid_server", fmt.Sprintf("Server entry %d is invalid: %s", i+1, err)); return }
		if seen[server.ID] { writeError(w, http.StatusBadRequest, "duplicate_server", "Server IDs must be unique."); return }
		seen[server.ID] = true
		server.UpdatedAt = a.now().UTC().Format(time.RFC3339)
	}
	if err := a.store.write(request.Servers); err != nil { writeError(w, http.StatusInternalServerError, "catalog_write_failed", "The managed server catalogue could not be saved securely."); return }
	writeJSON(w, http.StatusOK, map[string]int{"saved":len(request.Servers)})
}

func (a *api) issueToken(w http.ResponseWriter, r *http.Request) {
	if !a.authenticateAdmin(r) { writeError(w, http.StatusUnauthorized, "unauthorized", "Administrator authentication is required."); return }
	var request tokenRequest
	if err := decodeJSON(w, r, &request); err != nil { writeError(w, http.StatusBadRequest, "invalid_json", "The token request is not valid JSON."); return }
	request.UserID = strings.TrimSpace(request.UserID)
	if !idPattern.MatchString(request.UserID) { writeError(w, http.StatusBadRequest, "invalid_user", "userId has an invalid format."); return }
	if request.Plan != "free" && request.Plan != "premium" { writeError(w, http.StatusBadRequest, "invalid_plan", "plan must be free or premium."); return }
	if request.TTLSeconds < 60 || request.TTLSeconds > 366*24*60*60 { writeError(w, http.StatusBadRequest, "invalid_ttl", "ttlSeconds must be between 60 seconds and 366 days."); return }
	expires := a.now().Add(time.Duration(request.TTLSeconds)*time.Second)
	token, err := signToken(a.signingKey, Claims{request.UserID, request.Plan, expires.Unix()})
	if err != nil { writeError(w, http.StatusInternalServerError, "token_issue_failed", "An access token could not be created."); return }
	writeJSON(w, http.StatusCreated, map[string]any{"accessToken":token, "tokenType":"Bearer", "expiresAt":expires.UTC().Format(time.RFC3339)})
}

func (a *api) authenticateAdmin(r *http.Request) bool {
	token := bearerToken(r)
	return token != "" && hmac.Equal([]byte(token), a.adminToken)
}
func (a *api) authenticateUser(r *http.Request) (Claims, bool) {
	token := bearerToken(r)
	if token == "" { return Claims{}, false }
	claims, err := verifyToken(a.signingKey, token, a.now())
	return claims, err == nil
}
func bearerToken(r *http.Request) string {
	prefix, token, ok := strings.Cut(strings.TrimSpace(r.Header.Get("Authorization")), " ")
	if !ok || !strings.EqualFold(prefix, "Bearer") { return "" }
	return strings.TrimSpace(token)
}

func validateServer(s Server) error {
	if !idPattern.MatchString(s.ID) { return errors.New("id has an invalid format") }
	if strings.TrimSpace(s.Name) == "" || len(s.Name)>100 { return errors.New("name is required and must be at most 100 characters") }
	if strings.TrimSpace(s.Country) == "" || len(s.Country)>80 { return errors.New("country is required and must be at most 80 characters") }
	if strings.TrimSpace(s.Region) == "" || len(s.Region)>80 { return errors.New("region is required and must be at most 80 characters") }
	if strings.TrimSpace(s.Host) != s.Host || s.Host == "" || len(s.Host)>253 { return errors.New("host must be an IP address or hostname") }
	if net.ParseIP(s.Host)==nil && !hostnamePattern.MatchString(s.Host) { return errors.New("host must be an IP address or valid DNS hostname") }
	if s.Port<1 || s.Port>65535 { return errors.New("port must be between 1 and 65535") }
	if !protocolPattern.MatchString(s.Protocol) { return errors.New("protocol must be vless-reality or openvpn; WireGuard is not supported") }
	if strings.TrimSpace(s.Config)=="" || len(s.Config)>maxConfigLen { return errors.New("config is required and must be at most 64 KiB") }
	if s.MaxUsers<0 || s.MaxUsers>1_000_000 { return errors.New("maxUsers is outside the allowed range") }
	return nil
}

func decodeJSON(w http.ResponseWriter, r *http.Request, target any) error {
	defer r.Body.Close()
	r.Body = http.MaxBytesReader(w, r.Body, maxBodyBytes)
	decoder := json.NewDecoder(r.Body)
	decoder.DisallowUnknownFields()
	if err := decoder.Decode(target); err != nil { return err }
	var trailing any
	if err := decoder.Decode(&trailing); !errors.Is(err, io.EOF) { return errors.New("request must contain exactly one JSON value") }
	return nil
}

func (s serverStore) read() ([]Server, error) {
	data, err := os.ReadFile(s.path)
	if errors.Is(err, os.ErrNotExist) { return []Server{}, nil }
	if err != nil { return nil, err }
	plain, err := decrypt(s.key, data)
	if err != nil { return nil, err }
	var servers []Server
	if err := json.Unmarshal(plain, &servers); err != nil { return nil, err }
	return servers, nil
}
func (s serverStore) write(servers []Server) error {
	plain, err := json.Marshal(servers); if err != nil { return err }
	encrypted, err := encrypt(s.key, plain); if err != nil { return err }
	if err := os.MkdirAll(filepath.Dir(s.path), 0o700); err != nil { return err }
	tmp, err := os.CreateTemp(filepath.Dir(s.path), ".catalog-*.tmp"); if err != nil { return err }
	name := tmp.Name(); defer os.Remove(name)
	if err := tmp.Chmod(0o600); err != nil { tmp.Close(); return err }
	if _, err := tmp.Write(encrypted); err != nil { tmp.Close(); return err }
	if err := tmp.Sync(); err != nil { tmp.Close(); return err }
	if err := tmp.Close(); err != nil { return err }
	return os.Rename(name, s.path)
}
func encrypt(key, plain []byte) ([]byte, error) {
	block, err := aes.NewCipher(key); if err != nil { return nil, err }
	gcm, err := cipher.NewGCM(block); if err != nil { return nil, err }
	nonce := make([]byte, gcm.NonceSize()); if _, err := rand.Read(nonce); err != nil { return nil, err }
	return append(nonce, gcm.Seal(nil, nonce, plain, []byte("raahat-premium-catalog-v1"))...), nil
}
func decrypt(key, encrypted []byte) ([]byte, error) {
	block, err := aes.NewCipher(key); if err != nil { return nil, err }
	gcm, err := cipher.NewGCM(block); if err != nil { return nil, err }
	if len(encrypted)<gcm.NonceSize()+gcm.Overhead() { return nil, errors.New("encrypted catalogue is truncated") }
	return gcm.Open(nil, encrypted[:gcm.NonceSize()], encrypted[gcm.NonceSize():], []byte("raahat-premium-catalog-v1"))
}
func signToken(key []byte, claims Claims) (string, error) {
	payload, err := json.Marshal(claims); if err != nil { return "", err }
	encoded := base64.RawURLEncoding.EncodeToString(payload)
	mac := hmac.New(sha256.New, key); _, _ = mac.Write([]byte(encoded))
	return encoded+"."+base64.RawURLEncoding.EncodeToString(mac.Sum(nil)), nil
}
func verifyToken(key []byte, token string, now time.Time) (Claims, error) {
	encoded, signature, ok := strings.Cut(token, ".")
	if !ok || encoded=="" || signature=="" || strings.Contains(signature, ".") { return Claims{}, errors.New("malformed token") }
	supplied, err := base64.RawURLEncoding.DecodeString(signature); if err != nil { return Claims{}, errors.New("malformed signature") }
	mac := hmac.New(sha256.New, key); _, _ = mac.Write([]byte(encoded))
	if !hmac.Equal(supplied, mac.Sum(nil)) { return Claims{}, errors.New("invalid signature") }
	payload, err := base64.RawURLEncoding.DecodeString(encoded); if err != nil { return Claims{}, errors.New("malformed claims") }
	var claims Claims
	if err := json.Unmarshal(payload, &claims); err != nil { return Claims{}, errors.New("malformed claims") }
	if claims.Subject=="" || (claims.Plan!="free" && claims.Plan!="premium") || claims.Expires<=now.Unix() { return Claims{}, errors.New("token is expired or has invalid claims") }
	return claims, nil
}
func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status); _ = json.NewEncoder(w).Encode(value)
}
func writeError(w http.ResponseWriter, status int, code, message string) {
	writeJSON(w, status, map[string]string{"error":code, "message":message})
}
