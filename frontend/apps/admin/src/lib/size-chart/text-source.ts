import { ApiError } from '@cf/api-client';
import { accessDeniedOf, type AccessDenied } from '@/lib/api-errors';

/**
 * 상품 본문의 치수표 후보(BE #737). 정본은 `backend/docs/ai-size-chart-guide.md` §2.
 * - 후보 `GET /products/{id}/size-chart/text-sources` (CATALOG_READ) → `[{sourceId, bodyHash, sourceText}]`.
 *   국문 · 영문 상세 설명의 명확한 표만 오고, 없으면 빈 배열이다.
 * - 후보를 고르면 생성 본문에 `sourceText`(후보 텍스트 그대로)와 `textSelection:{sourceId, bodyHash}` 를 함께 보낸다.
 *   서버가 그 순간 본문에서 후보를 다시 만들어 대조하고, 다르면 409 `SIZE_CHART_DRAFT_017` 이다 — 후보를 다시 읽게 한다.
 * - 운영자가 텍스트를 고치면 후보와 같지 않으므로 선택 정보를 빼고 직접 입력으로 보낸다.
 * - 선택 정보는 멱등 해시를 바꾸지 않는다(BE) — 키 지문도 텍스트만 본다(`inputFingerprint`).
 */
export const textSourcesPath = (productId: string) =>
  `/api/v1/admin/products/${encodeURIComponent(productId)}/size-chart/text-sources`;

export type TextSource = {
  /** `TEXT-KO-1` · `TEXT-EN-2` — 서버가 발급한 토큰이라 그대로 돌려보낸다 */
  sourceId: string;
  /** 검토한 본문 SHA-256 소문자 16진수 */
  bodyHash: string;
  /** HTML 과 자유 문장을 뺀 치수 텍스트(줄바꿈 포함) */
  sourceText: string;
};

export type TextSelection = { sourceId: string; bodyHash: string };

export const selectionOf = (source: TextSource): TextSelection => ({
  sourceId: source.sourceId,
  bodyHash: source.bodyHash,
});

/** 텍스트를 고친 뒤의 고른 표 — 후보 텍스트와 한 글자라도 다르면 더는 그 표가 아니다(직접 입력으로 보낸다) */
export const pickAfterEdit = (picked: TextSource | null, text: string): TextSource | null =>
  picked && text === picked.sourceText ? picked : null;

/**
 * 고른 표가 그사이 바뀌었다(017) — 고른 표를 놓는다. 고친 데 없는 후보 텍스트였으면 칸을 비워 다시 고르게 하고,
 * 직접 입력이었으면 운영자가 쓴 글을 지킨다.
 */
export const afterSourceChanged = (
  picked: TextSource | null,
  text: string,
): { text: string; picked: null } => ({ text: picked ? '' : text, picked: null });

/** 후보 이름 — "국문 설명의 표 1". 아이디 원문은 화면에 보이지 않는다 */
export function sourceLabel(sourceId: string): string {
  const match = /^TEXT-(KO|EN)-(\d+)$/.exec(sourceId);
  if (!match) return '상품 설명의 표';
  return `${match[1] === 'KO' ? '국문' : '영문'} 설명의 표 ${match[2]}`;
}

/** 후보 목록 상태 */
export type TextSourcesState =
  | { status: 'idle' }
  | { status: 'loading' }
  | { status: 'ok'; sources: TextSource[] }
  | { status: 'error'; message: string };

export const TEXT_SOURCES_FAILED =
  '상품 설명에서 표를 불러오지 못했습니다. 다시 불러오거나 치수 텍스트를 직접 붙여넣어 주세요.';
export const TEXT_SOURCES_SESSION_EXPIRED =
  '로그인이 만료되어 상품 설명의 표를 불러오지 못했습니다. 새 탭에서 다시 로그인한 뒤 다시 불러와 주세요.';
export const TEXT_SOURCES_EMPTY =
  '상품 설명에서 고를 수 있는 치수표를 찾지 못했습니다. 치수 텍스트를 직접 붙여넣어 주세요.';
export const TEXT_SOURCES_CHECK =
  '설명에서 찾은 표입니다. 실제 상품 화면의 표와 같은지 확인한 뒤 골라 주세요.';
export const TEXT_SOURCE_CHANGED =
  '고른 뒤 상품 설명이 바뀌었습니다. 표를 다시 불러왔으니 다시 골라 주세요.';

export type TextSourcesFailure =
  { kind: 'access'; denied: AccessDenied } | { kind: 'retry'; message: string };

/** 후보 읽기 실패. 입력 중일 수 있어 401 이어도 로그인 화면으로 보내지 않는다 */
export function textSourcesFailure(cause: unknown): TextSourcesFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized') return { kind: 'retry', message: TEXT_SOURCES_SESSION_EXPIRED };
  if (denied) return { kind: 'access', denied };
  if (cause instanceof ApiError && cause.kind === 'rateLimited')
    return { kind: 'retry', message: cause.message };
  return { kind: 'retry', message: TEXT_SOURCES_FAILED };
}
