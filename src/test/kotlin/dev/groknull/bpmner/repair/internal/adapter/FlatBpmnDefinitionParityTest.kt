/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

package dev.groknull.bpmner.repair.internal.adapter

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import dev.groknull.bpmner.authoring.internal.adapter.outbound.FlatBpmnDefinition as AuthoringFlatBpmnDefinition
import dev.groknull.bpmner.authoring.internal.adapter.outbound.FlatBpmnEventDefinition as AuthoringFlatBpmnEventDefinition
import dev.groknull.bpmner.authoring.internal.adapter.outbound.FlatBpmnEventDefinitionKind as AuthoringFlatBpmnEventDefinitionKind
import dev.groknull.bpmner.authoring.internal.adapter.outbound.FlatBpmnNode as AuthoringFlatBpmnNode
import dev.groknull.bpmner.authoring.internal.adapter.outbound.FlatBpmnNodeKind as AuthoringFlatBpmnNodeKind
import dev.groknull.bpmner.authoring.internal.adapter.outbound.FlatMultiInstanceLoopCharacteristics as AuthoringFlatMultiInstanceLoopCharacteristics
import dev.groknull.bpmner.authoring.internal.adapter.outbound.FlatStandardLoopCharacteristics as AuthoringFlatStandardLoopCharacteristics
import dev.groknull.bpmner.repair.internal.adapter.FlatBpmnDefinition as RepairFlatBpmnDefinition
import dev.groknull.bpmner.repair.internal.adapter.FlatBpmnEventDefinition as RepairFlatBpmnEventDefinition
import dev.groknull.bpmner.repair.internal.adapter.FlatBpmnEventDefinitionKind as RepairFlatBpmnEventDefinitionKind
import dev.groknull.bpmner.repair.internal.adapter.FlatBpmnNode as RepairFlatBpmnNode
import dev.groknull.bpmner.repair.internal.adapter.FlatBpmnNodeKind as RepairFlatBpmnNodeKind
import dev.groknull.bpmner.repair.internal.adapter.FlatMultiInstanceLoopCharacteristics as RepairFlatMultiInstanceLoopCharacteristics
import dev.groknull.bpmner.repair.internal.adapter.FlatStandardLoopCharacteristics as RepairFlatStandardLoopCharacteristics

class FlatBpmnDefinitionParityTest {
    @Test
    fun `flat bpmn definition structures match exactly between authoring and repair`() {
        assertClassParity(AuthoringFlatBpmnDefinition::class.java, RepairFlatBpmnDefinition::class.java)
        assertClassParity(AuthoringFlatBpmnNode::class.java, RepairFlatBpmnNode::class.java)
        assertClassParity(AuthoringFlatBpmnNodeKind::class.java, RepairFlatBpmnNodeKind::class.java)
        assertClassParity(AuthoringFlatBpmnEventDefinition::class.java, RepairFlatBpmnEventDefinition::class.java)
        assertClassParity(AuthoringFlatBpmnEventDefinitionKind::class.java, RepairFlatBpmnEventDefinitionKind::class.java)
        assertClassParity(
            AuthoringFlatMultiInstanceLoopCharacteristics::class.java,
            RepairFlatMultiInstanceLoopCharacteristics::class.java,
        )
        assertClassParity(AuthoringFlatStandardLoopCharacteristics::class.java, RepairFlatStandardLoopCharacteristics::class.java)
    }

    private fun assertClassParity(authoringClass: Class<*>, repairClass: Class<*>) {
        if (authoringClass.isEnum) {
            assertTrue(repairClass.isEnum, "${repairClass.simpleName} should be an enum")
            val consts1 = authoringClass.enumConstants.map { (it as Enum<*>).name }
            val consts2 = repairClass.enumConstants.map { (it as Enum<*>).name }
            assertEquals(consts1, consts2, "Enum constants mismatch for ${authoringClass.simpleName}")
            return
        }

        val authoringFields = authoringClass.declaredFields.associateBy { it.name }
        val repairFields = repairClass.declaredFields.associateBy { it.name }

        assertEquals(authoringFields.keys, repairFields.keys, "Field name mismatch for ${authoringClass.simpleName}")

        for (name in authoringFields.keys) {
            val f1 = authoringFields[name]!!
            val f2 = repairFields[name]!!

            // Map types from authoring package to repair package
            val expectedTypeName = f1.type.name.replace(
                "dev.groknull.bpmner.authoring.internal.adapter.outbound",
                "dev.groknull.bpmner.repair.internal.adapter",
            )
            assertEquals(expectedTypeName, f2.type.name, "Field type mismatch for ${authoringClass.simpleName}.$name")

            // Compare field-level annotations
            val annos1 = f1.annotations.map { it.annotationClass.java.name }.toSet()
            val annos2 = f2.annotations.map { it.annotationClass.java.name }.toSet()
            assertEquals(annos1, annos2, "Annotations mismatch for ${authoringClass.simpleName}.$name")
        }

        // Compare class-level annotations
        val classAnnos1 = authoringClass.annotations.map { it.annotationClass.java.name }.toSet()
        val classAnnos2 = repairClass.annotations.map { it.annotationClass.java.name }.toSet()
        assertEquals(classAnnos1, classAnnos2, "Class annotations mismatch for ${authoringClass.simpleName}")
    }
}
