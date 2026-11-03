import type { KnowledgeDetail, KnowledgeSummary } from './types';

const codeName = (code: string, name: string) => ({ code, name, description: `${name} 설명` });

export function summary(overrides: Partial<KnowledgeSummary> = {}): KnowledgeSummary {
  return {
    documentId: '101',
    type: codeName('FAQ', '자주 묻는 질문'),
    locale: codeName('KO', '한국어'),
    latestRevision: 1,
    publishedRevision: null,
    documentVersion: 0,
    revisionVersion: 0,
    title: '배송은 얼마나 걸리나요?',
    category: codeName('SHIPPING', '배송'),
    status: codeName('DRAFT', '초안'),
    validFrom: '2026-11-01T00:00:00Z',
    validTo: null,
    approvedBy: null,
    approvedAt: null,
    publishedAt: null,
    ...overrides,
  };
}

export function detail(overrides: Partial<KnowledgeDetail> = {}): KnowledgeDetail {
  return {
    ...summary(),
    revisionId: '9001',
    revisionNumber: 1,
    body: '평균 3일 걸립니다.\n주말은 제외합니다.',
    ...overrides,
  };
}

export const approved = (overrides: Partial<KnowledgeDetail> = {}) =>
  detail({
    status: codeName('APPROVED', '승인'),
    revisionVersion: 1,
    approvedBy: '7',
    approvedAt: '2026-11-02T00:00:00Z',
    ...overrides,
  });

export const published = (overrides: Partial<KnowledgeDetail> = {}) =>
  approved({
    status: codeName('PUBLISHED', '게시'),
    publishedRevision: 1,
    documentVersion: 1,
    revisionVersion: 2,
    publishedAt: '2026-11-02T01:00:00Z',
    ...overrides,
  });

export const archived = (overrides: Partial<KnowledgeDetail> = {}) =>
  detail({ status: codeName('ARCHIVED', '보관'), ...overrides });
