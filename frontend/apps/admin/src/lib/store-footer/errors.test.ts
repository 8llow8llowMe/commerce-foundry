import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { SAVE_FAILED, SESSION_EXPIRED_ON_SAVE } from '@/lib/api-errors';
import {
  CHECK_FIELDS,
  CONFLICT_NOTICE,
  publishNotAppliedNotice,
  publishOutcome,
  publishReloadedNotice,
  UNKNOWN_OUTCOME_NOTICE,
  writeFailure,
} from './errors';
import type { FooterDetail } from './types';
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

describe('publishOutcome', () => {
  const detail = (latestRevision: number, publishedRevision: number | null): FooterDetail => ({
    version: 7,
    latestRevision,
    publishedRevision,
    revision: {
      revisionNumber: latestRevision,
      copyrightText: { ko: null, en: null },
      links: [],
      createdBy: '1',
      createdAt: '2026-11-27T00:00:00Z',
      publishedBy: null,
      publishedAt: null,
    },
  });
  const conflict = error(409, 'STORE_FOOTER_020', 'conflict');

  it('게시 409 뒤 다시 읽은 공개 번호가 요청 번호와 같으면 성공이다', () => {
    expect(publishOutcome(conflict, detail(5, 5), 5)).toEqual({ kind: 'done' });
  });

  it('게시 409 뒤 다른 저장본이 최신이면 화면을 그 저장본으로 바꾼다(미저장 표시가 남지 않게)', () => {
    expect(publishOutcome(conflict, detail(6, 4), 5)).toEqual({
      kind: 'reload',
      notice: publishReloadedNotice(6),
      conflict: false,
      resetTree: true,
    });
  });

  it('결과 모름 뒤 공개되지 않았으면 같은 번호를 다시 게시하라고 안내한다', () => {
    expect(
      publishOutcome(error(504, 'GATEWAY_TIMEOUT', 'unknownOutcome'), detail(5, 4), 5),
    ).toEqual({
      kind: 'reload',
      notice: publishNotAppliedNotice(5),
      conflict: false,
      resetTree: true,
    });
  });

  it('다시 읽을 필요 없는 실패는 화면을 바꾸지 않는다', () => {
    expect(publishOutcome(error(401, 'AUTH_001', 'unauthorized'), null, 5)).toEqual({
      kind: 'errors',
      errors: { form: [SESSION_EXPIRED_ON_SAVE] },
      resetTree: false,
    });
  });
});
