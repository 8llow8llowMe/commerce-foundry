import type { DisplayOrderRequest } from './types';

/** 목록에서 한 항목을 옮긴 새 배열. 범위 밖이면 그대로 돌려준다 */
export function moveItem<T>(list: readonly T[], from: number, to: number): T[] {
  if (from === to || from < 0 || to < 0 || from >= list.length || to >= list.length) {
    return [...list];
  }
  const next = [...list];
  const [moved] = next.splice(from, 1);
  next.splice(to, 0, moved as T);
  return next;
}

/** 두 순서가 같은가 — 바뀐 순서가 있을 때만 저장 버튼을 켠다 */
export function sameOrder(a: readonly string[], b: readonly string[]): boolean {
  return a.length === b.length && a.every((id, index) => id === b[index]);
}

/** 순서 변경 본문 — 지금 있는 섹션 아이디 전체를 새 순서대로(BE 는 누락 · 중복 · 모르는 아이디를 `PRODUCT_057` 로 거절한다) */
export function orderRequest(sectionIds: readonly string[]): DisplayOrderRequest {
  return { sectionIds: [...sectionIds] };
}
