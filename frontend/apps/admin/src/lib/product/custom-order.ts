import type { FormErrors } from '@/lib/api-errors';
import type { AdminProductDetail } from './detail';
import { addError, INTEGER, isPlainText } from './form-text';

// 어드민 상품 맞춤 제작 설정 수정(#29 · BE #580 PR-B) — `PUT /api/v1/admin/products/{productId}/custom-order`.
// 사용 여부 · 안내 문구 · 제작 소요일 · 입력 항목 전체 교체다. 항목은 `fieldKey` 로 구분한다 — 같은 키는 같은 항목,
// 빠진 키는 삭제, 새 키는 추가. 저장한 키는 고객 입력 · 주문이 가리키므로 바꾸지 않는다(지우고 새로 더한다).
// 꺼도 항목은 남는다. 칸 규칙 정본은 BE `ProductCustomOrderUpdateRequest` · `CustomOrderFieldRequest`, 정의 규칙 위반은 400 `PRODUCT_011`.

export const NOTICE_MAX = 500;
export const LEAD_TIME_MAX = 365;
export const FIELDS_MAX = 20;
export const FIELD_KEY_MAX = 30;
export const LABEL_MAX = 50;
export const UNIT_MAX = 10;
export const HELP_TEXT_MAX = 200;
export const TEXT_LENGTH_MAX = 500;
/** 숫자 입력의 상한(미만) — 주문 입력이 1억 이상을 받지 않는다 */
const NUMBER_LIMIT = 100_000_000;

export type CustomInputType = 'NUMBER' | 'TEXT';
export const INPUT_TYPE_LABELS: Record<CustomInputType, string> = {
  NUMBER: '숫자',
  TEXT: '글자',
};

/** 항목 한 줄. `saved` 면 서버에 있는 키라 고칠 수 없다. `id` 는 화면 key 용 */
export type CustomFieldForm = {
  id: string;
  saved: boolean;
  fieldKey: string;
  label: string;
  inputType: CustomInputType;
  unit: string;
  required: boolean;
  minValue: string;
  maxValue: string;
  stepValue: string;
  helpText: string;
};

export type CustomOrderForm = {
  enabled: boolean;
  notice: string;
  leadTimeDays: string;
  fields: CustomFieldForm[];
};

export type CustomOrderFieldBody = {
  fieldKey: string;
  label: string;
  inputType: CustomInputType;
  unit: string | null;
  required: boolean;
  minValue: number | null;
  maxValue: number | null;
  stepValue: number | null;
  helpText: string | null;
};

/** 등록 본문 `customOrder`(BE `CustomOrderSettingsRequest`)와 수정 본문에서 `version` 을 뺀 것 */
export type CustomOrderBody = {
  enabled: boolean;
  notice: string | null;
  leadTimeDays: number | null;
  fields: CustomOrderFieldBody[];
};

export type CustomOrderRequest = CustomOrderBody & { version: number };

/** 등록 화면의 처음 값 — 꺼짐 · 항목 없음(본문에서 빼는 것과 같다) */
export const emptyCustomOrderForm = (): CustomOrderForm => ({
  enabled: false,
  notice: '',
  leadTimeDays: '',
  fields: [],
});

/** 처음 값 그대로인가 — 등록 본문에서 `customOrder` 를 뺀다 */
export const isDefaultCustomOrder = (form: CustomOrderForm) =>
  !form.enabled &&
  form.notice.trim() === '' &&
  form.leadTimeDays.trim() === '' &&
  form.fields.length === 0;

const text = (value: number | null) => (value === null ? '' : String(value));

export function customOrderFormOf(product: AdminProductDetail): CustomOrderForm {
  return {
    enabled: product.customOrder,
    notice: product.customOrderNotice ?? '',
    leadTimeDays: text(product.customOrderLeadTimeDays),
    fields: product.customOrderFields.map((field) => ({
      id: field.fieldKey,
      saved: true,
      fieldKey: field.fieldKey,
      label: field.label,
      inputType: field.inputType.code === 'TEXT' ? 'TEXT' : 'NUMBER',
      unit: field.unit ?? '',
      required: field.required,
      minValue: text(field.minValue),
      maxValue: text(field.maxValue),
      stepValue: text(field.stepValue),
      helpText: field.helpText ?? '',
    })),
  };
}

