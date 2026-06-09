/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.layout.internal.adapter.outbound

import dev.groknull.bpmner.layout.BpmnLayoutPort
import jakarta.annotation.PostConstruct
import org.graalvm.polyglot.Context
import org.graalvm.polyglot.HostAccess
import org.graalvm.polyglot.Source
import org.graalvm.polyglot.Value
import org.jmolecules.architecture.hexagonal.SecondaryAdapter
import org.slf4j.LoggerFactory
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer

@SecondaryAdapter
@Service
internal open class BpmnLayoutService : BpmnLayoutPort {
    private val logger = LoggerFactory.getLogger(BpmnLayoutService::class.java)
    private var jsContext: Context? = null
    private var layoutApi: Value? = null

    @PostConstruct
    fun init() {
        try {
            logger.info("Initializing GraalJS layout context...")
            jsContext =
                Context
                    .newBuilder("js")
                    .allowHostAccess(HostAccess.ALL)
                    .allowHostClassLookup { className ->
                        className == "java.util.Base64" ||
                            className == "java.lang.String" ||
                            className == "java.nio.charset.StandardCharsets" ||
                            className == "java.util.function.Consumer"
                    }.build()

            val bundleResource = ClassPathResource("js/bpmn-layout-bundle.js")
            if (!bundleResource.exists()) {
                logger.warn("bpmn-layout-bundle.js not found on classpath. Auto-layout will be unavailable.")
                return
            }

            val bundleSource = Source.newBuilder("js", bundleResource.url).build()
            jsContext?.eval(bundleSource)
            layoutApi = jsContext?.getBindings("js")?.getMember("BpmnLayoutApi")

            if (layoutApi != null) {
                logger.info("GraalJS layout context initialized successfully.")
            } else {
                logger.warn("BpmnLayoutApi not found in the JS bundle.")
            }
        } catch (e: org.graalvm.polyglot.PolyglotException) {
            logger.error("Failed to initialize GraalJS layout context", e)
        }
    }

    // The function is long by necessity: the GraalJS Promise API requires separate `then`/`catch`
    // handlers, and each of the numerous exception types must be wrapped individually to preserve
    // the cause and produce a diagnostic message. Extracting each arm into a helper would obscure
    // the error-classification logic without reducing complexity.
    @Suppress("LongMethod")
    override fun layout(xml: String): String {
        val api = layoutApi
            ?: throw dev.groknull.bpmner.layout.BpmnAutoLayoutException(
                "BPMN auto-layout failed: layoutApi is unavailable (bundle not loaded)",
            )
        logger.debug("Starting in-process BPMN auto-layout. xmlLength={}", xml.length)

        val projector = BpmnLayoutXmlProjector()
        return try {
            val projectedXml = projector.projectForLayout(xml)
            val future = CompletableFuture<String>()
            val promise = api.invokeMember("layoutXml", projectedXml)

            promise.invokeMember(
                "then",
                Consumer<String> { result ->
                    future.complete(result)
                },
            )
            promise.invokeMember(
                "catch",
                Consumer<Any> { err ->
                    logger.error("JS Promise rejected: {}", err)
                    future.completeExceptionally(dev.groknull.bpmner.layout.BpmnAutoLayoutException("JS Promise rejected: $err"))
                },
            )

            jsContext?.eval("js", "/* flush microtasks */")

            val layoutedProjectedXml = future.get(LAYOUT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            projector.mergeLayout(xml, layoutedProjectedXml)
        } catch (e: org.graalvm.polyglot.PolyglotException) {
            logger.warn(AUTO_LAYOUT_EXEC_ERROR, e.message)
            throw dev.groknull.bpmner.layout.BpmnAutoLayoutException("BPMN auto-layout failed: ${e.message}", e)
        } catch (e: java.util.concurrent.ExecutionException) {
            logger.warn(AUTO_LAYOUT_EXEC_ERROR, e.message)
            val msg = e.cause?.message ?: e.message
            throw dev.groknull.bpmner.layout.BpmnAutoLayoutException("BPMN auto-layout failed: $msg", e)
        } catch (e: java.util.concurrent.TimeoutException) {
            logger.warn(AUTO_LAYOUT_EXEC_ERROR, e.message)
            throw dev.groknull.bpmner.layout.BpmnAutoLayoutException("BPMN auto-layout timed out: ${e.message}", e)
        } catch (e: InterruptedException) {
            logger.warn(AUTO_LAYOUT_EXEC_ERROR, e.message)
            throw dev.groknull.bpmner.layout.BpmnAutoLayoutException("BPMN auto-layout interrupted: ${e.message}", e)
        } catch (e: java.util.concurrent.CancellationException) {
            logger.warn(AUTO_LAYOUT_EXEC_ERROR, e.message)
            throw dev.groknull.bpmner.layout.BpmnAutoLayoutException("BPMN auto-layout cancelled: ${e.message}", e)
        } catch (e: IllegalArgumentException) {
            logger.warn(AUTO_LAYOUT_EXEC_ERROR, e.message)
            throw dev.groknull.bpmner.layout.BpmnAutoLayoutException(
                "BPMN auto-layout projection failed: ${e.message}",
                e,
            )
        } catch (e: org.xml.sax.SAXException) {
            logger.warn(AUTO_LAYOUT_EXEC_ERROR, e.message)
            throw dev.groknull.bpmner.layout.BpmnAutoLayoutException(
                "BPMN auto-layout failed: ${e.message}",
                e,
            )
        } catch (e: java.io.IOException) {
            logger.warn(AUTO_LAYOUT_EXEC_ERROR, e.message)
            throw dev.groknull.bpmner.layout.BpmnAutoLayoutException(
                "BPMN auto-layout failed: ${e.message}",
                e,
            )
        }
    }

    fun destroy() {
        jsContext?.close()
    }

    companion object {
        private const val AUTO_LAYOUT_EXEC_ERROR = "BPMN auto-layout execution error: {}"
        private const val LAYOUT_TIMEOUT_SECONDS = 30L
    }
}
