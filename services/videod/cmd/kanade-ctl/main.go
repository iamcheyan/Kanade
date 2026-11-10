// Command kanade-ctl is the shell-visible client for videod.
//
// The PocketJS guest has no network capability in the rocknix-h700-kms profile,
// so its bridge command shells out to this binary instead: one short-lived
// process per call, a hard timeout, and the response body on stdout. Everything
// is stdlib net/http, so it cross-compiles to a static aarch64 binary with no
// cgo and runs on BaseOS's glibc 2.35.
//
//	kanade-ctl GET  /api/library?limit=50
//	kanade-ctl POST /api/watch_later/abc123
//	kanade-ctl PUT  /api/settings '{"theme":"dark"}'
//
// Exit codes: 0 ok, 2 bad usage, 3 transport error, 4 non-2xx response
// (the response body is still printed so the caller can show the reason).
package main

import (
	"fmt"
	"encoding/base64"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"strings"
	"time"
)

const defaultBase = "http://127.0.0.1:8732"

func main() {
	if len(os.Args) < 3 {
		fmt.Fprintln(os.Stderr, "usage: kanade-ctl <GET|POST|PUT|DELETE> <path> [body]")
		os.Exit(2)
	}
	method := strings.ToUpper(os.Args[1])
	path := os.Args[2]
	var body io.Reader
	if len(os.Args) > 3 {
		body = strings.NewReader(os.Args[3])
	}

	base := os.Getenv("KANADE_BASE")
	if base == "" {
		base = defaultBase
	}
	target := base + path
	if _, err := url.Parse(target); err != nil {
		fmt.Fprintln(os.Stderr, "kanade-ctl: bad url:", err)
		os.Exit(2)
	}

	client := &http.Client{
		Timeout: 8 * time.Second,
		Transport: &http.Transport{
			DialContext: (&net.Dialer{Timeout: 2 * time.Second}).DialContext,
			// A launcher poll must not keep a socket alive between calls: the
			// process exits immediately and videod is on loopback anyway.
			DisableKeepAlives: true,
		},
	}

	req, err := http.NewRequest(method, target, body)
	if err != nil {
		fmt.Fprintln(os.Stderr, "kanade-ctl:", err)
		os.Exit(2)
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set("Accept", "application/json")

	resp, err := client.Do(req)
	if err != nil {
		fmt.Fprintln(os.Stderr, "kanade-ctl: unreachable:", err)
		os.Exit(3)
	}
	defer resp.Body.Close()

	out, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		fmt.Fprintln(os.Stderr, "kanade-ctl: read:", err)
		os.Exit(3)
	}
	// Binary bodies (the cover pixels) cannot travel the bridge as text: the
	// guest receives one String. Base64 the octet-stream case so the caller
	// gets something it can decode back to bytes.
	if strings.HasPrefix(resp.Header.Get("Content-Type"), "application/octet-stream") {
		fmt.Print(base64.StdEncoding.EncodeToString(out))
	} else {
		os.Stdout.Write(out)
	}
	if resp.StatusCode < 200 || resp.StatusCode >= 300 {
		os.Exit(4)
	}
}
