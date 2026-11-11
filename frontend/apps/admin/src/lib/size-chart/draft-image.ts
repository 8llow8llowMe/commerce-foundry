import { ApiError } from '@cf/api-client';
import { accessDeniedOf, type AccessDenied } from '@/lib/api-errors';
import type { CodeName } from './types';

/**
 * AI 사이즈표 초안 IMAGE 입력(BE #217 PR #690). 정본은 `backend/docs/ai-size-chart-guide.md` · `api-design-guide.md`.
 * - 후보 `GET /products/{id}/size-chart/image-sources` (CATALOG_READ) — `[{sourceId, previewUrl, eligible, reason}]`, 내려받지 않는다
 * - 확인 `GET /products/{id}/size-chart/image-sources/{sourceId}/inspection` (CATALOG_MANAGE) — 최대 5MiB 를 읽어 SHA-256 확인값을 준다
 * - 생성은 TEXT 와 같은 경로에 `{templateId, mode:'IMAGE', images:[{sourceId, approvedContentHash}], personalInfoRemovedConfirmed:true}`
 * BE 플래그 `size-chart.draft.image-enabled`(기본 꺼짐)가 닫혀 있으면 세 API 모두 400 `SIZE_CHART_DRAFT_011` 이다 — 화면은 텍스트만 남긴다.
 */
export const MAX_DRAFT_IMAGES = 4;
/** 형식 · 크기 상한 — BE `SizeChartImageLimits.MAX_IMAGE_BYTES` 와 같다 */
export const MAX_DRAFT_IMAGE_BYTES = 5 * 1024 * 1024;

export const imageSourcesPath = (productId: string) =>
  `/api/v1/admin/products/${encodeURIComponent(productId)}/size-chart/image-sources`;
export const inspectionPath = (productId: string, sourceId: string) =>
  `${imageSourcesPath(productId)}/${encodeURIComponent(sourceId)}/inspection`;

export type ImageSource = {
  /** `IMG-{imageId}` · `DESC-{주소 해시}` — 서버가 발급한 토큰이라 그대로 돌려보낸다 */
  sourceId: string;
  /** 공개 주소. 외부 주소 이미지는 null */
  previewUrl: string | null;
  eligible: boolean;
  /** 쓸 수 없는 사유. 쓸 수 있으면 null */
  reason: CodeName | null;
};

export type ImageInspection = {
  sourceId: string;
  /** SHA-256 소문자 16진수 64자. 이미지가 바뀌면 달라진다 */
  contentHash: string;
  contentType: string;
  contentLength: number;
};

export type ApprovedImage = { sourceId: string; approvedContentHash: string };

/** 생성 요청 입력. TEXT 흐름은 그대로 두고 IMAGE 를 나란히 둔다 */
export type DraftInput =
  { mode: 'TEXT'; sourceText: string } | { mode: 'IMAGE'; images: readonly ApprovedImage[] };

/** 생성 본문. IMAGE 는 운영자가 개인정보 확인란을 체크해야만 만든다(화면이 막는다) */
export function draftBody(templateId: string, input: DraftInput) {
  return input.mode === 'TEXT'
    ? { templateId, mode: 'TEXT' as const, sourceText: input.sourceText.trim() }
    : {
        templateId,
        mode: 'IMAGE' as const,
        images: input.images.map(({ sourceId, approvedContentHash }) => ({
          sourceId,
          approvedContentHash,
        })),
        personalInfoRemovedConfirmed: true as const,
      };
}

/**
 * 멱등 키 지문. TEXT 는 기존 모양(`템플릿\n텍스트`) 그대로다. IMAGE 는 순서 · 확인값까지 넣는다 —
 * BE 요청 해시가 `[productId, templateId, "IMAGE", [[sourceId, hash]…], true]`(순서 포함)라 하나라도 바뀌면 새 키여야 한다.
 */
