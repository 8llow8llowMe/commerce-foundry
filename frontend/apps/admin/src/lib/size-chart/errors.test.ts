import { ApiError, type ApiErrorKind, type FieldErrorItem } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { SAVE_FAILED, SESSION_EXPIRED_ON_SAVE } from '@/lib/api-errors';
import {
  BUSY,
  CHART_CONFLICT_NOTICE,
  chartSaveFailure,
  CHECK_FIELDS,
  DELETE_FAILED,
  PICKED_TEMPLATE_GONE,
  TEMPLATE_GONE_NOTICE,
  TEMPLATE_IN_USE_DELETE,
  TEMPLATE_IN_USE_STRUCTURE,
  templateDeleteFailure,
  templateSaveFailure,
  UNKNOWN_OUTCOME_NOTICE,
} from './errors';
import {
  cellKey,
  COLUMN_NAME_DUPLICATED,
  labelKey,
  REJECTED_CHARACTER,
  SIZE_LABEL_DUPLICATED,
  VALUE_RULE,
} from './form';
import type { TemplateColumn } from './types';

const apiError = (
  kind: ApiErrorKind,
  status: number | null,
  code: string | null,
  fieldErrors: FieldErrorItem[] = [],
) => new ApiError(kind, status, code, '서버 원문', fieldErrors);

const COLUMNS: TemplateColumn[] = [
  { columnId: '11', name: '가슴 단면', description: null, displayOrder: 0 },
  { columnId: '12', name: '총장', description: null, displayOrder: 1 },
];

