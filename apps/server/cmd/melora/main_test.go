package main

import (
	"bytes"
	"errors"
	"log/slog"
	"strings"
	"testing"
)

func TestDiagnosticLogsKeepReasonButRedactSecrets(t *testing.T) {
	const token = "test-only-private-token-1234567890"
	var buffer bytes.Buffer
	logger := slog.New(slog.NewJSONHandler(&buffer, &slog.HandlerOptions{ReplaceAttr: redactLogs(token)}))
	logger.Error("startup failed", "error", errors.New("permission denied; token="+token+" https://media.example/audio?signature=PRIVATE"))
	text := buffer.String()
	if strings.Contains(text, token) || strings.Contains(text, "signature=PRIVATE") || strings.Contains(text, "https://media") {
		t.Fatal("sensitive data leaked", text)
	}
	if !strings.Contains(text, "permission denied") || !strings.Contains(text, "startup failed") {
		t.Fatal("useful diagnostics were dropped")
	}
}