export function inputFingerprint(templateId: string, input: DraftInput): string {
  if (input.mode === 'TEXT') return `${templateId}\n${input.sourceText.trim()}`;
  return `${templateId}\nIMAGE\n${input.images
    .map((image) => `${image.sourceId}:${image.approvedContentHash}`)
    .join('\n')}`;
}

/** 이미지 하나의 확인 상태 */
export type InspectionState =
  | { status: 'checking' }
  | { status: 'ok'; inspection: ImageInspection }
  | { status: 'error'; message: string };

/** 고른 순서대로 확인이 모두 끝났으면 승인 목록, 아니면 null */
export function approvedImages(
  selected: readonly string[],
  inspections: Readonly<Record<string, InspectionState | undefined>>,
): ApprovedImage[] | null {
  if (selected.length === 0 || selected.length > MAX_DRAFT_IMAGES) return null;
  const approved: ApprovedImage[] = [];
  for (const sourceId of selected) {
    const state = inspections[sourceId];
    if (state?.status !== 'ok') return null;
    approved.push({ sourceId, approvedContentHash: state.inspection.contentHash });
  }
  return approved;
}

/**
 * 개인정보 확인이 걸리는 대상 — 승인할 `sourceId:확인값` 묶음(순서 포함). 확인이 끝나지 않았으면 null 이라 체크할 수 없다.
 * 다시 확인(015 · 114)으로 바이트가 바뀌면 키가 달라져 체크가 풀린다 — 가이드 "승인 뒤 자산이 바뀌면 다시 승인".
 */
export function confirmationKey(approved: readonly ApprovedImage[] | null): string | null {
  if (!approved) return null;
  return approved.map((image) => `${image.sourceId}:${image.approvedContentHash}`).join('\n');
}

/**
 * 요청 세대 — 같은 키로 다시 요청하거나 취소하면 늦게 온 이전 응답을 버린다(이미지 확인은 이미지 아이디, 목록은 고정 키).
 * 응답이 선택을 되살리지 않게, 빼낸 이미지는 `bump` 로 세대를 올린다.
 */
export function createGenerations() {
  const current = new Map<string, number>();
  return {
    start(key: string): number {
      const next = (current.get(key) ?? 0) + 1;
      current.set(key, next);
      return next;
    },
    bump(keys: readonly string[]) {
      for (const key of keys) current.set(key, (current.get(key) ?? 0) + 1);
    },
    isCurrent(key: string, generation: number): boolean {
      return current.get(key) === generation;
    },
  };
}

/** 선택 토글. 이미 4장이면 더 고르지 않는다(고른 순서 = 생성 순서) */
export function toggleSelection(selected: readonly string[], sourceId: string): string[] {
  if (selected.includes(sourceId)) return selected.filter((id) => id !== sourceId);
  if (selected.length >= MAX_DRAFT_IMAGES) return [...selected];
  return [...selected, sourceId];
}

