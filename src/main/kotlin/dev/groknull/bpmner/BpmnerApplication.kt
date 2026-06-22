/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import com.embabel.agent.config.annotation.EnableAgents
import com.embabel.agent.core.deployment.AgentScanningProperties
import com.embabel.agent.spi.config.spring.ContextRepositoryProperties
import com.embabel.agent.spi.support.RankingProperties
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.runApplication

@SpringBootApplication
@ConfigurationPropertiesScan
@EnableConfigurationProperties(
    AgentScanningProperties::class,
    ContextRepositoryProperties::class,
    RankingProperties::class,
)
@EnableAgents
class BpmnerApplication

fun main(args: Array<String>) {
    System.getenv("BUILD_WORKING_DIRECTORY")?.let {
        System.setProperty("user.dir", it)
    }
    runApplication<BpmnerApplication>(*args)
}
