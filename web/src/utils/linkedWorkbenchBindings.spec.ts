import { describe, expect, it } from 'vitest';

import type { CodeBindingContextItem, CodeBindingQueryItem, GitRepositorySource } from '@/api/git';
import {
  bindingDisplayState,
  bindingDocumentChoices,
  buildBindingFixture,
  computeDocumentScopeFileLinkCounts,
  contextBindingToQueryItem,
  expandContextBindingsToQueryItems,
  selectDefaultBinding,
} from './linkedWorkbenchBindings';

describe('linkedWorkbenchBindings', () => {
  it('keeps file-specific bindings isolated while switching A to B to A', () => {
    const aBinding = binding('binding-a', 'document-a', 'src/A.java');
    const bBinding = binding('binding-b', 'document-b', 'src/B.java');

    const firstA = buildBindingFixture(input(source('src/A.java'), [aBinding]));
    const b = buildBindingFixture(input(source('src/B.java'), [bBinding]));
    const secondA = buildBindingFixture(input(source('src/A.java'), [aBinding]));

    expect(firstA.codeAnchors.map(item => item.filePath)).toEqual(['src/A.java']);
    expect(firstA.codeAnchors[0]).toMatchObject({
      anchorKind: 'FILE',
      startLine: null,
      endLine: null,
    });
    expect(b.codeAnchors.map(item => item.filePath)).toEqual(['src/B.java']);
    expect(secondA).toEqual(firstA);
    expect(b.links.map(item => item.documentId)).toEqual(['document-b']);
  });

  it('keeps multiple file-level proposals for one document as separate fixtures', () => {
    const a = binding('binding-a', 'document-shared', 'src/A.java');
    const b = binding('binding-b', 'document-shared', 'src/B.java');

    const aFixture = buildBindingFixture(
      input(source('src/A.java'), [a]),
    );
    const bFixture = buildBindingFixture(
      input(source('src/B.java'), [b]),
    );

    expect(aFixture.links[0].id).toBe('binding-link-binding-a');
    expect(bFixture.links[0].id).toBe('binding-link-binding-b');
    expect(aFixture.links[0].blockId).toBeNull();
    expect(bFixture.links[0].blockId).toBeNull();
  });

  it('preserves FILE, RANGE and SYMBOL anchors without merging bindings', () => {
    const bindings = [
      binding('file', 'document-a', 'src/A.java'),
      { ...binding('range', 'document-a', 'src/A.java'), anchorKind: 'RANGE' as const, startLine: 2, endLine: 4, blockId: 'block-a', blockExists: true },
      { ...binding('symbol', 'document-a', 'src/A.java'), anchorKind: 'SYMBOL' as const, symbolKey: 'JAVA:A.run', startLine: 6, endLine: 8, blockId: 'block-b', blockExists: true },
    ];

    const fixture = buildBindingFixture(input(source('src/A.java'), bindings, 'document-a'));

    expect(fixture.links).toHaveLength(3);
    expect(fixture.codeAnchors.map(item => item.anchorKind)).toEqual(['SYMBOL', 'RANGE', 'FILE']);
    expect(fixture.codeAnchors[0]).toMatchObject({
      startLine: 6,
      endLine: 8,
    });
  });

  it('prefers exact revision block bindings and keeps legacy FILE selectable', () => {
    const legacy = { ...binding('legacy', 'document-a', 'src/A.java'), revision: null };
    const precise = {
      ...binding('precise', 'document-a', 'src/A.java'),
      anchorKind: 'SYMBOL' as const,
      blockId: 'block-a',
      symbolKey: 'JAVA:A.run',
      startLine: 2,
      endLine: 4,
      blockExists: true,
    };

    expect(selectDefaultBinding([legacy, precise], 'revision')).toEqual(precise);
    expect(buildBindingFixture(input(source('src/A.java'), [legacy, precise], 'document-a')).links)
      .toHaveLength(2);
  });

  it('restores a valid binding id, then block id, before using stable defaults', () => {
    const first = { ...binding('a', 'document-a', 'src/A.java'), blockId: 'block-a', blockExists: true };
    const second = { ...binding('b', 'document-a', 'src/A.java'), blockId: 'block-b', blockExists: true };

    expect(selectDefaultBinding([first, second], 'revision', 'b')?.bindingId).toBe('b');
    expect(selectDefaultBinding([first, second], 'revision', 'missing', 'block-a')?.bindingId)
      .toBe('a');
  });

  it('orders equal-role bindings by precision, line and stable id while keeping FILE last', () => {
    const bindings = [
      binding('file', 'document-a', 'src/A.java'),
      {
        ...binding('late', 'document-a', 'src/A.java'),
        anchorKind: 'RANGE' as const,
        blockId: 'block-late',
        startLine: 20,
        endLine: 22,
        blockExists: true,
      },
      {
        ...binding('same-b', 'document-a', 'src/A.java'),
        anchorKind: 'RANGE' as const,
        blockId: 'block-b',
        startLine: 2,
        endLine: 4,
        blockExists: true,
      },
      {
        ...binding('same-a', 'document-a', 'src/A.java'),
        anchorKind: 'RANGE' as const,
        blockId: 'block-a',
        startLine: 2,
        endLine: 4,
        blockExists: true,
      },
    ];
    const fixture = buildBindingFixture({
      ...input(source('src/A.java'), bindings, 'document-a'),
      blockSortOrders: new Map([
        ['block-a', 2],
        ['block-b', 1],
        ['block-late', 3],
      ]),
    });

    expect(fixture.links.map(item => item.bindingId))
      .toEqual(['same-a', 'same-b', 'late', 'file']);
    expect(selectDefaultBinding(
      bindings,
      'revision',
      'late',
      null,
      'src/A.java',
      new Map([['block-late', 3]]),
    )?.bindingId).toBe('late');
  });

  it('orders PRIMARY before SUPPORTING and preserves role metadata in the fixture', () => {
    const supportingSecond = {
      ...binding('supporting-2', 'document-a', 'src/A.java'),
      bindingRole: 'SUPPORTING' as const,
      bindingOrdinal: 2,
      anchorKind: 'SYMBOL' as const,
      symbolKey: 'JAVA:A.helper',
      startLine: 2,
      endLine: 4,
      blockExists: true,
    };
    const primary = {
      ...binding('primary', 'document-a', 'src/A.java'),
      bindingRole: 'PRIMARY' as const,
      bindingOrdinal: 1,
    };
    const supportingThird = {
      ...binding('supporting-3', 'document-a', 'src/A.java'),
      bindingRole: 'SUPPORTING' as const,
      bindingOrdinal: 3,
      anchorKind: 'RANGE' as const,
      startLine: 10,
      endLine: 12,
      blockExists: true,
    };

    const fixture = buildBindingFixture(input(
      source('src/A.java'),
      [supportingThird, supportingSecond, primary],
      'document-a',
    ));

    expect(fixture.links.map(item => item.bindingId)).toEqual([
      'primary', 'supporting-2', 'supporting-3',
    ]);
    expect(fixture.links.map(item => [item.bindingRole, item.bindingOrdinal])).toEqual([
      ['PRIMARY', 1], ['SUPPORTING', 2], ['SUPPORTING', 3],
    ]);
  });

  it('treats legacy bindings without role fields as PRIMARY ordinal one', () => {
    const fixture = buildBindingFixture(input(
      source('src/A.java'),
      [binding('legacy', 'document-a', 'src/A.java')],
    ));
    expect(fixture.links[0]).toMatchObject({
      bindingRole: 'PRIMARY',
      bindingOrdinal: 1,
    });
  });

  it('deduplicates related documents without merging different document ids', () => {
    const choices = bindingDocumentChoices([
      binding('binding-a1', 'document-a', 'src/A.java'),
      binding('binding-a2', 'document-a', 'src/A.java'),
      binding('binding-b', 'document-b', 'src/A.java'),
    ], [
      { id: 'document-a', title: '文档 A', depth: 0 },
      { id: 'document-b', title: '文档 B', depth: 1 },
    ]);

    expect(choices.map(item => item.id)).toEqual(['document-a', 'document-b']);
    expect(choices.map(item => item.title)).toEqual(['文档 A', '文档 B']);
  });

  it('filters block bindings by blockExists and activeDocumentId', () => {
    const valid = {
      ...binding('valid', 'document-a', 'src/A.java'),
      anchorKind: 'RANGE' as const,
      blockId: 'block-a',
      startLine: 2,
      endLine: 4,
      blockExists: true,
    };
    const deadBlock = {
      ...binding('dead-block', 'document-a', 'src/A.java'),
      anchorKind: 'RANGE' as const,
      blockId: 'block-dead',
      startLine: 5,
      endLine: 7,
      blockExists: false,
    };
    const otherDocument = {
      ...binding('other-doc', 'document-b', 'src/A.java'),
      anchorKind: 'RANGE' as const,
      blockId: 'block-b',
      startLine: 8,
      endLine: 10,
      blockExists: true,
    };
    const fixture = buildBindingFixture({
      ...input(source('src/A.java'), [valid, deadBlock, otherDocument], 'document-a'),
    });

    expect(fixture.links.map(item => item.bindingId)).toEqual(['valid']);
    expect(fixture.links[0].bindingDisplayState).toBe('precise');
  });

  it('labels file-level and symbol-without-range bindings as weak', () => {
    const legacy = { ...binding('legacy', 'document-a', 'src/A.java'), revision: null };
    const symbol = {
      ...binding('symbol', 'document-a', 'src/A.java'),
      anchorKind: 'SYMBOL' as const,
      symbolKey: 'JAVA:A.run',
      blockId: 'block-a',
      blockExists: true,
    };
    const fixture = buildBindingFixture({
      ...input(source('src/A.java'), [legacy, symbol], 'document-a'),
    });

    expect(fixture.links).toHaveLength(2);
    expect(fixture.links.every(item => item.bindingDisplayState === 'weak')).toBe(true);
    expect(fixture.codeAnchors.every(item => item.startLine === null)).toBe(true);
  });

  it('shows block bindings immediately when blockExists is true, without waiting for block loading', () => {
    const precise = {
      ...binding('precise', 'document-a', 'src/A.java'),
      anchorKind: 'RANGE' as const,
      blockId: 'block-a',
      startLine: 2,
      endLine: 4,
      blockExists: true,
    };
    const fixture = buildBindingFixture(input(source('src/A.java'), [precise], 'document-a'));

    expect(fixture.links).toHaveLength(1);
    expect(fixture.links[0].bindingDisplayState).toBe('precise');
  });

  it('bindingDisplayState returns precise for resolved block bindings, weak otherwise', () => {
    const precise = {
      ...binding('precise', 'document-a', 'src/A.java'),
      anchorKind: 'RANGE' as const,
      blockId: 'block-a',
      startLine: 2,
      endLine: 4,
    };
    const weakBlock = {
      ...binding('weak-block', 'document-a', 'src/A.java'),
      anchorKind: 'SYMBOL' as const,
      symbolKey: 'JAVA:A.run',
      blockId: 'block-a',
    };
    const fileLevel = binding('file', 'document-a', 'src/A.java');

    expect(bindingDisplayState(precise)).toBe('precise');
    expect(bindingDisplayState(weakBlock)).toBe('weak');
    expect(bindingDisplayState(fileLevel)).toBe('weak');
  });

  it('uses matchedFilePath for code anchor and link file paths', () => {
    const wildcard = {
      ...binding('wildcard', 'document-a', 'src/main/App.java'),
      pathPattern: 'src/**',
      matchedFilePath: 'src/main/App.java',
    };
    const fixture = buildBindingFixture(input(source('src/main/App.java'), [wildcard]));

    expect(fixture.codeAnchors[0].filePath).toBe('src/main/App.java');
    expect(fixture.links[0].filePath).toBe('src/main/App.java');
  });

  it('contextBindingToQueryItem picks current file path when available', () => {
    const contextItem: CodeBindingContextItem = {
      ...binding('ctx', 'document-a', 'src/main/Service.java'),
      matchingFilePaths: ['src/main/Service.java', 'src/test/ServiceTest.java'],
    };
    const result = contextBindingToQueryItem(contextItem, 'src/test/ServiceTest.java');
    expect(result.matchedFilePath).toBe('src/test/ServiceTest.java');
  });

  it('contextBindingToQueryItem falls back to first matching path', () => {
    const contextItem: CodeBindingContextItem = {
      ...binding('ctx', 'document-a', 'src/main/Service.java'),
      matchingFilePaths: ['src/main/Service.java', 'src/test/ServiceTest.java'],
    };
    const result = contextBindingToQueryItem(contextItem, null);
    expect(result.matchedFilePath).toBe('src/main/Service.java');
  });

  describe('computeDocumentScopeFileLinkCounts', () => {
    it('expands matchingFilePaths into per-file counts', () => {
      const items = [
        contextItem('ctx-1', 'document-a', ['src/A.java', 'src/B.java']),
        contextItem('ctx-2', 'document-a', ['src/B.java', 'src/C.java']),
      ];
      const counts = computeDocumentScopeFileLinkCounts(items, 'repository');
      expect(counts).toEqual({
        'src/A.java': 1,
        'src/B.java': 2,
        'src/C.java': 1,
      });
    });

    it('returns empty record for empty items', () => {
      expect(computeDocumentScopeFileLinkCounts([], 'repository')).toEqual({});
    });

    it('filters out items from different repositories', () => {
      const items = [
        contextItem('ctx-1', 'document-a', ['src/A.java']),
        { ...contextItem('ctx-2', 'document-a', ['src/B.java']), repositoryId: 'other-repo' },
      ];
      const counts = computeDocumentScopeFileLinkCounts(items, 'repository');
      expect(counts).toEqual({ 'src/A.java': 1 });
    });

    it('handles a single wildcard binding resolving to many files', () => {
      const items = [
        contextItem('wildcard', 'document-a', [
          'src/main/A.java',
          'src/main/B.java',
          'src/main/C.java',
        ]),
      ];
      const counts = computeDocumentScopeFileLinkCounts(items, 'repository');
      expect(counts).toEqual({
        'src/main/A.java': 1,
        'src/main/B.java': 1,
        'src/main/C.java': 1,
      });
    });
  });

  describe('expandContextBindingsToQueryItems', () => {
    it('returns a single item when matchingFilePaths has one entry', () => {
      const items = [contextItem('ctx-1', 'document-a', ['src/A.java'])];
      const result = expandContextBindingsToQueryItems(items, null);
      expect(result).toHaveLength(1);
      expect(result[0].bindingId).toBe('ctx-1@src/A.java');
      expect(result[0].matchedFilePath).toBe('src/A.java');
    });

    it('expands one context item into one query item per matching file', () => {
      const items = [contextItem('wildcard', 'document-a', [
        'src/A.java',
        'src/B.java',
        'src/C.java',
      ])];
      const result = expandContextBindingsToQueryItems(items, null);
      expect(result).toHaveLength(3);
      expect(result.map(r => r.matchedFilePath)).toEqual([
        'src/A.java',
        'src/B.java',
        'src/C.java',
      ]);
      expect(result.map(r => r.bindingId)).toEqual([
        'wildcard@src/A.java',
        'wildcard@src/B.java',
        'wildcard@src/C.java',
      ]);
      // All expanded items share the same document.
      expect(result.every(r => r.documentId === 'document-a')).toBe(true);
    });

    it('handles multiple context items each with multiple paths', () => {
      const items = [
        contextItem('ctx-1', 'document-a', ['src/A.java', 'src/B.java']),
        contextItem('ctx-2', 'document-b', ['src/C.java']),
      ];
      const result = expandContextBindingsToQueryItems(items, null);
      expect(result).toHaveLength(3);
      expect(result.map(r => r.matchedFilePath).sort()).toEqual([
        'src/A.java',
        'src/B.java',
        'src/C.java',
      ]);
    });

    it('handles empty matchingFilePaths by falling back to pathPattern', () => {
      const item: CodeBindingContextItem = {
        ...contextItem('empty-paths', 'document-a', []),
        matchingFilePaths: [],
      };
      const result = expandContextBindingsToQueryItems([item], null);
      expect(result).toHaveLength(1);
      expect(result[0].matchedFilePath).toBe('src/**');
    });
  });
});

