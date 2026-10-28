import { previewFailure, type PreviewFailure } from './errors';
import { draftRequest, unchangedRequest, type SectionForm } from './form';
import type { DisplaySectionAdmin, DisplaySectionRequest } from './types';

// 메인 전시 미리보기(#71) — 저장 전 편집 상태로 BE 미리보기(`POST /api/v1/admin/displays/preview`, #340)를 부르고,
// 받은 응답(고객 메인 응답과 같은 모양)을 스토어프론트 `/preview/main`(iframe)에 postMessage 로 넘긴다.
// 관리자 토큰 쿠키는 어드민 origin 에만 있어 스토어프론트가 이 API 를 부를 수 없다 — 어드민 BFF 가 부른다.
// 메시지 모양의 정본은 스토어프론트 `lib/display/preview.ts` 다(두 앱은 서로 import 하지 않아 상수 · 모양을 옮겨 둔다).

/** iframe → 어드민: 받을 준비가 됐다 */
export const PREVIEW_READY = 'cf:main-preview:ready';
/** 어드민 → iframe: BE 미리보기 응답 */
export const PREVIEW_DISPLAY = 'cf:main-preview:display';

/** 스토어프론트 미리보기 경로 — 기본 언어 */
export const PREVIEW_PATH = '/preview/main';

/** 새로 만드는 섹션의 임시 아이디(저장 전이라 서버 아이디가 없다) */
export const NEW_SECTION_ID = 'new';

/**
 * 미리보기 섹션 — 생성 · 수정 본문에 화면 아이디(저장 아이디 또는 `NEW_SECTION_ID`)를 더한 것.
 * 아이디는 화면 안에서만 쓰고 요청에는 싣지 않는다(`previewRequest`)
 */
export type PreviewSection = DisplaySectionRequest & { sectionId: string };

/**
 * BE 미리보기 응답 — 고객 메인 응답과 같은 모양이다. 어드민은 읽지 않고 그대로 넘긴다(모양 검사 · 그리기는 스토어프론트).
 * 응답의 `sectionId` · `itemId`(`preview-section-0` 등)는 임시 값이라 편집 상태 · 저장 요청에 섞지 않는다
 */
export type DisplayPreviewResponse = { sections: unknown[] };

export type PreviewDisplayMessage = {
  type: typeof PREVIEW_DISPLAY;
  display: DisplayPreviewResponse;
};

/** 미리보기 요청 본문 — 배열 순서가 노출 순서다. 화면 아이디는 뺀다 */
export function previewRequest(sections: readonly PreviewSection[]): {
  sections: DisplaySectionRequest[];
} {
  return {
    sections: sections.map((section) => {
      const request: DisplaySectionRequest & { sectionId?: string } = { ...section };
      delete request.sectionId;
      return request;
    }),
  };
}

const isPreviewResponse = (value: unknown): value is DisplayPreviewResponse =>
  typeof value === 'object' &&
  value !== null &&
  Array.isArray((value as { sections?: unknown }).sections);

export type PreviewOutcome =
  { kind: 'ok'; display: DisplayPreviewResponse } | { kind: 'failed'; failure: PreviewFailure };

/**
 * 미리보기 요청을 차례로 보낸다 — 새 요청을 보내면 앞 요청을 취소하고, 늦게 온 앞 응답(성공 · 실패 모두)은 버린다(null).
 * 그래서 화면은 늘 마지막으로 보낸 편집 상태의 결과만 본다. `cancel` 은 화면을 닫을 때 남은 요청을 버린다.
 */
export function createPreviewLoader(
  fetchPreview: (
    body: { sections: DisplaySectionRequest[] },
    signal: AbortSignal,
  ) => Promise<unknown>,
) {
  let latest = 0;
  let controller: AbortController | null = null;
  return {
    async load(sections: readonly PreviewSection[]): Promise<PreviewOutcome | null> {
      const sequence = ++latest;
      controller?.abort();
      const current = new AbortController();
      controller = current;
      try {
        const display = await fetchPreview(previewRequest(sections), current.signal);
        if (sequence !== latest) return null;
        return isPreviewResponse(display)
          ? { kind: 'ok', display }
          : { kind: 'failed', failure: previewFailure(null, sections) };
      } catch (cause) {
        if (sequence !== latest || current.signal.aborted) return null;
        return { kind: 'failed', failure: previewFailure(cause, sections) };
      }
    },
    cancel() {
      latest++;
      controller?.abort();
      controller = null;
    },
  };
}

export type PreviewLoader = Pick<ReturnType<typeof createPreviewLoader>, 'load' | 'cancel'>;

