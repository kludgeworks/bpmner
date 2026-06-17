---
markdownlint-disable: MD013
---

# act-activity-label-capitalization

- **Name**: Activity Label Capitalization
- **Category**: Activity
- **Severity**: WARNING
- **Target Elements**: `bpmn:Task`, `bpmn:SubProcess`, `bpmn:CallActivity`

## Intent

Keep activity labels in readable sentence case.

## Modeller Guidance

Capitalize the first word of an activity label and keep later words lowercase unless they are acronyms or proper nouns.

## AI Guidance

Detect activity, subprocess, and call activity labels that start lowercase or use title case after the first word.

## Diagnostic Messages

- `default`: Activity label should use sentence case
- `firstWord`: Activity label should start with a capitalized first word
- `sentenceCase`: Activity label should use sentence case after the first word \(except acronyms/proper nouns\)

## Repair

- **Kind**: `LOCAL_MODEL_FIX`
- **Safety**: `SAFE_AUTOMATIC`
- **Handler**: `fixSentenceCase`
