import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { SAVE_FAILED, SESSION_EXPIRED_ON_SAVE } from '@/lib/api-errors';
import { CHECK_FIELDS, policySaveFailure, withSummary } from './policy-errors';

describe('policySaveFailure', () => {
  it('401 은 폼을 지키고 다시 로그인을 안내하고, 403 은 권한 안내다', () => {
    expect(policySaveFailure(new ApiError('unauthorized', 401, 'GATEWAY_001', null))).toEqual({
      kind: 'errors',
      errors: { form: [SESSION_EXPIRED_ON_SAVE] },
    });
    expect(policySaveFailure(new ApiError('forbidden', 403, 'GATEWAY_002', null))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
  });

  it('409 DELIVERY_006 은 버전 충돌 — 최신 값을 다시 읽는다', () => {
    expect(policySaveFailure(new ApiError('conflict', 409, 'DELIVERY_006', '버전 충돌'))).toEqual({
      kind: 'conflict',
    });
  });

  it('검증 fieldErrors 는 서버 문구를 칸마다 붙인다', () => {
    const cause = new ApiError('validation', 400, 'DELIVERY_108', null, [
      {
        code: 'DELIVERY_108',
        field: 'remoteAreas[0].postalCodeFrom',
        message: '우편번호는 숫자 5자리',
      },
      { code: 'DELIVERY_102', field: 'baseFee.amount', message: '금액은 필수' },
      { code: 'DELIVERY_106', field: 'zones[0].countryCodes[1]', message: '국가 코드 형식' },
    ]);
    expect(policySaveFailure(cause)).toEqual({
      kind: 'errors',
      errors: {
        'remoteAreas.0.postalCodeFrom': ['우편번호는 숫자 5자리'],
        baseFee: ['금액은 필수'],
        'zones.0.countryCodes': ['국가 코드 형식'],
        form: [CHECK_FIELDS],
      },
    });
  });

  it('줄을 알려 주지 않는 도메인 오류는 그 묶음 칸에, 통화 변경 불가는 통화 칸에 붙인다', () => {
    const at = (code: string) =>
      policySaveFailure(
        new ApiError('server', code === 'DELIVERY_012' ? 409 : 400, code, '서버 문구'),
      );
    expect(at('DELIVERY_005')).toEqual({
      kind: 'errors',
      errors: { remoteAreas: ['서버 문구'], form: [CHECK_FIELDS] },
    });
    expect(at('DELIVERY_009')).toMatchObject({ errors: { zones: ['서버 문구'] } });
    expect(at('DELIVERY_003')).toEqual({ kind: 'errors', errors: { form: ['서버 문구'] } });
    expect(at('DELIVERY_012')).toMatchObject({ errors: { currency: ['서버 문구'] } });
  });

  it('폼이 그리지 않는 칸(request · 클래스 수준)의 오류는 폼 전체 오류로 모은다', () => {
    const cause = new ApiError('validation', 400, 'DELIVERY_100', null, [
      { code: 'DELIVERY_100', field: 'request', message: '요청 본문을 읽을 수 없습니다.' },
    ]);
    expect(policySaveFailure(cause)).toEqual({
      kind: 'errors',
      errors: { form: ['요청 본문을 읽을 수 없습니다.'] },
    });
  });

  it('502 · 504 는 결과를 모름 — 실패로 단정하지 않고 다시 읽는다', () => {
    expect(policySaveFailure(new ApiError('unknownOutcome', 504, null, null))).toEqual({
      kind: 'unknownOutcome',
    });
  });

  it('모르는 오류 · 연결 실패는 폼 전체 오류 한 문구다', () => {
    for (const cause of [
      new ApiError('server', 500, 'DELIVERY_999', '내부'),
      new ApiError('network', null, null, null),
      new Error('boom'),
    ]) {
      expect(policySaveFailure(cause)).toEqual({ kind: 'errors', errors: { form: [SAVE_FAILED] } });
    }
  });
});

describe('withSummary', () => {
  it('칸 오류만 있으면 요약을 더하고, 폼 전체 오류가 있거나 오류가 없으면 그대로 둔다', () => {
    expect(withSummary({ baseFee: ['x'] })).toEqual({ baseFee: ['x'], form: [CHECK_FIELDS] });
    expect(withSummary({ form: ['y'], baseFee: ['x'] })).toEqual({ form: ['y'], baseFee: ['x'] });
    expect(withSummary({})).toEqual({});
  });
});
