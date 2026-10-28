package tunnel

import (
	"encoding/json"
	"net"
	"sort"
	"strings"

	"github.com/dlclark/regexp2"

	"github.com/metacubex/mihomo/adapter/outboundgroup"
	C "github.com/metacubex/mihomo/constant"
	"github.com/metacubex/mihomo/constant/provider"
	"github.com/metacubex/mihomo/log"
	"github.com/metacubex/mihomo/tunnel"
)

type SortMode int

const (
	Default SortMode = iota
	Title
	Delay
)

type Proxy struct {
	Name     string `json:"name"`
	Title    string `json:"title"`
	Subtitle string `json:"subtitle"`
	Server   string `json:"server"`
	Type     string `json:"type"`
	Delay    int    `json:"delay"`
	IsGroup  bool   `json:"isGroup"`
}

func proxyServer(proxy C.Proxy) string {
	address := strings.TrimSpace(proxy.Addr())
	if address == "" {
		return ""
	}

	host, _, err := net.SplitHostPort(address)
	if err == nil {
		return strings.Trim(host, "[]")
	}

	return strings.Trim(address, "[]")
}

func proxyDelay(proxy C.Proxy, testURL string) int {
	state, tested := proxy.ExtraDelayHistories()[testURL]
	if !tested || len(state.History) == 0 {
		return 0
	}

	last := state.History[len(state.History)-1]
	if !state.Alive || last.Delay == 0 {
		return -1
	}

	return int(last.Delay)
}

func proxyGroupTestURL(group outboundgroup.ProxyGroup) string {
	// Group implementations already expose their configured health-check URL
	// through MarshalJSON. Read that value here without duplicating a concrete
	// type switch for every group implementation.
	var metadata struct {
		TestURL string `json:"testUrl"`
	}
	if payload, err := group.MarshalJSON(); err == nil {
		if err := json.Unmarshal(payload, &metadata); err == nil {
			if testURL := strings.TrimSpace(metadata.TestURL); testURL != "" {
				return testURL
			}
		}
	}

	return C.DefaultTestURL
}

type ProxyGroup struct {
	Type    string   `json:"type"`
	Now     string   `json:"now"`
	Proxies []*Proxy `json:"proxies"`
}

type sortableProxyList struct {
	list []*Proxy
	less func(a, b *Proxy) bool
}

func (s *sortableProxyList) Len() int {
	return len(s.list)
}

func (s *sortableProxyList) Less(i, j int) bool {
	return s.less(s.list[i], s.list[j])
}

func (s *sortableProxyList) Swap(i, j int) {
	s.list[i], s.list[j] = s.list[j], s.list[i]
}

func QueryProxyGroupNames(excludeNotSelectable bool) []string {
	mode := tunnel.Mode()

	if mode == tunnel.Direct {
		return []string{}
	}

	global := tunnel.Proxies()["GLOBAL"].Adapter().(outboundgroup.ProxyGroup)
	proxies := global.Providers()[0].Proxies()
	result := make([]string, 0, len(proxies)+1)

	if mode == tunnel.Global {
		result = append(result, "GLOBAL")
	}

	for _, p := range proxies {
		if g, ok := p.Adapter().(outboundgroup.ProxyGroup); ok {
			if !excludeNotSelectable || p.Type() == C.Selector {
				if g.Hidden() {
					continue
				}
				result = append(result, p.Name())
			}
		}
	}

	return result
}

