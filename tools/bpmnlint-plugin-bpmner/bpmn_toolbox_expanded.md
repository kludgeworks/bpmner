# BPMNER BPMN AI Style Rules

Author: Rovo Chat (AI assistant)  
Scope: BPMN modeling within BPMNER Operations (BpmnSubset)  
Primary sources:
- **2.1 – BPMN Toolbox**  
  https://afbpmner.atlassian.net/wiki/spaces/BPM/pages/188483501/2.1+-+BPMN+Toolbox
- **4.1 – Naming conventions for BPMN models in BpmnSubset**  
  https://afbpmner.atlassian.net/wiki/spaces/BPM/pages/187668405/4.1+-+Naming+conventions+for+BPMN+models+in+BpmnSubset

---

## 1. General Principles

**GEN‑01 – Use only the BpmnSubset BPMN subset**

- **For modellers:**  
  Use only the BPMN elements described in the *BpmnSubset BPMN Toolbox* (activities, events, gateways, pools/lanes, connecting objects, artifacts, data elements). Avoid other “exotic” BPMN 2.0 elements.
- **For AI:**  
  - Maintain an *allow‑list* of permitted element types exactly as listed in 2.1.  
  - Flag and propose replacements for any BPMN element not mentioned in the toolbox page.

**GEN‑02 – Prefer business clarity over technical detail**

- **For modellers:**  
  Model in business language and at a level understandable by operations stakeholders.
- **For AI:**  
  - Prefer business‑meaningful names (see naming rules) over technical or system‑centric labels.  
  - When generating names, choose business terms and avoid technical jargon unless clearly needed.

---

## 2. Activity & Task Rules (2.1.1 + 4.1)

### 2.1 Naming of activities, tasks, subprocesses

**ACT‑01 – Use “Verb + Object” naming**

Source: 4.1 Activities – guideline 1

- **For modellers:**  
  - Name all activities, tasks and subprocesses as:  
    `Present‑tense verb (imperative) + business object`, e.g. `Approve request`, `Handle baggage`.  
  - Do **not** label multiple activities with the same name within the same process.
- **For AI:**  
  - Enforce pattern: first token is a verb, followed by at least one noun phrase representing a business object.  
  - Detect and flag:
    - Pure noun phrases or gerunds (`Order creation`, `Baggage handling`).  
    - Passive forms where an active form is possible (`Passenger accepted` → `Accept passenger`).  
    - Duplicate activity names within the same process.
  - Auto‑suggest compliant renames, e.g.:
    - `Order creation` → `Create order`  
    - `Passenger accepted` → `Accept passenger`

**ACT‑02 – Capitalization of activity labels**

Source: 4.1 Activities – guideline 3

- **For modellers:**  
  - Only capitalize the **first word** of the label and **proper nouns** (e.g. departments, systems).  
  - Example: `Confirm passenger booking`, `Inform Department Lost-and-Found`.
- **For AI:**  
  - Normalize labels to:  
    - First word capitalized; subsequent words lower‑case except known/proper nouns and acronyms.  
  - Flag counterexamples such as `Receive Customer Request` or `handle Delay`.

**ACT‑03 – Use suitable business verbs**

Source: 4.1 Activities – guideline 2

