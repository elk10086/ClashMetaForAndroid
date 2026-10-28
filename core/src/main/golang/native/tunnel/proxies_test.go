package tunnel

import (
	"errors"
	"testing"

	"github.com/metacubex/mihomo/adapter"
	"github.com/metacubex/mihomo/adapter/outboundgroup"
	C "github.com/metacubex/mihomo/constant"
	coreTunnel "github.com/metacubex/mihomo/tunnel"
)

type patchSelectorTestGroup struct {
	outboundgroup.ProxyGroup
	selected string
	setError error
}

func (g *patchSelectorTestGroup) Set(name string) error {
	if g.setError != nil {
		return g.setError
	}
	g.selected = name
	return nil
}

func (g *patchSelectorTestGroup) ForceSet(name string) {
	g.selected = name
}

func usePatchSelectorTestGroup(t *testing.T, group *patchSelectorTestGroup) {
	t.Helper()
	previousProxies, previousProviders := coreTunnel.Proxies(), coreTunnel.Providers()
	coreTunnel.UpdateProxies(map[string]C.Proxy{
		"线路": adapter.NewProxy(group),
	}, nil)
	t.Cleanup(func() {
		coreTunnel.UpdateProxies(previousProxies, previousProviders)
	})
}

func TestPatchSelectorRejectsFailedSelection(t *testing.T) {
	group := &patchSelectorTestGroup{
		selected: "故障转移",
		setError: errors.New("proxy not exist"),
	}
	usePatchSelectorTestGroup(t, group)

	if PatchSelector("线路", "已删除线路") {
		t.Fatal("failed selection must not be reported as successful or persisted")
	}
	if group.selected != "故障转移" {
		t.Fatalf("failed selection changed current line to %q", group.selected)
	}
}

func TestPatchSelectorAcceptsSuccessfulSelection(t *testing.T) {
	group := &patchSelectorTestGroup{selected: "自动选择"}
	usePatchSelectorTestGroup(t, group)

	if !PatchSelector("线路", "故障转移") {
		t.Fatal("valid selection must succeed")
	}
	if group.selected != "故障转移" {
		t.Fatalf("selected %q instead of fallback", group.selected)
	}
}

func TestPatchSelectorRejectsMissingGroup(t *testing.T) {
	group := &patchSelectorTestGroup{selected: "故障转移"}
	usePatchSelectorTestGroup(t, group)

	if PatchSelector("不存在的分组", "自动选择") {
		t.Fatal("missing group must not report success")
	}
	if group.selected != "故障转移" {
		t.Fatal("missing group changed the existing selection")
	}
}
