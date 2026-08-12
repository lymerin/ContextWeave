# DevCollab Frontend Design Authority

## Authoritative Design Sources

Before changing the code/document workbench, read:

- `docs/design/code-doc-linked-workbench/DESIGN_SPEC.md`
- `web/src/views/CodeWorkbenchView.vue`
- `web/src/components/linked-workbench/`

The current Vue implementation is the visual authority. The design spec is the interaction contract.

## Required Workflow

1. Inspect the existing frontend architecture and identify reusable components.
2. Run the existing application and inspect the current workbench in a browser.
3. Capture the current state before and after changes at the same viewport.
4. Explain the minimum component/state changes required.
5. Implement one vertical slice at a time.
6. Compare the result against the current implementation style and interaction contract before declaring completion.
7. Run existing lint, type-check, unit tests, and browser tests.

## Non-Negotiable Behavior

- Code ranges and document Blocks have explicit bidirectional links.
- Clicking either side focuses and scrolls the other side.
- The center Code Anchor Rail remains visible in linked-comparison mode.
- The issue/evidence inspector is collapsible.
- Linked comparison, code focus, document focus, and drift review share the same selected context.
- Existing structured Block editing and document collaboration must be preserved.

## Forbidden Shortcuts

- Do not replace the product with two static panes.
- Do not redesign the information architecture without explicit approval.
- Do not introduce Monaco, terminal, LSP, or OpenVSCode Server for this task.
- Do not claim completion based only on compilation; visually inspect the running result.
- Do not modify backend architecture unless required by the approved vertical slice.

## Completion Evidence

A completion report must include:

- files changed;
- commands run;
- tests passed;
- screenshots produced;
- which acceptance criteria are satisfied;
- any remaining mismatches.
