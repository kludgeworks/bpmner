# bpmner — Project Summary & Progress

## What We're Building

A Kotlin/Spring Boot application that generates valid BPMN 2.0 XML from plain-language business process descriptions, implemented as an [Embabel](https://github.com/embabel/embabel-agent) agent.

The original implementation was a TypeScript/Bun CLI script. We are porting it to the Embabel JVM agent framework.

### Core behaviour

1. User provides a business process description (e.g. "Order goes from customer to warehouse to shipping")
2. An LLM generates BPMN 2.0 XML
3. The XML is validated against the BPMN 2.0 XSD schema
4. The XML is validated with bpmn-lint
5. Any validation errors are fed back to the LLM for correction (multi-turn loop, up to `maxAttempts`)
6. The validated BPMN XML is written to a file

---

## Architecture

### Embabel Agent pattern

Embabel uses GOAP (Goal-Oriented Action Planning). Actions are annotated Kotlin methods; the planner chains them automatically by matching output types to input types on a shared blackboard.

```
BpmnRequest  →  generateValidBpmn()  →  ValidatedBpmnXml  →  writeBpmn()  →  BpmnResult
```

`writeBpmn` is annotated `@AchievesGoal`, so the planner knows it is the terminal step.

### Key files

| File | Purpose |
|------|---------|
| `BpmnGeneratorAgent.kt` | `@Agent` with two `@Action` methods: LLM feedback loop + file write |
| `BpmnXsdValidator.kt` | `@Component` that validates XML against the BPMN 2.0 XSD on the classpath |
| `BpmnLintService.kt` | `@Service` that runs bpmn-lint inside a GraalJS context |
| `ClasspathResourceResolver.kt` | `LSResourceResolver` that resolves XSD imports (BPMNDI, DC, DI, Semantic) |
| `BpmnGeneratorRunner.kt` | `ApplicationRunner` CLI entry point (`--process`, `--process-file`, `--output`) |
| `domain.kt` | `BpmnRequest`, `ValidatedBpmnXml`, `BpmnResult` data classes |
| `BpmnerApplication.kt` | Spring Boot app with `@EnableAgents` + `@ConfigurationPropertiesScan` |
| `src/main/resources/xsd/` | BPMN20.xsd, BPMNDI.xsd, DC.xsd, DI.xsd, Semantic.xsd |

### Key dependency decisions

| Concern | Solution |
|---------|----------|
| Embabel version | `0.3.5` (latest release) from `https://repo.embabel.com/artifactory/libs-release` |
| Spring Boot | `3.5.9` |
| Java | `21` |
| bpmn-lint execution | GraalJS (`org.graalvm.polyglot:polyglot:25.0.2` + `js:25.0.2`) — runs JS in-process |
| bpmn-lint distribution | WebJar `org.webjars.npm:bpmnlint:11.12.1` — npm package as Maven dependency |
| Lint output parsing | Jackson (`jacksonObjectMapper`) — no regex |

### bpmn-lint in GraalJS

The bpmnlint WebJar resources are extracted at startup from `META-INF/resources/webjars/**` into a temp `node_modules/` directory using `PathMatchingResourcePatternResolver`. GraalJS CommonJS is pointed at this directory. The async JS Promises are bridged to Java via `ProxyExecutable` callbacks and a `CompletableFuture`, with the microtask queue pumped by `ctx.eval("0")`.

---

## Current Status

### What works

- [x] Full Embabel agent structure (`@Agent`, `@Action`, `@AchievesGoal`, `@Export`)
- [x] Multi-turn LLM feedback loop with XSD and lint error messages
- [x] XSD validation via `BpmnXsdValidator` (extracted from agent, now a standalone `@Component`)
- [x] CLI entry point (`BpmnGeneratorRunner`) with `--process`, `--process-file`, `--output`, `--style-guide` args
- [x] `BpmnLintService` scaffolded with GraalJS + WebJar extraction
- [x] Build compiles and passes unit tests (`BpmnerApplicationTests`)

### What is broken / in progress

- [ ] **bpmn-lint WebJar extraction** — the `PathMatchingResourcePatternResolver` finds 247 classpath resources matching `classpath*:META-INF/resources/webjars/**`, but all 196 readable ones are extracted under the package name `npm` (not `bpmnlint`). The WebJar URL path has an extra `npm` segment: `webjars/npm/bpmnlint/11.12.1/...` (because the Maven groupId is `org.webjars.npm`), so the regex captures `npm` as the package name and `bpmnlint/11.12.1/...` as the sub-path. The fix is to adjust the regex to skip the extra `npm` segment, or to handle the `org.webjars.npm` groupId convention explicitly.

### Next step

Fix the WebJar URL regex in `BpmnLintService.extractWebJars()`. The actual URL structure is:

```
...webjars/npm/bpmnlint/11.12.1/package.json
```

The current regex `webjars/([^/]+)/([^/]+)/(.+)` captures:
- Group 1: `npm`  ← wrong, should be `bpmnlint`
- Group 2: `bpmnlint` ← version segment, should be `11.12.1`
- Group 3: `11.12.1/package.json` ← wrong

The fix should handle both `org.webjars` (path: `webjars/{name}/{version}/...`) and `org.webjars.npm` (path: `webjars/npm/{name}/{version}/...`) URL patterns.

---

## How to Run

```bash
# Build
./mvnw clean package -DskipTests

# Run (requires OPENAI_API_KEY)
export OPENAI_API_KEY=sk-...
java -jar target/bpmner-0.0.1-SNAPSHOT.jar \
  --process="Customer places order, warehouse picks items, shipping dispatches" \
  --output=order.bpmn

# Or from a file
java -jar target/bpmner-0.0.1-SNAPSHOT.jar \
  --process-file=process.txt \
  --output=order.bpmn \
  --style-guide=style.md

# Run tests
./mvnw test
```

Configuration (`application.yaml`):
```yaml
bpmner:
  max-attempts: 5
  # model: gpt-4o   # leave blank to use auto LLM selection
```
