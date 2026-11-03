import type { FormErrors } from '@/lib/api-errors';
import { isoToLocalInput, localInputToIso } from '@/lib/display/text';
import {
  KNOWLEDGE_CATEGORIES,
  LIMITS,
  type KnowledgeCategoryCode,
  type KnowledgeContent,
  type KnowledgeDetail,
} from './types';

// 지식 내용 폼 → 요청 `content`. 입력 규칙은 BE `KnowledgeAdminRequests.Content` 와 같다(정본은 서버다).
// 화면은 같은 규칙으로 미리 막아 저장 버튼을 누른 뒤에야 알게 되는 일을 줄인다.

/** 폼 칸은 글자 그대로 둔다. 기간은 datetime-local 글자(이 컴퓨터 시간대) */
export type ContentForm = {
  title: string;
  body: string;
  category: KnowledgeCategoryCode | '';
  validFrom: string;
  validTo: string;
};

export const TITLE_REQUIRED = '제목을 입력해 주세요.';
export const TITLE_TOO_LONG = `제목은 ${LIMITS.title}자 이하로 입력해 주세요.`;
export const BODY_REQUIRED = '본문을 입력해 주세요.';
export const BODY_TOO_LONG = `본문은 ${LIMITS.body.toLocaleString('ko-KR')}자 이하로 입력해 주세요.`;
export const CATEGORY_REQUIRED = '카테고리를 골라 주세요.';
export const VALID_FROM_REQUIRED = '유효 시작 시각을 입력해 주세요.';
export const VALID_TO_FORMAT = '유효 종료 시각 형식이 올바르지 않습니다.';
export const PERIOD_INVERTED = '유효 종료는 시작보다 뒤여야 합니다.';

/** 새 문서 — 지금부터 무기한. 분 단위로 둔다(datetime-local) */
export function emptyContentForm(now: Date = new Date()): ContentForm {
  return {
    title: '',
    body: '',
    category: '',
    validFrom: isoToLocalInput(now.toISOString()),
    validTo: '',
  };
}

/** 최신 리비전 내용으로 채운 폼 — 초안 고치기 · 새 초안 만들기의 시작값 */
export function contentFormOf(detail: KnowledgeDetail): ContentForm {
  return {
    title: detail.title,
    body: detail.body,
    category: KNOWLEDGE_CATEGORIES.find((code) => code === detail.category.code) ?? '',
    validFrom: isoToLocalInput(detail.validFrom),
    validTo: isoToLocalInput(detail.validTo),
  };
}

/**
 * 폼 → 요청 내용. 어기면 칸 오류를 돌려주고 보내지 않는다.
 * 제목은 앞뒤 공백을 빼고 보낸다. 본문은 줄바꿈 · 들여쓰기가 뜻이라 그대로 보내고, 공백뿐이면 막는다(`@NotBlank`).
 * 길이는 Java String 길이(UTF-16 단위)로 센다 — JS `length` 와 같다.
 */
export function contentRequest(
  form: ContentForm,
): { kind: 'ok'; content: KnowledgeContent } | { kind: 'errors'; errors: FormErrors } {
  const errors: FormErrors = {};
  const title = form.title.trim();
  if (title === '') errors.title = [TITLE_REQUIRED];
  else if (title.length > LIMITS.title) errors.title = [TITLE_TOO_LONG];
  if (form.body.trim() === '') errors.body = [BODY_REQUIRED];
  else if (form.body.length > LIMITS.body) errors.body = [BODY_TOO_LONG];
  if (form.category === '') errors.category = [CATEGORY_REQUIRED];

  const validFrom = localInputToIso(form.validFrom);
  if (validFrom === null) errors.validFrom = [VALID_FROM_REQUIRED];
  let validTo: string | null = null;
  if (form.validTo !== '') {
    validTo = localInputToIso(form.validTo);
    if (validTo === null) errors.validTo = [VALID_TO_FORMAT];
    else if (validFrom !== null && Date.parse(validTo) <= Date.parse(validFrom))
      errors.validTo = [PERIOD_INVERTED];
  }

  if (Object.keys(errors).length > 0 || validFrom === null || form.category === '')
    return { kind: 'errors', errors };
  return {
    kind: 'ok',
    content: { title, body: form.body, category: form.category, validFrom, validTo },
  };
}
