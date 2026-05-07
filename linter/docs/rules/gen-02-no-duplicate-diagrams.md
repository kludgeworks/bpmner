# gen-02-no-duplicate-diagrams

- Severity: error
- Purpose: Ensure the BPMN document contains exactly one `bpmndi:BPMNDiagram` for compatibility with downstream viewers and tooling.
- Trigger: The root `bpmn:Definitions` contains more than one BPMN diagram entry.
