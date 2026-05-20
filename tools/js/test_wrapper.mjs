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
// Filter out .map files and other non-test arguments for the runner
const testFiles = allArgs.filter(arg => arg.endsWith('.js') || arg.endsWith('.mjs') || arg.endsWith('.cjs'));

if (testFiles.length === 0) {
    console.error('Error: No test files provided to test_wrapper.mjs');
    console.error('Arguments received:', allArgs);
    exit(1);
}

const args = ['--test'];

// Add reporters
args.push('--test-reporter=spec');

// If Bazel is requesting coverage collection
const coverageOutputFile = env.COVERAGE_OUTPUT_FILE;
if (coverageOutputFile) {
    args.push('--experimental-test-coverage');
    args.push('--test-reporter=lcov');
    args.push(`--test-reporter-destination=${coverageOutputFile}`);
    // Spec reporter destination must be explicit if using multiple reporters
    args.push('--test-reporter-destination=stdout');
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

exit(result.status ?? 1);
