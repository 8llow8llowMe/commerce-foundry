import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { SAVE_FAILED, SESSION_EXPIRED_ON_SAVE } from '@/lib/api-errors';
import { CHECK_FIELDS, CONFLICT_NOTICE, UNKNOWN_OUTCOME_NOTICE, writeFailure } from './errors';
import { LABEL_INVALID, URL_CUSTOM_HOST, URL_FORMAT } from './form';

const error = (
  status: number,
  code: string,
  kind: ApiError['kind'],
  fieldErrors: ApiError['fieldErrors'] = [],
) => new ApiError(kind, status, code, '서버 원문', fieldErrors);

describe('writeFailure', () => {
  it('401 은 입력을 지키고 다시 로그인을 안내한다, 403 은 권한 안내', () => {
    expect(writeFailure(error(401, 'AUTH_001', 'unauthorized'))).toEqual({
      kind: 'errors',
      errors: { form: [SESSION_EXPIRED_ON_SAVE] },
    });
    expect(writeFailure(error(403, 'AUTH_003', 'forbidden'))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
  });

  it('409 · 버전 · 리비전 오류는 다시 읽고, 결과 모름은 경합 없이 다시 읽는다', () => {
    for (const code of ['STORE_FOOTER_020', 'STORE_FOOTER_101', 'STORE_FOOTER_109'])
      expect(writeFailure(error(409, code, 'conflict'))).toEqual({
        kind: 'reload',
        notice: CONFLICT_NOTICE,
        conflict: true,
      });
    expect(writeFailure(error(504, 'GATEWAY_TIMEOUT', 'unknownOutcome'))).toEqual({
      kind: 'reload',
      notice: UNKNOWN_OUTCOME_NOTICE,
      conflict: false,
    });
  });

  it('fieldErrors 는 경로를 칸 키로 바꾸고 업무 문구를 쓴다', () => {
    const failure = writeFailure(
      error(400, 'STORE_FOOTER_100', 'validation', [
        { code: 'STORE_FOOTER_105', field: 'links[1].labels.en', message: '원문' },
        { code: 'STORE_FOOTER_106', field: 'links[0].url', message: '원문' },
      ]),
    );
    expect(failure).toEqual({
      kind: 'errors',
      errors: {
        'links.1.labels.en': [LABEL_INVALID],
        'links.0.url': [URL_FORMAT],
        form: [CHECK_FIELDS],
      },
    });
  });

  it('칸 정보 없는 URL 거절은 기타 링크 허용 도메인 안내다', () => {
    expect(writeFailure(error(400, 'STORE_FOOTER_106', 'validation'))).toEqual({
      kind: 'errors',
      errors: { form: [URL_CUSTOM_HOST] },
    });
  });

  it('그 밖은 저장 실패', () => {
    expect(writeFailure(new Error('boom'))).toEqual({
      kind: 'errors',
      errors: { form: [SAVE_FAILED] },
    });
    expect(writeFailure(error(500, 'X', 'server'))).toEqual({
      kind: 'errors',
      errors: { form: [SAVE_FAILED] },
    });
  });
});
