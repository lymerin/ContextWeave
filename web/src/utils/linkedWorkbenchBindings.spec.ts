import { describe, expect, it } from 'vitest';

import type { CodeBindingQueryItem, GitRepositorySource } from '@/api/git';
import {
  bindingDocumentChoices,
  buildBindingFixture,
  classifyBinding,
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
      { ...binding('range', 'document-a', 'src/A.java'), anchorKind: 'RANGE' as const, startLine: 2, endLine: 4, blockId: 'block-a' },
      { ...binding('symbol', 'document-a', 'src/A.java'), anchorKind: 'SYMBOL' as const, symbolKey: 'JAVA:A.run', startLine: 6, endLine: 8, blockId: 'block-b' },
    ];

    const fixture = buildBindingFixture(input(source('src/A.java'), bindings));

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
    };

    expect(selectDefaultBinding([legacy, precise], 'revision')).toEqual(precise);
    expect(buildBindingFixture(input(source('src/A.java'), [legacy, precise])).links)
      .toHaveLength(2);
  });

  it('restores a valid binding id, then block id, before using stable defaults', () => {
    const first = { ...binding('a', 'document-a', 'src/A.java'), blockId: 'block-a' };
    const second = { ...binding('b', 'document-a', 'src/A.java'), blockId: 'block-b' };

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
      },
      {
        ...binding('same-b', 'document-a', 'src/A.java'),
        anchorKind: 'RANGE' as const,
        blockId: 'block-b',
        startLine: 2,
        endLine: 4,
      },
      {
        ...binding('same-a', 'document-a', 'src/A.java'),
        anchorKind: 'RANGE' as const,
        blockId: 'block-a',
        startLine: 2,
        endLine: 4,
      },
    ];
    const fixture = buildBindingFixture({
      ...input(source('src/A.java'), bindings),
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
    };

    const fixture = buildBindingFixture(input(
      source('src/A.java'),
      [supportingThird, supportingSecond, primary],
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
  it('filters identity, repository, revision, path and missing Block mismatches', () => {
    const valid = {
      ...binding('valid', 'document-a', 'src/A.java'),
      anchorKind: 'RANGE' as const,
      blockId: 'block-a',
      startLine: 2,
      endLine: 4,
    };
    const invalid = [
      { ...valid, bindingId: '' },
      { ...valid, bindingId: 'wrong-repository', repositoryId: 'other' },
      { ...valid, bindingId: 'wrong-revision', revision: 'old-revision' },
      { ...valid, bindingId: 'wrong-path', pathPattern: 'src/B.java' },
      { ...valid, bindingId: 'missing-block', blockId: 'block-missing' },
    ];
    const fixture = buildBindingFixture({
      ...input(source('src/A.java'), [valid, ...invalid]),
      loadedDocumentId: 'document-a',
      loadedBlockIds: new Set(['block-a']),
    });

    expect(fixture.links.map(item => item.bindingId)).toEqual(['valid']);
    expect(fixture.links[0].bindingDisplayState).toBe('precise');
  });

  it('keeps unresolved Block targets loading until the document has loaded', () => {
    const precise = {
      ...binding('precise', 'document-a', 'src/A.java'),
      anchorKind: 'RANGE' as const,
      blockId: 'block-a',
      startLine: 2,
      endLine: 4,
    };
    const loadingInput = {
      repositoryId: 'repository',
      branch: 'main',
      commitSha: 'revision',
      source: source('src/A.java'),
      bindings: [precise],
    };

    expect(classifyBinding(precise, loadingInput)).toBe('loading');
    expect(buildBindingFixture(loadingInput).links).toEqual([]);
  });

  it('labels legacy file targets and symbols without ranges as weak', () => {
    const legacy = { ...binding('legacy', 'document-a', 'src/A.java'), revision: null };
    const symbol = {
      ...binding('symbol', 'document-a', 'src/A.java'),
      anchorKind: 'SYMBOL' as const,
      symbolKey: 'JAVA:A.run',
      blockId: 'block-a',
    };
    const fixture = buildBindingFixture({
      ...input(source('src/A.java'), [legacy, symbol]),
      loadedDocumentId: 'document-a',
      loadedBlockIds: new Set(['block-a']),
    });

    expect(fixture.links).toHaveLength(2);
    expect(fixture.links.every(item => item.bindingDisplayState === 'weak')).toBe(true);
    expect(fixture.codeAnchors.every(item => item.startLine === null)).toBe(true);
  });
});

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
) {
  return {
    repositoryId: 'repository',
    branch: 'main',
    commitSha: 'revision',
    source: sourceValue,
    bindings,
    loadedDocumentId: bindings.find(item => item.blockId)?.documentId,
    loadedBlockIds: new Set(bindings.flatMap(item => item.blockId ? [item.blockId] : [])),
  };
}