/** 확인 결과 한 줄 — "JPEG · 512KB" */
export function inspectionText(inspection: ImageInspection): string {
  const type = inspection.contentType.replace(/^image\//, '').toUpperCase();
  const kb = Math.max(1, Math.round(inspection.contentLength / 1024));
  return kb >= 1024
    ? `${type} · ${(kb / 1024).toFixed(1)}MB`
    : `${type} · ${kb.toLocaleString('ko-KR')}KB`;
}

// ── 실패 처리 ────────────────────────────────────────────

const CODE = (n: string) => `SIZE_CHART_DRAFT_${n}`;

export const IMAGE_INPUT_CLOSED =
  '이 환경에서는 아직 이미지로 초안을 만들 수 없습니다. 치수 텍스트로 요청해 주세요.';
export const IMAGE_SOURCES_FAILED =
  '이미지 목록을 불러오지 못했습니다. 잠시 후 다시 불러와 주세요.';
export const IMAGE_SOURCES_SESSION_EXPIRED =
  '로그인이 만료되어 이미지 목록을 불러오지 못했습니다. 새 탭에서 다시 로그인한 뒤 다시 불러와 주세요.';
export const IMAGE_NOT_IN_PRODUCT =
  '이 상품에서 빠진 이미지입니다. 이미지 목록을 다시 불러왔으니 다시 골라 주세요.';
export const IMAGE_UNUSABLE =
  '이 이미지는 사용할 수 없습니다. 상품 이미지로 다시 등록한 뒤 선택해 주세요.';
export const IMAGE_FORMAT = `JPEG · PNG · WebP 형식의 ${MAX_DRAFT_IMAGE_BYTES / 1024 / 1024}MB 이하 이미지만 사용할 수 있습니다.`;
export const IMAGE_STORAGE_UNAVAILABLE =
  '이미지를 확인하지 못했습니다. 잠시 후 다시 확인해 주세요.';
export const IMAGE_CHECK_SESSION_EXPIRED =
  '로그인이 만료되어 이미지를 확인하지 못했습니다. 새 탭에서 다시 로그인한 뒤 다시 확인해 주세요.';
export const IMAGE_CHANGED =
  '확인한 뒤 이미지가 바뀌어 요청하지 않았습니다. 고른 이미지를 다시 확인했으니 내용을 보고 다시 요청해 주세요.';
export const IMAGE_SELECTION = `사이즈표 이미지를 겹치지 않게 1~${MAX_DRAFT_IMAGES}장 골라 주세요.`;
export const IMAGE_RECHECK = '이미지를 다시 확인한 뒤 요청해 주세요.';
export const IMAGE_PERSONAL_INFO = '이미지에 고객 개인정보가 없는지 확인해 주세요.';

/** 후보 목록 읽기 실패 */
export type ImageSourcesFailure =
  | { kind: 'access'; denied: AccessDenied }
  /** 기능이 닫힌 환경 — 이미지 입력을 숨긴다 */
  | { kind: 'closed'; message: string }
  | { kind: 'retry'; message: string };

export function imageSourcesFailure(cause: unknown): ImageSourcesFailure {
  const denied = accessDeniedOf(cause);
  // 입력 중일 수 있어 로그인 화면으로 보내지 않는다
  if (denied === 'unauthorized') return { kind: 'retry', message: IMAGE_SOURCES_SESSION_EXPIRED };
  if (denied) return { kind: 'access', denied };
  if (cause instanceof ApiError) {
    if (cause.code === CODE('011')) return { kind: 'closed', message: IMAGE_INPUT_CLOSED };
    if (cause.kind === 'rateLimited') return { kind: 'retry', message: cause.message };
  }
  return { kind: 'retry', message: IMAGE_SOURCES_FAILED };
}

/** 이미지 하나 확인 실패 */
export type InspectionFailure =
  | { kind: 'access'; denied: AccessDenied }
  | { kind: 'closed'; message: string }
  /** 이 상품 후보가 아니다(그사이 빠짐) — 후보를 다시 읽는다 */
  | { kind: 'reloadSources'; message: string }
  /** 이 이미지는 쓸 수 없다 · 일시 장애 — 그 이미지 칸에 안내한다 */
  | { kind: 'image'; message: string };

export function inspectionFailure(cause: unknown): InspectionFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized') return { kind: 'image', message: IMAGE_CHECK_SESSION_EXPIRED };
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'image', message: IMAGE_STORAGE_UNAVAILABLE };
  switch (cause.code) {
    case CODE('011'):
      return { kind: 'closed', message: IMAGE_INPUT_CLOSED };
    case CODE('012'):
      return { kind: 'reloadSources', message: IMAGE_NOT_IN_PRODUCT };
    case CODE('013'):
      return { kind: 'image', message: IMAGE_UNUSABLE };
    case CODE('014'):
      return { kind: 'image', message: IMAGE_FORMAT };
  }
  if (cause.kind === 'rateLimited') return { kind: 'image', message: cause.message };
  return { kind: 'image', message: IMAGE_STORAGE_UNAVAILABLE };
}

