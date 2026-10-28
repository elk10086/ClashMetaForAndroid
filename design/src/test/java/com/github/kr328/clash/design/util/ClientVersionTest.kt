package com.github.kr328.clash.design.util

import org.junit.Assert.assertEquals
import org.junit.Test

class ClientVersionTest {
    @Test fun appendsProToTheVersionDisplay() {
        assertEquals("v1.2.3 pro", ClientVersion.display("1.2.3"))
    }

    @Test fun doesNotDuplicateAnExistingVersionPrefix() {
        assertEquals("v1.2.3 pro", ClientVersion.display("v1.2.3"))
        assertEquals("v1.2.3 pro", ClientVersion.display(" V1.2.3 "))
    }

    @Test fun preservesBuildSuffixes() {
        assertEquals("v1.2.3.Meta.debug pro", ClientVersion.display("1.2.3.Meta.debug"))
    }

    @Test fun missingVersionNeverShowsNull() {
        assertEquals("v— pro", ClientVersion.display(null))
        assertEquals("v— pro", ClientVersion.display(" "))
    }
}
