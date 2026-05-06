# bpmnlint-plugin-bpmner

Standalone Node project containing BPMNER-specific bpmnlint custom rules.

## Scope

This project currently implements:

- **Phase 1** deterministic rules (error)
- **Phase 2 (initial slice)** heuristic naming rules (warn)

Test fixtures are maintained under `test/fixtures` and consumed directly by the test suites.

## Install

```bash
cd tools/bpmnlint-plugin-bpmner
npm install
```

## Run tests

```bash
npm test
```

## Plugin usage (later wiring)

```json
{
  "extends": [
    "plugin:bpmner/recommended"
  ]
}
```
