import type { Locale } from '@cf/ui';

/**
 * 고객 공개 약관 · 개인정보처리방침 — operations-service `LegalDocumentWebController`(#569).
 * 정본은 `backend/docs/store-legal-guide.md` "고객 공개 계약". 익명 GET, 실제 게시한 리비전만 준다.
 * 언어는 `locale` query 로 고르고 대체(영문 → 국문)가 없다. 미게시 · 없는 언어 · 게시한 적 없는 번호는 모두 404 다.
 */

export type LegalType = 'TERMS' | 'PRIVACY';

/** 공개 문서. 관리자 아이디 · 작성 시각 · 편집 버전은 오지 않는다 */
export type LegalDocument = {
  type: LegalType;
  locale: Locale;
  revisionNumber: number;
  title: string;
  /** 단순 텍스트 — React 텍스트로만 그린다(HTML 로 해석하지 않는다) */
  body: string;
  publishedAt: string;
};

export type LegalLoad =
  | { kind: 'ok'; document: LegalDocument }
  /** 이 언어로 게시한 문서가 없다(또는 그 번호를 게시한 적이 없다) — 다른 언어로 대신 보이지 않는다 */
  | { kind: 'missing' }
  | { kind: 'failed' };

const BASE = '/api/v1/store/legal-documents';

export function legalApiPath(type: LegalType, locale: Locale, revision: number | null): string {
  const path = revision === null ? `${BASE}/${type}` : `${BASE}/${type}/revisions/${revision}`;
  return `${path}?locale=${locale}`;
}

/** 주소의 `?revision=` → 리비전 번호. 1 이상 정수(9자리까지)만, 아니면 현재 게시본(null) */
export function parseRevision(value: string | string[] | undefined): number | null {
  const raw = Array.isArray(value) ? value[0] : value;
  if (!raw || !/^[1-9]\d{0,8}$/.test(raw)) return null;
  return Number(raw);
}

/** 게시일 — 스토어 언어로, 한국 시각 날짜 */
export function formatPublishedDate(value: string, locale: Locale): string | null {
  const date = new Date(value);
  if (Number.isNaN(date.getTime())) return null;
  return new Intl.DateTimeFormat(locale === 'ko' ? 'ko-KR' : 'en-US', {
    dateStyle: 'long',
    timeZone: 'Asia/Seoul',
  }).format(date);
}
