# SonarCloud assessment failure investigation

Date: 2026-05-27

## Observed failure

The SonarCloud workflow currently reaches the scanner startup phase, confirms the
SonarCloud ALM binding, creates an analysis, and then fails while loading quality
profiles.

The repeated failure signature is:

```text
Failed to load the quality profiles of project 'kludgeworks_bpmner_backend':
An unexpected error occurred. Please try again later.
```

The same failure also occurs for `kludgeworks_bpmner_web`. Earlier runs showed
the same failure for `kludgeworks_bpmner_linter` before that project was retired
from the workflow.

This is not a coverage-generation failure. In the failing runs checked, `Run
Bazel coverage` completes successfully and the failure occurs in the individual
SonarScanner invocations.

## Timeline

- `2026-05-26T16:28:02Z` run `26461186852` passed for PR #262. It used
  SonarScanner `5.0.2.4997`; all three then-active projects loaded quality
  profiles and uploaded analysis reports successfully.
- `2026-05-26T16:39:33Z` run `26461775798` failed on `main`. It used the same
  SonarScanner `5.0.2.4997`, reached `Create analysis`, then failed at `Load
  quality profiles` for every project.
- Subsequent SonarCloud workflow runs on both PRs and `main` have continued to
  fail at the same point.
- PR #270 rerun `26518054617` on `2026-05-27T14:36:54Z` still fails at `Load
  quality profiles` for both backend and web.

## Local configuration facts

- `MODULE.bazel` uses `bazel_sonarqube` `1.0.6`.
- `bazel_sonarqube` `1.0.6` vendors SonarScanner CLI `5.0.2.4997`.
- The workflow passes `-Dsonar.host.url=https://sonarcloud.io`, so it targets
  the SonarQube Cloud EU instance.
- SonarCloud project keys in the workflow are:
  - `kludgeworks_bpmner_backend`
  - `kludgeworks_bpmner_web`
- The SonarQube API reports the current quality gate status as `OK` for
  `kludgeworks_bpmner_backend`, `kludgeworks_bpmner_web`, and the retired
  `kludgeworks_bpmner_linter`, which suggests the projects still exist and the
  last successful analyses remain readable.

## Current best explanation

This looks like a SonarCloud-side project/profile retrieval problem, not a
repository code problem.

The strongest evidence is that a run using the same scanner and workflow
successfully loaded quality profiles at `2026-05-26T16:36Z`, and the next main
run began failing at `2026-05-26T16:46Z` after analysis creation but before
source analysis. The failure is repeated across multiple SonarCloud projects.

The scanner version is also stale relative to current SonarQube Cloud
documentation. SonarScanner CLI 7.1 introduced SonarQube Cloud region support,
and the current CLI line is newer than the `5.0.2.4997` bundled by
`bazel_sonarqube` `1.0.6`. Because this workflow explicitly targets
`https://sonarcloud.io`, scanner age is not proven to be the direct cause, but it
is a realistic compatibility risk and should be removed from the equation.

## Recommended next steps

1. Run the SonarCloud workflow manually from this branch with `debug=true`.
   This adds SonarScanner `-X`, which the scanner explicitly requests after the
   failure. The debug log should reveal the failing quality-profile API request
   and HTTP response.
2. Check SonarCloud project administration for the backend and web projects:
   verify assigned quality profiles, profile inheritance, and organization
   defaults for Kotlin, JavaScript, TypeScript, XML, and any generated languages.
3. Upgrade or replace the scanner path so CI no longer depends on the
   `bazel_sonarqube`-bundled SonarScanner CLI `5.0.2.4997`.
4. If debug still reports only a generic server-side error, open a SonarSource
   support/community issue with one failing run ID and the project keys. The
   failure occurs after SonarCloud accepts the project binding and creates the
   analysis, so SonarSource will likely need server-side request logs.

## References

- SonarQube Cloud scanner CLI docs:
  https://docs.sonarsource.com/sonarqube-cloud/analyzing-source-code/scanners/sonarscanner-cli
- SonarQube Cloud region prerequisites:
  https://docs.sonarsource.com/sonarqube-cloud/getting-started/getting-started-in-us-region
- `bazel_sonarqube` module registry:
  https://registry.bazel.build/modules/bazel_sonarqube
