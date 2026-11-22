package httpapi

import (
	"context"
	"encoding/json"
	"net/http"
	"sort"
	"strings"

	"github.com/idan8005/OneTapVPN/backend/internal/domain"
)

type Catalogue interface {
	List(ctx context.Context) ([]domain.Server, error)
}

type Handler struct {
	catalogue Catalogue
}

type serverListResponse struct {
	Data  []domain.Server `json:"data"`
	Count int              `json:"count"`
}

type healthResponse struct {
	Status  string `json:"status"`
	Service string `json:"service"`
	Version string `json:"version"`
}

// NewHandler creates the public, read-only HTTP API. The catalogue contains
// public server metadata only; no credentials or reusable VPN profiles.
func NewHandler(catalogue Catalogue) http.Handler {
	return NewHandlerWithAllowedOrigins(catalogue, "*")
}

// NewHandlerWithAllowedOrigins enables CORS for a comma-separated list of
// exact browser origins. "*" is suitable only because these endpoints are
// public GET-only directory data and never accept credentials.
func NewHandlerWithAllowedOrigins(catalogue Catalogue, allowedOrigins string) http.Handler {
	h := &Handler{catalogue: catalogue}
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", h.health)
	mux.HandleFunc("/api/v1/health", h.health)
	mux.HandleFunc("/api/v1/servers", h.listServers)
	return secureHeaders(cors(allowedOrigins, mux))
}

func (h *Handler) health(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed")
		return
	}
	writeJSON(w, http.StatusOK, healthResponse{
		Status: "ok", Service: "raahat-control-plane", Version: "0.2.0-dev",
	})
}

func (h *Handler) listServers(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed")
		return
	}

	tierFilter := strings.TrimSpace(strings.ToLower(r.URL.Query().Get("tier")))
	if tierFilter != "" && tierFilter != string(domain.TierFree) && tierFilter != string(domain.TierPremium) {
		writeError(w, http.StatusBadRequest, "invalid_tier")
		return
	}

	servers, err := h.catalogue.List(r.Context())
	if err != nil {
		writeError(w, http.StatusServiceUnavailable, "catalogue_unavailable")
		return
	}

	visible := make([]domain.Server, 0, len(servers))
	for _, server := range servers {
		if !server.Enabled || !server.Healthy {
			continue
		}
		if server.Source == "vpn-gate" && server.VerificationLevel != "tcp-reachable" {
			continue
		}
		if tierFilter != "" && string(server.Tier) != tierFilter {
			continue
		}
		visible = append(visible, server)
	}

	sort.SliceStable(visible, func(i, j int) bool {
		leftLatency := visible[i].LatencyMS
		if visible[i].VerificationLevel == "tcp-reachable" {
			leftLatency = visible[i].ProbeLatencyMS
		}
		rightLatency := visible[j].LatencyMS
		if visible[j].VerificationLevel == "tcp-reachable" {
			rightLatency = visible[j].ProbeLatencyMS
		}
		if leftLatency == rightLatency {
			if visible[i].QualityScore == visible[j].QualityScore {
				return visible[i].ID < visible[j].ID
			}
			return visible[i].QualityScore > visible[j].QualityScore
		}
		return leftLatency < rightLatency
	})

	writeJSON(w, http.StatusOK, serverListResponse{Data: visible, Count: len(visible)})
}

func cors(allowedOrigins string, next http.Handler) http.Handler {
	allowed := make(map[string]struct{})
	wildcard := false
	for _, origin := range strings.Split(allowedOrigins, ",") {
		origin = strings.TrimSpace(strings.TrimRight(origin, "/"))
		if origin == "*" {
			wildcard = true
			break
		}
		if origin != "" {
			allowed[origin] = struct{}{}
		}
	}
	// An empty setting intentionally means no cross-origin browser access.
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		origin := strings.TrimSpace(r.Header.Get("Origin"))
		if origin != "" {
			_, exactMatch := allowed[origin]
			if !wildcard && !exactMatch {
				writeError(w, http.StatusForbidden, "origin_not_allowed")
				return
			}
			if wildcard {
				w.Header().Set("Access-Control-Allow-Origin", "*")
			} else {
				w.Header().Set("Access-Control-Allow-Origin", origin)
				w.Header().Add("Vary", "Origin")
			}
		}

		if r.Method == http.MethodOptions {
			w.Header().Set("Access-Control-Allow-Methods", "GET, OPTIONS")
			w.Header().Set("Access-Control-Allow-Headers", "Accept, Content-Type")
			w.Header().Set("Access-Control-Max-Age", "600")
			w.WriteHeader(http.StatusNoContent)
			return
		}
		next.ServeHTTP(w, r)
	})
}

func secureHeaders(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("Cache-Control", "no-store")
		w.Header().Set("Referrer-Policy", "no-referrer")
		next.ServeHTTP(w, r)
	})
}

func writeJSON(w http.ResponseWriter, status int, value any) {
	w.Header().Set("Content-Type", "application/json; charset=utf-8")
	w.WriteHeader(status)
	_ = json.NewEncoder(w).Encode(value)
}

func writeError(w http.ResponseWriter, status int, code string) {
	writeJSON(w, status, map[string]string{"error": code})
}
