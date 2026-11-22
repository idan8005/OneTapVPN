package discovery

import (
	"bufio"
	"bytes"
	"context"
	"crypto/sha256"
	"encoding/base64"
	"encoding/csv"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/idan8005/OneTapVPN/backend/internal/domain"
)

const (
	VPNGateAPIURL       = "https://www.vpngate.net/api/iphone/"
	maxResponseBytes   = 32 << 20
	maxProfileBytes    = 512 << 10
	defaultMaxCandidates = 80
)

type VPNGateSource struct {
	Client         *http.Client
	Endpoint       string
	MaxCandidates  int
	MaxParallel    int
	ProbeTimeout   time.Duration
}

type candidate struct {
	server domain.Server
	ip     string
	port   int
}

func NewVPNGateSource() *VPNGateSource {
	return &VPNGateSource{
		Client: &http.Client{
			Timeout: 15 * time.Second,
			CheckRedirect: func(req *http.Request, via []*http.Request) error {
				if len(via) >= 3 {
					return fmt.Errorf("too many redirects")
				}
				if req.URL.Scheme != "https" || !isAllowedVPNGateHost(req.URL.Hostname()) {
					return fmt.Errorf("redirect outside the official VPN Gate HTTPS host")
				}
				return nil
			},
		},
		Endpoint:      VPNGateAPIURL,
		MaxCandidates: defaultMaxCandidates,
		MaxParallel:   16,
		ProbeTimeout:  1800 * time.Millisecond,
	}
}

// Fetch retrieves the official VPN Gate directory, validates each OpenVPN
// profile, performs bounded TCP reachability probes, and returns only entries
// whose configured TCP endpoint accepted a connection. A TCP probe is not an
// OpenVPN handshake and must not be presented to users as one.
func (s *VPNGateSource) Fetch(ctx context.Context) ([]domain.Server, error) {
	if s == nil {
		return nil, fmt.Errorf("VPN Gate source is nil")
	}
	endpoint := s.Endpoint
	if endpoint == "" {
		endpoint = VPNGateAPIURL
	}
	parsed, err := url.Parse(endpoint)
	if err != nil || parsed.Scheme != "https" || !isAllowedVPNGateHost(parsed.Hostname()) {
		return nil, fmt.Errorf("VPN Gate endpoint must use the official HTTPS host")
	}

	client := s.Client
	if client == nil {
		client = NewVPNGateSource().Client
	}
	request, err := http.NewRequestWithContext(ctx, http.MethodGet, endpoint, nil)
	if err != nil {
		return nil, fmt.Errorf("create VPN Gate request: %w", err)
	}
	request.Header.Set("Accept", "text/plain")
	request.Header.Set("User-Agent", "RaahatVPN-ControlPlane/0.2")

	response, err := client.Do(request)
	if err != nil {
		return nil, fmt.Errorf("fetch official VPN Gate directory: %w", err)
	}
	defer response.Body.Close()
	if response.StatusCode != http.StatusOK {
		return nil, fmt.Errorf("VPN Gate returned HTTP %d", response.StatusCode)
	}
	body, err := io.ReadAll(io.LimitReader(response.Body, maxResponseBytes+1))
	if err != nil {
		return nil, fmt.Errorf("read VPN Gate directory: %w", err)
	}
	if len(body) > maxResponseBytes {
		return nil, fmt.Errorf("VPN Gate directory exceeds %d bytes", maxResponseBytes)
	}

	candidates, err := parseVPNGateCSV(body, s.limit())
	if err != nil {
		return nil, err
	}
	if len(candidates) == 0 {
		return nil, fmt.Errorf("VPN Gate returned no valid TCP OpenVPN candidates")
	}
	return s.probe(ctx, candidates), nil
}

func (s *VPNGateSource) limit() int {
	if s.MaxCandidates <= 0 || s.MaxCandidates > 500 {
		return defaultMaxCandidates
	}
	return s.MaxCandidates
}

func (s *VPNGateSource) probe(ctx context.Context, candidates []candidate) []domain.Server {
	parallel := s.MaxParallel
	if parallel <= 0 || parallel > 64 {
		parallel = 16
	}
	timeout := s.ProbeTimeout
	if timeout <= 0 || timeout > 10*time.Second {
		timeout = 1800 * time.Millisecond
	}

	type result struct {
		server domain.Server
		ok     bool
	}
	results := make(chan result, len(candidates))
	semaphore := make(chan struct{}, parallel)
	var wg sync.WaitGroup

	for _, item := range candidates {
		item := item
		wg.Add(1)
		go func() {
			defer wg.Done()
			select {
			case semaphore <- struct{}{}:
			case <-ctx.Done():
				return
			}
			defer func() { <-semaphore }()

			probeContext, cancel := context.WithTimeout(ctx, timeout)
			defer cancel()
			started := time.Now()
			address := net.JoinHostPort(item.ip, strconv.Itoa(item.port))
			connection, err := (&net.Dialer{Timeout: timeout}).DialContext(probeContext, "tcp", address)
			latency := int(time.Since(started).Milliseconds())
			if connection != nil {
				_ = connection.Close()
			}
			if err != nil {
				return
			}

			server := item.server
			server.Healthy = true
			server.ProbeLatencyMS = latency
			server.VerificationLevel = "tcp-reachable"
			server.LastCheckedAt = time.Now().UTC().Format(time.RFC3339)
			results <- result{server: server, ok: true}
		}()
	}

	wg.Wait()
	close(results)

	servers := make([]domain.Server, 0, len(candidates))
	for item := range results {
		if item.ok {
			servers = append(servers, item.server)
		}
	}
	sort.SliceStable(servers, func(i, j int) bool {
		if servers[i].ProbeLatencyMS == servers[j].ProbeLatencyMS {
			if servers[i].LatencyMS == servers[j].LatencyMS {
				return servers[i].QualityScore > servers[j].QualityScore
			}
			return servers[i].LatencyMS < servers[j].LatencyMS
		}
		return servers[i].ProbeLatencyMS < servers[j].ProbeLatencyMS
	})
	return servers
}

