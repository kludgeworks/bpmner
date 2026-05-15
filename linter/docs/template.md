# Work Instruction (WI) Template for BPMN 2.0 Modeling

This template outlines the mandatory and optional information required to generate a high-quality, valid BPMN 2.0 model for your organization.

## 1. Process Overview
- **Name of the Process**: (e.g., "Order Fulfillment")
- **Process Goal**: What is the final achievement of this process?
- **Scope**: Where does it start and where does it end?

## 2. Participants (Pools & Lanes)
- **Primary Pool**: Usually the organization (e.g., "ACME Operations").
- **Lanes**: Every role or system that performs an action must be listed.
    - *Example*: HR Officer, Compliance Auditor, Learning Management System.

## 3. Process Steps (Activities)
For each step, provide:
- **Actor**: Who is doing it?
- **Action (Verb + Object)**: What are they doing? (e.g., "Approve request")
- **Inputs**: What documents/data are needed?
- **Outputs**: What is produced?

## 4. Decision Points (Gateways)
- **Question**: What is the decision being made? (e.g., "Is information complete?")
- **Outcomes**: List all possible paths (e.g., "Yes", "No", "Needs Revision").

## 5. Events
- **Start Event**: What triggers the process? (e.g., "New training request received")
- **End Event**: What signifies completion? (e.g., "Employee qualified")
- **Intermediate Events**: Are there timers or wait states? (e.g., "Wait for 2 days", "Receive approval email")

## 6. Business Rules & Compliance
- **References**: List any regulatory references (e.g., "ISO 9001 §4.2").
- **SLA**: Are there time constraints for specific steps?

---
# BPMN Transcription Questionnaire

Use this questionnaire during interviews or while transcribing a process to ensure no "BPMN-critical" information is missed.

### I. The "Who" (Structure)
1. Who is ultimately responsible for this entire process?
2. Which different departments or roles are involved in the execution?
3. Does this process interact with external parties (suppliers, customers, authorities)?

### II. The "What" (Flow)
4. What exactly happens first? What is the "trigger"?
5. Can you walk through the steps in chronological order?
6. Are any steps done in parallel (at the same time)?
7. Are there steps that are repeated or looped until a condition is met?

### III. The "Decision" (Control)
8. At what points can the process go in different directions?
9. What are the specific criteria for choosing one path over another?
10. What happens if something goes wrong (the "exception path")?

### IV. The "Information" (Data)
11. What IT systems are used at each step?
12. Are there specific forms, documents, or emails that must be sent/received?
13. Where is the final evidence of completion stored?

### V. The "Timing" (Dynamics)
14. How long does each step typically take?
15. Are there any points where the process waits for a specific time or date?
