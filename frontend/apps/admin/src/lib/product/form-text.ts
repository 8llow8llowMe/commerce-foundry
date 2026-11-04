import type { FormErrors } from '@/lib/api-errors';

// 상품 폼 칸 검사 공용(등록 · 섹션 수정 · 맞춤 제작). 다른 상품 모듈을 가져오지 않는다 —
// `create` ↔ `custom-order` 처럼 서로 가져오면 모듈을 읽는 순서에 따라 상수가 비어 보인다.

// BE `ProductValidationMessage.PLAIN_TEXT` — 제어 · 서식 · 대리 · 줄/문단 구분 문자 금지
const NOT_PLAIN = /[\p{Cc}\p{Cf}\p{Cs}\p{Zl}\p{Zp}]/u;
export const isPlainText = (text: string) => !NOT_PLAIN.test(text);
export const INTEGER = /^\d+$/;

/** 칸 오류를 모은다 — 같은 칸의 같은 문구는 한 번만 */
export function addError(errors: FormErrors, key: string, message: string) {
  errors[key] = [...new Set([...(errors[key] ?? []), message])];
}
