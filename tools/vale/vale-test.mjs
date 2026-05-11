import { spawnSync } from 'node:child_process';
import { createRequire } from 'node:module';
import { dirname, join } from 'node:path';
import fs from 'node:fs';

const require = createRequire(import.meta.url);
const valePackageJson = require.resolve('@vvago/vale/package.json');
const valeBin = join(dirname(valePackageJson), 'bin', process.platform === 'win32' ? 'vale.exe' : 'vale');

const staticProductionDocs = [
  'README.md',
  'linter/README.md',
  'linter/docs/toolbox.md',
  'linter/docs/template.md',
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
  const ruleDocsDir = process.argv[3];
  const docs = [...staticProductionDocs];
  if (ruleDocsDir) {
    const ruleFiles = fs
      .readdirSync(ruleDocsDir)
      .filter((file) => file.endsWith('.md') && file !== 'README.md')
      .map((file) => join(ruleDocsDir, file));
    docs.push(...ruleFiles);
  }
  assertNoAlerts(docs);
} else if (mode === 'fixtures') {
  assertFixtureAlerts();
} else {
  throw new Error(`Unknown Vale test mode: ${mode}`);
}
