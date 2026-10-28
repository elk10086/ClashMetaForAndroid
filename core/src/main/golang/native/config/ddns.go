package config

import (
	"encoding/json"
	"fmt"
	"net"
	"os"
	P "path"
	"sort"
	"strconv"
	"strings"

	"github.com/metacubex/mihomo/common/yaml"
)

type ddnsConfiguration struct {
	Enabled   bool              `json:"enabled"`
	Protocols []string          `json:"protocols"`
	Mapping   map[string]string `json:"mapping"`
}

type ddnsRuntime struct {
	Bindings map[string]string `json:"bindings"`
	Assigned map[string]string `json:"assigned"`
	Cursors  map[string]int    `json:"cursors"`
}

func applyDdnsToProfile(profileDir, configPath string, captureOriginal bool) ([]byte, bool, error) {
	filesDir := P.Dir(profileDir)
	if P.Base(filesDir) == "imported" || P.Base(filesDir) == "pending" {
		filesDir = P.Dir(filesDir)
	} else if P.Base(filesDir) == "profiles-runtime" {
		// Encrypted profiles are decrypted into <app-cache>/profiles-runtime/<uuid>
		// before loading. DDNS state remains in <app-files>, beside the cache dir.
		filesDir = P.Join(P.Dir(P.Dir(filesDir)), "files")
	}
	originalPath := configPath + ".origin.yaml"
	if captureOriginal {
		if profile, readErr := os.ReadFile(configPath); readErr == nil {
			_ = os.WriteFile(originalPath, profile, 0600)
		}
	}
	configBytes, err := os.ReadFile(P.Join(filesDir, "ddns-config.json"))
	if err != nil {
		return nil, false, nil
	}
	var cfg ddnsConfiguration
	if json.Unmarshal(configBytes, &cfg) != nil {
		return nil, false, nil
	}
	original, originalErr := os.ReadFile(originalPath)
	if !cfg.Enabled || len(cfg.Protocols) == 0 || len(cfg.Mapping) == 0 {
		if originalErr == nil {
			current, _ := os.ReadFile(configPath)
			if string(current) != string(original) {
				return current, true, os.WriteFile(configPath, original, 0600)
			}
		}
		return nil, false, nil
	}

	runtimePath := P.Join(filesDir, "ddns-runtime.json")
	runtime := ddnsRuntime{}
	if bytes, readErr := os.ReadFile(runtimePath); readErr == nil {
		_ = json.Unmarshal(bytes, &runtime)
	}
	ensureDdnsRuntime(&runtime)

	profile := original
	if originalErr != nil {
		profile, err = os.ReadFile(configPath)
		if err != nil {
			return nil, false, err
		}
	}
	updated, changed := replaceProxyServers(profile, cfg, &runtime)
	if !changed {
		return nil, false, nil
	}
	if err := os.WriteFile(configPath, updated, 0600); err != nil {
		return nil, false, err
	}
	if bytes, marshalErr := json.Marshal(runtime); marshalErr == nil {
		_ = os.WriteFile(runtimePath, bytes, 0600)
	}
	return profile, true, nil
}

func ensureDdnsRuntime(runtime *ddnsRuntime) {
	if runtime.Bindings == nil {
		runtime.Bindings = map[string]string{}
	}
	if runtime.Assigned == nil {
		runtime.Assigned = map[string]string{}
	}
	if runtime.Cursors == nil {
		runtime.Cursors = map[string]int{}
	}
}

func restoreDdnsOriginal(configPath string) bool {
	original, err := os.ReadFile(configPath + ".origin.yaml")
	if err != nil {
		return false
	}
	return os.WriteFile(configPath, original, 0600) == nil
}

