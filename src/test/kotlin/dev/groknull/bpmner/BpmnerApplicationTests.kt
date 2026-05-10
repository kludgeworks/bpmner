package dev.groknull.bpmner

import dev.groknull.bpmner.core.BpmnRequest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class BpmnerApplicationTests {

    @Test
    fun `BpmnRequest defaults output file to output dot bpmn`() {
        val request = BpmnRequest(processDescription = "Order fulfillment")
        assertEquals("output.bpmn", request.outputFile)
        assertNull(request.styleGuide)
    }

    @Test
    fun `BpmnRequest accepts style guide`() {
        val request = BpmnRequest(
            processDescription = "Order fulfillment",
            styleGuide = "Use PascalCase for task names",
            outputFile = "order.bpmn",
        )
        assertNotNull(request.styleGuide)
        assertEquals("order.bpmn", request.outputFile)
    }
}
