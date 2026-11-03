import {
  KNOWLEDGE_LOCALES,
  KNOWLEDGE_PATH,
  KNOWLEDGE_TYPES,
  type KnowledgeLocaleCode,
  type KnowledgeTypeCode,
} from './types';

/** 서버 기본값과 같다(1~100) */
export const PAGE_SIZE = 20;

export const KNOWLEDGE_HREF = '/support/knowledge';
export const NEW_KNOWLEDGE_HREF = `${KNOWLEDGE_HREF}/new`;
export const knowledgeHref = (documentId: string) =>
  `${KNOWLEDGE_HREF}/${encodeURIComponent(documentId)}`;
export const knowledgeEditHref = (documentId: string) => `${knowledgeHref(documentId)}/edit`;

/** 화면 조건. null 은 "전체"(서버에 보내지 않는다) */
export type KnowledgeQuery = {
  type: KnowledgeTypeCode | null;
  locale: KnowledgeLocaleCode | null;
  page: number;
};

export const DEFAULT_QUERY: KnowledgeQuery = { type: null, locale: null, page: 0 };

type SearchParams = Record<string, string | string[] | undefined>;

const first = (value: string | string[] | undefined) => (Array.isArray(value) ? value[0] : value);

/**
 * 주소(query string) → 조건. 허용 값(enum 이름 그대로)만 받고 나머지는 "전체" 다 — 손으로 고친 주소가 400(`SUPPORT_198`)이 되지 않게.
 * 페이지는 숫자 글자 6자리까지만 받는다.
 */
export function parseKnowledgeQuery(params: SearchParams): KnowledgeQuery {
  const type = first(params.type);
  const locale = first(params.locale);
  const page = first(params.page);
  return {
    type: KNOWLEDGE_TYPES.find((item) => item === type) ?? null,
    locale: KNOWLEDGE_LOCALES.find((item) => item === locale) ?? null,
    page: page && /^\d{1,6}$/.test(page) ? Number(page) : 0,
  };
}

/** 화면 주소. 기본값은 빼서 주소를 짧게 둔다 */
export function knowledgeListHref(query: KnowledgeQuery): string {
  const params = new URLSearchParams();
  if (query.type) params.set('type', query.type);
  if (query.locale) params.set('locale', query.locale);
  if (query.page > 0) params.set('page', String(query.page));
  const search = params.toString();
  return search ? `${KNOWLEDGE_HREF}?${search}` : KNOWLEDGE_HREF;
}

/** BFF 경로. 페이지 · 크기는 늘 보낸다 */
export function knowledgeApiPath(query: KnowledgeQuery): string {
  const params = new URLSearchParams();
  if (query.type) params.set('type', query.type);
  if (query.locale) params.set('locale', query.locale);
  params.set('page', String(query.page));
  params.set('size', String(PAGE_SIZE));
  return `${KNOWLEDGE_PATH}?${params.toString()}`;
}

/** 페이지를 뺀 조건이 하나라도 있는지 — "조건 초기화" 를 보일지 */
export function hasFilter(query: KnowledgeQuery): boolean {
  return query.type !== null || query.locale !== null;
}
