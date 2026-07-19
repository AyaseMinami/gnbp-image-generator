package io.github.ayaseminami.gnbp.provider.transport

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build

internal class AndroidLocalNetworkPermissionChecker(
    private val context: Context,
) : LocalNetworkPermissionChecker {
    override fun isGrantedWhenRequired(): Boolean =
        Build.VERSION.SDK_INT < 37 ||
            context.checkSelfPermission(Manifest.permission.ACCESS_LOCAL_NETWORK) ==
            PackageManager.PERMISSION_GRANTED
}
