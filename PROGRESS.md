# bpmner — Project Summary & Progress

## What We're Building

A Kotlin/Spring Boot application that generates valid BPMN 2.0 XML from plain-language business process descriptions, implemented as an [Embabel](https://github.com/embabel/embabel-agent) agent.

The original implementation was a TypeScript/Bun CLI script. We are porting it to the Embabel JVM agent framework.

### Core behaviour

1. User provides a business process description (e.g. "Order goes from customer to warehouse to shipping")
2. An LLM generates a typed `BpmnDefinition` object containing BPMN topology and BPMNDI layout
3. The typed definition is converted deterministically into BPMN 2.0 XML
4. The XML is validated against the BPMN 2.0 XSD schema
5. The XML is validated with `bpmn-lint`
6. Graph, XSD, or lint validation errors are fed back to the LLM for correction (multi-turn loop, up to `maxAttempts`)
7. The validated BPMN XML is written to a file

---

## Architecture

### Embabel Agent pattern

Embabel uses GOAP (Goal-Oriented Action Planning). Actions are annotated Kotlin methods; the planner chains them automatically by matching output types to input types on a shared blackboard.

```
BpmnRequest  →  generateBpmnDefinition()  →  BpmnDefinition  →  validateAndRenderBpmn()  →  ValidatedBpmnXml  →  writeBpmn()  →  BpmnResult
```

`writeBpmn` is annotated `@AchievesGoal`, so the planner knows it is the terminal step.

### Key files

| File | Purpose |
|------|---------|
| `BpmnGeneratorAgent.kt` | `@Agent` with typed definition generation, validate/render loop, and file write |
| `BpmnDefinitionToXmlConverter.kt` | Deterministically renders typed BPMN definitions into semantic BPMN + BPMNDI |
| `BpmnDefinitionValidator.kt` | Graph-level validation that complements Embabel bean validation |
| `BpmnXsdValidator.kt` | `@Component` that validates XML against the BPMN 2.0 XSD on the classpath |
| `BpmnLintService.kt` | `@Service` that runs `bpmn-lint` via `npx` |
| `ClasspathResourceResolver.kt` | `LSResourceResolver` that resolves XSD imports (BPMNDI, DC, DI, Semantic) |
| `BpmnGeneratorRunner.kt` | `ApplicationRunner` CLI entry point (`--process`, `--process-file`, `--output`) |
| `domain.kt` | `BpmnRequest`, `BpmnDefinition`, layout DTOs, `ValidatedBpmnXml`, `BpmnResult` |
| `BpmnerApplication.kt` | Spring Boot app with `@EnableAgents` + `@ConfigurationPropertiesScan` |
| `src/main/resources/xsd/` | BPMN20.xsd, BPMNDI.xsd, DC.xsd, DI.xsd, Semantic.xsd |

### Key dependency decisions

| Concern | Solution |
|---------|----------|
| Embabel version | `0.3.5` (latest release) from `https://repo.embabel.com/artifactory/libs-release` |
| Spring Boot | `3.5.9` |
| Java | `21` |
| Typed LLM boundary | `BpmnDefinition` object generation via Embabel `createObject(...)` |
| BPMN rendering | Deterministic Kotlin/Camunda model conversion from typed DTO to XML |
| bpmn-lint execution | `npx --yes --package=bpmnlint@11.12.1` running a small Node script |
| Layout ownership | The LLM supplies BPMNDI bounds and waypoints directly |

### Typed object generation and correction

Embabel now generates a typed `BpmnDefinition` first. That object contains:

- semantic process data (`processId`, `processName`, nodes, sequence flows)
- per-node `BpmnBounds`
- per-edge `BpmnWaypoint` lists
- optional sequence-flow `conditionExpression`

Embabel bean validation handles simple DTO/schema constraints on that object. Custom graph validation, BPMN conversion, XSD validation, and `bpmn-lint` run in a second action. Any resulting feedback is turned into a correction prompt that asks the LLM for a full corrected `BpmnDefinition`.

### `bpmn-lint` execution

`BpmnLintService` shells out to `npx`, installs `bpmnlint` on demand, and runs a small Node script using the official `bpmnlint` API. This avoids the earlier WebJar/GraalJS compatibility path.

---

## Current Status

### What works

- [x] Full Embabel agent structure (`@Agent`, `@Action`, `@AchievesGoal`, `@Export`)
- [x] Type-driven blackboard flow from `BpmnRequest` to `BpmnDefinition` to `ValidatedBpmnXml`
- [x] Multi-turn LLM feedback loop for graph/XSD/lint correction
- [x] XSD validation via `BpmnXsdValidator` (extracted from agent, now a standalone `@Component`)
- [x] Deterministic typed-to-XML conversion with semantic BPMN and BPMNDI
- [x] CLI entry point (`BpmnGeneratorRunner`) with `--process`, `--process-file`, `--output`, `--style-guide` args
- [x] `BpmnLintService` running through `npx`
- [x] Build compiles and passes unit tests (`BpmnerApplicationTests`)

### What is broken / in progress

- [ ] **Anthropic model catalog alignment** — Embabel's packaged Anthropic aliases and Anthropic's currently accepted model ids do not fully line up in this environment, so profile/model selection still needs cleanup.
- [ ] **Coverage depth** — there are unit tests for the typed validator and converter, but the typed correction loop could use more targeted action-level tests.

### Next step

Add action-level tests around the typed correction flow so we can verify that invalid `BpmnDefinition` outputs are repaired through graph/XSD/lint feedback without regressing the Embabel blackboard pipeline.

---

## How to Run

```bash
# Build
./mvnw clean package -DskipTests

# Run (requires ANTHROPIC_API_KEY and npx on PATH)
export ANTHROPIC_API_KEY="$(op read 'op://Personal/claude.ai/personal-mac-token')"
./mvnw spring-boot:run -Dspring-boot.run.arguments="--process-file=toast-process.txt --output=toast.bpmn"

# Run tests
./mvnw test
```

Configuration (`application.yaml`):
```yaml
bpmner:
  max-attempts: 8
  # model: claude-sonnet-4-0
```
