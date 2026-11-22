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

// Server contains public display and health metadata only. It must never
// contain private keys, authentication tokens, or reusable credentials.
type Server struct {
	ID                   string   `json:"id"`
	Name                 string   `json:"name"`
	CountryCode          string   `json:"country_code"`
	Region               string   `json:"region"`
	Tier                 Tier     `json:"tier"`
	Protocol             Protocol `json:"protocol"`
	Enabled              bool     `json:"enabled"`
	Healthy              bool     `json:"healthy"`
	LatencyMS            int      `json:"latency_ms"`
	PacketLossPercent    float64  `json:"packet_loss_percent"`
	CapacityPercent      float64  `json:"capacity_percent"`
	PacketLossKnown      bool     `json:"packet_loss_known"`
	CapacityKnown        bool     `json:"capacity_known"`
	Source               string   `json:"source,omitempty"`
	EndpointHost         string   `json:"endpoint_host,omitempty"`
	EndpointPort         int      `json:"endpoint_port,omitempty"`
	ProbeLatencyMS       int      `json:"probe_latency_ms,omitempty"`
	QualityScore         int64    `json:"quality_score,omitempty"`
	ActiveSessions       int      `json:"active_sessions,omitempty"`
	VerificationLevel    string   `json:"verification_level,omitempty"`
	LastCheckedAt        string   `json:"last_checked_at,omitempty"`
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
	if s.LatencyMS < 0 || s.ProbeLatencyMS < 0 {
		return fmt.Errorf("latency values cannot be negative")
	}
	if s.EndpointPort < 0 || s.EndpointPort > 65535 {
		return fmt.Errorf("endpoint_port must be between 0 and 65535")
	}
	if s.PacketLossPercent < 0 || s.PacketLossPercent > 100 {
		return fmt.Errorf("packet_loss_percent must be between 0 and 100")
	}
	if s.CapacityPercent < 0 || s.CapacityPercent > 100 {
		return fmt.Errorf("capacity_percent must be between 0 and 100")
	}
	if s.QualityScore < 0 || s.ActiveSessions < 0 {
		return fmt.Errorf("quality_score and active_sessions cannot be negative")
	}
	if s.Healthy && s.Source == "vpn-gate" && s.VerificationLevel != "tcp-reachable" {
		return fmt.Errorf("VPN Gate servers marked healthy require tcp-reachable verification")
	}
	return nil
}
