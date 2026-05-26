#!/bin/bash
sed -i 's/fun matches(/fun matches(elementType: String, targetType: String): Boolean = targetType == elementType || elementType in broadTypeMembers\[targetType\].orEmpty()\/\//' src/main/kotlin/dev/groknull/bpmner/rules/internal/domain/primitives/BpmnTypeMatcher.kt
