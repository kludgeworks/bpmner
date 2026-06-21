/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.llm

import com.embabel.agent.openai.OpenAiCompatibleModelFactory
import com.embabel.agent.openai.StandardOpenAiOptionsConverter
import com.embabel.agent.spi.common.RetryProperties
import com.embabel.agent.spi.support.springai.SpringAiLlmService
import com.embabel.common.ai.autoconfig.ProviderInitialization
import com.embabel.common.ai.autoconfig.RegisteredModel
import com.embabel.common.ai.model.PricingModel
import io.micrometer.observation.ObservationRegistry
import org.springframework.beans.factory.ObjectProvider
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.beans.factory.config.ConfigurableBeanFactory
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.client.RestClient
import org.springframework.web.reactive.function.client.WebClient

private const val PROVIDER = "OpenRouter"
private const val DEFAULT_MAX_ATTEMPTS = 10
private const val DEFAULT_BACKOFF_MILLIS = 5000L
private const val DEFAULT_BACKOFF_MULTIPLIER = 5.0
private const val DEFAULT_BACKOFF_MAX_INTERVAL = 180000L
private const val BASE_URL = "https://openrouter.ai/api/v1"
private const val COMPLETIONS_PATH = "/chat/completions"

// OpenRouter is OpenAI-compatible but ships no Embabel pricing bundle, so register pricing explicitly
// so the smoke-history dashboard reports real cost (`cost_known=priced`). These are the Cerebras rates
// for the Llama model — Cerebras is pinned at the OpenRouter account level ("Provider preferences →
// only Cerebras"), because OpenRouter's per-request `provider` routing is a request-body field that
// Spring AI's OpenAiChatOptions cannot set. Verify the exact rate on the OpenRouter model page
// (https://openrouter.ai/meta-llama/llama-3.3-70b-instruct, Cerebras provider). USD per 1M tokens.
private const val LLAMA_70B_USD_PER_1M_IN = 0.85
private const val LLAMA_70B_USD_PER_1M_OUT = 1.2

private val OPENROUTER_PRICING: Map<String, PricingModel> =
    mapOf(
        "meta-llama/llama-3.3-70b-instruct" to
            PricingModel.usdPer1MTokens(LLAMA_70B_USD_PER_1M_IN, LLAMA_70B_USD_PER_1M_OUT),
    )

// OpenRouter uses these for rate-limit tier and usage attribution; optional, harmless if absent.
private val OPENROUTER_HEADERS =
    mapOf(
        "HTTP-Referer" to "https://github.com/kludgeworks/bpmner",
        "X-Title" to "bpmner-smoke",
    )

@ConfigurationProperties(prefix = "embabel.agent.platform.models.openrouter")
class OpenRouterProperties : RetryProperties {
    var apiKey: String? = null
    var models: List<String> = listOf("meta-llama/llama-3.3-70b-instruct")
    override var maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
    override var backoffMillis: Long = DEFAULT_BACKOFF_MILLIS
    override var backoffMultiplier: Double = DEFAULT_BACKOFF_MULTIPLIER
    override var backoffMaxInterval: Long = DEFAULT_BACKOFF_MAX_INTERVAL
}

// Activated by the `llama` profile: OpenRouter is just the proxy; the Llama model (on Cerebras,
// pinned account-level) is the family under test, so the profile/CI row is named for the model.
@Profile("llama")
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenRouterProperties::class)
class OpenRouterModelsConfig(
    observationRegistry: ObjectProvider<ObservationRegistry>,
    @Qualifier("aiModelRestClientBuilder")
    restClientBuilder: ObjectProvider<RestClient.Builder>,
    @Qualifier("aiModelWebClientBuilder")
    webClientBuilder: ObjectProvider<WebClient.Builder>,
    private val properties: OpenRouterProperties,
    private val configurableBeanFactory: ConfigurableBeanFactory,
) : OpenAiCompatibleModelFactory(
    baseUrl = BASE_URL,
    apiKey =
    properties.apiKey?.takeIf { it.isNotBlank() }
        ?: error(
            "OpenRouter API key required: set OPENROUTER_API_KEY env var or" +
                " embabel.agent.platform.models.openrouter.api-key",
        ),
    completionsPath = COMPLETIONS_PATH,
    embeddingsPath = null,
    httpHeaders = OPENROUTER_HEADERS,
    observationRegistry = observationRegistry.getIfUnique { ObservationRegistry.NOOP },
    restClientBuilder = restClientBuilder,
    webClientBuilder = webClientBuilder,
) {
    @Bean
    fun openRouterModelsInitializer(): ProviderInitialization {
        val registeredLlms =
            properties.models.map { modelId ->
                val llm =
                    SpringAiLlmService(
                        name = modelId,
                        provider = PROVIDER,
                        chatModel = chatModelOf(modelId, properties.retryTemplate(modelId)),
                        optionsConverter = StandardOpenAiOptionsConverter,
                        pricingModel = OPENROUTER_PRICING[modelId],
                    )
                configurableBeanFactory.registerSingleton(modelId, llm)
                logger.info("Registered OpenRouter model: {}", modelId)
                RegisteredModel(beanName = modelId, modelId = modelId)
            }

        return ProviderInitialization(provider = PROVIDER, registeredLlms = registeredLlms)
            .also { logger.info(it.summary()) }
    }
}
