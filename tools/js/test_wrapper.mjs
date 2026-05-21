/**
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

import { spawnSync } from 'node:child_process';
import { env, argv, exit, execPath } from 'node:process';

/**
 * Wrapper for Node.js tests in Bazel.
 *
 * This version uses spawnSync to run the built-in Node.js test runner
 * while filtering out non-JS files (like source maps) that would cause
 * SyntaxErrors.
 *
 * Usage: node test_wrapper.mjs <file1> <file2> ...
 */

const allArgs = argv.slice(2);
// Filter out .map files only. We deliberately use a denylist (rather than an
// allowlist of .js/.mjs/.cjs) so that legitimate runner flags passed via
// Bazel `--test_arg=...` — for example `--test-name-pattern`, `--test-only`,
// `--test-concurrency` — survive the filter. .map files are the one
// pathological case: the bundler emits source-map sidecars into the runfiles
// alongside the test JS, and Node tries to parse them as JS, throwing
// SyntaxError on the leading `{`.
const testFiles = allArgs.filter(arg => !arg.endsWith('.map'));

if (testFiles.length === 0) {
    console.error('Error: No test arguments provided to test_wrapper.mjs');
    console.error('Arguments received:', allArgs);
    exit(1);
}

const args = ['--test'];

// If Bazel is requesting coverage collection
const coverageOutputFile = env.COVERAGE_OUTPUT_FILE;
if (coverageOutputFile) {
    args.push('--experimental-test-coverage');

    // Node.js pairs reporters and destinations positionally:
    // 1. spec -> stdout
    args.push('--test-reporter=spec');
    args.push('--test-reporter-destination=stdout');

    // 2. lcov -> coverage file
    args.push('--test-reporter=lcov');
    args.push(`--test-reporter-destination=${coverageOutputFile}`);
} else {
    // Default reporter for non-coverage runs
    args.push('--test-reporter=spec');
}

// Add the filtered test files
args.push(...testFiles);

console.log(`Running test runner: ${execPath} ${args.join(' ')}`);

const result = spawnSync(execPath, args, {
    stdio: 'inherit',
    env: {
        ...env,
        BAZEL_TEST: '1',
    }
});

// When spawnSync's own machinery fails (binary missing, EACCES, sandbox
// violation, etc.), result.error is set and result.status is null. Without
// this line the wrapper exits 1 with no trace — the test log just shows
// our "Running test runner: ..." line and then nothing.
if (result.error) {
    console.error('test_wrapper: failed to spawn runner:', result.error.message);
}

exit(result.status ?? 1);
