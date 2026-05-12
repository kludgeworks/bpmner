@file:Suppress(
    "CyclomaticComplexMethod",
    "ForbiddenComment",
    "LongMethod",
    "LongParameterList",
    "MagicNumber",
    "MaxLineLength",
    "NestedBlockDepth",
    "ReturnCount",
    "SpreadOperator",
    "TooGenericExceptionCaught",
    "TooManyFunctions",
    "UnusedParameter",
    "UnusedPrivateProperty",
)

package dev.groknull.bpmner.layout.internal.adapter.outbound

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
internal open class BpmnLayoutService {
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
        } catch (e: Exception) {
            logger.error("Failed to initialize GraalJS layout context", e)
        }
    }

    fun layout(xml: String): String {
        val api = layoutApi ?: return xml
        logger.debug("Starting in-process BPMN auto-layout. xmlLength={}", xml.length)

        return try {
            val future = CompletableFuture<String>()
            val promise = api.invokeMember("layoutXml", xml)

            promise.invokeMember(
                "then",
                Consumer<String> { result ->
                    future.complete(result)
                },
            )

            future.get(30, TimeUnit.SECONDS)
        } catch (e: Exception) {
            logger.warn("BPMN auto-layout execution error: {}", e.message)
            xml
        }
    }

    fun destroy() {
        jsContext?.close()
    }
}
