package config

import (
	"strings"
	"testing"
)

func TestReplaceProxyServersHonorsWhitelistAndKeepsSNI(t *testing.T) {
	source := []byte(`proxies:
  - name: allowed
    type: vmess
    server: edge.example.com
    port: 443
    sni: tls.example.com
  - name: blocked
    type: ss
    server: edge.example.com
    port: 8443
`)
	runtime := ddnsRuntime{}
	ensureDdnsRuntime(&runtime)
	updated, changed := replaceProxyServers(source, ddnsConfiguration{
		Enabled: true, Protocols: []string{"vmess"},
		Mapping: map[string]string{"edge.example.com": "203.0.113.10,203.0.113.11"},
	}, &runtime)
	if !changed {
		t.Fatal("expected DDNS replacement")
	}
	text := string(updated)
	if !strings.Contains(text, "server: 203.0.113.10") {
		t.Fatalf("allowed server was not replaced:\n%s", text)
	}
	if !strings.Contains(text, "server: edge.example.com") {
		t.Fatalf("blocked server was replaced:\n%s", text)
	}
	if !strings.Contains(text, "sni: tls.example.com") {
		t.Fatalf("SNI changed:\n%s", text)
	}
}

func TestReplaceProxyServersKeepsStableAssignment(t *testing.T) {
	source := []byte("proxies:\n  - name: node\n    type: vmess\n    server: edge.example.com\n")
	runtime := ddnsRuntime{}
	ensureDdnsRuntime(&runtime)
	cfg := ddnsConfiguration{Enabled: true, Protocols: []string{"vmess"}, Mapping: map[string]string{"edge.example.com": "203.0.113.10,203.0.113.11"}}
	first, changed := replaceProxyServers(source, cfg, &runtime)
	if !changed {
		t.Fatal("expected first replacement")
	}
	second, _ := replaceProxyServers(first, cfg, &runtime)
	if string(second) != string(first) {
		t.Fatalf("assignment changed:\n%s\n%s", first, second)
	}
}

func TestReplaceProxyServersRejectsEmptyWhitelist(t *testing.T) {
	source := []byte("proxies:\n  - name: node\n    type: vmess\n    server: edge.example.com\n")
	runtime := ddnsRuntime{}
	ensureDdnsRuntime(&runtime)
	updated, changed := replaceProxyServers(source, ddnsConfiguration{Enabled: true, Mapping: map[string]string{"edge.example.com": "203.0.113.10"}}, &runtime)
	if changed || string(updated) != string(source) {
		t.Fatal("empty whitelist must not replace nodes")
	}
}
