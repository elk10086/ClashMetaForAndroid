package com.github.kr328.clash.design.util

import com.github.kr328.clash.core.model.Proxy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class ProxyOrderingTest {
    private fun proxy(name: String, type: String = "Shadowsocks", delay: Int = 0, group: Boolean = false) =
        Proxy(name = name, title = name, subtitle = type, type = type, delay = delay, isGroup = group)

    @Test fun pinsAutomaticThenFallbackAheadOfRegularNodes() {
        val regular = proxy("香港 01")
        val fallback = proxy("故障转移", "Fallback", group = true)
        val automatic = proxy("自动选择", "URLTest", group = true)
        assertEquals(listOf(automatic, fallback, regular),
            listOf(regular, fallback, automatic).withPinnedStrategies())
    }

    @Test fun pinsExactNamesEvenWhenWrappedInSelectorGroups() {
        val automatic = proxy(" 自动选择 ", "Selector", group = true)
        val fallback = proxy("故障转移", "Selector", group = true)
        val regular = proxy("日本 01")
        assertEquals(listOf(automatic, fallback, regular),
            listOf(regular, fallback, automatic).withPinnedStrategies())
    }

    @Test fun recognizesRenamedOrDecoratedStrategyGroupsByType() {
        val automatic = proxy("♻️ 智能优选", "url-test", group = true)
        val fallback = proxy("备用线路", "Fallback", group = true)
        val regular = proxy("香港 01")
        assertEquals(listOf(automatic, fallback, regular),
            listOf(fallback, regular, automatic).withPinnedStrategies())
    }

    @Test fun preservesDefaultTitleAndDelayOrderForOtherNodes() {
        val regular = listOf(proxy("香港", delay = 90), proxy("日本", delay = 30), proxy("新加坡", delay = 60))
        val automatic = proxy("自动选择", "URLTest", delay = 500, group = true)
        val fallback = proxy("故障转移", "Fallback", delay = -1, group = true)
        val all = regular + fallback + automatic
        for (input in listOf(all, all.sortedBy { it.title }, all.sortedBy { it.delay })) {
            val expectedPeers = input.filter { it !== automatic && it !== fallback }
            assertEquals(listOf(automatic, fallback) + expectedPeers, input.withPinnedStrategies())
        }
    }

    @Test fun keepsOrderWithinEachStrategyTypeStable() {
        val firstAuto = proxy("自动组 B", "URLTest", group = true)
        val secondAuto = proxy("自动组 A", "URLTest", group = true)
        val firstFallback = proxy("备用组 B", "Fallback", group = true)
        val secondFallback = proxy("备用组 A", "Fallback", group = true)
        val regular = proxy("香港 01")
        assertEquals(listOf(firstAuto, secondAuto, firstFallback, secondFallback, regular),
            listOf(firstFallback, regular, firstAuto, secondFallback, secondAuto).withPinnedStrategies())
    }

    @Test fun noSpecialEntriesLeavesOrderUnchanged() {
        val regular = listOf(proxy("香港自动选择节点"), proxy("故障转移专线"), proxy("日本 01"))
        assertEquals(regular, regular.withPinnedStrategies())
        assertEquals(emptyList<Proxy>(), emptyList<Proxy>().withPinnedStrategies())
    }

    @Test fun handlesEitherStrategyMissingWithoutAddingEntries() {
        val regular = proxy("香港 01")
        val automatic = proxy("自动选择", "URLTest", group = true)
        val fallback = proxy("故障转移", "Fallback", group = true)
        assertEquals(listOf(automatic, regular), listOf(regular, automatic).withPinnedStrategies())
        assertEquals(listOf(fallback, regular), listOf(regular, fallback).withPinnedStrategies())
    }

    @Test fun repeatedUpdatesDoNotChangeIdentityOrInputData() {
        val regular = proxy("香港 01")
        val fallback = proxy("故障转移", "Fallback", group = true)
        val automatic = proxy("自动选择", "URLTest", group = true)
        val input = mutableListOf(regular, fallback, automatic)
        val sorted = input.withPinnedStrategies()
        assertEquals(listOf(regular, fallback, automatic), input)
        assertEquals(sorted, sorted.withPinnedStrategies())
        assertSame(automatic, sorted[0])
        assertSame(fallback, sorted[1])
        assertSame(regular, sorted[2])
    }
}
