# ADR-002: Retryable generation exception — kernel placement & feedback contract

**ADR-002:** `RetryableBpmnGenerationException` lives in the **`bpmn` kernel** so both `authoring`
and `contract` can throw it without crossing an internal boundary. It carries the kernel's sole
`com.embabel.agent.core.Retryable` import; implementing `Retryable` ensures the Embabel retry
policy classifies it for retry.

**ADR-002 (message-is-feedback):** The exception **message is the feedback** — it preserves the
violation count and one `- [code] message` line per fidelity issue (no structured payload). The
repair loop reads the message text directly; callers must not strip it.

Origin: epic #458. See `../architecture.md` §3.
