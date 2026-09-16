// Package version exposes the build version injected by release scripts.
package version

// Value is replaced with the root package.json version through -ldflags.
// Local `go test`/plain `go run` builds intentionally report "dev".
var Value = "dev"
