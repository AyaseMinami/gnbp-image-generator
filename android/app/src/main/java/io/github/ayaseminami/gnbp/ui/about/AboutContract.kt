package io.github.ayaseminami.gnbp.ui.about

import android.content.ActivityNotFoundException
import android.content.Intent
import androidx.core.net.toUri
import io.github.ayaseminami.gnbp.BuildConfig

private const val REPOSITORY_URL = "https://github.com/AyaseMinami/gnbp-image-generator"

enum class AboutDestination(val url: String) {
    Repository(REPOSITORY_URL),
    License("$REPOSITORY_URL/blob/main/LICENSE"),
    Releases("$REPOSITORY_URL/releases"),
}

data class AboutAppInfo(
    val versionName: String,
    val versionCode: Int,
    val author: String = "Ayase Minami",
) {
    companion object {
        fun current() = AboutAppInfo(
            versionName = BuildConfig.VERSION_NAME,
            versionCode = BuildConfig.VERSION_CODE,
        )
    }
}

class AboutPageLauncher(
    private val startActivity: (Intent) -> Unit,
) {
    fun open(destination: AboutDestination): Boolean = try {
        startActivity(Intent(Intent.ACTION_VIEW, destination.url.toUri()))
        true
    } catch (_: ActivityNotFoundException) {
        false
    }
}
