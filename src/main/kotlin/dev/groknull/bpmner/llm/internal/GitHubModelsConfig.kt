/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.llm.internal

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

private const val PROVIDER = "GitHubModels"
private const val DEFAULT_MAX_ATTEMPTS = 10
private const val DEFAULT_BACKOFF_MILLIS = 5000L
private const val DEFAULT_BACKOFF_MULTIPLIER = 5.0
private const val DEFAULT_BACKOFF_MAX_INTERVAL = 180000L
private const val BASE_URL = "https://models.github.ai/inference"
private const val COMPLETIONS_PATH = "/chat/completions"

private val GITHUB_MODELS_PRICING: Map<String, PricingModel> =
    mapOf(
        "openai/gpt-4o" to PricingModel.ALL_YOU_CAN_EAT,
        "openai/gpt-4o-mini" to PricingModel.ALL_YOU_CAN_EAT,
    )

@ConfigurationProperties(prefix = "embabel.agent.platform.models.githubmodels")
internal class GitHubModelsProperties : RetryProperties {
    var apiKey: String? = null
    var models: List<String> = listOf("openai/gpt-4o", "openai/gpt-4o-mini")
    override val propertyPrefix: String = "embabel.agent.platform.models.githubmodels"
    override var maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
    override var backoffMillis: Long = DEFAULT_BACKOFF_MILLIS
    override var backoffMultiplier: Double = DEFAULT_BACKOFF_MULTIPLIER
    override var backoffMaxInterval: Long = DEFAULT_BACKOFF_MAX_INTERVAL
}

@Profile("githubmodels")
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GitHubModelsProperties::class)
internal class GitHubModelsConfig(
    observationRegistry: ObjectProvider<ObservationRegistry>,
    @Qualifier("aiModelRestClientBuilder")
    restClientBuilder: ObjectProvider<RestClient.Builder>,
    @Qualifier("aiModelWebClientBuilder")
    webClientBuilder: ObjectProvider<WebClient.Builder>,
    private val properties: GitHubModelsProperties,
    private val configurableBeanFactory: ConfigurableBeanFactory,
) : OpenAiCompatibleModelFactory(
    baseUrl = BASE_URL,
    apiKey = properties.apiKey?.takeIf { it.isNotBlank() } ?: "UNCONFIGURED",
    completionsPath = COMPLETIONS_PATH,
    embeddingsPath = null,
    httpHeaders = emptyMap(),
    observationRegistry = observationRegistry.getIfUnique { ObservationRegistry.NOOP },
    restClientBuilder = restClientBuilder,
    webClientBuilder = webClientBuilder,
) {
    @Bean
    fun gitHubModelsInitializer(): ProviderInitialization {
        val registeredLlms =
            properties.models.distinct().map { modelId ->
                val pricingModel = GITHUB_MODELS_PRICING[modelId]
                if (pricingModel == null) {
                    logger.warn(
                        "No pricing model found for GitHub Models model: {}. Cost tracking will be disabled.",
                        modelId,
                    )
                }
                val llm =
                    SpringAiLlmService(
                        name = modelId,
                        provider = PROVIDER,
                        chatModel = chatModelOf(modelId, properties.retryTemplate(modelId)),
                        optionsConverter = StandardOpenAiOptionsConverter,
                        pricingModel = pricingModel,
                    )
                configurableBeanFactory.registerSingleton(modelId, llm)
                logger.info("Registered GitHub Models model: {}", modelId)
                RegisteredModel(beanName = modelId, modelId = modelId)
            }

        return ProviderInitialization(provider = PROVIDER, registeredLlms = registeredLlms)
            .also { logger.info(it.summary()) }
    }
}
