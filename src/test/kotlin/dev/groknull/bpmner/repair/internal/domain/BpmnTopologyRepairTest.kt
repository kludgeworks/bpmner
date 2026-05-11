package dev.groknull.bpmner.repair.internal.domain

import dev.groknull.bpmner.core.BpmnBounds
import dev.groknull.bpmner.core.BpmnDefinition
import dev.groknull.bpmner.core.BpmnDiagnostic
import dev.groknull.bpmner.core.BpmnDiagnosticSource
import dev.groknull.bpmner.core.BpmnEdge
import dev.groknull.bpmner.core.BpmnNode
import dev.groknull.bpmner.core.BpmnWaypoint
import dev.groknull.bpmner.core.NodeType
import dev.groknull.bpmner.repair.internal.adapter.outbound.BpmnPatchApplier
import dev.groknull.bpmner.validation.internal.domain.BpmnDefinitionValidator
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class BpmnTopologyRepairTest {

    private val applier = BpmnPatchApplier()
    private val repair = BpmnTopologyRepair(applier)
    private val validator = BpmnDefinitionValidator()

    private val stdWaypoints = listOf(BpmnWaypoint(100.0, 100.0), BpmnWaypoint(200.0, 100.0))

    // -------------------------------------------------------------------------
    // no-gateway-join-fork: split mixed join+fork gateway
    // -------------------------------------------------------------------------

    @Test
    fun `no-gateway-join-fork adds converging gateway before mixed gateway`() {
        val definition = joinForkDefinition()
        val patch = repair.buildTopologyPatch(definition, listOf(topologyDiag("Gateway_1", "klm/gtw-20-no-gateway-join-fork")))

        assertNotNull(patch)
        val ops = patch.operations
        val addNode = ops.single { it.type == BpmnPatchOperationType.ADD_NODE }
        val addEdge = ops.single { it.type == BpmnPatchOperationType.ADD_EDGE }
        val replaceEdges = ops.filter { it.type == BpmnPatchOperationType.REPLACE_EDGE }

        assertEquals(NodeType.EXCLUSIVE_GATEWAY, addNode.node!!.type)
        assertNull(addNode.node!!.name, "Inserted converging gateway must have no name")
        assertEquals(addNode.node!!.id, addEdge.edge!!.sourceRef)
        assertEquals("Gateway_1", addEdge.edge!!.targetRef)
        assertEquals(2, replaceEdges.size)
        replaceEdges.forEach { op ->
            assertEquals(addNode.node!!.id, op.edge!!.targetRef)
        }
    }

    @Test
    fun `no-gateway-join-fork repair produces valid definition`() {
        val definition = joinForkDefinition()
        val patch = repair.buildTopologyPatch(definition, listOf(topologyDiag("Gateway_1", "klm/gtw-20-no-gateway-join-fork")))!!
        val result = assertIs<PatchApplicationResult.Success>(applier.apply(definition, patch))
        val errors = validator.validate(result.definition)
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: $errors")
    }

    @Test
    fun `no-gateway-join-fork repair leaves original gateway with single incoming`() {
        val definition = joinForkDefinition()
        val patch = repair.buildTopologyPatch(definition, listOf(topologyDiag("Gateway_1", "klm/gtw-20-no-gateway-join-fork")))!!
        val result = assertIs<PatchApplicationResult.Success>(applier.apply(definition, patch))
        val incomingToGw1 = result.definition.sequences.count { it.targetRef == "Gateway_1" }
        assertEquals(1, incomingToGw1, "Gateway_1 should have exactly 1 incoming after split")
    }

    @Test
    fun `no-gateway-join-fork returns null for non-mixed gateway`() {
        val definition = divergingGatewayDefinition()
        val patch = repair.buildTopologyPatch(definition, listOf(topologyDiag("Gateway_1", "klm/gtw-20-no-gateway-join-fork")))
        assertNull(patch)
    }

    // -------------------------------------------------------------------------
    // fake-join: insert converging gateway before multi-incoming task
    // -------------------------------------------------------------------------

    @Test
    fun `fake-join adds converging gateway before task with multiple incoming`() {
        val definition = fakeJoinDefinition()
        val patch = repair.buildTopologyPatch(definition, listOf(topologyDiag("Task_1", "klm/gtw-21-fake-join")))

        assertNotNull(patch)
        val ops = patch.operations
        val addNode = ops.single { it.type == BpmnPatchOperationType.ADD_NODE }
        val addEdge = ops.single { it.type == BpmnPatchOperationType.ADD_EDGE }
        val replaceEdges = ops.filter { it.type == BpmnPatchOperationType.REPLACE_EDGE }

        assertEquals(NodeType.EXCLUSIVE_GATEWAY, addNode.node!!.type)
        assertNull(addNode.node!!.name, "Inserted converging gateway must have no name")
        assertEquals(addNode.node!!.id, addEdge.edge!!.sourceRef)
        assertEquals("Task_1", addEdge.edge!!.targetRef)
        assertEquals(2, replaceEdges.size)
        replaceEdges.forEach { op ->
            assertEquals(addNode.node!!.id, op.edge!!.targetRef)
        }
    }

    @Test
    fun `fake-join repair produces valid definition`() {
        val definition = fakeJoinDefinition()
        val patch = repair.buildTopologyPatch(definition, listOf(topologyDiag("Task_1", "klm/gtw-21-fake-join")))!!
        val result = assertIs<PatchApplicationResult.Success>(applier.apply(definition, patch))
        val errors = validator.validate(result.definition)
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: $errors")
    }

    @Test
    fun `fake-join repair leaves task with single incoming`() {
        val definition = fakeJoinDefinition()
        val patch = repair.buildTopologyPatch(definition, listOf(topologyDiag("Task_1", "klm/gtw-21-fake-join")))!!
        val result = assertIs<PatchApplicationResult.Success>(applier.apply(definition, patch))
        val incomingToTask = result.definition.sequences.count { it.targetRef == "Task_1" }
        assertEquals(1, incomingToTask, "Task_1 should have exactly 1 incoming after repair")
    }

    @Test
    fun `fake-join returns null for single-incoming task`() {
        val definition = singleIncomingTaskDefinition()
        val patch = repair.buildTopologyPatch(definition, listOf(topologyDiag("Task_1", "klm/gtw-21-fake-join")))
        assertNull(patch)
    }

    // -------------------------------------------------------------------------
    // superfluous-gateway: contract single-in/single-out gateway
    // -------------------------------------------------------------------------

    @Test
    fun `superfluous-gateway produces replace-remove-remove operations`() {
        val definition = superfluousGatewayDefinition()
        val patch = repair.buildTopologyPatch(definition, listOf(topologyDiag("Gateway_1", "klm/gtw-22-superfluous-gateway")))

        assertNotNull(patch)
        val ops = patch.operations
        assertEquals(3, ops.size)
        assertIs<BpmnPatchOperationType>(ops[0].type)
        assertEquals(BpmnPatchOperationType.REPLACE_EDGE, ops[0].type)
        assertEquals(BpmnPatchOperationType.REMOVE_EDGE, ops[1].type)
        assertEquals(BpmnPatchOperationType.REMOVE_NODE, ops[2].type)
        assertEquals("Gateway_1", ops[2].nodeId)
    }

    @Test
    fun `superfluous-gateway repair bypasses gateway`() {
        val definition = superfluousGatewayDefinition()
        val patch = repair.buildTopologyPatch(definition, listOf(topologyDiag("Gateway_1", "klm/gtw-22-superfluous-gateway")))!!
        val result = assertIs<PatchApplicationResult.Success>(applier.apply(definition, patch))

        assertNull(result.definition.nodes.firstOrNull { it.id == "Gateway_1" }, "Gateway should be removed")
        val incomingToTask = result.definition.sequences.filter { it.targetRef == "Task_1" }
        assertEquals(1, incomingToTask.size, "Task_1 should have exactly 1 incoming after bypass")
        assertEquals("Start_1", incomingToTask.first().sourceRef, "Incoming should come from Start_1")
    }

    @Test
    fun `superfluous-gateway repair produces valid definition`() {
        val definition = superfluousGatewayDefinition()
        val patch = repair.buildTopologyPatch(definition, listOf(topologyDiag("Gateway_1", "klm/gtw-22-superfluous-gateway")))!!
        val result = assertIs<PatchApplicationResult.Success>(applier.apply(definition, patch))
        val errors = validator.validate(result.definition)
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: $errors")
    }

    @Test
    fun `superfluous-gateway returns null for diverging gateway`() {
        val definition = divergingGatewayDefinition()
        val patch = repair.buildTopologyPatch(definition, listOf(topologyDiag("Gateway_1", "klm/gtw-22-superfluous-gateway")))
        assertNull(patch)
    }

    // -------------------------------------------------------------------------
    // No topology diagnostics
    // -------------------------------------------------------------------------

    @Test
    fun `returns null when no topology diagnostics present`() {
        val definition = superfluousGatewayDefinition()
        val patch = repair.buildTopologyPatch(
            definition,
            listOf(
                BpmnDiagnostic(source = BpmnDiagnosticSource.LINT, message = "label issue", rule = "klm/gtw-01-diverging-gateway-question"),
            ),
        )
        assertNull(patch)
    }

    @Test
    fun `returns null for empty diagnostics`() {
        assertNull(repair.buildTopologyPatch(superfluousGatewayDefinition(), emptyList()))
    }

    @Test
    fun `picks first topology diagnostic when multiple present`() {
        val definition = joinForkDefinition()
        val patch = repair.buildTopologyPatch(
            definition,
            listOf(
                topologyDiag("Gateway_1", "klm/gtw-20-no-gateway-join-fork"),
                topologyDiag("Task_1", "klm/gtw-21-fake-join"),
            ),
        )
        assertNotNull(patch)
        assertTrue(patch.reason?.contains("no-gateway-join-fork") == true)
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private fun topologyDiag(elementId: String, rule: String) = BpmnDiagnostic(
        source = BpmnDiagnosticSource.LINT,
        message = "topology violation",
        rule = rule,
        elementId = elementId,
    )

    private fun joinForkDefinition() = BpmnDefinition(
        processId = "Process_1",
        processName = "Join Fork Process",
        nodes = listOf(
            BpmnNode("Start_1", "Start", NodeType.START_EVENT, BpmnBounds(80.0, 160.0, 36.0, 36.0)),
            BpmnNode("Start_2", "Trigger", NodeType.START_EVENT, BpmnBounds(80.0, 260.0, 36.0, 36.0)),
            BpmnNode("Gateway_1", "Route?", NodeType.EXCLUSIVE_GATEWAY, BpmnBounds(240.0, 155.0, 50.0, 50.0)),
            BpmnNode("Task_1", "Handle A", NodeType.USER_TASK, BpmnBounds(360.0, 140.0, 100.0, 80.0)),
            BpmnNode("Task_2", "Handle B", NodeType.USER_TASK, BpmnBounds(360.0, 250.0, 100.0, 80.0)),
            BpmnNode("End_1", "End", NodeType.END_EVENT, BpmnBounds(520.0, 160.0, 36.0, 36.0)),
        ),
        sequences = listOf(
            BpmnEdge("Flow_1", "Start_1", "Gateway_1", waypoints = stdWaypoints),
            BpmnEdge("Flow_2", "Start_2", "Gateway_1", waypoints = stdWaypoints),
            BpmnEdge("Flow_3", "Gateway_1", "Task_1", name = "Path A", waypoints = stdWaypoints),
            BpmnEdge("Flow_4", "Gateway_1", "Task_2", name = "Path B", waypoints = stdWaypoints),
            BpmnEdge("Flow_5", "Task_1", "End_1", waypoints = stdWaypoints),
            BpmnEdge("Flow_6", "Task_2", "End_1", waypoints = stdWaypoints),
        ),
    )

    private fun fakeJoinDefinition() = BpmnDefinition(
        processId = "Process_1",
        processName = "Fake Join Process",
        nodes = listOf(
            BpmnNode("Start_1", "Start", NodeType.START_EVENT, BpmnBounds(80.0, 140.0, 36.0, 36.0)),
            BpmnNode("Start_2", "Trigger", NodeType.START_EVENT, BpmnBounds(80.0, 240.0, 36.0, 36.0)),
            BpmnNode("Task_1", "Do work", NodeType.USER_TASK, BpmnBounds(240.0, 138.0, 100.0, 80.0)),
            BpmnNode("End_1", "End", NodeType.END_EVENT, BpmnBounds(400.0, 160.0, 36.0, 36.0)),
        ),
        sequences = listOf(
            BpmnEdge("Flow_1", "Start_1", "Task_1", waypoints = stdWaypoints),
            BpmnEdge("Flow_2", "Start_2", "Task_1", waypoints = stdWaypoints),
            BpmnEdge("Flow_3", "Task_1", "End_1", waypoints = stdWaypoints),
        ),
    )

    private fun singleIncomingTaskDefinition() = BpmnDefinition(
        processId = "Process_1",
        processName = "Single Incoming",
        nodes = listOf(
            BpmnNode("Start_1", "Start", NodeType.START_EVENT, BpmnBounds(80.0, 140.0, 36.0, 36.0)),
            BpmnNode("Task_1", "Do work", NodeType.USER_TASK, BpmnBounds(200.0, 120.0, 100.0, 80.0)),
            BpmnNode("End_1", "End", NodeType.END_EVENT, BpmnBounds(360.0, 140.0, 36.0, 36.0)),
        ),
        sequences = listOf(
            BpmnEdge("Flow_1", "Start_1", "Task_1", waypoints = stdWaypoints),
            BpmnEdge("Flow_2", "Task_1", "End_1", waypoints = stdWaypoints),
        ),
    )

    private fun superfluousGatewayDefinition() = BpmnDefinition(
        processId = "Process_1",
        processName = "Superfluous Gateway Process",
        nodes = listOf(
            BpmnNode("Start_1", "Start", NodeType.START_EVENT, BpmnBounds(80.0, 140.0, 36.0, 36.0)),
            BpmnNode("Gateway_1", null, NodeType.EXCLUSIVE_GATEWAY, BpmnBounds(180.0, 135.0, 50.0, 50.0)),
            BpmnNode("Task_1", "Do work", NodeType.USER_TASK, BpmnBounds(300.0, 118.0, 100.0, 80.0)),
            BpmnNode("End_1", "End", NodeType.END_EVENT, BpmnBounds(460.0, 140.0, 36.0, 36.0)),
        ),
        sequences = listOf(
            BpmnEdge("Flow_1", "Start_1", "Gateway_1", waypoints = stdWaypoints),
            BpmnEdge("Flow_2", "Gateway_1", "Task_1", waypoints = stdWaypoints),
            BpmnEdge("Flow_3", "Task_1", "End_1", waypoints = stdWaypoints),
        ),
    )

    private fun divergingGatewayDefinition() = BpmnDefinition(
        processId = "Process_1",
        processName = "Diverging Process",
        nodes = listOf(
            BpmnNode("Start_1", "Start", NodeType.START_EVENT, BpmnBounds(80.0, 140.0, 36.0, 36.0)),
            BpmnNode("Gateway_1", "Is valid?", NodeType.EXCLUSIVE_GATEWAY, BpmnBounds(180.0, 135.0, 50.0, 50.0)),
            BpmnNode("Task_1", "Approve", NodeType.USER_TASK, BpmnBounds(300.0, 100.0, 100.0, 80.0)),
            BpmnNode("Task_2", "Reject", NodeType.USER_TASK, BpmnBounds(300.0, 200.0, 100.0, 80.0)),
            BpmnNode("End_1", "End", NodeType.END_EVENT, BpmnBounds(460.0, 140.0, 36.0, 36.0)),
        ),
        sequences = listOf(
            BpmnEdge("Flow_1", "Start_1", "Gateway_1", waypoints = stdWaypoints),
            BpmnEdge("Flow_2", "Gateway_1", "Task_1", name = "Yes", waypoints = stdWaypoints),
            BpmnEdge("Flow_3", "Gateway_1", "Task_2", name = "No", waypoints = stdWaypoints),
            BpmnEdge("Flow_4", "Task_1", "End_1", waypoints = stdWaypoints),
            BpmnEdge("Flow_5", "Task_2", "End_1", waypoints = stdWaypoints),
        ),
    )

    private fun convergingNamedGatewayDefinition(gatewayName: String? = "Join") = BpmnDefinition(
        processId = "Process_1",
        processName = "Converging Named Gateway Process",
        nodes = listOf(
            BpmnNode("Start_1", "Path A", NodeType.START_EVENT, BpmnBounds(80.0, 100.0, 36.0, 36.0)),
            BpmnNode("Start_2", "Path B", NodeType.START_EVENT, BpmnBounds(80.0, 200.0, 36.0, 36.0)),
            BpmnNode("Gateway_1", gatewayName, NodeType.EXCLUSIVE_GATEWAY, BpmnBounds(240.0, 135.0, 50.0, 50.0)),
            BpmnNode("Task_1", "Continue", NodeType.USER_TASK, BpmnBounds(360.0, 118.0, 100.0, 80.0)),
            BpmnNode("End_1", "End", NodeType.END_EVENT, BpmnBounds(520.0, 140.0, 36.0, 36.0)),
        ),
        sequences = listOf(
            BpmnEdge("Flow_1", "Start_1", "Gateway_1", waypoints = stdWaypoints),
            BpmnEdge("Flow_2", "Start_2", "Gateway_1", waypoints = stdWaypoints),
            BpmnEdge("Flow_3", "Gateway_1", "Task_1", waypoints = stdWaypoints),
            BpmnEdge("Flow_4", "Task_1", "End_1", waypoints = stdWaypoints),
        ),
    )

    // -------------------------------------------------------------------------
    // gtw-02: clear converging gateway name
    // -------------------------------------------------------------------------

    @Test
    fun `gtw-02 clears name from named converging gateway`() {
        val definition = convergingNamedGatewayDefinition("Join")
        val patch = repair.buildTopologyPatch(definition, listOf(topologyDiag("Gateway_1", "klm/gtw-02-converging-gateway-unnamed")))

        assertNotNull(patch)
        val op = patch.operations.single()
        assertEquals(BpmnPatchOperationType.SET_NODE_NAME, op.type)
        assertEquals("Gateway_1", op.nodeId)
        assertNull(op.name, "Converging gateway name must be cleared to null")
    }

    @Test
    fun `gtw-02 repair produces valid definition`() {
        val definition = convergingNamedGatewayDefinition("Join")
        val patch = repair.buildTopologyPatch(definition, listOf(topologyDiag("Gateway_1", "klm/gtw-02-converging-gateway-unnamed")))!!
        val result = assertIs<PatchApplicationResult.Success>(applier.apply(definition, patch))
        val errors = validator.validate(result.definition)
        assertTrue(errors.isEmpty(), "Expected no validation errors, got: $errors")
        assertNull(result.definition.nodes.single { it.id == "Gateway_1" }.name)
    }

    @Test
    fun `gtw-02 returns null for already unnamed converging gateway`() {
        val definition = convergingNamedGatewayDefinition(gatewayName = null)
        val patch = repair.buildTopologyPatch(definition, listOf(topologyDiag("Gateway_1", "klm/gtw-02-converging-gateway-unnamed")))
        assertNull(patch)
    }

    @Test
    fun `gtw-02 returns null for diverging gateway`() {
        val definition = divergingGatewayDefinition()
        val patch = repair.buildTopologyPatch(definition, listOf(topologyDiag("Gateway_1", "klm/gtw-02-converging-gateway-unnamed")))
        assertNull(patch)
    }
}
