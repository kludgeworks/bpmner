/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.pipeline.internal.adapter.inbound

import org.jmolecules.architecture.onion.simplified.InfrastructureRing
import org.springframework.context.annotation.Configuration
import org.springframework.context.annotation.Profile
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/**
 * Configures Spring MVC to route any client-side SPA path starting with `/p/`
 * to `/index.html` via a forward.
 */
@InfrastructureRing
@Configuration
@Profile("web")
internal class PermalinkRoutingConfig : WebMvcConfigurer {
    override fun addViewControllers(registry: ViewControllerRegistry) {
        registry.addViewController("/p/**").setViewName("forward:/index.html")
    }
}
