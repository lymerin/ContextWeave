# ContextWeave Code ↔ Doc Linked Workbench

## 1. Product definition

This page is not a generic code browser, online IDE, or document website.

It is a **code-aware engineering collaboration workspace** where code and structured engineering documentation are two synchronized views of the same engineering object.

The implementation must preserve this central interaction:

> Selecting a code anchor focuses the corresponding document Block; selecting a document Block focuses the corresponding code range. Issues, evidence, review state, and drift warnings are shared across both sides.

The HTML prototype in this directory is the authoritative interaction reference.

## 2. Non-negotiable layout

Desktop layout contains five layers:

1. **Repository/context sidebar**
   - Repository tree
   - Related engineering documents
   - Current symbol summary
   - Drift/review entry points

2. **Code pane**
   - Real repository file
   - Current branch and commit context
   - Highlighted code anchors
   - Issue markers
   - Ability to focus one linked range

3. **Code Anchor Rail**
   - Explicit visual mapping between code ranges and document Blocks
   - Selecting a node activates both sides
   - This rail must not be replaced by a generic divider

4. **Document pane**
   - Structured Block document, not plain Markdown
   - Each Block can bind to one or more code anchors
   - Active Block is visually emphasized
   - Document remains editable and collaborative

5. **Collapsible inspector**
   - Comments
   - Issues
   - Evidence
   - Relations
   - Versions
   - Must be hideable so the core comparison area is not permanently compressed

## 3. Required interaction contract

### 3.1 Bidirectional focus

Given link id `L`:

- Clicking a code line belonging to `L`:
  - activates the matching rail node;
  - activates the matching document Block;
  - scrolls the matching Block into view;
  - updates the inspector to the issue/evidence for `L`.

- Clicking a document Block belonging to `L`:
  - activates the matching rail node;
  - highlights the matching code range;
  - scrolls the matching code range into view;
  - updates the inspector.

- Clicking the rail node for `L`:
  - activates both sides;
  - scrolls both sides into view.

Only one link is primary-active at a time. Other links may retain low-emphasis indicators but must not look equally active.

### 3.2 Work modes

The page provides:

- **Linked comparison**: code + anchor rail + document
- **Code focus**: code uses the main width
- **Document focus**: document uses the main width
- **Drift review**: linked comparison opens the inspector and emphasizes conflicting anchors

These modes are not separate routes. They are views of the same selected engineering context.

### 3.3 Issue and evidence closure

An issue is not an isolated card. It must point to:

- document Block;
- code anchor;
- commit/branch context;
- rule or human review source;
- current resolution state.

Clicking any evidence item should focus or open the referenced object.

## 4. Domain concepts

The UI should be built around these concepts rather than around arbitrary pages:

```ts
type CodeAnchor = {
  id: string
  repositoryId: string
  branch: string
  commitSha: string
  filePath: string
  language: string
  symbolName?: string
  qualifiedSymbol?: string
  startLine: number
  endLine: number
  contentHash?: string
  status: 'VALID' | 'DRIFTED' | 'BROKEN'
}

type DocumentBlock = {
  id: string
  documentId: string
  type: string
  title?: string
  content: unknown
  version: number
}

type CodeDocumentLink = {
  id: string
  codeAnchorId: string
  blockId: string
  relationType:
    | 'IMPLEMENTS'
    | 'DESCRIBES'
    | 'TESTS'
    | 'EVIDENCE'
    | 'CONFLICTS_WITH'
}

type EngineeringIssue = {
  id: string
  title: string
  severity: 'LOW' | 'MEDIUM' | 'HIGH'
  status: 'OPEN' | 'ACCEPTED' | 'FALSE_POSITIVE' | 'RESOLVED'
  blockId?: string
  codeAnchorId?: string
  commitSha?: string
  sourceType: 'RULE' | 'HUMAN' | 'AI'
  sourceKey?: string
}
```

Exact naming may adapt to the current repository, but these relationships must remain explicit.

## 5. What must not happen

Do not convert the design into:

- a normal file tree plus a single code viewer;
- a document page with a code hyperlink;
- two unrelated panes placed side by side;
- a low-fidelity VS Code clone;
- a generic admin dashboard;
- a permanent three-column layout where the issue panel always steals width;
- a mockup with no working code ↔ Block synchronization;
- a full rewrite that discards existing document collaboration components.

Do not add Monaco, terminal, LSP, OpenVSCode Server, or Git client merely to make the project look larger.

## 6. Implementation priorities

### Slice 1 — faithful frontend interaction

Use mock data if necessary, but implement:

- exact linked layout;
- active link state;
- code-to-Block focus;
- Block-to-code focus;
- rail node focus;
- collapsible inspector;
- four work modes.

### Slice 2 — existing application integration

Replace mock data with the current repository/document APIs while retaining the interaction contract.

### Slice 3 — persistence

Persist:

- CodeAnchor;
- Block relation;
- issue/evidence relation;
- selected repository/branch/commit context.

### Slice 4 — collaboration

Reuse the existing document collaboration mechanism. Presence and editing must remain document/Block collaboration, not collaborative Java source editing.

### Slice 5 — drift review

Compare the saved anchor context with a newer commit and mark anchors/documents for re-review.

## 7. Visual acceptance criteria

At a desktop viewport around 1440–1600 px:

- code pane and document pane are both usable without horizontal page scrolling;
- the anchor rail is visible and clearly belongs to the mapping interaction;
- the inspector can be closed;
- active code and active Block use the same visual semantic;
- code and documentation remain readable at the same time;
- switching focus modes does not lose selected link state;
- the page resembles the reference screenshot and HTML more than the old code workbench.

Pixel-perfect identity is not required, but changing the information architecture or removing the bidirectional mapping is a failed implementation.
