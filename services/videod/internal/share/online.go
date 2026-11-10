package share

// Reachability gate for a pull.
//
// "Pull when Wi-Fi is up" is the intent, but the service cannot see the
// panel's Wi-Fi state and an 8 second dial timeout is a poor way to answer
// "there is no network". A carrier on any non-loopback interface is the
// cheap local proxy: it is false in exactly the case that matters (nothing
// plugged in, radio off) and it costs no packets.

import (
	"os"
	"path/filepath"
	"strings"
)

// Online reports whether some real interface has a carrier.
func Online() bool { return onlineIn("/sys/class/net") }

func onlineIn(root string) bool {
	entries, err := os.ReadDir(root)
	if err != nil {
		// No sysfs view: do not block the pull, let the dial decide.
		return true
	}
	for _, entry := range entries {
		name := entry.Name()
		if name == "lo" || strings.HasPrefix(name, "docker") || strings.HasPrefix(name, "br-") ||
			strings.HasPrefix(name, "veth") || strings.HasPrefix(name, "virbr") {
			continue
		}
		// A carrier file may be absent on virtual links; operstate covers those.
		if raw, err := os.ReadFile(filepath.Join(root, name, "carrier")); err == nil {
			if strings.TrimSpace(string(raw)) == "1" {
				return true
			}
			continue
		}
		if raw, err := os.ReadFile(filepath.Join(root, name, "operstate")); err == nil {
			state := strings.TrimSpace(string(raw))
			if state == "up" || state == "unknown" {
				return true
			}
		}
	}
	return false
}