/**
 * 미리보기 패널의 연결 — 로더 · 실패 안내 · iframe 전송을 묶는다(컴포넌트는 이벤트를 넘기기만 한다).
 * - `update`: 편집 상태로 미리보기를 부른다(묶음 지연은 호출자). 성공하면 응답을 기억하고 안내를 지우고 보낸다.
 *   실패하면 마지막 성공 응답을 그대로 두고(다시 보내지 않는다) 안내를 세운다. 늦게 온 앞 응답은 로더가 버린다
 * - `ready`: iframe 이 준비를 알렸다 — 마지막 성공 응답이 있으면 보낸다. 그 전에는 보내지 않는다
 * - `frameReset`: iframe 을 다시 연다 — 다음 준비 알림까지 보내지 않는다
 * - `retry`: 마지막으로 부른 편집 상태로 다시 부른다(429 면 막아 둔 시간 동안은 부르지 않는다)
 * - `close`: 남은 요청을 버린다
 */
export function createPreviewSession({
  loader,
  post,
  onFailure,
  now = Date.now,
}: {
  loader: PreviewLoader;
  post: (message: PreviewDisplayMessage) => void;
  onFailure: (failure: PreviewFailure | null) => void;
  now?: () => number;
}) {
  let display: DisplayPreviewResponse | null = null;
  let isReady = false;
  let lastSections: readonly PreviewSection[] | null = null;
  let retryAt = 0;

  const send = () => {
    if (isReady && display) post({ type: PREVIEW_DISPLAY, display });
  };

  const update = async (sections: readonly PreviewSection[]): Promise<void> => {
    lastSections = sections;
    const outcome = await loader.load(sections);
    if (!outcome) return;
    if (outcome.kind === 'failed') {
      const { cooldownSec } = outcome.failure;
      retryAt = cooldownSec === null ? 0 : now() + cooldownSec * 1000;
      onFailure(outcome.failure);
      return;
    }
    retryAt = 0;
    display = outcome.display;
    onFailure(null);
    send();
  };

  return {
    update,
    ready() {
      isReady = true;
      send();
    },
    frameReset() {
      isReady = false;
    },
    async retry(): Promise<void> {
      if (lastSections === null || now() < retryAt) return;
      await update(lastSections);
    },
    close() {
      loader.cancel();
    },
  };
}

/**
 * 받은 메시지가 우리가 연 iframe 의 준비 알림인가 — 스토어프론트 origin 과 그 iframe 창이 보낸 것만 믿는다
 * (다른 창 · 다른 origin 이 보낸 같은 모양의 메시지에는 미리보기 응답을 보내지 않는다).
 */
export function isPreviewReady(
  event: Pick<MessageEvent, 'origin' | 'source' | 'data'>,
  origin: string,
  frame: MessageEventSource | null,
): boolean {
  if (event.origin !== origin || frame === null || event.source !== frame) return false;
  const data: unknown = event.data;
  return (
    typeof data === 'object' && data !== null && (data as { type?: unknown }).type === PREVIEW_READY
  );
}

/** 보는 폭 — 스토어프론트 메인의 모바일 · PC 기준 폭 */
export const PREVIEW_WIDTHS = { mobile: 390, desktop: 1440 } as const;
export type PreviewDevice = keyof typeof PREVIEW_WIDTHS;

/** 편집 중인 폼 — 고치는 섹션은 그 자리에, 새 섹션은 맨 뒤에(서버도 맨 뒤에 둔다) */
export type PreviewEditing =
  { mode: 'edit'; sectionId: string; form: SectionForm } | { mode: 'create'; form: SectionForm };

/**
 * 지금 화면의 편집 상태 → 미리보기 섹션. 순서는 화면 순서(저장하지 않은 순서 변경 포함)이고,
 * 저장된 섹션은 받은 값 그대로, 열린 폼은 저장 전 값(`draftRequest`)이다. 노출 판정(노출 여부 · 기간 · 숨긴 상품)은 BE 미리보기가 메인과 같게 한다.
 */
export function previewSections(
  ordered: readonly DisplaySectionAdmin[],
  editing: PreviewEditing | null,
): PreviewSection[] {
  const sections = ordered.map((section): PreviewSection =>
    editing?.mode === 'edit' && editing.sectionId === section.sectionId
      ? { sectionId: section.sectionId, ...draftRequest(editing.form) }
      : { sectionId: section.sectionId, ...unchangedRequest(section, {}) },
  );
  if (editing?.mode === 'create') {
    sections.push({ sectionId: NEW_SECTION_ID, ...draftRequest(editing.form) });
  }
  return sections;
}

/** origin 값(env) → 정규화한 origin. 비었거나 http(s) origin 이 아니면 null — 미리보기를 끈다 */
export function parseOrigin(value: string | undefined): string | null {
  if (!value) return null;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' || url.protocol === 'http:' ? url.origin : null;
  } catch {
    return null;
  }
}

/** 보는 폭을 칸 폭에 맞춰 줄이는 배율 — 칸이 더 넓으면 줄이지 않는다 */
export function previewScale(frameWidth: number, boxWidth: number): number {
  if (boxWidth <= 0) return 1;
  return Math.min(1, boxWidth / frameWidth);
}