func replaceProxyServers(source []byte, cfg ddnsConfiguration, runtime *ddnsRuntime) ([]byte, bool) {
	var document map[string]any
	if yaml.Unmarshal(source, &document) != nil {
		return source, false
	}
	proxies, ok := document["proxies"].([]any)
	if !ok {
		// mihomo's YAML decoder may produce the concrete map slice directly.
		mapped, mappedOK := document["proxies"].([]map[string]any)
		if !mappedOK {
			return source, false
		}
		proxies = make([]any, len(mapped))
		for index := range mapped {
			proxies[index] = mapped[index]
		}
	}
	whitelist := map[string]bool{}
	for _, protocol := range cfg.Protocols {
		whitelist[strings.ToLower(strings.TrimSpace(protocol))] = true
	}
	pools := map[string][]string{}
	for key, raw := range cfg.Mapping {
		if values := parseDdnsCandidates(raw); len(values) > 0 {
			pools[strings.ToLower(strings.TrimSpace(key))] = values
		}
	}
	changed := false
	for _, item := range proxies {
		proxy, ok := item.(map[string]any)
		if !ok || !whitelist[strings.ToLower(strings.TrimSpace(asString(proxy["type"])))] {
			continue
		}
		name, server := strings.TrimSpace(asString(proxy["name"])), strings.TrimSpace(asString(proxy["server"]))
		if name == "" || server == "" {
			continue
		}
		key := inferDdnsKey(name, hostOnly(server), asString(proxy["sni"]), pools, runtime)
		candidates := pools[key]
		if len(candidates) == 0 {
			continue
		}
		selected := stableDdnsCandidate(name, server, candidates, runtime)
		if selected == "" {
			cursor := runtime.Cursors[key] % len(candidates)
			selected = candidates[cursor]
			runtime.Cursors[key] = cursor + 1
		}
		runtime.Bindings[name], runtime.Assigned[name] = key, hostOnly(selected)
		originalPort := asString(proxy["port"])
		selectedHost, selectedPort := splitHostPort(selected)
		if _, hasPort := proxy["port"]; hasPort {
			proxy["server"] = selectedHost
			if selectedPort != "" {
				if port, parseErr := strconv.Atoi(selectedPort); parseErr == nil {
					proxy["port"] = port
				} else {
					proxy["port"] = selectedPort
				}
			}
		} else {
			proxy["server"] = selected
		}
		changed = changed || asString(proxy["server"]) != server || asString(proxy["port"]) != originalPort
	}
	if !changed {
		return source, false
	}
	updated, err := yaml.Marshal(document)
	if err != nil {
		return source, false
	}
	return updated, true
}

func parseDdnsCandidates(raw string) []string {
	trimmed := strings.Trim(strings.TrimSpace(raw), "[]")
	parts := strings.FieldsFunc(trimmed, func(r rune) bool {
		return r == ',' || r == ';' || r == '|' || r == '、' || r == '，' || r == ' ' || r == '\n' || r == '\t'
	})
	seen, values := map[string]bool{}, []string{}
	for _, part := range parts {
		value := strings.Trim(strings.TrimSpace(part), "\"'")
		if value != "" && !seen[strings.ToLower(value)] {
			seen[strings.ToLower(value)] = true
			values = append(values, value)
		}
	}
	sort.Strings(values)
	return values
}

func inferDdnsKey(name, host, sni string, pools map[string][]string, runtime *ddnsRuntime) string {
	for _, candidate := range []string{runtime.Bindings[name], sni, host} {
		key := strings.ToLower(strings.TrimSpace(candidate))
		if _, ok := pools[key]; ok {
			return key
		}
	}
	for key, candidates := range pools {
		for _, candidate := range candidates {
			if equalHost(host, candidate) || equalHost(runtime.Assigned[name], candidate) {
				return key
			}
		}
	}
	return ""
}

func stableDdnsCandidate(name, server string, candidates []string, runtime *ddnsRuntime) string {
	for _, current := range []string{runtime.Assigned[name], server} {
		for _, candidate := range candidates {
			if equalHost(current, candidate) {
				return candidate
			}
		}
	}
	return ""
}

func equalHost(left, right string) bool { return strings.EqualFold(hostOnly(left), hostOnly(right)) }
func hostOnly(value string) string      { host, _ := splitHostPort(value); return strings.Trim(host, "[]") }
func splitHostPort(value string) (string, string) {
	value = strings.TrimSpace(value)
	if host, port, err := net.SplitHostPort(value); err == nil {
		return host, port
	}
	if strings.Count(value, ":") == 1 {
		parts := strings.SplitN(value, ":", 2)
		if _, err := strconv.Atoi(parts[1]); err == nil {
			return parts[0], parts[1]
		}
	}
	return value, ""
}
func asString(value any) string {
	if value == nil {
		return ""
	}
	return strings.TrimSpace(strings.Trim(fmt.Sprint(value), "\""))
}
