import { describe, expect, it } from 'vitest';
import {
  customOrderFormOf,
  customOrderRequest,
  emptyCustomField,
  FIELD_KEY_DUPLICATED,
  FIELD_KEY_INVALID,
  LEAD_TIME_INVALID,
  MIN_OVER_MAX,
  NUMBER_INVALID,
  sameCustomOrder,
  STEP_NOT_POSITIVE,
  TEXT_MAX_INVALID,
} from './custom-order';
import { adminProduct } from './test-fixtures';

const product = adminProduct({
  customOrder: true,
  customOrderNotice: '제작 2주',
  customOrderLeadTimeDays: 14,
  customOrderFields: [
    {
      fieldKey: 'chest',
      label: '가슴둘레',
      inputType: { code: 'NUMBER', name: '숫자', description: '' },
      unit: 'cm',
      required: true,
      minValue: 60,
      maxValue: 150,
      stepValue: 0.5,
      helpText: null,
      displayOrder: 0,
    },
  ],
});

describe('맞춤 제작 수정', () => {
  it('저장된 항목은 키를 잠그고 그대로 다시 보내면 같다', () => {
    const form = customOrderFormOf(product);
    expect(form.fields[0]).toMatchObject({ saved: true, fieldKey: 'chest', stepValue: '0.5' });
    expect(sameCustomOrder(form, customOrderFormOf(product))).toBe(true);
    expect(customOrderRequest(form, 4)).toEqual({
      ok: true,
      request: {
        version: 4,
        enabled: true,
        notice: '제작 2주',
        leadTimeDays: 14,
        fields: [
          {
            fieldKey: 'chest',
            label: '가슴둘레',
            inputType: 'NUMBER',
            unit: 'cm',
            required: true,
            minValue: 60,
            maxValue: 150,
            stepValue: 0.5,
            helpText: null,
          },
        ],
      },
    });
  });

  it('키 형식 · 중복, 숫자 범위 · 증분, 소요일을 막는다', () => {
    const form = customOrderFormOf(product);
    form.leadTimeDays = '0';
    form.fields = [
      { ...form.fields[0]!, minValue: '200', stepValue: '0' },
      { ...emptyCustomField('n1'), fieldKey: 'chest', label: '다시', maxValue: '1.234' },
      { ...emptyCustomField('n2'), fieldKey: 'Bad', label: '이름' },
    ];
    const built = customOrderRequest(form, 1);
    expect(built).toEqual({
      ok: false,
      errors: {
        leadTimeDays: [LEAD_TIME_INVALID],
        'fields.0.minValue': [MIN_OVER_MAX],
        'fields.0.stepValue': [STEP_NOT_POSITIVE],
        'fields.1.fieldKey': [FIELD_KEY_DUPLICATED],
        'fields.1.maxValue': [NUMBER_INVALID],
        'fields.2.fieldKey': [FIELD_KEY_INVALID],
      },
    });
  });

  it('글자 입력은 최대 글자 수만 보내고 1~500 정수여야 한다', () => {
    const form = customOrderFormOf(product);
    form.fields = [
      {
        ...emptyCustomField('t'),
        fieldKey: 'memo',
        label: '요청',
        inputType: 'TEXT',
        minValue: '3',
        maxValue: '100',
      },
    ];
    const built = customOrderRequest(form, 1);
    expect(built.ok && built.request.fields[0]).toMatchObject({
      minValue: null,
      maxValue: 100,
      stepValue: null,
    });
    form.fields[0]!.maxValue = '501';
    expect(customOrderRequest(form, 1)).toEqual({
      ok: false,
      errors: { 'fields.0.maxValue': [TEXT_MAX_INVALID] },
    });
  });

  it('빈 안내 · 소요일은 null 로 지운다', () => {
    const form = customOrderFormOf(adminProduct());
    expect(customOrderRequest({ ...form, notice: '  ', leadTimeDays: '' }, 0)).toEqual({
      ok: true,
      request: { version: 0, enabled: false, notice: null, leadTimeDays: null, fields: [] },
    });
  });
});
