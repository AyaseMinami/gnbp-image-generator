package io.github.ayaseminami.gnbp

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AppConfigurationTest {
    @Test
    fun `application identity and version shape match the release contract`() {
        assertEquals("io.github.ayaseminami.gnbp", BuildConfig.APPLICATION_ID)
        assertTrue(BuildConfig.VERSION_CODE > 0)
        assertTrue(BuildConfig.VERSION_NAME.matches(Regex("\\d+\\.\\d+\\.\\d+")))
    }
}
