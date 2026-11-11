import type { DriveStep } from 'driver.js';
import type { Tour } from './tours';

/** `data-tour` 값 → 선택자 */
export const tourSelector = (target: string) => `[data-tour="${target}"]`;

/** driver.js 는 제목 · 설명을 innerHTML 로 넣는다 — 투어 문구는 평문이지만 그래도 이스케이프한다 */
export function escapeHtml(text: string): string {
  return text
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}

/**
 * 투어 → driver.js 단계. 없는 선택(`optional`) 요소를 가리키는 단계는 뺀다(빈 목록 · 템플릿을 고르기 전의 표 등).
 * 필수 요소가 하나라도 없으면(권한 없음 안내 · 불러오기 실패처럼 화면이 다르게 떴다) null — 시작하지 않고 본 것으로도 남기지 않는다.
 */
export function driveSteps(tour: Tour, exists: (target: string) => boolean): DriveStep[] | null {
  const missingRequired = tour.steps.some(
    (step) => step.target !== null && !step.optional && !exists(step.target),
  );
  if (missingRequired) return null;
  const present = tour.steps.filter((step) => step.target === null || exists(step.target));
  return present.map((step) => ({
    ...(step.target ? { element: tourSelector(step.target) } : {}),
    popover: {
      title: escapeHtml(step.title),
      description: escapeHtml(step.body),
      ...(step.side ? { side: step.side } : {}),
    },
  }));
}

/**
 * 운영자가 OS · 브라우저에서 동작 줄이기(`prefers-reduced-motion: reduce`)를 켰는지. `matchMedia` 가 없거나 실패하면
 * 켜지 않은 것으로 본다(라이브러리 기본 동작).
 */
export function prefersReducedMotion(
  win: { matchMedia?: Window['matchMedia'] } | undefined,
): boolean {
  try {
    return win?.matchMedia?.('(prefers-reduced-motion: reduce)').matches === true;
  } catch {
    return false;
  }
}

/**
 * 투어 움직임 옵션. 동작 줄이기면 단계 전환 · 오버레이 애니메이션을 끈다. 스크롤은 늘 즉시다 —
 * 부드러운 스크롤을 쓰면 스크롤이 끝나기 전에 팝오버 위치를 잡아 화면 아래 요소(상품 상세 SKU 표)에서 팝오버가 떨어져 뜬다(#29 시연).
 */
export function tourMotion(reduced: boolean): { animate: boolean; smoothScroll: false } {
  return { animate: !reduced, smoothScroll: false };
}
