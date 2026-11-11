import { ApiError } from '@cf/api-client';
import { accessDeniedOf, type AccessDenied } from '@/lib/api-errors';
import type { CategoryRow } from '@/lib/category/tree';
import { isLongId } from '@/lib/product/query';
import { productDraftPath, type DraftImageType } from './form';

// AI 상품 초안 상세(#360 · BE #359) — `GET /api/v1/admin/product-drafts/{draftId}` 응답과 화면 판정.
// 화면에는 운영자 문구(상태 `name` · `description`, 부족한 입력 `name`)만 보인다 — 초안 · 회원 아이디는 보이지 않는다(#305).

export type CodeNameDescription = { code: string; name: string; description: string };

export type DraftStatusCode =
  'REQUESTED' | 'GENERATING' | 'READY' | 'FAILED' | 'APPLIED' | 'REJECTED' | 'EXPIRED';

export type ProductDraftFacts = {
  nameHint: string | null;
  categoryId: string | null;
  materials: string[];
  careNotes: string[];
  measurementsNote: string | null;
  origin: string | null;
  certifications: string[];
  tone: string | null;
  mustInclude: string[];
  mustAvoid: string[];
};

export type ProductDraft = {
  draftId: string;
  status: CodeNameDescription;
  facts: ProductDraftFacts;
  images: {
    url: string;
    imageType: DraftImageType | string;
    width: number | null;
    height: number | null;
  }[];
  missingRequiredInputs: CodeNameDescription[];
  requestedByMemberId: string;
  requestedAt: string;
  expiresAt: string;
  rejectedAt: string | null;
};

/** 끝난 상태 — 거절할 수 없다(BE `ProductDraftStatus.isOpen` 의 반대) */
const CLOSED: ReadonlySet<string> = new Set(['APPLIED', 'REJECTED', 'EXPIRED']);
export const canReject = (status: CodeNameDescription) => !CLOSED.has(status.code);

/**
 * 상태별 화면 덧붙임. 생성 작업 · AI 결과 칸 · 적용 API 가 아직 없다(#359 후속) — 서버 설명이 말하는 "적용" · "다시 생성" 을 이 화면에서
 * 할 수 없음을 정직하게 알린다.
 */
export function statusNote(status: CodeNameDescription): string | null {
  switch (status.code) {
    case 'REQUESTED':
    case 'GENERATING':
      return 'AI 초안 생성은 준비 중입니다. 결과가 준비되면 이 화면에서 확인할 수 있습니다. 그동안 상품은 상품 등록 화면에서 직접 등록할 수 있습니다.';
    case 'READY':
      return '초안을 상품에 적용하는 기능은 준비 중입니다.';
    case 'FAILED':
      return '다시 생성하는 기능은 준비 중입니다. 새 초안을 요청하거나 상품을 직접 등록해 주세요.';
    default:
      return null;
  }
}

export type ProductDraftLoad =
  | { kind: 'ok'; draft: ProductDraft }
  | { kind: 'notFound' }
  | { kind: 'denied'; denied: AccessDenied }
  | { kind: 'failed' };

/** 형식이 틀린 주소(양수 아님 `127` · 타입 불일치 `198`)도 없는 초안으로 본다 */
const INVALID_PATH_CODES = new Set(['PRODUCT_DRAFT_127', 'PRODUCT_198']);

export async function loadProductDraft(
  draftId: string,
  load: <T>(path: string) => Promise<T>,
): Promise<ProductDraftLoad> {
  if (!isLongId(draftId)) return { kind: 'notFound' };
  try {
    return { kind: 'ok', draft: await load<ProductDraft>(productDraftPath(draftId)) };
  } catch (cause) {
    const denied = accessDeniedOf(cause);
    if (denied) return { kind: 'denied', denied };
    if (cause instanceof ApiError) {
      if (cause.kind === 'notFound') return { kind: 'notFound' };
      if (cause.code !== null && INVALID_PATH_CODES.has(cause.code)) return { kind: 'notFound' };
    }
    return { kind: 'failed' };
  }
}

const dateTime = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'medium',
  timeStyle: 'short',
  timeZone: 'Asia/Seoul',
});

/** 시각(UTC ISO) → 한국 시각. 없거나 읽을 수 없으면 대시 */
export function formatDraftTime(value: string | null): string {
  if (value === null) return '—';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? '—' : dateTime.format(date);
}

/** 사실 칸 이름(입력한 값만 보인다) */
export const FACT_LABELS = {
  nameHint: '상품명 후보',
  categoryId: '카테고리',
  materials: '소재',
  careNotes: '세탁 · 관리 방법',
  measurementsNote: '치수 메모',
  origin: '원산지',
  certifications: '인증',
  tone: '문구 분위기',
  mustInclude: '꼭 넣을 표현',
  mustAvoid: '쓰지 않을 표현',
} as const satisfies Record<keyof ProductDraftFacts, string>;

export const CATEGORY_UNKNOWN = '고른 카테고리 (이름을 불러오지 못했습니다)';
export const CATEGORY_MISSING = '삭제됐거나 찾을 수 없는 카테고리';

/** 사실의 카테고리 → 경로 이름. 고르지 않았으면 null. 아이디 원문은 보이지 않는다(#305) */
export function categoryLabel(
  categoryId: string | null,
  rows: readonly CategoryRow[] | null,
): string | null {
  if (categoryId === null) return null;
  if (rows === null) return CATEGORY_UNKNOWN;
  const row = rows.find((item) => item.node.categoryId === categoryId);
  return row ? row.path.join(' > ') : CATEGORY_MISSING;
}
