import { periodLabel } from './text';
import { isSectionType, type DisplaySectionAdmin, type SectionTypeCode } from './types';

/** 목록 · 안내 문구에 쓰는 섹션 이름 — 제목, 없으면 유형 이름 */
export function sectionName(section: DisplaySectionAdmin): string {
  return section.title ?? `${section.sectionType.name} (제목 없음)`;
}

/** 아이템 요약 — `상품 5개` · `이미지 · 영상 2개` */
export function itemSummary(section: DisplaySectionAdmin): string {
  const media = section.items.filter((item) => item.media).length;
  const products = section.items.filter((item) => item.product).length;
  const parts: string[] = [];
  if (media > 0) parts.push(`이미지 · 영상 ${media}개`);
  if (products > 0) parts.push(`상품 ${products}개`);
  return parts.length > 0 ? parts.join(' · ') : '항목 없음';
}

/**
 * 이 화면에서 내용을 고칠 수 있는 유형. 모르는 유형은 순서 · 노출만 바꾼다 — 폼이 그 유형의 규칙을 모른다.
 */
export function editableType(section: DisplaySectionAdmin): SectionTypeCode | null {
  const code = section.sectionType.code;
  return isSectionType(code) ? code : null;
}

export function sectionPeriod(section: DisplaySectionAdmin): string {
  return periodLabel(section.startAt, section.endAt);
}

/** 순서 바꾸기 안내(드래그 · 키보드) — 스크린리더에 읽히는 문장 */
export const DRAG_INSTRUCTIONS =
  '스페이스 또는 엔터로 섹션을 집고, 위 · 아래 화살표로 옮긴 뒤 스페이스 또는 엔터로 놓습니다. Esc 로 취소합니다.';

/** 드래그 중 스크린리더 안내 — 자리는 1부터 센다 */
export const dragAnnouncements = {
  start: (name: string) => `${name} 섹션을 집었습니다.`,
  over: (name: string, position: number) =>
    `${name} 섹션을 ${position}번째 자리로 옮기는 중입니다.`,
  end: (name: string, position: number) =>
    `${name} 섹션을 ${position}번째 자리에 놓았습니다. 순서 저장을 눌러야 메인에 반영됩니다.`,
  cancel: (name: string) => `${name} 섹션 옮기기를 취소했습니다.`,
};
