// Copyright 2026 The Project Contributors
// SPDX-License-Identifier: MIT

package dev.groknull.bpmner.tools.spotbugs

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class SpotBugsSonarConverterTest {
    @Test
    fun `converts valid Kotlin source findings to Sonar generic issue data`() {
        val json = SpotBugsSonarConverter.convert(
            """
            <BugCollection>
              <BugInstance type="NP_NULL_ON_SOME_PATH" priority="1" category="CORRECTNESS">
                <LongMessage>Possible null dereference in mapper</LongMessage>
                <SourceLine sourcepath="dev/groknull/bpmner/Foo.kt" start="42" end="43"/>
              </BugInstance>
              <BugPattern type="NP_NULL_ON_SOME_PATH">
                <ShortDescription>Null pointer dereference</ShortDescription>
                <Details>Dereference can happen on a null path.</Details>
              </BugPattern>
            </BugCollection>
            """.trimIndent(),
        )

        assertContains(json, """"rules"""")
        assertContains(json, """"issues"""")
        assertContains(json, """"engineId": "spotbugs"""")
        assertContains(json, """"ruleId": "NP_NULL_ON_SOME_PATH"""")
        assertContains(json, """"filePath": "src/main/kotlin/dev/groknull/bpmner/Foo.kt"""")
        assertContains(json, """"type": "BUG"""")
        assertContains(json, """"softwareQuality": "RELIABILITY"""")
        assertContains(json, """"severity": "HIGH"""")
        assertContains(json, """"startLine": 42""")
        assertContains(json, """"endLine": 43""")
    }

    @Test
    fun `prefers primary source line when SpotBugs reports several source locations`() {
        val json = SpotBugsSonarConverter.convert(
            """
            <BugCollection>
              <BugInstance type="BC_BAD_CAST_TO_ABSTRACT_COLLECTION" priority="2" category="STYLE">
                <LongMessage>Questionable cast</LongMessage>
                <SourceLine sourcepath="dev/groknull/bpmner/Foo.kt" start="1"/>
                <SourceLine sourcepath="dev/groknull/bpmner/Foo.kt" primary="true" start="99"/>
              </BugInstance>
            </BugCollection>
            """.trimIndent(),
        )

        assertContains(json, """"filePath": "src/main/kotlin/dev/groknull/bpmner/Foo.kt"""")
        assertContains(json, """"startLine": 99""")
    }

    @Test
    fun `deduplicates rules while preserving multiple issues`() {
        val json = SpotBugsSonarConverter.convert(
            """
            <BugCollection>
              <BugInstance type="DLS_DEAD_LOCAL_STORE" priority="2" category="STYLE">
                <LongMessage>Dead store one</LongMessage>
                <SourceLine sourcepath="src/main/kotlin/A.kt" start="10"/>
              </BugInstance>
              <BugInstance type="DLS_DEAD_LOCAL_STORE" priority="3" category="STYLE">
                <LongMessage>Dead store two</LongMessage>
                <SourceLine sourcepath="src/main/kotlin/B.kt" start="20"/>
              </BugInstance>
            </BugCollection>
            """.trimIndent(),
        )

        assertEquals(1, """"id": "DLS_DEAD_LOCAL_STORE"""".toRegex().findAll(json).count())
        assertEquals(2, """"ruleId": "DLS_DEAD_LOCAL_STORE"""".toRegex().findAll(json).count())
    }

    @Test
    fun `skips findings outside analyzed production Kotlin sources`() {
        val json = SpotBugsSonarConverter.convert(
            """
            <BugCollection>
              <BugInstance type="TEST_RULE" priority="1" category="CORRECTNESS">
                <LongMessage>Test source issue</LongMessage>
                <SourceLine sourcepath="src/test/kotlin/dev/groknull/bpmner/FooTest.kt" start="10"/>
              </BugInstance>
              <BugInstance type="NO_LINE" priority="1" category="CORRECTNESS">
                <LongMessage>No source line</LongMessage>
                <SourceLine sourcepath="src/main/kotlin/dev/groknull/bpmner/Foo.kt"/>
              </BugInstance>
            </BugCollection>
            """.trimIndent(),
        )

        assertFalse(json.contains("TEST_RULE"))
        assertFalse(json.contains("NO_LINE"))
        assertContains(
            json,
            """"rules": [
  ]""",
        )
        assertContains(
            json,
            """"issues": [
  ]""",
        )
    }

    @Test
    fun `maps security findings to vulnerability issues`() {
        val json = SpotBugsSonarConverter.convert(
            """
            <BugCollection>
              <BugInstance type="SQL_INJECTION" priority="2" category="SECURITY">
                <ShortMessage>SQL injection risk</ShortMessage>
                <SourceLine sourcepath="src/main/kotlin/dev/groknull/bpmner/Foo.kt" start="12"/>
              </BugInstance>
            </BugCollection>
            """.trimIndent(),
        )

        assertContains(json, """"type": "VULNERABILITY"""")
        assertContains(json, """"softwareQuality": "SECURITY"""")
        assertContains(json, """"severity": "MEDIUM"""")
    }
}
