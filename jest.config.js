/*
 * Copyright 2026 The Project Contributors
 * SPDX-License-Identifier: MIT
 */

/** @type {import('ts-jest').JestConfigWithTsJest} */
module.exports = {
	preset: "ts-jest",
	testEnvironment: "node",
	testMatch: ["**/test/**/*.test.ts"],
	collectCoverage: true,
	coverageReporters: ["lcov", "text"],
	coverageDirectory: ".qodana/code-coverage/js",
	moduleNameMapper: {
		"^@bpmner/(.*)$": "<rootDir>/$1/src",
	},
	transform: {
		"^.+\\.tsx?$": [
			"ts-jest",
			{
				tsconfig: "tsconfig.json",
			},
		],
	},
}
