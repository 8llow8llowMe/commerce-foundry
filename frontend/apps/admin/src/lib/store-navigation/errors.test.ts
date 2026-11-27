import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { SAVE_FAILED, SESSION_EXPIRED_ON_SAVE } from '@/lib/api-errors';
import {
  CHECK_FIELDS,
  CONFLICT_NOTICE,
  PLACEMENT_INVALID,
  PUBLISH_HOST_REMOVED,
  publishNeedsReload,
  publishNotAppliedNotice,
  publishOutcome,
  publishReloadedNotice,
  UNKNOWN_OUTCOME_NOTICE,
  writeFailure,
} from './errors';
import { LABEL_INVALID, PATH_INVALID, TOO_DEEP, URL_HOST_NOT_ALLOWED } from './form';
import type { NavDetail, NavItemPayload } from './types';

const error = (
  status: number,
  code: string,
  kind: ApiError['kind'],
  fieldErrors: ApiError['fieldErrors'] = [],
) => new ApiError(kind, status, code, '서버 원문', fieldErrors);

const sent = (types: NavItemPayload['type'][]): NavItemPayload[] =>
  types.map((type, index) => ({
    key: `k${index}`,
    parentKey: null,
    type,
    labels: { ko: '가', en: 'a' },
    descriptions: null,
    url: type === 'EXTERNAL' ? 'https://m.example.com' : type === 'INTERNAL' ? '/a' : null,
    categoryId: null,
    displayOrder: index,
    visible: true,
    primary: false,
    shortcut: false,
  }));

describe('writeFailure', () => {
  it('401 은 입력을 지키고 다시 로그인을 안내한다, 403 은 권한 안내', () => {
    expect(writeFailure(error(401, 'AUTH_001', 'unauthorized'), [])).toEqual({
      kind: 'errors',
      errors: { form: [SESSION_EXPIRED_ON_SAVE] },
    });
    expect(writeFailure(error(403, 'SECURITY_005', 'forbidden'), [])).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
  });

  it('409 · 버전 · 리비전 번호 오류는 다시 읽고, 결과 모름은 경합 없이 다시 읽는다(자동 재전송 없음)', () => {
    for (const [status, code, kind] of [
      [409, 'STORE_NAV_020', 'conflict'],
      [400, 'STORE_NAV_101', 'server'],
      [400, 'STORE_NAV_113', 'server'],
    ] as const)
      expect(writeFailure(error(status, code, kind), [])).toEqual({
        kind: 'reload',
        notice: CONFLICT_NOTICE,
        conflict: true,
      });
    expect(writeFailure(error(504, 'GATEWAY_TIMEOUT', 'unknownOutcome'), [])).toEqual({
      kind: 'reload',
      notice: UNKNOWN_OUTCOME_NOTICE,
      conflict: false,
    });
  });

  it('fieldErrors 는 경로를 칸 키로 바꾸고 업무 문구를 쓴다', () => {
    const failure = writeFailure(
      error(400, 'STORE_NAV_100', 'validation', [
        { field: 'items[2].labels.ko', code: 'STORE_NAV_108', message: '원문' },
      ]),
      [],
    );
    expect(failure).toEqual({
      kind: 'errors',
      errors: { 'items.2.labels.ko': [LABEL_INVALID], form: [CHECK_FIELDS] },
    });
  });

  it('URL 거절(109)은 다른 사이트 항목의 주소 칸에 허용 사이트 문구로 붙인다', () => {
    expect(
      writeFailure(error(400, 'STORE_NAV_109', 'server'), sent(['INTERNAL', 'EXTERNAL'])),
    ).toEqual({
      kind: 'errors',
      errors: { 'items.1.url': [URL_HOST_NOT_ALLOWED], form: [URL_HOST_NOT_ALLOWED] },
    });
    // 게시 때는 허용 목록에서 빠진 사이트라는 안내
    const publish = writeFailure(error(400, 'STORE_NAV_109', 'server'), sent(['EXTERNAL']), true);
    expect(publish).toEqual({
      kind: 'errors',
      errors: { 'items.0.url': [URL_HOST_NOT_ALLOWED], form: [PUBLISH_HOST_REMOVED] },
    });
    // 다른 사이트가 없으면 경로 형식 문제다
    expect(writeFailure(error(400, 'STORE_NAV_109', 'server'), sent(['INTERNAL']))).toEqual({
      kind: 'errors',
      errors: { form: [PATH_INVALID] },
    });
  });

  it('칸 정보 없는 도메인 규칙 오류는 업무 문구를 폼 전체에 둔다', () => {
    expect(writeFailure(error(400, 'STORE_NAV_106', 'server'), [])).toEqual({
      kind: 'errors',
      errors: { form: [TOO_DEEP] },
    });
    expect(writeFailure(error(400, 'STORE_NAV_115', 'server'), [])).toEqual({
      kind: 'errors',
      errors: { form: [PLACEMENT_INVALID] },
    });
  });

  it('그 밖의 400 은 입력 확인, 서버 오류 · 알 수 없는 실패는 저장 실패', () => {
    expect(writeFailure(error(400, 'STORE_NAV_198', 'server'), [])).toEqual({
      kind: 'errors',
      errors: { form: [CHECK_FIELDS] },
    });
    expect(writeFailure(error(500, 'INTERNAL', 'server'), [])).toEqual({
      kind: 'errors',
      errors: { form: [SAVE_FAILED] },
    });
    expect(writeFailure(new Error('x'), [])).toEqual({
      kind: 'errors',
      errors: { form: [SAVE_FAILED] },
    });
  });
});

