import type { LegalDetail, LegalRevision, LegalSummary, LegalTemplate } from './types';

// 가짜 값만 쓴다(공개 저장소) — 실제 고객사 정보 · 실제 약관 본문을 넣지 않는다

export function summary(overrides: Partial<LegalSummary> = {}): LegalSummary {
  return {
    type: 'TERMS',
    locale: 'ko',
    latestRevision: 2,
    publishedRevision: 1,
    version: 3,
    ...overrides,
  };
}

export function revision(overrides: Partial<LegalRevision> = {}): LegalRevision {
  return {
    revisionNumber: 2,
    title: '테스트 이용약관',
    body: '제1조 (목적)\n테스트 본문입니다.',
    createdBy: '7',
    createdAt: '2026-11-03T01:00:00Z',
    publishedBy: null,
    publishedAt: null,
    ...overrides,
  };
}

export function detail(
  document: Partial<LegalSummary> = {},
  latest: Partial<LegalRevision> = {},
): LegalDetail {
  return { document: summary(document), revision: revision(latest) };
}

export function template(overrides: Partial<LegalTemplate> = {}): LegalTemplate {
  return {
    templateId: 'legal-terms-ko',
    version: 1,
    type: 'TERMS',
    locale: 'ko',
    title: '{{상호}} 이용약관',
    body: '제1조 (목적)\n이 약관은 {{상호}} 가 ...\n제2조 {{시행일}}',
    checklist: ['실제 처리 사실과 맞는지 확인', '시행일 확인'],
    guidance: '질문 · 빈칸 중심의 시작 구조입니다.',
    ...overrides,
  };
}