function contextItem(
  bindingId: string,
  documentId: string,
  matchingFilePaths: string[],
): CodeBindingContextItem {
  return {
    bindingId,
    workspaceId: 'workspace',
    repositoryId: 'repository',
    revision: 'revision',
    anchorKind: 'FILE',
    symbolKey: null,
    startLine: null,
    endLine: null,
    documentId,
    blockId: null,
    targetKey: 'DOCUMENT',
    pathPattern: matchingFilePaths[0] || 'src/**',
    documentTitle: documentId,
    matchedFilePath: matchingFilePaths[0] || 'src/**',
    matchingFilePaths,
    blockExists: false,
  };
}

function binding(
  bindingId: string,
  documentId: string,
  pathPattern: string,
): CodeBindingQueryItem {
  return {
    bindingId,
    workspaceId: 'workspace',
    repositoryId: 'repository',
    revision: 'revision',
    anchorKind: 'FILE',
    symbolKey: null,
    startLine: null,
    endLine: null,
    documentId,
    blockId: null,
    targetKey: 'DOCUMENT',
    pathPattern,
    documentTitle: documentId,
    matchedFilePath: pathPattern,
    blockExists: false,
  };
}

function source(path: string): GitRepositorySource {
  return {
    repositoryId: 'repository',
    commitSha: 'revision',
    path,
    blobSha: `blob-${path}`,
    sizeBytes: 10,
    language: 'Java',
    readable: true,
    content: 'class Example {}',
    symbols: [],
  };
}

function input(
  sourceValue: GitRepositorySource,
  bindings: CodeBindingQueryItem[],
  activeDocumentId?: string | null,
) {
  return {
    repositoryId: 'repository',
    branch: 'main',
    commitSha: 'revision',
    source: sourceValue,
    bindings,
    activeDocumentId: activeDocumentId ?? null,
  };
}
