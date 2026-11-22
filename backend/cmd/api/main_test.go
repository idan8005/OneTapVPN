package main

import "testing"

func TestListenAddressPrefersExplicitAddress(t *testing.T) {
	t.Setenv("RAAHAT_LISTEN_ADDR", "127.0.0.1:9090")
	t.Setenv("PORT", "8088")
	if got := listenAddress(); got != "127.0.0.1:9090" {
		t.Fatalf("listenAddress() = %q, want explicit address", got)
	}
}

func TestListenAddressUsesManagedPlatformPort(t *testing.T) {
	t.Setenv("RAAHAT_LISTEN_ADDR", "")
	t.Setenv("PORT", "8088")
	if got := listenAddress(); got != "0.0.0.0:8088" {
		t.Fatalf("listenAddress() = %q, want 0.0.0.0:8088", got)
	}
}

func TestListenAddressKeepsSafeLocalDefault(t *testing.T) {
	t.Setenv("RAAHAT_LISTEN_ADDR", "")
	t.Setenv("PORT", "")
	if got := listenAddress(); got != "127.0.0.1:8080" {
		t.Fatalf("listenAddress() = %q, want loopback default", got)
	}
}