func parseVPNGateCSV(data []byte, limit int) ([]candidate, error) {
	reader := bufio.NewReader(bytes.NewReader(data))
	// The official API starts with a service marker followed by a commented CSV header.
	if _, err := reader.ReadString('\n'); err != nil {
		return nil, fmt.Errorf("VPN Gate response is missing its service marker")
	}
	headerLine, err := reader.ReadString('\n')
	if err != nil {
		return nil, fmt.Errorf("VPN Gate response is missing its CSV header")
	}
	headerLine = strings.TrimSpace(strings.TrimPrefix(strings.TrimSpace(headerLine), "#"))
	csvReader := csv.NewReader(io.MultiReader(strings.NewReader(headerLine+"\n"), reader))
	csvReader.FieldsPerRecord = -1
	header, err := csvReader.Read()
	if err != nil {
		return nil, fmt.Errorf("parse VPN Gate CSV header: %w", err)
	}

	columns := make(map[string]int, len(header))
	for index, name := range header {
		columns[strings.ToLower(strings.TrimSpace(strings.TrimPrefix(name, "#")))] = index
	}
	required := []string{"hostname", "ip", "score", "ping", "countrylong", "countryshort", "numvpnsessions", "openvpn_configdata_base64"}
	for _, name := range required {
		if _, ok := columns[name]; !ok {
			return nil, fmt.Errorf("VPN Gate CSV is missing required column %q", name)
		}
	}

	var all []candidate
	for {
		row, readErr := csvReader.Read()
		if readErr == io.EOF {
			break
		}
		if readErr != nil {
			continue
		}
		get := func(name string) string {
			index := columns[name]
			if index >= len(row) {
				return ""
			}
			return strings.TrimSpace(row[index])
		}

		host := get("hostname")
		ipText := get("ip")
		ip := net.ParseIP(ipText)
		if !validPublicIPv4(ip) || !validVPNGateHostname(host) {
			continue
		}
		country := strings.ToUpper(get("countryshort"))
		if len(country) != 2 || country[0] < 'A' || country[0] > 'Z' || country[1] < 'A' || country[1] > 'Z' {
			continue
		}
		score, scoreErr := strconv.ParseInt(get("score"), 10, 64)
		ping, pingErr := strconv.Atoi(get("ping"))
		sessions, sessionsErr := strconv.Atoi(get("numvpnsessions"))
		if scoreErr != nil || score <= 0 || pingErr != nil || ping < 0 || sessionsErr != nil || sessions < 0 {
			continue
		}

		profileText := get("openvpn_configdata_base64")
		if profileText == "" || len(profileText) > maxProfileBytes*2 {
			continue
		}
		profile, decodeErr := base64.StdEncoding.DecodeString(profileText)
		if decodeErr != nil {
			profile, decodeErr = base64.RawStdEncoding.DecodeString(profileText)
		}
		if decodeErr != nil || len(profile) == 0 || len(profile) > maxProfileBytes {
			continue
		}
		remoteHost, port, protocol, profileErr := parseOpenVPNRemote(string(profile), host, ipText)
		if profileErr != nil || protocol != "tcp" {
			continue
		}

		idHash := sha256.Sum256([]byte("vpn-gate|" + host + "|" + ipText + "|" + strconv.Itoa(port)))
		id := "vg-" + hex.EncodeToString(idHash[:8])
		name := host
		if !strings.Contains(strings.ToLower(name), "vpn") {
			name = "VPN Gate " + name
		}

		server := domain.Server{
			ID:                id,
			Name:              name,
			CountryCode:       country,
			Region:            get("countrylong"),
			Tier:              domain.TierFree,
			Protocol:          domain.ProtocolOpenVPN,
			Enabled:           true,
			Healthy:           false,
			LatencyMS:         ping,
			PacketLossPercent: 0,
			CapacityPercent:   0,
			Source:            "vpn-gate",
			EndpointHost:      remoteHost,
			EndpointPort:      port,
			QualityScore:      score,
			ActiveSessions:    sessions,
		}
		if err := server.Validate(); err != nil {
			continue
		}
		all = append(all, candidate{server: server, ip: ipText, port: port})
	}

	sort.SliceStable(all, func(i, j int) bool {
		if all[i].server.QualityScore == all[j].server.QualityScore {
			if all[i].server.LatencyMS == all[j].server.LatencyMS {
				return all[i].ip < all[j].ip
			}
			return all[i].server.LatencyMS < all[j].server.LatencyMS
		}
		return all[i].server.QualityScore > all[j].server.QualityScore
	})
	if limit > 0 && len(all) > limit {
		all = all[:limit]
	}
	return all, nil
}

