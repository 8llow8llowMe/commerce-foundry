import { draftRequest, unchangedRequest, type SectionForm } from './form';
import type { DisplaySectionAdmin, DisplaySectionRequest } from './types';

// 메인 전시 미리보기(#71) — 저장 전 편집 상태를 스토어프론트 `/preview/main`(iframe)에 postMessage 로 넘긴다.
// 메시지 모양의 정본은 스토어프론트 `lib/display/preview.ts` 다(두 앱은 서로 import 하지 않아 상수 · 모양을 옮겨 둔다).

/** iframe → 어드민: 받을 준비가 됐다 */
export const PREVIEW_READY = 'cf:main-preview:ready';
/** 어드민 → iframe: 지금 편집 상태 */
export const PREVIEW_DRAFT = 'cf:main-preview:draft';

/** 스토어프론트 미리보기 경로 — 기본 언어 */
export const PREVIEW_PATH = '/preview/main';

/** 새로 만드는 섹션의 임시 아이디(저장 전이라 서버 아이디가 없다) */
export const NEW_SECTION_ID = 'new';

export type PreviewSection = DisplaySectionRequest & { sectionId: string };

export type PreviewDraft = { type: typeof PREVIEW_DRAFT; sections: PreviewSection[] };

/**
 * 받은 메시지가 우리가 연 iframe 의 준비 알림인가 — 스토어프론트 origin 과 그 iframe 창이 보낸 것만 믿는다
 * (다른 창 · 다른 origin 이 보낸 같은 모양의 메시지에는 편집 상태를 보내지 않는다).
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
 * 저장된 섹션은 받은 값 그대로, 열린 폼은 저장 전 값(`draftRequest`)이다. 노출 판정은 스토어프론트가 메인과 같게 한다.
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
