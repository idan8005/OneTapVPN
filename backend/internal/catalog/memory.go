package catalog

import (
	"context"
	"fmt"
	"sync"

	"github.com/idan8005/OneTapVPN/backend/internal/domain"
)

// Memory is an atomically replaceable in-memory catalogue. It is used for
// periodically refreshed public sources while preserving administrator data.
type Memory struct {
	mu      sync.RWMutex
	servers []domain.Server
}

func NewMemory(initial []domain.Server) (*Memory, error) {
	if _, err := NewStatic(initial); err != nil {
		return nil, err
	}
	copyOfServers := append([]domain.Server(nil), initial...)
	return &Memory{servers: copyOfServers}, nil
}

// ReplaceSource replaces only entries owned by source. Entries from other
// sources, including administrator-managed Premium servers, remain untouched.
func (m *Memory) ReplaceSource(source string, servers []domain.Server) error {
	if source == "" {
		return fmt.Errorf("source is required")
	}
	if len(servers) == 0 {
		return fmt.Errorf("refusing to replace source %q with an empty catalogue", source)
	}

	seen := make(map[string]struct{}, len(servers))
	nextSource := make([]domain.Server, len(servers))
	for i, server := range servers {
		if server.Source != source {
			return fmt.Errorf("server at index %d belongs to source %q, want %q", i, server.Source, source)
		}
		if server.Tier != domain.TierFree {
			return fmt.Errorf("source %q may only replace Free servers", source)
		}
		if err := server.Validate(); err != nil {
			return fmt.Errorf("server at index %d: %w", i, err)
		}
		if _, exists := seen[server.ID]; exists {
			return fmt.Errorf("duplicate server id %q", server.ID)
		}
		seen[server.ID] = struct{}{}
		nextSource[i] = server
	}

	m.mu.Lock()
	defer m.mu.Unlock()

	combined := make([]domain.Server, 0, len(m.servers)+len(nextSource))
	for _, existing := range m.servers {
		if existing.Source != source {
			combined = append(combined, existing)
		}
	}
	existingIDs := make(map[string]struct{}, len(combined))
	for _, existing := range combined {
		existingIDs[existing.ID] = struct{}{}
	}
	for _, incoming := range nextSource {
		if _, exists := existingIDs[incoming.ID]; exists {
			return fmt.Errorf("server id %q conflicts with another catalogue source", incoming.ID)
		}
		combined = append(combined, incoming)
	}
	m.servers = combined
	return nil
}

func (m *Memory) List(_ context.Context) ([]domain.Server, error) {
	m.mu.RLock()
	defer m.mu.RUnlock()
	result := make([]domain.Server, len(m.servers))
	copy(result, m.servers)
	return result, nil
}
