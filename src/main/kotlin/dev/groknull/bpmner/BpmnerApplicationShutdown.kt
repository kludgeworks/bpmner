/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner

import org.springframework.boot.SpringApplication
import org.springframework.context.ConfigurableApplicationContext
import org.springframework.stereotype.Component
import kotlin.system.exitProcess

fun interface BpmnerApplicationShutdown {
    fun exit()
}

@Component
internal class SpringBpmnerApplicationShutdown(
    private val applicationContext: ConfigurableApplicationContext,
) : BpmnerApplicationShutdown {
    override fun exit() {
        exitProcess(SpringApplication.exit(applicationContext))
    }
}
