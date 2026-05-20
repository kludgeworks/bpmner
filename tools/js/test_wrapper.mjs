import { run } from 'node:test';
import { spec, lcov } from 'node:test/reporters';
import { createWriteStream } from 'node:fs';
import { env, argv, exitCode } from 'node:process';

/**
 * Idiomatic wrapper for Node.js tests in Bazel.
 *
 * This script uses the programmatic node:test API to run tests and
 * pipes coverage data to the path requested by Bazel (COVERAGE_OUTPUT_FILE).
 *
 * Usage: node test_wrapper.mjs <test_file1.js> <test_file2.js> ...
 */

const allArgs = argv.slice(2);
// Filter out .map files as Node.js test runner tries to execute them as tests
const testFiles = allArgs.filter(arg => !arg.endsWith('.map'));
const coverageOutputFile = env.COVERAGE_OUTPUT_FILE;

if (testFiles.length === 0) {
    console.error('Error: No test files provided to test_wrapper.mjs');
    console.error('Arguments received:', allArgs);
    process.exit(1);
}

console.log('Executing test files:', testFiles);

// Start the test runner
const stream = run({
    files: testFiles,
    coverage: !!coverageOutputFile,
});

// 1. Pipe human-readable results to stdout for Bazel logs
stream.compose(new spec()).pipe(process.stdout);

// 2. Pipe LCOV data to the path Bazel expects
if (coverageOutputFile) {
    console.log(`Coverage collection enabled. Writing to: ${coverageOutputFile}`);
    stream.compose(new lcov()).pipe(createWriteStream(coverageOutputFile));
}

// 3. Ensure failures result in a non-zero exit code
stream.on('test:fail', () => {
    process.exitCode = 1;
});
