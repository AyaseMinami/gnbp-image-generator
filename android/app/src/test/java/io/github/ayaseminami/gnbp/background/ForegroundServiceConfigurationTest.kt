package io.github.ayaseminami.gnbp.background

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import androidx.test.core.app.ApplicationProvider
import io.github.ayaseminami.gnbp.GnbpApplication
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ForegroundServiceConfigurationTest {
    @Test
    fun `manifest registers a private data-sync foreground service`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val packageInfo = context.packageManager.getPackageInfo(
            context.packageName,
            PackageManager.GET_PERMISSIONS,
        )
        val requestedPermissions = packageInfo.requestedPermissions.orEmpty().toSet()
        val serviceInfo = context.packageManager.getServiceInfo(
            ComponentName(context, GenerationForegroundService::class.java),
            PackageManager.ComponentInfoFlags.of(0),
        )

        assertEquals(GnbpApplication::class.java.name, context.applicationInfo.className)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE in requestedPermissions)
        assertTrue(Manifest.permission.FOREGROUND_SERVICE_DATA_SYNC in requestedPermissions)
        assertFalse(serviceInfo.exported)
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC, serviceInfo.foregroundServiceType)
    }
}
