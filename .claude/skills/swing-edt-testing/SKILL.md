---
name: swing-edt-testing
description: Strict rules for writing or reviewing Java Swing/UI tests, especially tests involving EDT, invokeLater/invokeAndWait, async callbacks, temporary files, panel disposal, or flaky CI behavior. Use before creating, modifying, or reviewing Swing tests or fixing EDT-related flakes.
---

# Swing EDT Testing

Use this skill for every Java Swing/UI test change in this project. The goal is deterministic tests that do not leak queued EDT work, background work, native resources, or temp-file access past the end of a test.

## Non-negotiable rules

### Component lifecycle

- Create Swing components on the EDT.
  - Prefer a local helper like `callOnEdt(() -> new Panel(...))`.
  - Do not construct panels directly on the test thread when constructors build UI, add listeners, start refreshes, or read settings used by UI state.
- Dispose/remove Swing components on the EDT.
  - Prefer `runOnEdt(subject::removeNotify)` or `SwingUtilities.invokeAndWait(subject::removeNotify)`.
  - Do not call `removeNotify()`, `dispose()`, or equivalent cleanup from the test thread except in a test that is explicitly modeling a blocked-EDT race and cannot express the state transition otherwise.
- Always cleanup in `finally`.
  - Release latches first when needed to unblock worker threads.
  - Then remove/dispose the component on the EDT.
  - Flush the EDT queue before the test exits if callbacks may have been queued.

### EDT access

- All Swing reads and writes must happen on the EDT.
  - Button clicks, combo-box selection, label text reads, reflected UI-field reads, model inspection, and component visibility checks all count as Swing access.
  - Use `callOnEdt` for reads and `runOnEdt` for writes/actions.
- Do not mix direct test-thread access with EDT access in the same test. It hides races.
- If a helper touches Swing state, either require callers to invoke it on the EDT or have the helper marshal to the EDT itself. Be consistent.

### Async and queued callback control

- Every async operation started by a test must have a deterministic completion path.
  - Prefer `CountDownLatch`, `CompletableFuture`, thread references, and explicit release latches.
  - Join known worker threads before assertions or teardown when the test depends on them finishing.
- After completing background work that queues EDT callbacks, flush the EDT with `SwingUtilities.invokeAndWait(() -> {})` before asserting final UI state or letting `@TempDir` cleanup run.
- Never rely on arbitrary sleeps to prove callbacks did or did not run. Poll with a deadline only when no deterministic signal exists, and keep the poll tied to a concrete state.
- Avoid leaving virtual threads, timers, or executor tasks able to touch test temp files after the test returns.
- Do not use an available provider/component that starts automatic catalog refresh, preview, download, or persistence work in a test that is not specifically about that behavior. Prefer an empty registry, disabled provider, unavailable provider, a fresh cached catalog entry that prevents automatic refresh, or a provider with explicit latches so the test owns every background write.

### TempDir and Windows CI

- Treat Windows as strict about open handles and queued file access.
- Do not let EDT callbacks or background refreshes read/write `@TempDir` settings files after test cleanup begins.
- Before a test exits:
  - cancel/remove the panel on the EDT,
  - release any blocked provider/model/catalog workers,
  - join workers when possible,
  - flush the EDT queue.
- If a test intentionally makes settings reads/writes fail, reset the failure flag before cleanup so panel disposal and final EDT flushes cannot fail for the wrong reason.
- For tests using `@TempDir`, treat component construction as potentially asynchronous. If the constructor can auto-refresh catalogs or persist default selections, either disable that path for unrelated tests or wait/join/flush it before returning. A passing assertion is not enough; the temp directory must be quiet before JUnit cleanup.
- For provider/settings helper-copy tests that only inspect text or visibility, pre-seed provider catalogs or use unavailable/disabled providers so constructors do not start automatic catalog refreshes. If model-management services are needed only to satisfy constructor dependencies, pass explicit services in try-with-resources, remove the panel on the EDT in `finally`, then close services before the test exits instead of relying on asynchronous owned-service cleanup.

### Blocked-EDT tests

- Blocking the EDT is allowed only to verify ordering/race behavior.
- Never call `invokeAndWait` while the EDT is intentionally blocked.
- In blocked-EDT tests, carefully separate:
  - work queued before the block,
  - state changes done while blocked,
  - work queued after release.
- If direct off-EDT lifecycle mutation is unavoidable to model a stale queued callback, keep it isolated to that test and do not copy that pattern elsewhere.

## Production-code expectations for async UI callbacks

When reviewing or changing Swing production code that queues callbacks from background work:

- Check stale/current request IDs before expensive work, before persistence, and again on the EDT before applying UI state.
- Check `removed`/disposed state before queueing and inside the queued EDT callback.
- Catch expected persistence/settings failures inside queued EDT callbacks and report them through UI status instead of letting uncaught exceptions escape the EDT.
- Do not hold refresh/cancellation locks while doing file I/O unless there is a proven need. Prefer conditional persistence guarded by request IDs.
- Do not let stale async completion overwrite newer provider/model/voice/token UI state.

## Preferred test helper pattern

```java
private void runOnEdt(ThrowingAction action) throws Exception {
    callOnEdt(() -> {
        action.run();
        return null;
    });
}

private <T> T callOnEdt(Callable<T> action) throws Exception {
    if (SwingUtilities.isEventDispatchThread()) {
        return action.call();
    }
    var result = new AtomicReference<T>();
    var error = new AtomicReference<Throwable>();
    SwingUtilities.invokeAndWait(() -> {
        try {
            result.set(action.call());
        } catch (Throwable t) {
            error.set(t);
        }
    });
    if (error.get() instanceof Exception e) {
        throw e;
    }
    if (error.get() instanceof Error e) {
        throw e;
    }
    if (error.get() != null) {
        throw new AssertionError(error.get());
    }
    return result.get();
}

@FunctionalInterface
private interface ThrowingAction {
    void run() throws Exception;
}
```

Use the helper consistently rather than open-coding `invokeAndWait` everywhere. If an existing test class already has equivalent helpers, use those.

## Review checklist

Before considering a Swing/UI test done, verify:

- [ ] Swing component construction happens on the EDT.
- [ ] All Swing reads/writes happen on the EDT.
- [ ] Cleanup runs in `finally` and removes/disposes on the EDT.
- [ ] Background operations are released and joined or otherwise proven complete.
- [ ] The EDT queue is flushed after async completion/removal when needed.
- [ ] No arbitrary sleep is used as synchronization.
- [ ] No queued callback can access `@TempDir` files after cleanup starts.
- [ ] Stale callbacks cannot overwrite newer UI state.
- [ ] Failure-injection flags are reset before cleanup.
- [ ] The focused test was repeated locally when fixing a flake.
