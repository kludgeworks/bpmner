# Third-Party License Closure

<!-- markdownlint-disable MD013 -->

This document records the third-party artifact closure introduced by sub-issues of
epic [#557](https://github.com/kludgeworks/bpmner/issues/557) (JVM-native ELK BPMN
layout). It is not auto-generated; update it when adding new transitive dependencies.

## ELK 0.11.0 — added in #557-2 ([#564](https://github.com/kludgeworks/bpmner/issues/564))

Direct dependency: `org.eclipse.elk:org.eclipse.elk.alg.layered:0.11.0`

| Artifact | Version | License |
| --- | --- | --- |
| `org.eclipse.elk:org.eclipse.elk.alg.layered` | 0.11.0 | EPL-2.0 |
| `org.eclipse.elk:org.eclipse.elk.alg.common` | 0.11.0 | EPL-2.0 |
| `org.eclipse.elk:org.eclipse.elk.core` | 0.11.0 | EPL-2.0 |
| `org.eclipse.elk:org.eclipse.elk.graph` | 0.11.0 | EPL-2.0 |
| `org.eclipse.emf:org.eclipse.emf.ecore` | 2.12.0 | EPL-1.0 |
| `org.eclipse.emf:org.eclipse.emf.common` | 2.12.0 | EPL-1.0 |
| `org.eclipse.emf:org.eclipse.emf.ecore.xmi` | 2.12.0 | EPL-1.0 |
| `com.google.guava:guava` | (resolved by Bazel) | Apache-2.0 |

**EPL-2.0:** <https://www.eclipse.org/legal/epl-2.0/>
**EPL-1.0:** <https://www.eclipse.org/legal/epl-v10.html>
**Apache-2.0:** <https://www.apache.org/licenses/LICENSE-2.0>

ELK is used as a pure JVM graph-layout library; no ELK Eclipse platform components
are bundled or distributed. The EMF runtime artifacts are transitive requirements of
the ELK graph model (EMF Ecore-based), not used directly by application code.
