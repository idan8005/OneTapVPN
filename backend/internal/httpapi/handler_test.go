package httpapi

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/idan8005/OneTapVPN/backend/internal/catalog"
	"github.com/idan8005/OneTapVPN/backend/internal/domain"
)

func TestListServersOnlyReturnsEnabledHealthyEntries(t *testing.T) {
	repo, err := catalog.NewStatic([]domain.Server{
		{ID: "premium-fast", Name: "Premium Fast", CountryCode: "DE", Region: "Frankfurt", Tier: domain.TierPremium, Protocol: domain.ProtocolVLESSReality, Enabled: true, Healthy: true, LatencyMS: 40},
		{ID: "free-slow", Name: "Free Slow", CountryCode: "NL", Region: "Amsterdam", Tier: domain.TierFree, Protocol: domain.ProtocolOpenVPN, Enabled: true, Healthy: true, LatencyMS: 90},
		{ID: "disabled", Name: "Disabled", CountryCode: "US", Region: "New York", Tier: domain.TierFree, Protocol: domain.ProtocolOpenVPN, Enabled: false, Healthy: true},
		{ID: "unhealthy", Name: "Unhealthy", CountryCode: "FR", Region: "Paris", Tier: domain.TierFree, Protocol: domain.ProtocolOpenVPN, Enabled: true, Healthy: false},
	})
	if err != nil {
		t.Fatal(err)
	}

	handler := NewHandler(repo)
	request := httptest.NewRequest(http.MethodGet, "/api/v1/servers", nil)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)

	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want %d", response.Code, http.StatusOK)
	}
	var payload serverListResponse
	if err := json.Unmarshal(response.Body.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	if payload.Count != 2 {
		t.Fatalf("count = %d, want 2", payload.Count)
	}
	if payload.Data[0].ID != "premium-fast" {
		t.Fatalf("first server = %q, want premium-fast", payload.Data[0].ID)
	}
}

func TestListServersTierFilter(t *testing.T) {
	repo, err := catalog.NewStatic([]domain.Server{
		{ID: "free", Name: "Free", CountryCode: "NL", Region: "Amsterdam", Tier: domain.TierFree, Protocol: domain.ProtocolOpenVPN, Enabled: true, Healthy: true},
		{ID: "premium", Name: "Premium", CountryCode: "DE", Region: "Frankfurt", Tier: domain.TierPremium, Protocol: domain.ProtocolVLESSReality, Enabled: true, Healthy: true},
	})
	if err != nil {
		t.Fatal(err)
	}
	handler := NewHandler(repo)

	request := httptest.NewRequest(http.MethodGet, "/api/v1/servers?tier=free", nil)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)

	var payload serverListResponse
	if err := json.Unmarshal(response.Body.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	if payload.Count != 1 || payload.Data[0].ID != "free" {
		t.Fatalf("unexpected filtered response: %+v", payload)
	}
}

func TestListServersRejectsInvalidTier(t *testing.T) {
	repo, _ := catalog.NewStatic(nil)
	handler := NewHandler(repo)
	request := httptest.NewRequest(http.MethodGet, "/api/v1/servers?tier=unlimited", nil)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusBadRequest {
		t.Fatalf("status = %d, want 400", response.Code)
	}
}

func TestHealthEndpoint(t *testing.T) {
	repo, _ := catalog.NewStatic(nil)
	handler := NewHandler(repo)
	request := httptest.NewRequest(http.MethodGet, "/healthz", nil)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)
	if response.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", response.Code)
	}
	if got := response.Header().Get("Cache-Control"); got != "no-store" {
		t.Fatalf("Cache-Control = %q, want no-store", got)
	}
}

func TestListServersRanksVerifiedProbeLatencyBeforeUnverifiedSourcePing(t *testing.T) {
	repo, err := catalog.NewStatic([]domain.Server{
		{
			ID: "free-verified", Name: "Free verified", CountryCode: "JP", Region: "Tokyo",
			Tier: domain.TierFree, Protocol: domain.ProtocolOpenVPN, Enabled: true, Healthy: true,
			LatencyMS: 18, ProbeLatencyMS: 42, Source: "vpn-gate", VerificationLevel: "tcp-reachable",
		},
		{
			ID: "premium-unverified", Name: "Premium unverified", CountryCode: "DE", Region: "Frankfurt",
			Tier: domain.TierPremium, Protocol: domain.ProtocolVLESSReality, Enabled: true, Healthy: true,
			LatencyMS: 25,
		},
	})
	if err != nil {
		t.Fatal(err)
	}

	handler := NewHandler(repo)
	request := httptest.NewRequest(http.MethodGet, "/api/v1/servers", nil)
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)

	var payload serverListResponse
	if err := json.Unmarshal(response.Body.Bytes(), &payload); err != nil {
		t.Fatal(err)
	}
	if payload.Count != 2 || payload.Data[0].ID != "premium-unverified" {
		t.Fatalf("expected measured latency ordering, got %+v", payload.Data)
	}
}

func TestCORSAllowsPublicReadOnlyPreflight(t *testing.T) {
	repo, _ := catalog.NewStatic(nil)
	handler := NewHandlerWithAllowedOrigins(repo, "*")
	request := httptest.NewRequest(http.MethodOptions, "/api/v1/servers", nil)
	request.Header.Set("Origin", "https://raahat.example")
	request.Header.Set("Access-Control-Request-Method", "GET")
	response := httptest.NewRecorder()
	handler.ServeHTTP(response, request)

	if response.Code != http.StatusNoContent {
		t.Fatalf("status = %d, want 204", response.Code)
	}
	if got := response.Header().Get("Access-Control-Allow-Origin"); got != "*" {
		t.Fatalf("allow-origin = %q, want *", got)
	}
	if got := response.Header().Get("Access-Control-Allow-Credentials"); got != "" {
		t.Fatalf("credentials must not be enabled, got %q", got)
	}
}

func TestCORSExactOriginAllowList(t *testing.T) {
	repo, _ := catalog.NewStatic(nil)
	handler := NewHandlerWithAllowedOrigins(repo, "https://raahat.example, https://www.raahat.example")

	allowed := httptest.NewRequest(http.MethodGet, "/api/v1/health", nil)
	allowed.Header.Set("Origin", "https://raahat.example")
	allowedResponse := httptest.NewRecorder()
	handler.ServeHTTP(allowedResponse, allowed)
	if allowedResponse.Code != http.StatusOK {
		t.Fatalf("allowed origin status = %d, want 200", allowedResponse.Code)
	}
	if got := allowedResponse.Header().Get("Access-Control-Allow-Origin"); got != "https://raahat.example" {
		t.Fatalf("allow-origin = %q", got)
	}

	denied := httptest.NewRequest(http.MethodGet, "/api/v1/health", nil)
	denied.Header.Set("Origin", "https://attacker.example")
	deniedResponse := httptest.NewRecorder()
	handler.ServeHTTP(deniedResponse, denied)
	if deniedResponse.Code != http.StatusForbidden {
		t.Fatalf("denied origin status = %d, want 403", deniedResponse.Code)
	}
}
