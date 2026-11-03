import { describe, expect, it } from 'vitest';
import {
  approveRequest,
  archiveQuery,
  EXPIRED,
  knowledgeActions,
  NOT_YET_VALID,
  publishRequest,
} from './actions';
import { approved, archived, detail, published } from './test-fixtures';

const NOW = new Date('2026-11-03T00:00:00Z');

describe('knowledgeActions', () => {
  it('초안 — 고치기 · 승인 · 보관', () => {
    expect(knowledgeActions(detail(), NOW)).toEqual({
      edit: 'draft',
      approve: true,
      publish: false,
      publishBlocked: null,
      archive: 'archive',
    });
  });

  it('승인 — 새 초안 · 게시 · 보관', () => {
    expect(knowledgeActions(approved(), NOW)).toEqual({
      edit: 'revision',
      approve: false,
      publish: true,
      publishBlocked: null,
      archive: 'archive',
    });
  });

  it('승인이어도 유효기간 밖이면 게시를 막고 이유를 보인다', () => {
    const early = knowledgeActions(approved({ validFrom: '2026-11-04T00:00:00Z' }), NOW);
    expect(early).toMatchObject({ publish: false, publishBlocked: NOT_YET_VALID });
    const late = knowledgeActions(approved({ validTo: '2026-11-03T00:00:00Z' }), NOW);
    expect(late).toMatchObject({ publish: false, publishBlocked: EXPIRED });
  });

  it('게시 — 새 초안 · 게시 해제', () => {
    expect(knowledgeActions(published(), NOW)).toEqual({
      edit: 'revision',
      approve: false,
      publish: false,
      publishBlocked: null,
      archive: 'unpublish',
    });
  });

  it('보관 — 새 초안만', () => {
    expect(knowledgeActions(archived(), NOW)).toMatchObject({ edit: 'revision', archive: null });
  });

  it('다른 리비전이 게시 중인 초안을 보관해도 게시는 해제되지 않는다', () => {
    const draft = detail({ revisionNumber: 2, latestRevision: 2, publishedRevision: 1 });
    expect(knowledgeActions(draft, NOW).archive).toBe('archive');
  });
});

describe('요청', () => {
  const target = published({ revisionNumber: 3, revisionVersion: 5, documentVersion: 4 });

  it('승인 · 게시는 응답에 실린 버전을 그대로 보낸다', () => {
    expect(approveRequest(target)).toEqual({ revisionNumber: 3, expectedVersion: 5 });
    expect(publishRequest(target)).toEqual({
      revisionNumber: 3,
      expectedVersion: 5,
      expectedDocumentVersion: 4,
    });
  });

  it('보관은 query 세 개다', () => {
    expect(archiveQuery(target)).toBe(
      'revisionNumber=3&expectedVersion=5&expectedDocumentVersion=4',
    );
  });
});
