package share

import (
	"os"
	"path/filepath"
	"testing"
)

// fakeNet builds a sysfs-shaped tree: name -> (operstate, carrier or "").
func fakeNet(t *testing.T, links map[string][2]string) string {
	t.Helper()
	root := t.TempDir()
	for name, state := range links {
		dir := filepath.Join(root, name)
		if err := os.MkdirAll(dir, 0o750); err != nil {
			t.Fatal(err)
		}
		if err := os.WriteFile(filepath.Join(dir, "operstate"), []byte(state[0]+"\n"), 0o640); err != nil {
			t.Fatal(err)
		}
		if state[1] != "" {
			if err := os.WriteFile(filepath.Join(dir, "carrier"), []byte(state[1]+"\n"), 0o640); err != nil {
				t.Fatal(err)
			}
		}
	}
	return root
}

func TestOnline(t *testing.T) {
	cases := []struct {
		name  string
		links map[string][2]string
		want  bool
	}{
		{"radio off", map[string][2]string{"lo": {"unknown", "1"}, "wlan0": {"down", "0"}}, false},
		{"wifi up", map[string][2]string{"lo": {"unknown", "1"}, "wlan0": {"up", "1"}}, true},
		{"wired, no operstate file", map[string][2]string{"lo": {"unknown", "1"}, "eth0": {"", ""}}, false},
		{"only bridges and loopback", map[string][2]string{"lo": {"unknown", "1"}, "docker0": {"down", "0"}, "br-123": {"up", "1"}}, false},
		{"virtual link without carrier", map[string][2]string{"lo": {"unknown", "1"}, "tailscale0": {"unknown", ""}}, true},
	}
	for _, tc := range cases {
		if got := onlineIn(fakeNet(t, tc.links)); got != tc.want {
			t.Errorf("%s: got %v, want %v", tc.name, got, tc.want)
		}
	}
}