- **For modellers:**  
  - Use verbs that clearly express business actions (e.g., `Create`, `Validate`, `Assign`, `Confirm`).  
  - Avoid vague verbs such as `Handle` where a more specific one exists (see verb list page: https://afbpmner.atlassian.net/wiki/spaces/BPM/pages/187636531).
- **For AI:**  
  - Maintain a list of recommended and discouraged verbs from the referenced page.  
  - When encountering discouraged verbs, propose more precise alternatives.

### 2.2 Activity types and behavioral constraints (2.1.1)

**ACT‑10 – Business Process element usage**

- **For modellers:**  
  - Use `Business Process` element only for high‑level overview diagrams (top‑level view of multiple processes).  
  - Do **not** model detailed flows with it.  
  - Use it to trace to an ArchiMate Business Process via `<<trace>>`.
- **For AI:**  
  - If a `Business Process` element contains detailed flow, flag and recommend converting to a BPMN `Process` (pool with activities) and using `Business Process` for overview only.

**ACT‑11 – Task vs Subprocess vs Call Activity**

- **For modellers:**  
  - Use **Task** for atomic work that should not be decomposed further.  
  - Use **Subprocess** when you need to group multiple activities/gateways/events into a compound activity *embedded* inside its parent process.  
  - Use **Call Activity** (rounded rectangle with thick border) for **reusable** processes defined outside the current process.
- **For AI:**  
  - Check: if an activity has a child diagram that is logically part of only this process → it should be a `Subprocess`, not a `Call Activity`.  
  - If the same logic appears in multiple processes → suggest factoring it into a separate process and using `Call Activity`.

**ACT‑12 – Loop Task rules**

Source: 2.1.1 Loop Task

- **For modellers:**  
  - Use `Loop Task` when repeating the same activity on the **same** object until a condition is met.  
  - Always attach a **Text Annotation** describing the condition in the form:  
    `Loop until <condition>` (e.g., `Loop until instruction is understood`).
- **For AI:**  
  - Enforce: every Loop Task must have an associated Text Annotation linked by association.  
  - Check that annotation text contains `Loop until` or equivalent phrasing.

**ACT‑13 – Multi‑Instance Task rules**

Source: 2.1.1 Multi-instance Task (sequential / parallel)

- **For modellers:**  
  - Use **Multi‑Instance Task or Subprocess** when repeating work for each item in a list (each iteration has different data).  
  - Always attach a **Text Annotation** describing items processed in the form:  
    `For each <item>` (e.g., `For each passenger` or `For each tire`).  
  - Choose **sequential** MI when items are processed one after another; **parallel** MI when processed simultaneously.
- **For AI:**  
  - Enforce: every Multi‑Instance activity must have a Text Annotation referencing `For each`.  
  - Ensure the MI marker (sequential vs parallel) is present and consistent with the scenario described if inference is possible.

**ACT‑14 – Receive Task vs Intermediate Message Event**

Source: 2.1.1 Receive Task

- **For modellers:**  
  - Use **Receive Task** when the process must *explicitly* wait for and receive a message from an **external participant** before it can continue.  
  - Use a catching Intermediate Message Event when receiving the message does **not** depend on the user, but on an external business event that may or may not occur.
- **For AI:**  
  - Detect message‑driven waits where the description implies “must receive” from an external participant → suggest `Receive Task`.  
  - Where the text suggests a passive external event, consider a message event instead.

**ACT‑15 – Send Task vs Throwing Message Event**

Source: 2.1.1 Send Task

- **For modellers:**  
  - Use **Send Task** when a step always involves sending a message and has a clear performer.  
  - Use a throwing Intermediate Message Event for message sending that is tied to an external event, not to a user action, and has no explicit performer.
- **For AI:**  
  - When an activity description is purely about sending a message and is mandatory for the process, suggest or enforce a `Send Task` instead of a generic task.  
  - If there is no performer and the send is event‑driven, suggest a throwing message event.

---

## 3. Event Rules (2.1.2 + 4.1)

### 3.1 Naming of events

**EVT‑01 – Events represent states of reality**

Source: 4.1 Events section

- **For modellers:**  
  - Model events as **things that happen** (states of reality), not actions performed by the process.  
  - Use events to mark triggers, milestones, waiting points, and end states.
- **For AI:**  
  - Identify event labels that look like actions (`Approve request`) and suggest converting to a task where appropriate.  
  - Encourage patterns where events describe achieved states (`Request approved`).

**EVT‑02 – Event naming pattern “Noun + Past Participle”**

Source: 4.1 Events

- **For modellers:**  
  - Name events as `Noun + past participle`, e.g. `Confirmation sent`, `Order received`.  
  - Name **Timer events** with their schedule, e.g. `Daily at 16:00h`, `Two days`.  
  - Name **Error events** with a descriptive error name.  
  - Name **End events** using the end state, e.g. `Request fulfilled`, `Request rejected`.
- **For AI:**  
  - Enforce event label format:  
    - At least one noun followed by a past participle or clear state description.  
  - Check that timer event labels contain a time or duration indicator.  
  - Check that error events are explicitly named and reused consistently between throw/catch pairs.

### 3.2 Structural rules on events (2.1.2)

**EVT‑10 – Start events must have no incoming sequence flow**

- **For modellers:**  
  - Use start events (`none`, message, timer, conditional) to initiate the process. They cannot be attached to other elements and have no incoming sequence flows.
- **For AI:**  
  - Validate: start events have **zero incoming** sequence flows.

**EVT‑11 – Message start events require message flows**

Source: 2.1.2 Message start event

- **For modellers:**  
  - When a process starts via **message start event**, model the corresponding **Message Flow** from the external participant.
- **For AI:**  
  - Enforce: every message start event must have at least one incoming message flow from another pool.

**EVT‑12 – Timer start events block until time**

Source: 2.1.2 Timer start event

- **For modellers:**  
  - Use timer start when the process waits until a specific date/time or duration before starting.
- **For AI:**  
  - Check that timer start events are not combined with inappropriate incoming sequence flows.

**EVT‑13 – Intermediate events are not activities**

Source: 2.1.2 Intermediate events

- **For modellers:**  
  - Do **not** use intermediate events to model work. They represent events that *happen* (including exceptions) while activities do the work.
- **For AI:**  
  - If an intermediate event is named like an action (verb+object), suggest converting to a task or re‑naming to a state.

**EVT‑14 – Boundary events for exception handling**

Source: 2.1.2 boundary events; 4.1 Events

- **For modellers:**  
  - Attach boundary events (Message, Error, Escalation, Signal, etc.) to a task or subprocess to represent exception or compensation paths.  
  - Error boundary events are always **interrupting**.
- **For AI:**  
  - Enforce:
    - Boundary events have **no incoming** sequence flows and **one** outgoing sequence flow.  
    - Error boundary events are marked interrupting.  
    - Boundary events are attached to a task or subprocess, not floating.

**EVT‑15 – Error end event pairing**

Source: 2.1.2 Error end event

- **For modellers:**  
  - Use **Error end event** inside a subprocess to signal an exception to a matching **Error boundary event** in the parent process.  
  - Names/codes must match.
- **For AI:**  
  - Enforce: each Error end event in a subprocess has a matching Error boundary event in the parent diagram with the same name/code.

**EVT‑16 – Link intermediate events pair with same reference**

Source: 2.1.2 Link events

- **For modellers:**  
  - Use throwing/catching **Link intermediate events** as on‑page or off‑page connectors.  
  - Both ends must share the same reference (number or code).
- **For AI:**  
  - Enforce that:
    - Throwing link events and catching link events are paired by a common reference.  
    - No unpaired link events exist.

**EVT‑17 – Signal events for broadcast only when needed**

Source: 2.1.2 Signal events

- **For modellers:**  
  - Use **Signal events** only when broadcast behavior is required and more targeted mechanisms (Message, Error, Escalation) are insufficient.
- **For AI:**  
  - Warn when Signal events are used where simple message flow between pools would suffice.

---

## 4. Gateway Rules (2.1.3 + 4.1)

### 4.1 Naming and purpose of gateways

**GTW‑01 – Diverging gateways named as a question**

Source: 4.1 Gateways – guideline 1

- **For modellers:**  
  - Name diverging (splitting) **Exclusive** and **Inclusive** gateways using a **question** that expresses the decision, e.g.:  
    `Is document valid?`  
    `Passenger eligible for upgrade or special assistance?`
- **For AI:**  
  - Enforce:
    - Diverging exclusive/inclusive gateways must have a non‑empty label ending with `?` (or equivalent interrogative form).  
  - Flag labels that are not questions (e.g. `Document check result`) and suggest replacements.

**GTW‑02 – Converging gateways remain unnamed**

Source: 4.1 Gateways – guideline 2

- **For modellers:**  
  - Do **not** name converging (merge/join) gateways (Exclusive, Inclusive, Parallel).  
  - If convergence logic is unclear, use a **Text Annotation** instead of naming the gateway.
- **For AI:**  
  - Detect converging gateways with labels and flag them for removal of the label.  
  - Optionally suggest adding/using a Text Annotation when complex.

**GTW‑03 – Gateways test conditions; they do not perform work**

Source: 4.1 Gateways – guideline 3

- **For modellers:**  
  - Gateways only evaluate conditions based on prior activities. They must not perform work.  
  - The work (e.g., checking documents) must be modeled as a preceding activity; the gateway only branches based on the outcome.
- **For AI:**  
  - If a gateway label describes work (e.g., `Check document and approve application?`), suggest splitting into:
    - A task (e.g., `Check identity document`) and  
    - A decision gateway (e.g., `Is document valid?`).

### 4.2 Structural rules and semantics (2.1.3)

**GTW‑10 – Exclusive, Inclusive and Parallel semantics**

Source: 2.1.3

- **For modellers:**  
  - **Exclusive (XOR):** one outgoing path is taken; one incoming path active at a time.  
  - **Inclusive (OR):** one or more outgoing paths can be taken; merges wait for active branches.  
  - **Parallel (AND):** all outgoing paths are taken; merges wait for tokens from all incoming paths.
- **For AI:**  
  - Check label and branch structure for consistency with semantics (e.g., avoid mutually exclusive condition labels on an OR gateway).  
  - Ensure split/join patterns are coherent (parallel split should have matching parallel join where appropriate).

**GTW‑11 – Event‑based gateway usage**

Source: 2.1.3 event‑based gateways

- **For modellers:**  
  - Use **Event‑based gateways** only when the process waits for **events** rather than data conditions.  
  - Immediately after an event‑based gateway, each outgoing sequence flow must go directly to an **intermediate event** (no tasks between the gateway and those events).  
  - Exclusive event‑based: one of the following events occurs.  
  - Parallel event‑based: **all** following events must occur.
- **For AI:**  
  - Enforce:
    - All outgoing flows from an event‑based gateway connect directly to events (not tasks).  
    - Instantiating event‑based gateways are only used at process starts and followed by events as described.

**GTW‑12 – Sequence flow naming on diverging gateways**

Source: 4.1 Sequence Flows – guideline 1

- **For modellers:**  
  - Name sequence flows coming out of diverging Exclusive, Inclusive and Complex gateways using **conditions stated as outcomes**, e.g. `Valid`, `Invalid`, `Eligible`, `Not eligible`.
- **For AI:**  
  - Ensure outgoing flows from such gateways have labels.  
  - Flag missing or vague labels and suggest short outcome phrases derived from the gateway question.

---

## 5. Pools, Lanes & Participant Rules (2.1.4 + 4.1)

### 5.1 Pools

**POOL‑01 – White‑box pool named by process**

Source: 4.1 Pools – guideline 1

- **For modellers:**  
  - Name a **white‑box pool** with the **process name**, not with an organization or role, e.g.:  
    - `Check-in passengers at manned check-in desk`  
    - `Handle rebooking request`
- **For AI:**  
  - Detect pool names that look like organization/role names (`Department Passenger Services`, `Agent`) and suggest process‑oriented names.

**POOL‑02 – Black‑box pool named by external entity or process**

Source: 4.1 Pools – guideline 2; 2.1.4 Pool (black box)

- **For modellers:**  
  - Name black‑box pools using the name of the **external process, entity, organization, department, system**, etc.
- **For AI:**  
  - Ensure that pools without internal process content (black‑box) have labels consistent with entities (e.g., `Customer`, `External system X`, `Bank`).

**POOL‑03 – Child diagrams keep pool = process name**

Source: 4.1 Pools – guideline 3

- **For modellers:**  
  - In a child‑level diagram, if the pool represents a subprocess of a higher‑level process, its label must **still be the name of the upper‑level process**, not the subprocess.
- **For AI:**  
  - Check pool labels across levels; if a child diagram’s pool name diverges from the parent process name, flag and suggest alignment.

### 5.2 Lanes

**LANE‑01 – Lane labels = business roles/performers**

Source: 4.1 Lanes – guideline 1; 2.1.4 Lane

- **For modellers:**  
  - Name each lane by the **business role or performer** of the activities, e.g.:  
    - `Team Member Apron Services`, `Cockpit Crew`, `Department Baggage Services`.  
  - Lanes sit inside a **white‑box pool** and represent responsibility from start to end of the process.
- **For AI:**  
  - Flag overly generic or unclear labels like `BPMNER Employee`, `Cleaning`.  
  - Suggest more specific role/department names when context is available.

**LANE‑02 – Actor artifact usage**

Source: 2.1.6 Actor (custom artifact)

- **For modellers:**  
  - You may place an `Actor` custom artifact **inside a lane** to indicate which actor performs the activities in that lane.  
  - This is for clarification and decoration; it does not change lane semantics.
- **For AI:**  
  - Treat Actor artifacts as documentation only; do not confuse them with additional lanes or participants.

---

## 6. Flow & Connector Rules (2.1.5 + 4.1)

### 6.1 Sequence flows

**FLOW‑01 – Sequence flows stay within a pool**

Source: 2.1.5 Sequence Flow

- **For modellers:**  
  - Sequence flows can connect:
    - Elements within the same **lane**, or  
    - Elements in different lanes that belong to the **same pool**.  
  - They must **not** cross pool boundaries.
- **For AI:**  
  - Enforce: no sequence flow may have endpoints in different pools.  
  - If such a case is found, suggest converting to a **Message Flow**.

**FLOW‑02 – Name sequence flows from diverging gateways**

Source: 4.1 Sequence Flows

- **For modellers:**  
  - For flows leaving diverging Exclusive / Inclusive / Complex gateways, name flows with their **associated outcome conditions**.
- **For AI:**  
  - Ensure such outgoing flows have non‑empty labels.  
  - Derive short outcome labels from gateway question when absent.

### 6.2 Message flows

**MSG‑01 – Message flows only across pools**

Source: 2.1.5 Message Flow

- **For modellers:**  
  - Use Message Flows to represent communication across **different pools** (across participants).  
  - They can connect:
    - Activities belonging to **different pools**  
    - Activities of one pool and a different **black‑box pool**  
    - Two pools  
    - Message events in different pools
- **For AI:**  
  - Enforce that both ends of a Message Flow are in **different pools**.  
  - Flag any Message Flow entirely inside one pool as an error.

**MSG‑02 – Message flow naming pattern**

Source: 2.1.5 Message Flow; 4.1 Message Flows

- **For modellers:**  
  - Label Message Flows with the **name of the message**, not the action:  
    - Acceptable:
      - A simple **noun**: `Order message`  
      - An **adjectively used past participle + noun**: `Confirmed order message`  
      - Message state terms like `Approval confirmation`, `'Accepted' task status update`.
    - Not acceptable:
      - `Create order message` (verb + object)  
      - `Order message confirmed` (noun + past participle)  
      - `Send approval` (action)
- **For AI:**  
  - Enforce patterns:
    - Reject pure verb+object labels.  
    - Prefer noun or adjective(past participle)+noun structure.  
  - Suggest compliant renames, e.g.:
    - `Send approval` → `Approval confirmation`  
    - `Order message confirmed` → `Confirmed order message`.

**MSG‑03 – Envelope icon usage**

Source: 2.1.5 Message Flow

- **For modellers:**  
  - Use the **envelope icon** on message flows to depict **electronic messages** (paper‑based flows, web services, IT interfaces).  
  - Message flows **without** envelope icon are reserved for **direct human communication** (spoken, hand signals).
- **For AI:**  
  - When the name or context indicates an interface/IT communication, suggest adding the envelope icon.  
  - When clearly human communication, suggest no envelope icon and ensure consistency.

### 6.3 Associations

**ASSOC‑01 – Associations for annotations and artifacts**

Source: 2.1.5 Association; 2.1.6 Text Annotation

- **For modellers:**  
  - Use **Associations** to link Text Annotations, Groups, and other artifacts to BPMN elements.
- **For AI:**  
  - Verify:
    - Loop and multi‑instance tasks have an associated Text Annotation via association.  
    - Actor artifacts, groups, and annotations are connected where their purpose is to clarify a specific element.

---

## 7. Artifact & Data Rules (2.1.6–2.1.7 + 4.1 Data)

### 7.1 Artifacts

**ART‑01 – Group usage**

Source: 2.1.6 Group

- **For modellers:**  
  - Use `Group` to visually group related elements; it does not affect process logic.
- **For AI:**  
  - Treat groups as non‑semantic containers; do not infer control flow or data flow from group membership.

**ART‑02 – Text Annotation usage**

Source: 2.1.6 Text Annotation

- **For modellers:**  
  - Use `Text Annotation` to document clarifications or extra details about processes, activities, events, etc.  
  - Always attach it to its target with an association.
- **For AI:**  
  - Enforce that text annotations intended to explain a specific element are associated to that element.  
  - Check required uses:
    - Loop tasks → loop condition annotation.  
    - Multi‑instance tasks → `For each <item>` annotation.  
    - Complex gateway joins → optional clarifying annotation.

### 7.2 Data naming and application representation

**DATA‑01 – Data object naming**

Source: 4.1 Data – guideline 1

- **For modellers:**  
  - Name data objects with a **qualified noun** that is the name of a business or information object meaningful to the business (e.g., `Passenger manifest`, `Baggage tag data`).
- **For AI:**  
  - Enforce that data object names are nouns or noun phrases, not actions.  
  - Reject labels that include `activity`, `process`, etc., in the name.

**DATA‑02 – Information Item vs Application Component**

Source: 2.1.7 Data

- **For modellers:**  
  - Use `Information Item` as a temporary custom BPMN artifact in Function Allocation Diagrams (FAD) to allocate Application Components to Business Processes or Activities.  
  - Note: In the PRADA project, `Application Component (Archimate)` is **not** used to depict IT applications; `Information Item` is used instead, until a central PaxOps repository integration is complete.
- **For AI:**  
  - In PRADA/FAD contexts, prefer `Information Item` for application allocation, not `Application Component`.  
  - When analyzing older diagrams, be prepared to see `Application Component` but promote convergence toward `Information Item` as per the update.

---

## 8. General Naming Rules (4.1)

**NAME‑01 – Use business‑meaningful keywords**

Source: 4.1 General naming – guideline 1

- **For modellers:**  
  - Choose names that are meaningful to the business and understandable by non‑technical stakeholders.
- **For AI:**  
  - Flag overly technical or cryptic names (e.g., pure code names without business context), and if possible, suggest clearer alternatives.

**NAME‑02 – Avoid uncommon abbreviations**

Source: 4.1 General naming – guideline 2

- **For modellers:**  
  - Do not use uncommon abbreviations. If you must use them, provide:
    - A glossary, or  
    - A Text Annotation explaining the abbreviation.  
  - Example:
    - Good: `Clear passengers from Ineligible-To-Board list (ITBL)`  
    - Bad: `Clear ITBL pax`
- **For AI:**  
  - Detect sequences of uppercase letters or domain‑specific short forms.  
  - If abbreviation is not a well‑known acronym, suggest adding its expansion either to the label or via Text Annotation.

**NAME‑03 – Do not include element type in the name**

Source: 4.1 General naming – guideline 3

- **For modellers:**  
  - Do not append words like `activity`, `process`, `event` in element names. The BPMN shape already indicates type.  
  - Example:
    - Good: `Clean aircraft interior`  
    - Bad: `Clean aircraft interior activity`, `Deep clean interior process`
- **For AI:**  
  - Strip redundant words such as `activity`, `process`, `event` from labels and suggest cleaner versions.

---

## 9. How AI Should Use This File

1. **Generation phase**
   - Only use BPMN elements permitted in section **2.1 – 2.1.7** of the toolbox.
   - When creating labels, apply:
     - Activities: **ACT‑01 to ACT‑03**  
     - Events: **EVT‑01 to EVT‑02**  
     - Gateways: **GTW‑01 to GTW‑03**  
     - Pools/lanes: **POOL‑01 to POOL‑03**, **LANE‑01**  
     - Message/sequence flows: **FLOW‑01–02**, **MSG‑01–03**  
     - Data: **DATA‑01–02**, **NAME‑01–03**.

2. **Validation phase**
   - Run structural checks:
     - Sequence flows within pools only (**FLOW‑01**).  
     - Message flows between pools only (**MSG‑01**).  
     - Boundary event and link event pairing (**EVT‑14–17**).  
   - Run naming checks on all elements according to their type (sections 2–8).
   - Report violations with:
     - Rule ID  
     - Short description  
     - Suggestion for automatic or semi‑automatic correction.

3. **Correction phase**
   - Apply auto‑fixes where safe:
     - Rename elements to match patterns (e.g., `Order creation` → `Create order`).  
     - Add missing annotations for loop/MI tasks.  
     - Adjust flow types when sequence flows incorrectly cross pools.
   - For ambiguous cases, propose options and request human confirmation.

---