package io.github.ayaseminami.gnbp

import org.junit.Assert.assertEquals
import org.junit.Test

class AppConfigurationTest {
    @Test
    fun `application identity matches the approved release contract`() {
        assertEquals("io.github.ayaseminami.gnbp", BuildConfig.APPLICATION_ID)
        assertEquals("0.1.0", BuildConfig.VERSION_NAME)
    }
}