func QueryProxyGroup(name string, sortMode SortMode, uiSubtitlePattern *regexp2.Regexp) *ProxyGroup {
	p := tunnel.Proxies()[name]

	if p == nil {
		log.Warnln("Query group `%s`: not found", name)

		return nil
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		log.Warnln("Query group `%s`: invalid type %s", name, p.Type().String())

		return nil
	}

	// The group owns the URL used to health-check all of its members. Use it
	// consistently instead of picking an arbitrary key from each proxy's
	// delay-history map (Go map iteration order is intentionally random).
	proxies := convertProxies(g.Proxies(), proxyGroupTestURL(g), uiSubtitlePattern)
	// 	proxies := collectProviders(g.Providers(), uiSubtitlePattern)

	switch sortMode {
	case Title:
		wrapper := &sortableProxyList{
			list: proxies,
			less: func(a, b *Proxy) bool {
				return strings.Compare(a.Title, b.Title) < 0
			},
		}

		sort.Sort(wrapper)
	case Delay:
		wrapper := &sortableProxyList{
			list: proxies,
			less: func(a, b *Proxy) bool {
				return a.Delay < b.Delay
			},
		}

		sort.Sort(wrapper)
	case Default:
	default:
	}

	return &ProxyGroup{
		Type:    g.Type().String(),
		Now:     g.Now(),
		Proxies: proxies,
	}
}

func PatchSelector(selector, name string) bool {
	p := tunnel.Proxies()[selector]

	if p == nil {
		log.Warnln("Patch selector `%s`: not found", selector)

		return false
	}

	g, ok := p.Adapter().(outboundgroup.ProxyGroup)
	if !ok {
		log.Warnln("Patch selector `%s`: invalid type %s", selector, p.Type().String())

		return false
	}

	s, ok := g.(outboundgroup.SelectAble)
	if !ok {
		log.Warnln("Patch selector `%s`: invalid type %s", selector, p.Type().String())

		return false
	}

	if err := s.Set(name); err != nil {
		log.Warnln("Patch selector `%s`: %s", selector, err.Error())
		return false
	}

	log.Infoln("Patch selector %s -> %s", selector, name)

	closeConnByGroup(selector)

	return true
}

func convertProxies(proxies []C.Proxy, testURL string, uiSubtitlePattern *regexp2.Regexp) []*Proxy {
	result := make([]*Proxy, 0, 128)

	for _, p := range proxies {
		name := p.Name()
		title := name
		subtitle := p.Type().String()

		if uiSubtitlePattern != nil {
			if _, ok := p.Adapter().(outboundgroup.ProxyGroup); !ok {
				runes := []rune(name)
				match, err := uiSubtitlePattern.FindRunesMatch(runes)
				if err == nil && match != nil {
					title = string(runes[:match.Index]) + string(runes[match.Index+match.Length:])
					subtitle = string(runes[match.Index : match.Index+match.Length])
				}
			}
		}
		_, isGroup := p.Adapter().(outboundgroup.ProxyGroup)

		result = append(result, &Proxy{
			Name:     name,
			Title:    strings.TrimSpace(title),
			Subtitle: strings.TrimSpace(subtitle),
			Server:   proxyServer(p),
			Type:     p.Type().String(),
			Delay:    proxyDelay(p, testURL),
			IsGroup:  isGroup,
		})
	}
	return result
}

func collectProviders(providers []provider.ProxyProvider, testURL string, uiSubtitlePattern *regexp2.Regexp) []*Proxy {
	result := make([]*Proxy, 0, 128)

	for _, p := range providers {
		for _, px := range p.Proxies() {
			name := px.Name()
			title := name
			subtitle := px.Type().String()

			if uiSubtitlePattern != nil {
				if _, ok := px.Adapter().(outboundgroup.ProxyGroup); !ok {
					runes := []rune(name)
					match, err := uiSubtitlePattern.FindRunesMatch(runes)
					if err == nil && match != nil {
						title = string(runes[:match.Index]) + string(runes[match.Index+match.Length:])
						subtitle = string(runes[match.Index : match.Index+match.Length])
					}
				}
			}

			_, isGroup := px.Adapter().(outboundgroup.ProxyGroup)

			result = append(result, &Proxy{
				Name:     name,
				Title:    strings.TrimSpace(title),
				Subtitle: strings.TrimSpace(subtitle),
				Server:   proxyServer(px),
				Type:     px.Type().String(),
				Delay:    proxyDelay(px, testURL),
				IsGroup:  isGroup,
			})
		}
	}

	return result
}