func parseOpenVPNRemote(profile, expectedHost, expectedIP string) (string, int, string, error) {
	if len(profile) == 0 || len(profile) > maxProfileBytes {
		return "", 0, "", fmt.Errorf("OpenVPN profile size is invalid")
	}

	type remoteEndpoint struct {
		host     string
		port     int
		protocol string
	}
	var profileProtocol string
	var remotes []remoteEndpoint

	scanner := bufio.NewScanner(strings.NewReader(profile))
	scanner.Buffer(make([]byte, 4096), 64*1024)
	for scanner.Scan() {
		line := strings.TrimSpace(scanner.Text())
		if line == "" || strings.HasPrefix(line, "#") || strings.HasPrefix(line, ";") {
			continue
		}
		fields := strings.Fields(line)
		if len(fields) == 0 {
			continue
		}
		switch strings.ToLower(fields[0]) {
		case "proto":
			if len(fields) >= 2 {
				profileProtocol = normalizeOpenVPNProtocol(fields[1])
				if profileProtocol == "" {
					return "", 0, "", fmt.Errorf("unsupported OpenVPN protocol %q", fields[1])
				}
			}
		case "remote":
			if len(fields) < 3 {
				continue
			}
			host := strings.TrimSuffix(strings.ToLower(fields[1]), ".")
			expected := strings.TrimSuffix(strings.ToLower(expectedHost), ".")
			if host != expected && host != expected+".opengw.net" && !strings.EqualFold(host, expectedIP) {
				continue
			}
			port, err := strconv.Atoi(fields[2])
			if err != nil || port < 1 || port > 65535 {
				continue
			}
			remoteProtocol := ""
			if len(fields) >= 4 {
				remoteProtocol = normalizeOpenVPNProtocol(fields[3])
				if remoteProtocol == "" {
					return "", 0, "", fmt.Errorf("unsupported remote protocol %q", fields[3])
				}
			}
			remotes = append(remotes, remoteEndpoint{host: host, port: port, protocol: remoteProtocol})
		}
	}
	if err := scanner.Err(); err != nil {
		return "", 0, "", fmt.Errorf("read OpenVPN profile: %w", err)
	}

	for _, remote := range remotes {
		protocol := remote.protocol
		if protocol == "" {
			protocol = profileProtocol
		}
		if protocol == "" {
			protocol = "udp"
		}
		if protocol == "tcp" {
			return remote.host, remote.port, protocol, nil
		}
	}
	return "", 0, "", fmt.Errorf("profile has no matching TCP OpenVPN remote endpoint")
}
func normalizeOpenVPNProtocol(value string) string {
	switch strings.ToLower(strings.TrimSpace(value)) {
	case "tcp", "tcp-client", "tcp4", "tcp6", "tcp4-client", "tcp6-client":
		return "tcp"
	case "udp", "udp4", "udp6":
		return "udp"
	default:
		return ""
	}
}

func validVPNGateHostname(host string) bool {
	if len(host) == 0 || len(host) > 253 {
		return false
	}
	for _, char := range host {
		if !(char >= 'a' && char <= 'z') && !(char >= 'A' && char <= 'Z') &&
			!(char >= '0' && char <= '9') && char != '-' && char != '.' {
			return false
		}
	}
	return !strings.HasPrefix(host, ".") && !strings.HasSuffix(host, ".")
}

func validPublicIPv4(ip net.IP) bool {
	if ip == nil || ip.To4() == nil || !ip.IsGlobalUnicast() || ip.IsPrivate() ||
		ip.IsLoopback() || ip.IsLinkLocalUnicast() || ip.IsMulticast() || ip.IsUnspecified() {
		return false
	}
	blocked := []string{
		"0.0.0.0/8", "100.64.0.0/10", "192.0.0.0/24", "192.0.2.0/24",
		"192.88.99.0/24", "198.18.0.0/15", "198.51.100.0/24", "203.0.113.0/24",
		"240.0.0.0/4",
	}
	for _, block := range blocked {
		_, network, _ := net.ParseCIDR(block)
		if network.Contains(ip) {
			return false
		}
	}
	return true
}

func isAllowedVPNGateHost(host string) bool {
	host = strings.ToLower(strings.TrimSuffix(host, "."))
	return host == "www.vpngate.net" || host == "vpngate.net"
}
