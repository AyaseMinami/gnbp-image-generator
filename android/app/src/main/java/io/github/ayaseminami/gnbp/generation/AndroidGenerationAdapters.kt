package io.github.ayaseminami.gnbp.generation

import android.content.Context
import io.github.ayaseminami.gnbp.persistence.profile.ProviderKind
import io.github.ayaseminami.gnbp.persistence.profile.ProviderProfile
import io.github.ayaseminami.gnbp.provider.GeminiProvider
import io.github.ayaseminami.gnbp.provider.ImageGenerationProvider
import io.github.ayaseminami.gnbp.provider.OpenAiProvider
import io.github.ayaseminami.gnbp.provider.transport.createAndroidProviderHttpTransport

internal class AndroidGenerationProviderFactory(
    context: Context,
) : GenerationProviderFactory {
    private val transport = createAndroidProviderHttpTransport(context.applicationContext)

    override fun create(profile: ProviderProfile): ImageGenerationProvider =
        when (profile.providerKind) {
            ProviderKind.Gemini -> GeminiProvider(
                transport = transport,
                binding = profile.binding,
                apiKey = profile.apiKey,
            )
            ProviderKind.OpenAiCompatible -> OpenAiProvider(
                transport = transport,
                binding = profile.binding,
                apiKey = profile.apiKey,
            )
        }
}
