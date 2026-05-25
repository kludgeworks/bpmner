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

private const val PROVIDER = "GitHub"
private const val DEFAULT_MAX_ATTEMPTS = 10
private const val DEFAULT_BACKOFF_MILLIS = 5000L
private const val DEFAULT_BACKOFF_MULTIPLIER = 5.0
private const val DEFAULT_BACKOFF_MAX_INTERVAL = 180000L
private const val BASE_URL = "https://models.github.ai/inference"
private const val COMPLETIONS_PATH = "/chat/completions"
private const val CATALOG_URL = "https://models.github.ai/catalog/models"

@ConfigurationProperties(prefix = "embabel.agent.platform.models.github")
class GitHubProperties : RetryProperties {
    var apiKey: String? = null
    var models: List<String> = listOf("openai/gpt-4o")
    var catalogUrl: String = CATALOG_URL
    override var maxAttempts: Int = DEFAULT_MAX_ATTEMPTS
    override var backoffMillis: Long = DEFAULT_BACKOFF_MILLIS
    override var backoffMultiplier: Double = DEFAULT_BACKOFF_MULTIPLIER
    override var backoffMaxInterval: Long = DEFAULT_BACKOFF_MAX_INTERVAL
}

@Profile("github")
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GitHubProperties::class)
class GitHubModelsConfig(
    observationRegistry: ObjectProvider<ObservationRegistry>,
    @Qualifier("aiModelRestClientBuilder")
    restClientBuilder: ObjectProvider<RestClient.Builder>,
    @Qualifier("aiModelWebClientBuilder")
    webClientBuilder: ObjectProvider<WebClient.Builder>,
    private val properties: GitHubProperties,
    private val configurableBeanFactory: ConfigurableBeanFactory,
) : OpenAiCompatibleModelFactory(
    baseUrl = BASE_URL,
    apiKey =
    properties.apiKey
        ?: error(
            "GitHub token required: set GITHUB_TOKEN env var or" +
                " embabel.agent.platform.models.github.api-key",
        ),
    completionsPath = COMPLETIONS_PATH,
    embeddingsPath = null,
    httpHeaders = emptyMap(),
    observationRegistry = observationRegistry.getIfUnique { ObservationRegistry.NOOP },
    restClientBuilder = restClientBuilder,
    webClientBuilder = webClientBuilder,
) {
    private val modelList = properties.models

    @Bean
    fun gitHubModelsInitializer(): ProviderInitialization {
        if (modelList.isEmpty()) {
            logger.warn(
                "No GitHub Models configured. " +
                    "Set embabel.agent.platform.models.github.models" +
                    " (e.g. --embabel.agent.platform.models.github.models=openai/gpt-4o)",
            )
            printCatalog()
            return ProviderInitialization(provider = PROVIDER, registeredLlms = emptyList())
        }

        val registeredLlms =
            modelList.map { modelId ->
                val llm =
                    SpringAiLlmService(
                        name = modelId,
                        provider = PROVIDER,
                        chatModel = chatModelOf(modelId, properties.retryTemplate(modelId)),
                        optionsConverter = StandardOpenAiOptionsConverter,
                    )
                configurableBeanFactory.registerSingleton(modelId, llm)
                logger.info("Registered GitHub model: {}", modelId)
                RegisteredModel(beanName = modelId, modelId = modelId)
            }

        return ProviderInitialization(provider = PROVIDER, registeredLlms = registeredLlms)
            .also { logger.info(it.summary()) }
    }

    private fun printCatalog() {
        val models = GitHubCatalogClient.fetchTextModels(properties.catalogUrl)
        if (models.isEmpty()) return
        logger.info("Available GitHub Models (use IDs in embabel.agent.platform.models.github.models):")
        models.groupBy { it.publisher }.forEach { (publisher, entries) ->
            logger.info("  {}:", publisher)
            entries.forEach { logger.info("    {}", it.id) }
        }
    }
}
