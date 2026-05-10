import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';

const require = createRequire(import.meta.url);
const valePackageJson = require.resolve('@vvago/vale/package.json');
const valeBin = join(dirname(valePackageJson), 'bin', process.platform === 'win32' ? 'vale.exe' : 'vale');

const productionDocs = [
  'README.md',
  'linter/README.md',
  'linter/bpmn_toolbox_expanded.md',
  'linter/docs/rules/README.md',
  'linter/docs/rules/act-01-verb-object-name.md',
  'linter/docs/rules/act-02-activity-label-capitalization.md',
  'linter/docs/rules/act-03-discouraged-business-verbs.md',
  'linter/docs/rules/act-12-loop-task-annotation.md',
  'linter/docs/rules/act-13-mi-task-annotation.md',
  'linter/docs/rules/assoc-01-required-annotation-association.md',
  'linter/docs/rules/data-01-no-type-words-in-data-name.md',
  'linter/docs/rules/evt-01-event-state-name.md',
  'linter/docs/rules/evt-02-event-state-pattern.md',
  'linter/docs/rules/evt-10-start-no-incoming.md',
  'linter/docs/rules/evt-11-message-start-has-message-flow.md',
  'linter/docs/rules/evt-13-intermediate-event-not-action.md',
  'linter/docs/rules/evt-14-boundary-event-constraints.md',
  'linter/docs/rules/evt-15-error-end-boundary-pair.md',
  'linter/docs/rules/evt-16-link-event-pairing.md',
  'linter/docs/rules/flow-01-sequence-flow-within-pool.md',
  'linter/docs/rules/flow-02-diverging-flow-outcome-label.md',
  'linter/docs/rules/gen-01-bpmnsubset-allowed-elements.md',
  'linter/docs/rules/gen-02-no-duplicate-diagrams.md',
  'linter/docs/rules/gtw-01-diverging-gateway-question.md',
  'linter/docs/rules/gtw-02-converging-gateway-unnamed.md',
  'linter/docs/rules/gtw-03-gateway-no-work-label.md',
  'linter/docs/rules/gtw-11-event-based-direct-events.md',
  'linter/docs/rules/gtw-12-diverging-flow-names.md',
  'linter/docs/rules/gtw-20-no-gateway-join-fork.md',
  'linter/docs/rules/gtw-21-fake-join.md',
  'linter/docs/rules/gtw-22-superfluous-gateway.md',
  'linter/docs/rules/msg-01-message-flow-across-pools.md',
  'linter/docs/rules/msg-02-message-flow-name-pattern.md',
  'linter/docs/rules/name-01-business-meaningful-label.md',
  'linter/docs/rules/name-02-uncommon-abbreviations.md',
  'linter/docs/rules/name-03-no-element-type-words.md',
];

function runVale(files) {
  const result = spawnSync(
    valeBin,
    ['--output=JSON', '--config=.vale.ini', ...files],
    { encoding: 'utf8' },
  );

  if (result.error) {
    throw result.error;
  }
  if (result.status === 2) {
    throw new Error(`Vale runtime error:\n${result.stderr}\n${result.stdout}`);
  }

  const output = result.stdout.trim();
  return output === '' ? {} : JSON.parse(output);
}

function flattenAlerts(report) {
  return Object.entries(report).flatMap(([file, alerts]) =>
    alerts.map((alert) => ({
      file,
      check: alert.Check,
      line: alert.Line,
      message: alert.Message,
    })),
  );
}

function assertNoAlerts(files) {
  const alerts = flattenAlerts(runVale(files));
  if (alerts.length > 0) {
    const rendered = alerts
      .map((alert) => `${alert.file}:${alert.line} ${alert.check}: ${alert.message}`)
      .join('\n');
    throw new Error(`Vale reported ${alerts.length} alert(s):\n${rendered}`);
  }
}

function assertFixtureAlerts() {
  assertNoAlerts(['test/prose/valid.md']);

  const alerts = flattenAlerts(runVale(['test/prose/invalid.md']));
  const checks = alerts.map((alert) => alert.check).sort();
  const expected = [
    'BPMN.Inclusive',
    'BPMN.RuleProse',
    'BPMN.Terms',
    'BPMN.Terms',
    'BPMN.Terms',
    'BPMN.Terms',
  ].sort();

  if (JSON.stringify(checks) !== JSON.stringify(expected)) {
    throw new Error(
      `Unexpected Vale fixture checks.\nExpected: ${expected.join(', ')}\nActual: ${checks.join(', ')}`,
    );
  }
}

const mode = process.argv[2];

if (mode === 'docs') {
  assertNoAlerts(productionDocs);
} else if (mode === 'fixtures') {
  assertFixtureAlerts();
} else {
  throw new Error(`Unknown Vale test mode: ${mode}`);
}
