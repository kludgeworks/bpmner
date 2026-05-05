# bpmnlint-plugin-klm

Standalone Node project containing KLM-specific bpmnlint custom rules.

## Scope

This project currently implements:

- **Phase 1** deterministic rules (error)
- **Phase 2 (initial slice)** heuristic naming rules (warn)

Test fixtures are maintained under `test/fixtures` and consumed directly by the test suites.

## Install

```bash
cd tools/bpmnlint-plugin-klm
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
    "plugin:klm/recommended"
  ]
}
```
