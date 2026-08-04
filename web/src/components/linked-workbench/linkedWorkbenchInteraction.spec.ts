import { mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { describe, expect, it } from 'vitest';
import CodeAnchorRail from './CodeAnchorRail.vue';
import LinkedCodePane from './LinkedCodePane.vue';
import LinkedWorkbenchContent from './LinkedWorkbenchContent.vue';
import { focusPlan, linkIdForBlock } from '@/utils/linkedWorkbenchInteraction';

const anchors = [{ id: 'a1', repositoryId: 'r', branch: 'main', commitSha: 'abc', filePath: 'A.java', language: 'Java', startLine: 2, endLine: 3, status: 'VALID' as const }];
const links = [{
  id: 'l1',
  codeAnchorId: 'a1',
  documentId: 'd1',
  blockId: 'b1',
  relationType: 'IMPLEMENTS' as const,
}];

describe('linked workbench interactions', () => {
  it('emits the linked id when a code range is clicked', async () => {
    const wrapper = mount(LinkedCodePane, { props: { workspaceId: 'w', repositoryId: 'r', content: 'one\ntwo\nthree', path: 'A.java', anchors, links, issues: [], activeLinkId: null }, global: { plugins: [createPinia()], stubs: { ElTag: true, ElSkeleton: true, ElEmpty: true } } });
    await wrapper.findAll('.code-line')[1].trigger('click');
    expect(wrapper.emitted('activate')?.[0]).toEqual(['l1']);
  });

  it('rail selection emits its link and requests both focus targets', async () => {
    const wrapper = mount(CodeAnchorRail, { props: { anchors, links, issues: [], activeLinkId: null } });
    await wrapper.get('.rail-node').trigger('click');
    expect(wrapper.emitted('activate')?.[0]).toEqual(['l1']);
    expect(focusPlan('rail')).toEqual({ code: true, document: true });
  });

  it('maps a selected Block to the linked id and only requests code focus', () => {
    expect(linkIdForBlock(links, 'b1')).toBe('l1');
    expect(focusPlan('document')).toEqual({ code: true, document: false });
  });

  it('removes inspector width when closed', () => {
    const wrapper = mount(LinkedWorkbenchContent, {
      props: {
        workspaceId: 'w', repositoryId: 'r',
        mode: 'LINKED', inspectorOpen: false, sourceContent: '', sourcePath: '', anchors, links,
        issues: [], activeLinkId: null, document: null, activeBlockId: null, unboundBlockId: null, readonly: false,
        activeLink: null, activeAnchor: null, activeBlock: null, activeIssue: null, activeEvidence: [], versions: [],
      },
      global: { stubs: { LinkedWorkbenchCanvas: true, LinkedInspector: true } },
    });
    expect(wrapper.get('.linked-content').classes()).not.toContain('has-inspector');
    expect(wrapper.findComponent({ name: 'LinkedInspector' }).isVisible()).toBe(false);
  });
});