describe('templateSaveFailure', () => {
  it('401 은 폼을 지키고, 403 은 권한 안내다', () => {
    expect(templateSaveFailure(apiError('unauthorized', 401, null))).toEqual({
      kind: 'errors',
      errors: { form: [SESSION_EXPIRED_ON_SAVE] },
    });
    expect(templateSaveFailure(apiError('forbidden', 403, null))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
  });

  it('fieldErrors 는 경로로 칸을 찾아 업무 문구를 붙이고 요약을 둔다', () => {
    const failure = templateSaveFailure(
      apiError('validation', 400, 'PRODUCT_100', [
        { field: 'columns[1].name', code: 'PRODUCT_178', message: 'x' },
        { field: 'name', code: 'PRODUCT_999', message: 'x' },
      ]),
    );
    expect(failure).toEqual({
      kind: 'errors',
      errors: {
        'columns.1.name': [REJECTED_CHARACTER],
        name: [CHECK_FIELDS],
        form: [CHECK_FIELDS],
      },
    });
  });

  it('쓰는 중 구조 변경(062) · 잠금(072) · 없는 템플릿(059) · 결과 모름', () => {
    expect(templateSaveFailure(apiError('conflict', 409, 'PRODUCT_062'))).toEqual({
      kind: 'errors',
      errors: { form: [TEMPLATE_IN_USE_STRUCTURE] },
    });
    expect(templateSaveFailure(apiError('conflict', 409, 'PRODUCT_072'))).toEqual({
      kind: 'errors',
      errors: { form: [BUSY] },
    });
    expect(templateSaveFailure(apiError('notFound', 404, 'PRODUCT_059'))).toEqual({
      kind: 'reload',
      notice: TEMPLATE_GONE_NOTICE,
    });
    expect(templateSaveFailure(apiError('unknownOutcome', 504, null))).toEqual({
      kind: 'reload',
      notice: UNKNOWN_OUTCOME_NOTICE,
    });
  });

  it('모르는 실패는 서버 원문 대신 일반 문구다', () => {
    expect(templateSaveFailure(apiError('server', 500, 'X'))).toEqual({
      kind: 'errors',
      errors: { form: [SAVE_FAILED] },
    });
    expect(templateSaveFailure(new Error('boom'))).toEqual({
      kind: 'errors',
      errors: { form: [SAVE_FAILED] },
    });
  });
});

describe('templateDeleteFailure', () => {
  it('쓰는 중(063)은 안내, 401 은 로그인 안내 상태다', () => {
    expect(templateDeleteFailure(apiError('conflict', 409, 'PRODUCT_063'))).toEqual({
      kind: 'message',
      message: TEMPLATE_IN_USE_DELETE,
    });
    expect(templateDeleteFailure(apiError('unauthorized', 401, null))).toEqual({
      kind: 'access',
      denied: 'unauthorized',
    });
    expect(templateDeleteFailure(apiError('server', 500, null))).toEqual({
      kind: 'message',
      message: DELETE_FAILED,
    });
  });
});

describe('chartSaveFailure', () => {
  it('칸 경로 rows[i].cells[j] 는 보낸 항목 순서로 칸을 찾는다', () => {
    const failure = chartSaveFailure(
      apiError('validation', 400, 'PRODUCT_100', [
        { field: 'rows[1].cells[1].value', code: 'PRODUCT_197', message: 'x' },
        { field: 'rows[0].sizeLabel', code: 'PRODUCT_190', message: 'x' },
      ]),
      COLUMNS,
    );
    expect(failure.kind).toBe('errors');
    if (failure.kind !== 'errors') return;
    expect(failure.errors[cellKey(1, '12')]).toEqual([VALUE_RULE]);
    expect(failure.errors[labelKey(0)]).toHaveLength(1);
  });

  it('항목 불일치(066~068)는 템플릿을 다시 읽고, 상품 표 경합(073)은 표를 다시 읽는다', () => {
    for (const code of ['PRODUCT_066', 'PRODUCT_067', 'PRODUCT_068']) {
      expect(chartSaveFailure(apiError('server', 400, code), COLUMNS)).toEqual({
        kind: 'templateChanged',
      });
    }
    expect(chartSaveFailure(apiError('conflict', 409, 'PRODUCT_073'), COLUMNS)).toEqual({
      kind: 'reload',
      notice: CHART_CONFLICT_NOTICE,
    });
  });

  it('상품 없음(064) · 템플릿 없음(065)', () => {
    expect(chartSaveFailure(apiError('notFound', 404, 'PRODUCT_064'), COLUMNS)).toEqual({
      kind: 'productGone',
    });
    expect(chartSaveFailure(apiError('server', 400, 'PRODUCT_065'), COLUMNS)).toEqual({
      kind: 'errors',
      errors: { templateId: [PICKED_TEMPLATE_GONE], form: [CHECK_FIELDS] },
    });
  });

  it('401 은 폼을 지키고, 사이즈 이름 중복(069) · 잠금(072) · 요청 제한은 폼 안내다', () => {
    expect(chartSaveFailure(apiError('unauthorized', 401, null), COLUMNS)).toEqual({
      kind: 'errors',
      errors: { form: [SESSION_EXPIRED_ON_SAVE] },
    });
    expect(chartSaveFailure(apiError('server', 400, 'PRODUCT_069'), COLUMNS)).toEqual({
      kind: 'errors',
      errors: { rows: [SIZE_LABEL_DUPLICATED], form: [CHECK_FIELDS] },
    });
    expect(chartSaveFailure(apiError('conflict', 409, 'PRODUCT_072'), COLUMNS)).toEqual({
      kind: 'errors',
      errors: { form: [BUSY] },
    });
    const limited = new ApiError('rateLimited', 429, 'GATEWAY_006', '요청이 너무 많습니다.', []);
    expect(chartSaveFailure(limited, COLUMNS)).toEqual({
      kind: 'errors',
      errors: { form: ['요청이 너무 많습니다.'] },
    });
  });
});

describe('templateSaveFailure 칸 경로 · 060', () => {
  it('항목 이름 중복(060)은 항목 묶음, 칸을 특정하지 못한 경로는 묶음 · 폼 전체다', () => {
    expect(templateSaveFailure(apiError('server', 400, 'PRODUCT_060'))).toEqual({
      kind: 'errors',
      errors: { columns: [COLUMN_NAME_DUPLICATED], form: [CHECK_FIELDS] },
    });
    const failure = templateSaveFailure(
      apiError('validation', 400, 'PRODUCT_100', [
        { field: 'columns[0].displayOrder', code: 'PRODUCT_182', message: 'x' },
        { field: 'baseUnit', code: 'PRODUCT_173', message: 'x' },
      ]),
    );
    expect(failure).toEqual({
      kind: 'errors',
      errors: { columns: [CHECK_FIELDS], form: [CHECK_FIELDS] },
    });
  });
});

describe('templateDeleteFailure 나머지 분기', () => {
  it('없는 템플릿 · 결과 모름은 다시 읽고, 잠금은 안내다', () => {
    expect(templateDeleteFailure(apiError('notFound', 404, 'PRODUCT_059'))).toEqual({
      kind: 'reload',
      notice: TEMPLATE_GONE_NOTICE,
    });
    expect(templateDeleteFailure(apiError('unknownOutcome', 502, null))).toEqual({
      kind: 'reload',
      notice: UNKNOWN_OUTCOME_NOTICE,
    });
    expect(templateDeleteFailure(apiError('conflict', 409, 'PRODUCT_072'))).toEqual({
      kind: 'message',
      message: BUSY,
    });
  });
});
