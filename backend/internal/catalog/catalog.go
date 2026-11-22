package catalog

import (
	"context"
	"encoding/json"
	"fmt"
	"os"

	"github.com/idan8005/OneTapVPN/backend/internal/domain"
)

type Repository interface {
	List(ctx context.Context) ([]domain.Server, error)
}

type Static struct {
	servers []domain.Server
}

func NewStatic(servers []domain.Server) (*Static, error) {
	seen := make(map[string]struct{}, len(servers))
	copyOfServers := make([]domain.Server, len(servers))
	for i, server := range servers {
		if err := server.Validate(); err != nil {
			return nil, fmt.Errorf("server at index %d: %w", i, err)
		}
		if _, exists := seen[server.ID]; exists {
			return nil, fmt.Errorf("duplicate server id %q", server.ID)
		}
		seen[server.ID] = struct{}{}
		copyOfServers[i] = server
	}
	return &Static{servers: copyOfServers}, nil
}

func Load(path string) (*Static, error) {
	if path == "" {
		return NewStatic(nil)
	}
	file, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("open catalogue: %w", err)
	}
	defer file.Close()

	decoder := json.NewDecoder(file)
	decoder.DisallowUnknownFields()
	var servers []domain.Server
	if err := decoder.Decode(&servers); err != nil {
		return nil, fmt.Errorf("decode catalogue: %w", err)
	}
	return NewStatic(servers)
}

func (s *Static) List(_ context.Context) ([]domain.Server, error) {
	result := make([]domain.Server, len(s.servers))
	copy(result, s.servers)
	return result, nil
}
