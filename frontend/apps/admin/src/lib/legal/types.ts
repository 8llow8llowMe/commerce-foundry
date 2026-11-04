/**
 * 어드민 약관 · 개인정보처리방침 화면이 쓰는 API 계약 — operations-service `LegalDocumentAdminController`(#569).
 * 정본은 `backend/docs/store-legal-guide.md` "ADMIN 계약". 모두 ADMIN 이고 응답은 `no-store` 다.
 * type · locale 은 경로에 그대로 싣는 원시 제어 코드다(대소문자 구분, 언어 대체 없음).
 * 제목 · 본문은 단순 텍스트다 — HTML 로 해석하지 않고 이스케이프된 글자로만 보인다.
 */

export const LEGAL_TYPES = ['TERMS', 'PRIVACY'] as const;
export type LegalType = (typeof LEGAL_TYPES)[number];

export const LEGAL_LOCALES = ['ko', 'en'] as const;
export type LegalLocale = (typeof LEGAL_LOCALES)[number];

export const TYPE_LABELS: Record<LegalType, string> = {
  TERMS: '이용약관',
  PRIVACY: '개인정보처리방침',
};
export const LOCALE_LABELS: Record<LegalLocale, string> = { ko: '국문', en: '영문' };

/** 입력 상한 — BE 검증과 같다(Java String 길이 = UTF-16 단위, JS `length` 와 같다) */
export const LIMITS = { title: 200, body: 50000 } as const;

/** 이력 한 페이지 — 서버 기본값(1~20) */
export const REVISION_PAGE_SIZE = 10;

const ADMIN_PATH = '/api/v1/admin/store';
export const LEGAL_LIST_PATH = `${ADMIN_PATH}/legal-documents`;
export const legalPath = (type: LegalType, locale: LegalLocale) =>
  `${LEGAL_LIST_PATH}/${type}/${locale}`;
export const legalTemplatePath = (type: LegalType, locale: LegalLocale) =>
  `${ADMIN_PATH}/legal-document-templates/${type}?locale=${locale}`;
export const legalRevisionsPath = (type: LegalType, locale: LegalLocale, page: number) =>
  `${legalPath(type, locale)}/revisions?page=${page}&size=${REVISION_PAGE_SIZE}`;

/** 문서 포인터. 등록하지 않은 조합은 목록에서 빠진다 */
export type LegalSummary = {
  type: LegalType;
  locale: LegalLocale;
  latestRevision: number;
  /** 지금 공개 중인 리비전 번호. 게시한 적 없으면 null */
  publishedRevision: number | null;
  /** 낙관적 편집 버전 — 초안 저장 · 게시의 `expectedVersion` */
  version: number;
};

/** 불변 리비전. 관리자 식별자는 문자열, 시각은 UTC ISO */
export type LegalRevision = {
  revisionNumber: number;
  title: string;
  body: string;
  createdBy: string;
  createdAt: string;
  publishedBy: string | null;
  publishedAt: string | null;
};

/** 상세 — revision 은 항상 **최신** 리비전이다(게시본이 아닐 수 있다) */
export type LegalDetail = { document: LegalSummary; revision: LegalRevision };

/** 시작 템플릿 — 질문 · 빈칸 중심 구조. 완성 법률 문서가 아니다 */
export type LegalTemplate = {
  templateId: string;
  version: number;
  type: LegalType;
  locale: LegalLocale;
  title: string;
  body: string;
  checklist: string[];
  guidance: string;
};

/** PUT `/draft` — 제목 · 본문 전체로 새 리비전을 더한다. 처음이면 expectedVersion 은 null */
export type LegalDraftRequest = { expectedVersion: number | null; title: string; body: string };

/** POST `/publish` — 최신 리비전만 게시할 수 있다 */
export type LegalPublishRequest = {
  expectedVersion: number;
  revisionNumber: number;
  reviewConfirmed: true;
};
