/**
 * 어드민 상담 지식(FAQ · 정책 · 응답 템플릿) 화면이 쓰는 API 계약 — support-service `KnowledgeAdminController`(#469 PR-B).
 * 정본은 `backend/docs/support-knowledge-guide.md` "관리자 계약". 모두 ADMIN 이다.
 * 아이디는 문자열, enum 은 `{ code, name, description }` 이다. 상세는 **최신 리비전 하나**만 준다(지난 리비전 목록 API 는 없다).
 * 제목 · 본문은 plain text 다 — HTML 로 해석하지 않고 이스케이프된 글자로만 보인다.
 */

export const KNOWLEDGE_PATH = '/api/v1/admin/support/knowledge';
export const knowledgePath = (documentId: string) =>
  `${KNOWLEDGE_PATH}/${encodeURIComponent(documentId)}`;

/** 입력 상한 — BE `KnowledgeAdminRequests.Content` 와 같다(Java String 길이 = UTF-16 단위) */
export const LIMITS = { title: 200, body: 8000 } as const;

export const KNOWLEDGE_TYPES = ['FAQ', 'POLICY', 'TEMPLATE'] as const;
export type KnowledgeTypeCode = (typeof KNOWLEDGE_TYPES)[number];

export const KNOWLEDGE_LOCALES = ['KO', 'EN'] as const;
export type KnowledgeLocaleCode = (typeof KNOWLEDGE_LOCALES)[number];

export const KNOWLEDGE_CATEGORIES = [
  'SHIPPING',
  'SHIPPING_FEE',
  'INTERNATIONAL_SHIPPING',
  'EXCHANGE_REFUND',
  'SIZE',
  'MATERIAL',
  'PRODUCTION_TIME',
  'CUSTOMIZATION',
  'OTHER',
] as const;
export type KnowledgeCategoryCode = (typeof KNOWLEDGE_CATEGORIES)[number];

export type KnowledgeStatusCode = 'DRAFT' | 'APPROVED' | 'PUBLISHED' | 'ARCHIVED';

/**
 * 필터 · 새 문서 고르기에만 쓰는 사본(서버 enum `displayName` 과 같다). 저장된 값은 서버 `name` 을 보인다.
 * BE 가 값을 더하면 같이 더한다.
 */
export const TYPE_LABELS: Record<KnowledgeTypeCode, string> = {
  FAQ: '자주 묻는 질문',
  POLICY: '정책',
  TEMPLATE: '응답 템플릿',
};
export const LOCALE_LABELS: Record<KnowledgeLocaleCode, string> = { KO: '한국어', EN: '영어' };
export const CATEGORY_LABELS: Record<KnowledgeCategoryCode, string> = {
  SHIPPING: '배송',
  SHIPPING_FEE: '배송비',
  INTERNATIONAL_SHIPPING: '해외배송',
  EXCHANGE_REFUND: '교환환불',
  SIZE: '사이즈',
  MATERIAL: '소재',
  PRODUCTION_TIME: '제작기간',
  CUSTOMIZATION: '맞춤제작',
  OTHER: '기타',
};

export type CodeName = { code: string; name: string; description: string };

/** 목록 줄 — 최신 리비전 요약(본문 없음) */
export type KnowledgeSummary = {
  documentId: string;
  type: CodeName;
  locale: CodeName;
  latestRevision: number;
  /** 현재 게시 리비전 번호. 게시본이 없으면 null */
  publishedRevision: number | null;
  documentVersion: number;
  revisionVersion: number;
  title: string;
  category: CodeName;
  /** 최신 리비전의 상태 */
  status: CodeName;
  validFrom: string;
  validTo: string | null;
  approvedBy: string | null;
  approvedAt: string | null;
  publishedAt: string | null;
};

/** 상세 — 문서 포인터 + 최신 리비전 내용 · 버전 */
export type KnowledgeDetail = Omit<KnowledgeSummary, 'status'> & {
  revisionId: string;
  /** 응답 리비전 번호(= 최신 리비전) */
  revisionNumber: number;
  body: string;
  status: CodeName;
};

export type KnowledgeContent = {
  title: string;
  body: string;
  category: KnowledgeCategoryCode;
  validFrom: string;
  /** 생략(null)이면 무기한 */
  validTo: string | null;
};

export type KnowledgeCreateRequest = {
  type: KnowledgeTypeCode;
  locale: KnowledgeLocaleCode;
  content: KnowledgeContent;
};

/** PUT — 최신 DRAFT 전체 교체 */
export type KnowledgeEditRequest = {
  revisionNumber: number;
  expectedVersion: number;
  content: KnowledgeContent;
};

/** POST `/revisions` — 새 DRAFT. 기존 게시본은 그대로 남는다 */
export type KnowledgeDraftRequest = {
  expectedDocumentVersion: number;
  content: KnowledgeContent;
};

/** POST `/approve` — 최신 DRAFT 승인 */
export type KnowledgeApproveRequest = { revisionNumber: number; expectedVersion: number };

/** POST `/publish` — 최신 APPROVED 게시 */
export type KnowledgePublishRequest = {
  revisionNumber: number;
  expectedVersion: number;
  expectedDocumentVersion: number;
};