export const emptyCustomField = (id: string): CustomFieldForm => ({
  id,
  saved: false,
  fieldKey: '',
  label: '',
  inputType: 'NUMBER',
  unit: '',
  required: true,
  minValue: '',
  maxValue: '',
  stepValue: '',
  helpText: '',
});

const FIELD_KEY = /^[a-z][a-z0-9_]{0,29}$/;
const DECIMAL = /^\d+(\.\d{1,2})?$/;
// BE `CustomOrderSettingsRequest.NOTICE_TEXT` — 줄바꿈(CR · LF)만 더 허용한다
const noticeText = (value: string) => isPlainText(value.replace(/[\r\n]/g, ''));

export const NOTICE_INVALID = `안내 문구는 탭 · 보이지 않는 문자 없이 ${NOTICE_MAX}자 이하로 입력해 주세요(줄바꿈은 됩니다).`;
export const LEAD_TIME_INVALID = `제작 소요일은 1~${LEAD_TIME_MAX} 사이의 정수입니다. 모르면 비워 두세요.`;
export const FIELDS_TOO_MANY = `입력 항목은 ${FIELDS_MAX}개까지입니다.`;
export const FIELD_KEY_INVALID = `키는 영문 소문자로 시작하는 소문자 · 숫자 · 밑줄 ${FIELD_KEY_MAX}자 이하입니다(예: chest).`;
export const FIELD_KEY_DUPLICATED = '같은 키의 항목이 이미 있습니다.';
export const LABEL_INVALID = `이름은 줄바꿈 · 탭 · 보이지 않는 문자 없이 1~${LABEL_MAX}자로 입력해 주세요.`;
export const UNIT_INVALID = `단위는 ${UNIT_MAX}자 이하입니다.`;
export const HELP_TEXT_INVALID = `도움말은 줄바꿈 · 탭 · 보이지 않는 문자 없이 ${HELP_TEXT_MAX}자 이하입니다.`;
export const NUMBER_INVALID = '0 이상, 소수 둘째 자리까지, 1억 미만의 숫자로 입력해 주세요.';
export const MIN_OVER_MAX = '최소값이 최대값보다 큽니다.';
export const STEP_NOT_POSITIVE = '증분은 0보다 커야 합니다.';
export const TEXT_MAX_INVALID = `최대 글자 수는 1~${TEXT_LENGTH_MAX} 사이의 정수입니다.`;

/** 요청 검증 코드(`PRODUCT_EDIT_1xx`) → 문구 */
export const CUSTOM_ORDER_VALIDATION: Record<string, string> = {
  PRODUCT_EDIT_105: NOTICE_INVALID,
  PRODUCT_EDIT_106: LEAD_TIME_INVALID,
  PRODUCT_EDIT_107: FIELDS_TOO_MANY,
  PRODUCT_EDIT_108: FIELD_KEY_INVALID,
  PRODUCT_EDIT_109: LABEL_INVALID,
};

/** 비면 null, 숫자가 아니면 NaN */
function decimalOf(raw: string): number | null {
  const value = raw.trim();
  if (value === '') return null;
  if (!DECIMAL.test(value)) return Number.NaN;
  const number = Number(value);
  return number < NUMBER_LIMIT ? number : Number.NaN;
}

const blankToNull = (value: string) => (value.trim() === '' ? null : value.trim());

export type BuildCustomOrder =
  { ok: true; request: CustomOrderRequest } | { ok: false; errors: FormErrors };

export type BuildCustomOrderBody =
  { ok: true; body: CustomOrderBody } | { ok: false; errors: FormErrors };

/** 폼 → PUT 본문. 칸 오류 키는 서버 `fieldErrors`(`fields[0].label`)와 같은 `fields.0.label` 이다 */
export function customOrderRequest(form: CustomOrderForm, version: number): BuildCustomOrder {
  const built = customOrderBody(form);
  return built.ok ? { ok: true, request: { version, ...built.body } } : built;
}

