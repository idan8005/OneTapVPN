package catalog

import (
	"context"
	"testing"

	"github.com/idan8005/OneTapVPN/backend/internal/domain"
)

func TestMemoryReplaceSourcePreservesOtherSources(t *testing.T) {
	memory, err := NewMemory([]domain.Server{
		{ID: "premium-1", Name: "Managed Premium", CountryCode: "DE", Region: "Frankfurt", Tier: domain.TierPremium, Protocol: domain.ProtocolVLESSReality, Enabled: true, Healthy: true},
		{ID: "old-public", Name: "Old public", CountryCode: "JP", Region: "Japan", Tier: domain.TierFree, Protocol: domain.ProtocolOpenVPN, Enabled: true, Healthy: true, Source: "vpn-gate", VerificationLevel: "tcp-reachable"},
	})
	if err != nil {
		t.Fatal(err)
	}

	err = memory.ReplaceSource("vpn-gate", []domain.Server{
		{ID: "new-public", Name: "New public", CountryCode: "JP", Region: "Japan", Tier: domain.TierFree, Protocol: domain.ProtocolOpenVPN, Enabled: true, Healthy: true, Source: "vpn-gate", VerificationLevel: "tcp-reachable"},
	})
	if err != nil {
		t.Fatal(err)
	}

	servers, err := memory.List(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if len(servers) != 2 {
		t.Fatalf("got %d servers, want 2", len(servers))
	}
	seen := map[string]bool{}
	for _, server := range servers {
		seen[server.ID] = true
	}
	if !seen["premium-1"] || !seen["new-public"] || seen["old-public"] {
		t.Fatalf("unexpected catalogue after replacement: %+v", servers)
	}
}

func TestMemoryRejectsEmptyRefreshAndKeepsPreviousCatalog(t *testing.T) {
	memory, err := NewMemory([]domain.Server{
		{ID: "old-public", Name: "Old public", CountryCode: "JP", Region: "Japan", Tier: domain.TierFree, Protocol: domain.ProtocolOpenVPN, Enabled: true, Healthy: true, Source: "vpn-gate", VerificationLevel: "tcp-reachable"},
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := memory.ReplaceSource("vpn-gate", nil); err == nil {
		t.Fatal("empty refresh must be rejected")
	}
	servers, _ := memory.List(context.Background())
	if len(servers) != 1 || servers[0].ID != "old-public" {
		t.Fatalf("last known good catalogue was lost: %+v", servers)
	}
}
