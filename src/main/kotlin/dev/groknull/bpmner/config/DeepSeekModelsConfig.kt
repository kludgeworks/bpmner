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

private const val PROVIDER = "DeepSeek"
private const val DEFAULT_MAX_ATTEMPTS = 10
private const val DEFAULT_BACKOFF_MILLIS = 5000L
private const val DEFAULT_BACKOFF_MULTIPLIER = 5.0
private const val DEFAULT_BACKOFF_MAX_INTERVAL = 180000L
private const val BASE_URL = "https://api.deepseek.com/v1"
private const val COMPLETIONS_PATH = "/chat/completions"

private const val DEEPSEEK_CHAT_INPUT_PRICE = 0.14
private const val DEEPSEEK_CHAT_OUTPUT_PRICE = 0.28
private const val DEEPSEEK_REASONER_INPUT_PRICE = 0.55
private const val DEEPSEEK_REASONER_OUTPUT_PRICE = 2.19

// DeepSeek pricing (as of June 2026, cache-miss rates)
// V3 (deepseek-chat): $0.14 / 1M input tokens, $0.28 / 1M output tokens
// R1 (deepseek-reasoner): $0.55 / 1M input tokens, $2.19 / 1M output tokens
private val DEEPSEEK_PRICING: Map<String, PricingModel> =
    mapOf(
        "deepseek-chat" to PricingModel.usdPer1MTokens(DEEPSEEK_CHAT_INPUT_PRICE, DEEPSEEK_CHAT_OUTPUT_PRICE),
        "deepseek-reasoner" to PricingModel.usdPer1MTokens(DEEPSEEK_REASONER_INPUT_PRICE, DEEPSEEK_REASONER_OUTPUT_PRICE),
    )

@ConfigurationProperties(prefix = "embabel.agent.platform.models.deepseek")
class DeepSeekProperties : RetryProperties {
    var apiKey: String? = null
    var models: List<String> = listOf("deepseek-chat", "deepseek-reasoner")
    override var maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
    override var backoffMillis: Long = DEFAULT_BACKOFF_MILLIS
    override var backoffMultiplier: Double = DEFAULT_BACKOFF_MULTIPLIER
    override var backoffMaxInterval: Long = DEFAULT_BACKOFF_MAX_INTERVAL
}

@Profile("deepseek")
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(DeepSeekProperties::class)
class DeepSeekModelsConfig(
    observationRegistry: ObjectProvider<ObservationRegistry>,
    @Qualifier("aiModelRestClientBuilder")
    restClientBuilder: ObjectProvider<RestClient.Builder>,
    @Qualifier("aiModelWebClientBuilder")
    webClientBuilder: ObjectProvider<WebClient.Builder>,
    private val properties: DeepSeekProperties,
    private val configurableBeanFactory: ConfigurableBeanFactory,
) : OpenAiCompatibleModelFactory(
    baseUrl = BASE_URL,
    apiKey =
    properties.apiKey?.takeIf { it.isNotBlank() }
        ?: "UNCONFIGURED", // Allow context to load without an API key; fails only at call time
    completionsPath = COMPLETIONS_PATH,
    embeddingsPath = null,
    httpHeaders = emptyMap(),
    observationRegistry = observationRegistry.getIfUnique { ObservationRegistry.NOOP },
    restClientBuilder = restClientBuilder,
    webClientBuilder = webClientBuilder,
) {
    @Bean
    fun deepSeekModelsInitializer(): ProviderInitialization {
        val registeredLlms =
            properties.models.map { modelId ->
                val llm =
                    SpringAiLlmService(
                        name = modelId,
                        provider = PROVIDER,
                        chatModel = chatModelOf(modelId, properties.retryTemplate(modelId)),
                        optionsConverter = StandardOpenAiOptionsConverter,
                        pricingModel = DEEPSEEK_PRICING[modelId],
                    )
                configurableBeanFactory.registerSingleton(modelId, llm)
                logger.info("Registered DeepSeek model: {}", modelId)
                RegisteredModel(beanName = modelId, modelId = modelId)
            }

        return ProviderInitialization(provider = PROVIDER, registeredLlms = registeredLlms)
            .also { logger.info(it.summary()) }
    }
}
