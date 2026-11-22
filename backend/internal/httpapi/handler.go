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
	Count int             `json:"count"`
}

type healthResponse struct {
	Status  string `json:"status"`
	Service string `json:"service"`
	Version string `json:"version"`
}

func NewHandler(catalogue Catalogue) http.Handler {
	h := &Handler{catalogue: catalogue}
	mux := http.NewServeMux()
	mux.HandleFunc("/healthz", h.health)
	mux.HandleFunc("/api/v1/health", h.health)
	mux.HandleFunc("/api/v1/servers", h.listServers)
	return secureHeaders(mux)
}

func (h *Handler) health(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodGet {
		writeError(w, http.StatusMethodNotAllowed, "method_not_allowed")
		return
	}
	writeJSON(w, http.StatusOK, healthResponse{
		Status: "ok", Service: "raahat-control-plane", Version: "0.1.0-dev",
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
		if tierFilter != "" && string(server.Tier) != tierFilter {
			continue
		}
		visible = append(visible, server)
	}

	sort.SliceStable(visible, func(i, j int) bool {
		if visible[i].LatencyMS == visible[j].LatencyMS {
			return visible[i].ID < visible[j].ID
		}
		return visible[i].LatencyMS < visible[j].LatencyMS
	})

	writeJSON(w, http.StatusOK, serverListResponse{Data: visible, Count: len(visible)})
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
