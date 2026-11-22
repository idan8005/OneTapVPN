package domain

import (
	"fmt"
	"strings"
)

type Tier string

const (
	TierFree    Tier = "free"
	TierPremium Tier = "premium"
)

type Protocol string

const (
	ProtocolVLESSReality Protocol = "vless-reality"
	ProtocolOpenVPN      Protocol = "openvpn"
)

// Server contains display and health metadata only. It must never contain
// private keys, authentication tokens, or reusable connection credentials.
type Server struct {
	ID                string   `json:"id"`
	Name              string   `json:"name"`
	CountryCode       string   `json:"country_code"`
	Region            string   `json:"region"`
	Tier              Tier     `json:"tier"`
	Protocol          Protocol `json:"protocol"`
	Enabled           bool     `json:"enabled"`
	Healthy           bool     `json:"healthy"`
	LatencyMS         int      `json:"latency_ms"`
	PacketLossPercent float64  `json:"packet_loss_percent"`
	CapacityPercent   float64  `json:"capacity_percent"`
}

func (s Server) Validate() error {
	if strings.TrimSpace(s.ID) == "" {
		return fmt.Errorf("server id is required")
	}
	if strings.TrimSpace(s.Name) == "" {
		return fmt.Errorf("server name is required")
	}
	if len(s.CountryCode) != 2 || strings.ToUpper(s.CountryCode) != s.CountryCode {
		return fmt.Errorf("country_code must be a two-letter uppercase code")
	}
	if s.Tier != TierFree && s.Tier != TierPremium {
		return fmt.Errorf("unsupported server tier %q", s.Tier)
	}
	if s.Protocol != ProtocolVLESSReality && s.Protocol != ProtocolOpenVPN {
		return fmt.Errorf("unsupported server protocol %q", s.Protocol)
	}
	if s.LatencyMS < 0 {
		return fmt.Errorf("latency_ms cannot be negative")
	}
	if s.PacketLossPercent < 0 || s.PacketLossPercent > 100 {
		return fmt.Errorf("packet_loss_percent must be between 0 and 100")
	}
	if s.CapacityPercent < 0 || s.CapacityPercent > 100 {
		return fmt.Errorf("capacity_percent must be between 0 and 100")
	}
	return nil
}
