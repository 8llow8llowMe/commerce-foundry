import type { FormErrors } from '@/lib/api-errors';
import { LIMITS, type LegalDetail, type LegalSummary } from './types';

/** 편집기 입력. 저장 전까지 화면에만 있다 */
export type LegalForm = { title: string; body: string };

export const EMPTY_FORM: LegalForm = { title: '', body: '' };

export const formOf = (detail: LegalDetail | null): LegalForm =>
  detail ? { title: detail.revision.title, body: detail.revision.body } : EMPTY_FORM;

/** 최신 리비전과 입력이 다른가 — 다르면 저장 전 입력이다(게시는 저장한 최신 리비전만 할 수 있다) */
export function isDirty(form: LegalForm, base: LegalDetail | null): boolean {
  const saved = formOf(base);
  return form.title !== saved.title || form.body !== saved.body;
}

export const hasInput = (form: LegalForm) => form.title !== '' || form.body !== '';

export const TITLE_INVALID =
  '제목을 공백이 아닌 200자 이내로 입력해 주세요(줄바꿈 · 제어 문자 제외).';
export const BODY_INVALID =
  '본문을 공백이 아닌 50,000자 이내로 입력해 주세요(탭 · 줄바꿈 외 제어 문자 제외).';
export const PLACEHOLDER_LEFT =
  '{{...}} 로 표시된 빈칸이 남아 있어 게시할 수 없습니다. 실제 내용으로 바꾼 뒤 초안을 저장해 주세요.';

// BE 와 같은 기준 — Java `Character.isISOControl`(C0 · DEL · C1). 본문은 CR · LF · TAB 만 허용
const TITLE_CONTROL = /[\u0000-\u001f\u007f-\u009f]/;
const BODY_CONTROL = /[\u0000-\u0008\u000b\u000c\u000e-\u001f\u007f-\u009f]/;

/** 초안 저장 전 검사. 서버도 같은 규칙으로 다시 본다 */
export function draftErrors(form: LegalForm): FormErrors {
  const errors: FormErrors = {};
  const title = form.title.trim();
  if (title === '' || title.length > LIMITS.title || TITLE_CONTROL.test(title))
    errors.title = [TITLE_INVALID];
  if (form.body.trim() === '' || form.body.length > LIMITS.body || BODY_CONTROL.test(form.body))
    errors.body = [BODY_INVALID];
  return errors;
}

/**
 * 아직 바꾸지 않은 `{{...}}` 빈칸 — 나온 순서대로 한 번씩. 게시는 `{{` · `}}` 가 하나라도 남으면 거부된다(`LEGAL_DOCUMENT_022`).
 * 짝이 맞지 않는 `{{` · `}}` 는 `strayMarks` 로 따로 알린다.
 */
export function placeholdersOf(form: LegalForm): { tokens: string[]; strayMarks: boolean } {
  const text = `${form.title}\n${form.body}`;
  const tokens = [...new Set(Array.from(text.matchAll(/\{\{[^{}\n]{0,100}\}\}/g), (m) => m[0]))];
  const rest = text.replace(/\{\{[^{}\n]{0,100}\}\}/g, '');
  return { tokens, strayMarks: rest.includes('{{') || rest.includes('}}') };
}

export const hasPlaceholders = (form: LegalForm) => {
  const { tokens, strayMarks } = placeholdersOf(form);
  return tokens.length > 0 || strayMarks;
};

/**
 * 미리보기용 조각 — 빈칸(`{{...}}`)만 표시하고 나머지는 글자 그대로. 둘 다 React 텍스트로만 그린다(HTML 해석 없음).
 */
export function previewParts(text: string): { text: string; placeholder: boolean }[] {
  return text
    .split(/(\{\{[^{}\n]{0,100}\}\})/g)
    .filter((part) => part !== '')
    .map((part) => ({ text: part, placeholder: /^\{\{[^{}\n]{0,100}\}\}$/.test(part) }));
}

/** 공개 상태 한 줄 — 초안 저장은 공개되지 않고 게시만 공개된다는 것을 같이 알린다 */
export function publicStateText(summary: LegalSummary | null): string {
  if (!summary)
    return '아직 저장한 문서가 없습니다. 공개 페이지에는 "게시된 문서 없음" 으로 보입니다.';
  const { latestRevision, publishedRevision } = summary;
  if (publishedRevision === null)
    return `${latestRevision}번 초안까지 저장했습니다. 아직 게시하지 않아 고객에게 보이지 않습니다.`;
  if (publishedRevision === latestRevision)
    return `${publishedRevision}번이 공개 중입니다(최신 리비전).`;
  return `${publishedRevision}번이 공개 중입니다. 최신 ${latestRevision}번 초안은 게시 전이라 고객에게 보이지 않습니다.`;
}

/** 지금 최신 리비전을 게시할 수 있는가(게시 버튼을 둘지). 이유가 있으면 그 문구 */
export function publishBlocker(
  form: LegalForm,
  base: LegalDetail | null,
):
  | { kind: 'none' }
  | { kind: 'published' }
  | { kind: 'blocked'; reason: string }
  | { kind: 'ready' } {
  if (!base) return { kind: 'none' };
  if (base.document.publishedRevision === base.revision.revisionNumber && !isDirty(form, base))
    return { kind: 'published' };
  if (isDirty(form, base))
    return {
      kind: 'blocked',
      reason:
        '저장하지 않은 입력이 있습니다. 게시는 저장한 최신 초안만 할 수 있으니 먼저 초안을 저장해 주세요.',
    };
  if (hasPlaceholders(form)) return { kind: 'blocked', reason: PLACEHOLDER_LEFT };
  return { kind: 'ready' };
}