describe('publishOutcome', () => {
  const detail = (latestRevision: number, publishedRevision: number | null): NavDetail => ({
    version: 7,
    latestRevision,
    publishedRevision,
    revision: {
      revisionNumber: latestRevision,
      items: [],
      createdBy: '1',
      createdAt: '2026-11-27T00:00:00Z',
      publishedBy: null,
      publishedAt: null,
    },
  });
  const conflict = error(409, 'STORE_NAV_020', 'conflict');

  it('게시 409 뒤 다시 읽은 공개 번호가 요청 번호와 같으면 성공이다', () => {
    expect(publishNeedsReload(conflict, [])).toBe(true);
    expect(publishOutcome(conflict, detail(5, 5), 5, [])).toEqual({ kind: 'done' });
  });

  it('게시 409 뒤 다른 저장본이 최신이면 화면을 그 저장본으로 바꾼다', () => {
    expect(publishOutcome(conflict, detail(6, 4), 5, [])).toEqual({
      kind: 'reload',
      notice: publishReloadedNotice(6),
      conflict: false,
      resetTree: true,
    });
    expect(publishReloadedNotice(6)).toBe(
      '다른 관리자가 새 초안을 저장해 최신 6번을 불러왔습니다. 확인한 뒤 다시 게시해 주세요.',
    );
  });

  it('결과 모름 뒤 공개됐으면 성공, 아니면 같은 번호를 다시 게시하라고 안내한다', () => {
    const unknown = error(504, 'GATEWAY_TIMEOUT', 'unknownOutcome');
    expect(publishOutcome(unknown, detail(5, 5), 5, [])).toEqual({ kind: 'done' });
    expect(publishOutcome(unknown, detail(5, 4), 5, [])).toEqual({
      kind: 'reload',
      notice: publishNotAppliedNotice(5),
      conflict: false,
      resetTree: true,
    });
  });

  it('401 · 입력 오류는 다시 읽지 않고 화면을 바꾸지 않는다', () => {
    const expired = error(401, 'AUTH_001', 'unauthorized');
    expect(publishNeedsReload(expired, [])).toBe(false);
    expect(publishOutcome(expired, null, 5, [])).toEqual({
      kind: 'errors',
      errors: { form: [SESSION_EXPIRED_ON_SAVE] },
      resetTree: false,
    });
  });
});
