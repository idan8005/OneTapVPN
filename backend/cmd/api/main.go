package main

import (
	"context"
	"errors"
	"log"
	"net"
	"net/http"
	"os"
	"os/signal"
	"strings"
	"syscall"
	"time"

	"github.com/idan8005/OneTapVPN/backend/internal/catalog"
	"github.com/idan8005/OneTapVPN/backend/internal/httpapi"
)

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

func run() error {
	repository, err := catalog.Load(os.Getenv("RAAHAT_CATALOG_FILE"))
	if err != nil {
		return err
	}

	address := envOr("RAAHAT_LISTEN_ADDR", "127.0.0.1:8080")
	certFile := os.Getenv("RAAHAT_TLS_CERT_FILE")
	keyFile := os.Getenv("RAAHAT_TLS_KEY_FILE")
	allowProxyHTTP := strings.EqualFold(os.Getenv("RAAHAT_TLS_TERMINATED_BY_PROXY"), "true")

	if (certFile == "") != (keyFile == "") {
		return errors.New("both RAAHAT_TLS_CERT_FILE and RAAHAT_TLS_KEY_FILE must be set together")
	}
	if certFile == "" && !allowProxyHTTP && !isLoopbackAddress(address) {
		return errors.New("refusing non-loopback plain HTTP; configure TLS or a trusted TLS-terminating proxy")
	}

	server := &http.Server{
		Addr:              address,
		Handler:           httpapi.NewHandler(repository),
		ReadHeaderTimeout: 5 * time.Second,
		ReadTimeout:       15 * time.Second,
		WriteTimeout:      15 * time.Second,
		IdleTimeout:       60 * time.Second,
	}

	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()

	errorsChannel := make(chan error, 1)
	go func() {
		log.Printf("Raahat control plane listening on %s", address)
		if certFile != "" {
			errorsChannel <- server.ListenAndServeTLS(certFile, keyFile)
			return
		}
		errorsChannel <- server.ListenAndServe()
	}()

	select {
	case err := <-errorsChannel:
		if errors.Is(err, http.ErrServerClosed) {
			return nil
		}
		return err
	case <-ctx.Done():
		shutdownContext, cancel := context.WithTimeout(context.Background(), 10*time.Second)
		defer cancel()
		return server.Shutdown(shutdownContext)
	}
}

func envOr(key, fallback string) string {
	if value := strings.TrimSpace(os.Getenv(key)); value != "" {
		return value
	}
	return fallback
}

func isLoopbackAddress(address string) bool {
	host, _, err := net.SplitHostPort(address)
	if err != nil {
		return false
	}
	if strings.EqualFold(host, "localhost") {
		return true
	}
	ip := net.ParseIP(host)
	return ip != nil && ip.IsLoopback()
}
