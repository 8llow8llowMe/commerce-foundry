/**
 * 맞춤 제작 입력 value 의 입력 문자 규칙(BE #232, `backend/docs/order-flow-guide.md` §2-1).
 * BE 는 제어 문자(`\p{Cc}` — C0 · DEL · C1, NUL · 탭 · 개행 포함)나 짝 없는 서로게이트가 있으면 위치와 상관없이 400 `CART_115` 로 거절한다
 * (`NoControlCharactersValidator` — `Character.getType` 이 CONTROL 또는 SURROGATE). 판정은 그와 같다:
 * `u` 플래그 정규식은 코드 포인트 단위라 짝 있는 서로게이트(이모지)는 `\p{Cs}` 에 걸리지 않고 짝 없는 것만 걸린다.
 * 서식 문자(`\p{Cf}`, 이모지 ZWJ 등)는 막지 않는다.
 */
const REJECTED = /[\p{Cc}\p{Cs}]/u;
const REJECTED_ALL = /[\p{Cc}\p{Cs}]/gu;
/** 줄바꿈 · 탭 같은 공백 성격의 제어 문자 — 붙여넣은 여러 줄이 한 단어로 붙지 않게 공백 하나로 바꾼다 */
const BREAKS = /[\t\n\v\f\r\u0085]+/gu;

export function hasRejectedCharacters(value: string): boolean {
  return REJECTED.test(value);
}

/** BE 가 거절하는 문자를 지운다 — 탭 · 개행(연속이면 한 번)은 공백으로, 그 밖의 제어 문자 · 짝 없는 서로게이트는 없앤다 */
export function stripRejectedCharacters(value: string): string {
  if (!hasRejectedCharacters(value)) return value;
  return value.replace(BREAKS, ' ').replace(REJECTED_ALL, '');
}