/**
 * 생성 요청의 IMAGE 전용 실패. 해당하지 않으면 null(공통 처리로 넘긴다).
 * - 015(승인 뒤 바뀜)는 고른 이미지를 다시 확인하게 한다 — 새 확인값이면 지문이 바뀌어 새 키다.
 * - 112~115 는 화면이 막는 입력이라 오면 다시 확인 · 선택을 안내한다.
 */
export type ImageCreateFailure =
  | { kind: 'closed'; message: string }
  | { kind: 'reloadSources'; message: string }
  /** 선택이 화면 밖에서 어긋났다(112 · 113) — 선택을 비우고 목록을 다시 읽는다 */
  | { kind: 'resetSelection'; message: string }
  | { kind: 'reinspect'; message: string }
  | { kind: 'image'; message: string }
  /** 저장소 일시 장애(504) — 같은 키로 다시 보내면 된다 */
  | { kind: 'retry'; message: string };

export function imageCreateFailure(cause: unknown): ImageCreateFailure | null {
  if (!(cause instanceof ApiError)) return null;
  switch (cause.code) {
    case CODE('011'):
      return { kind: 'closed', message: IMAGE_INPUT_CLOSED };
    case CODE('012'):
      return { kind: 'reloadSources', message: IMAGE_NOT_IN_PRODUCT };
    case CODE('013'):
      return { kind: 'image', message: IMAGE_UNUSABLE };
    case CODE('014'):
      return { kind: 'image', message: IMAGE_FORMAT };
    case CODE('015'):
      return { kind: 'reinspect', message: IMAGE_CHANGED };
    case CODE('504'):
      return { kind: 'retry', message: IMAGE_STORAGE_UNAVAILABLE };
  }
  const codes = new Set(cause.fieldErrors.map((item) => item.code));
  if (codes.has(CODE('115'))) return { kind: 'image', message: IMAGE_PERSONAL_INFO };
  if (codes.has(CODE('112')) || codes.has(CODE('113')))
    return { kind: 'resetSelection', message: IMAGE_SELECTION };
  if (codes.has(CODE('114'))) return { kind: 'reinspect', message: IMAGE_RECHECK };
  return null;
}

/** 생성 요청이 돌려준 IMAGE 후속 동작 */
export type ImageFollowUp = {
  action: 'closed' | 'reloadSources' | 'resetSelection' | 'reinspect';
  message: string;
};

/**
 * 후속 동작 → 화면 처리. 안내는 한 곳에만 둔다 — 목록이 바뀌는 동작은 목록 안내로 옮기고 입력 칸 오류를 지운다.
 * 다시 확인은 입력 칸 오류(왜 다시 확인했는지)를 남기고 개인정보 확인 체크를 푼다.
 */
export type ImageFollowUpPlan = {
  clearSourceError: boolean;
  resetConfirmation: boolean;
  images:
    | { kind: 'close'; message: string }
    | { kind: 'reload'; message: string; clearSelection: boolean }
    | { kind: 'reinspect' };
};

export function imageFollowUpPlan(follow: ImageFollowUp): ImageFollowUpPlan {
  switch (follow.action) {
    case 'closed':
      return {
        clearSourceError: true,
        resetConfirmation: true,
        images: { kind: 'close', message: follow.message },
      };
    case 'reloadSources':
    case 'resetSelection':
      return {
        clearSourceError: true,
        resetConfirmation: true,
        images: {
          kind: 'reload',
          message: follow.message,
          clearSelection: follow.action === 'resetSelection',
        },
      };
    case 'reinspect':
      return { clearSourceError: false, resetConfirmation: true, images: { kind: 'reinspect' } };
  }
}
