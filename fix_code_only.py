import re

# We will only apply the application code changes since the test suite modifications with python were deemed too brittle

def fix_domain():
    with open('src/main/kotlin/dev/groknull/bpmner/core/BpmnDomain.kt', 'r') as f:
        content = f.read()

    # Remove bounds from BpmnNode
    content = re.sub(r',\n\s*val bounds: BpmnBounds\?', '', content)
    content = re.sub(r'val bounds: BpmnBounds\? = null', '', content)

    # Remove waypoints from BpmnEdge
    content = re.sub(r',\n\s*val waypoints: List<BpmnWaypoint> = emptyList\(\)', '', content)

    # Remove data classes
    content = re.sub(r'data class BpmnWaypoint\([^\n]+\n', '', content)
    content = re.sub(r'data class BpmnBounds\([^\n]+\n', '', content)

    with open('src/main/kotlin/dev/groknull/bpmner/core/BpmnDomain.kt', 'w') as f:
        f.write(content)


def fix_converters():
    with open('src/main/kotlin/dev/groknull/bpmner/generation/internal/adapter/outbound/BpmnXmlToDefinitionConverter.kt', 'r') as f:
        content = f.read()

    # Remove BpmnBounds from node parsing
    content = re.sub(r'val shape = boundsMap\[nodeId\]\n\s*val bounds = shape\?\.bounds\n\s*', '', content)
    content = re.sub(r'bounds = bounds,', '', content)
    content = re.sub(r',\s*bounds = bounds', '', content)

    # Remove waypoints from edge parsing
    content = re.sub(r'val waypoints = waypointsMap\[edgeId\]\?\.waypoints \?: emptyList\(\)\n\s*', '', content)
    content = re.sub(r'waypoints = waypoints,', '', content)
    content = re.sub(r',\s*waypoints = waypoints', '', content)

    # Remove parsing of bpmndi
    content = re.sub(r'val boundsMap = mutableMapOf<String, BpmnShape>\(\)\n\s*val waypointsMap = mutableMapOf<String, BpmnEdgeElement>\(\)\n\s*parseBpmndi\(document, boundsMap, waypointsMap\)', '', content)

    # Delete parseBpmndi functions
    content = re.sub(r'private fun parseBpmndi\(.*?\n\s*\}\n', '', content, flags=re.DOTALL)

    # Delete BpmnShape and BpmnEdgeElement internal classes
    content = re.sub(r'private data class BpmnShape.*?\n', '', content)
    content = re.sub(r'private data class BpmnEdgeElement.*?\n', '', content)

    with open('src/main/kotlin/dev/groknull/bpmner/generation/internal/adapter/outbound/BpmnXmlToDefinitionConverter.kt', 'w') as f:
        f.write(content)

def fix_prompt():
    with open('src/main/kotlin/dev/groknull/bpmner/generation/internal/adapter/inbound/BpmnContractGenerationPromptFactory.kt', 'r') as f:
        content = f.read()
    content = re.sub(r'\s*-\s*Coordinates\s*\(bounds and waypoints\)\s*MUST\s*be\s*provided.*?\n', '', content)
    with open('src/main/kotlin/dev/groknull/bpmner/generation/internal/adapter/inbound/BpmnContractGenerationPromptFactory.kt', 'w') as f:
        f.write(content)

fix_domain()
fix_converters()
fix_prompt()
