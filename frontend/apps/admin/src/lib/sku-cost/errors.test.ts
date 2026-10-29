import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { LOAD_FAILED, SAVE_FAILED, SESSION_EXPIRED_ON_SAVE } from '@/lib/api-errors';
import {
  BUSY,
  CHECK_FIELDS,
  costFailure,
  CURRENCY_LOCKED,
  DUPLICATED,
  NOT_AFTER_LATEST,
  readFailure,
  RETROACTIVE,
  SKU_NOT_FOUND,
  UNKNOWN_OUTCOME,
} from './errors';
import { AMOUNT_NEGATIVE, REASON_TOO_LONG, START_IN_FUTURE, START_TOO_EARLY } from './form';

const business = (status: number, code: string) =>
  new ApiError(
    status === 404 ? 'notFound' : status === 409 ? 'conflict' : 'server',
    status,
    code,
    '서버 문구',
  );

describe('costFailure', () => {
  it('401 은 폼을 지키고 다시 로그인을 안내한다 · 403 은 접근 상태다', () => {
    expect(costFailure(new ApiError('unauthorized', 401, 'AUTH_001', null))).toEqual({
      kind: 'errors',
      errors: { form: [SESSION_EXPIRED_ON_SAVE] },
      reload: false,
    });
    expect(costFailure(new ApiError('forbidden', 403, 'SECURITY_003', null))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
  });

  it.each([
    ['SKU_COST_001', 400, 'effectiveFrom', START_IN_FUTURE, false],
    ['SKU_COST_002', 409, 'effectiveFrom', NOT_AFTER_LATEST, true],
    ['SKU_COST_003', 409, 'currency', CURRENCY_LOCKED, true],
    ['SKU_COST_004', 409, 'form', DUPLICATED, true],
    ['SKU_COST_005', 409, 'form', BUSY, false],
    ['SKU_COST_006', 409, 'effectiveFrom', RETROACTIVE, true],
    ['PRODUCT_002', 404, 'form', SKU_NOT_FOUND, false],
    ['SKU_COST_110', 400, 'form', SKU_NOT_FOUND, false],
    ['PRODUCT_100', 400, 'form', CHECK_FIELDS, false],
  ])('%s → %s 칸 업무 문구, 다시 읽기 %s', (code, status, key, message, reload) => {
    expect(costFailure(business(status, code))).toEqual({
      kind: 'errors',
      errors: { [key]: [message] },
      reload,
    });
  });

  it('검증 코드는 칸별 업무 문구로 · 모르는 코드는 경로로 칸을 찾는다', () => {
    const cause = new ApiError('validation', 400, 'PRODUCT_100', null, [
      {
        code: 'SKU_COST_104',
        field: 'amount.amount',
        message: 'amount.amount 는 0 이상이어야 합니다.',
      },
      { code: 'SKU_COST_107', field: 'reason', message: 'x' },
      { code: 'SKU_COST_109', field: 'effectiveFromInRange', message: 'x' },
      { code: 'SKU_COST_199', field: 'amount.currency', message: 'x' },
      { code: 'SKU_COST_198', field: 'somethingElse', message: 'x' },
    ]);
    expect(costFailure(cause)).toEqual({
      kind: 'errors',
      errors: {
        amount: [AMOUNT_NEGATIVE],
        reason: [REASON_TOO_LONG],
        effectiveFrom: [START_TOO_EARLY],
        currency: [CHECK_FIELDS],
        form: [CHECK_FIELDS],
      },
      reload: false,
    });
  });

  it('결과 모름(502 · 504)은 실패로 단정하지 않고 다시 읽는다', () => {
    expect(costFailure(new ApiError('unknownOutcome', 504, null, null))).toEqual({
      kind: 'errors',
      errors: { form: [UNKNOWN_OUTCOME] },
      reload: true,
    });
  });

  it('모르는 4xx 는 서버 문구, 5xx · 네트워크 밖 오류는 저장 실패 문구다', () => {
    expect(costFailure(business(400, 'PRODUCT_999'))).toMatchObject({
      errors: { form: ['서버 문구'] },
    });
    expect(costFailure(new ApiError('server', 500, 'PRODUCT_500', '서버 문구'))).toMatchObject({
      errors: { form: [SAVE_FAILED] },
    });
    expect(costFailure(new Error('x'))).toMatchObject({ errors: { form: [SAVE_FAILED] } });
  });
});

describe('readFailure', () => {
  it('없는 SKU 는 notFound, 401 · 403 은 접근 상태, 그 밖은 읽기 실패 문구다', () => {
    expect(readFailure(business(404, 'PRODUCT_002'))).toEqual({ kind: 'notFound' });
    expect(readFailure(business(400, 'SKU_COST_110'))).toEqual({ kind: 'notFound' });
    expect(readFailure(new ApiError('unauthorized', 401, null, null))).toEqual({
      kind: 'access',
      denied: 'unauthorized',
    });
    expect(readFailure(new ApiError('network', null, null, null))).toEqual({
      kind: 'failed',
      message: LOAD_FAILED,
    });
  });
});
