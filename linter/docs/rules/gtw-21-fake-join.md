# gtw-21-fake-join

- Severity: error
- Purpose: Ensure that all converging flows pass through an explicit gateway rather than arriving directly at a task, which makes merge semantics implicit and engine-dependent.
- Trigger: A task element (UserTask, ServiceTask, etc.) has two or more incoming sequence flows.
