import { spawnSync } from 'node:child_process';
import { env, argv, exit, execPath } from 'node:process';

/**
 * Wrapper for Node.js tests to enable coverage collection in Bazel.
 *
 * Usage: node test_wrapper.mjs <file1> <file2> ...
 */

// Find the .js file among arguments
const testFile = argv.slice(2).find(arg => arg.endsWith('.js'));

if (!testFile) {
    console.error('Error: No .js test file provided to test_wrapper.mjs');
    console.error('Arguments received:', argv.slice(2));
    exit(1);
}

const args = [];

// If Bazel is requesting coverage collection
if (env.COVERAGE_OUTPUT_FILE) {
    args.push('--experimental-test-coverage');
    args.push('--test-reporter=spec');
    args.push('--test-reporter-destination=stdout');
    args.push('--test-reporter=lcov');
    args.push(`--test-reporter-destination=${env.COVERAGE_OUTPUT_FILE}`);
} else {
    // Default reporter for non-coverage runs
    args.push('--test-reporter=spec');
}

args.push('--test');
args.push(testFile);

console.log(`Running test: ${execPath} ${args.join(' ')}`);

const result = spawnSync(execPath, args, {
    stdio: 'inherit',
    env: {
        ...env,
        BAZEL_TEST: '1',
    }
});

exit(result.status ?? 1);
