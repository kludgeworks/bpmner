/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.config

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

private const val PROVIDER = "Groq"
private const val DEFAULT_MAX_ATTEMPTS = 10
private const val DEFAULT_BACKOFF_MILLIS = 5000L
private const val DEFAULT_BACKOFF_MULTIPLIER = 5.0
private const val DEFAULT_BACKOFF_MAX_INTERVAL = 180000L
private const val BASE_URL = "https://api.groq.com/openai"
private const val COMPLETIONS_PATH = "/v1/chat/completions"

// Groq is OpenAI-compatible but ships no Embabel pricing bundle (unlike first-class
// providers, whose autoconfigure jar carries a models/<provider>-models.yml), so register
// pricing explicitly — Groq public on-demand rates, USD per 1M input/output tokens. Models
// absent here fall back to no pricing (cost reported as unknown). Verify rates and ids
// against https://groq.com/pricing and https://console.groq.com/docs/models.
private val GROQ_PRICING: Map<String, PricingModel> = mapOf(
    "llama-3.3-70b-versatile" to PricingModel.usdPer1MTokens(0.59, 0.79),
    "llama-3.1-8b-instant" to PricingModel.usdPer1MTokens(0.05, 0.08),
)

@ConfigurationProperties(prefix = "embabel.agent.platform.models.groq")
class GroqProperties : RetryProperties {
    var apiKey: String? = null
    var models: List<String> = listOf("llama-3.3-70b-versatile", "llama-3.1-8b-instant")
    override var maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
    override var backoffMillis: Long = DEFAULT_BACKOFF_MILLIS
    override var backoffMultiplier: Double = DEFAULT_BACKOFF_MULTIPLIER
    override var backoffMaxInterval: Long = DEFAULT_BACKOFF_MAX_INTERVAL
}

@Profile("groq")
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GroqProperties::class)
class GroqModelsConfig(
    observationRegistry: ObjectProvider<ObservationRegistry>,
    @Qualifier("aiModelRestClientBuilder")
    restClientBuilder: ObjectProvider<RestClient.Builder>,
    @Qualifier("aiModelWebClientBuilder")
    webClientBuilder: ObjectProvider<WebClient.Builder>,
    private val properties: GroqProperties,
    private val configurableBeanFactory: ConfigurableBeanFactory,
) : OpenAiCompatibleModelFactory(
    baseUrl = BASE_URL,
    apiKey =
    properties.apiKey?.takeIf { it.isNotBlank() }
        ?: error(
            "Groq API key required: set GROQ_API_KEY env var or" +
                " embabel.agent.platform.models.groq.api-key",
        ),
    completionsPath = COMPLETIONS_PATH,
    embeddingsPath = null,
    httpHeaders = emptyMap(),
    observationRegistry = observationRegistry.getIfUnique { ObservationRegistry.NOOP },
    restClientBuilder = restClientBuilder,
    webClientBuilder = webClientBuilder,
) {
    @Bean
    fun groqModelsInitializer(): ProviderInitialization {
        val registeredLlms =
            properties.models.map { modelId ->
                val llm =
                    SpringAiLlmService(
                        name = modelId,
                        provider = PROVIDER,
                        chatModel = chatModelOf(modelId, properties.retryTemplate(modelId)),
                        optionsConverter = StandardOpenAiOptionsConverter,
                        pricingModel = GROQ_PRICING[modelId],
                    )
                configurableBeanFactory.registerSingleton(modelId, llm)
                logger.info("Registered Groq model: {}", modelId)
                RegisteredModel(beanName = modelId, modelId = modelId)
            }

        return ProviderInitialization(provider = PROVIDER, registeredLlms = registeredLlms)
            .also { logger.info(it.summary()) }
    }
}