/**
 * 폼 → 본문(버전 없음). `prefix` 는 칸 오류 키 앞에 붙는다 — 등록 본문 안에서는 `customOrder.` 라
 * 서버 `fieldErrors`(`customOrder.fields[0].label`)와 같은 `customOrder.fields.0.label` 이 된다.
 */
export function customOrderBody(form: CustomOrderForm, prefix = ''): BuildCustomOrderBody {
  const errors: FormErrors = {};
  const add = (key: string, message: string) => addError(errors, `${prefix}${key}`, message);
  const notice = form.notice.trim();
  if (notice.length > NOTICE_MAX || !noticeText(notice)) add('notice', NOTICE_INVALID);

  let leadTimeDays: number | null = null;
  const lead = form.leadTimeDays.trim();
  if (lead !== '') {
    const days = Number(lead);
    if (!INTEGER.test(lead) || days < 1 || days > LEAD_TIME_MAX) {
      add('leadTimeDays', LEAD_TIME_INVALID);
    } else leadTimeDays = days;
  }

  if (form.fields.length > FIELDS_MAX) add('fields', FIELDS_TOO_MANY);
  const keys = new Set<string>();
  const fields = form.fields.map((field, index): CustomOrderFieldBody => {
    const at = (name: string) => `fields.${index}.${name}`;
    const fieldKey = field.fieldKey.trim();
    if (!FIELD_KEY.test(fieldKey)) add(at('fieldKey'), FIELD_KEY_INVALID);
    else if (keys.has(fieldKey)) add(at('fieldKey'), FIELD_KEY_DUPLICATED);
    keys.add(fieldKey);

    const label = field.label.trim();
    if (label === '' || label.length > LABEL_MAX || !isPlainText(label)) {
      add(at('label'), LABEL_INVALID);
    }
    const unit = field.unit.trim();
    if (unit.length > UNIT_MAX || !isPlainText(unit)) add(at('unit'), UNIT_INVALID);
    const helpText = field.helpText.trim();
    if (helpText.length > HELP_TEXT_MAX || !isPlainText(helpText)) {
      add(at('helpText'), HELP_TEXT_INVALID);
    }

    let minValue: number | null = null;
    let maxValue: number | null = null;
    let stepValue: number | null = null;
    if (field.inputType === 'TEXT') {
      // 글자 입력은 최대 글자 수만 뜻이 있다 — 최소 · 증분은 보내지 않는다
      const raw = field.maxValue.trim();
      if (raw !== '') {
        const length = Number(raw);
        if (!INTEGER.test(raw) || length < 1 || length > TEXT_LENGTH_MAX) {
          add(at('maxValue'), TEXT_MAX_INVALID);
        } else maxValue = length;
      }
    } else {
      minValue = decimalOf(field.minValue);
      maxValue = decimalOf(field.maxValue);
      stepValue = decimalOf(field.stepValue);
      if (Number.isNaN(minValue)) add(at('minValue'), NUMBER_INVALID);
      if (Number.isNaN(maxValue)) add(at('maxValue'), NUMBER_INVALID);
      if (Number.isNaN(stepValue)) add(at('stepValue'), NUMBER_INVALID);
      else if (stepValue === 0) add(at('stepValue'), STEP_NOT_POSITIVE);
      if (minValue !== null && maxValue !== null && minValue > maxValue) {
        add(at('minValue'), MIN_OVER_MAX);
      }
    }
    return {
      fieldKey,
      label,
      inputType: field.inputType,
      unit: blankToNull(unit),
      required: field.required,
      minValue,
      maxValue,
      stepValue,
      helpText: blankToNull(helpText),
    };
  });

  if (Object.keys(errors).length > 0) return { ok: false, errors };
  return {
    ok: true,
    body: { enabled: form.enabled, notice: blankToNull(notice), leadTimeDays, fields },
  };
}

/** 바뀐 것이 없나 — 본문(버전 제외)을 비교한다. 칸 오류가 있으면 다르다고 본다(저장을 눌러 오류를 보게) */
export function sameCustomOrder(a: CustomOrderForm, b: CustomOrderForm): boolean {
  const body = (form: CustomOrderForm) => {
    const built = customOrderRequest(form, 0);
    return built.ok ? JSON.stringify(built.request) : null;
  };
  const left = body(a);
  return left !== null && left === body(b);
}
