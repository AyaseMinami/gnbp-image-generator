package io.github.ayaseminami.gnbp.provider.transport

import android.content.Context
import android.net.ConnectivityManager
import android.os.Build
import androidx.annotation.RequiresApi

internal fun interface Nat64PrefixProvider {
    fun current(): Nat64Prefix?
}

internal class AndroidNat64PrefixProvider(
    context: Context,
) : Nat64PrefixProvider {
    private val connectivityManager = context.applicationContext
        .getSystemService(ConnectivityManager::class.java)

    override fun current(): Nat64Prefix =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) currentApi30() else Nat64Prefix.WellKnown

    @RequiresApi(Build.VERSION_CODES.R)
    private fun currentApi30(): Nat64Prefix {
        val linkProperties = runCatching {
            connectivityManager.activeNetwork?.let(connectivityManager::getLinkProperties)
        }.getOrNull() ?: return Nat64Prefix.WellKnown
        val platformPrefix = linkProperties.nat64Prefix?.let { prefix ->
            runCatching { Nat64Prefix.fromAddress(prefix.address, prefix.prefixLength) }.getOrNull()
        }
        return platformPrefix ?: Nat64Prefix.WellKnown
    }
}

internal fun createAndroidProviderHttpTransport(context: Context): ProviderHttpTransport =
    OkHttpProviderHttpTransport(
        localNetworkPermissionChecker = AndroidLocalNetworkPermissionChecker(context),
        nat64PrefixProvider = AndroidNat64PrefixProvider(context),
    )
