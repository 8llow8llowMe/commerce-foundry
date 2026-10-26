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
