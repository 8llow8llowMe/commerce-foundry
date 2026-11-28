import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { draftBody } from './draft-image';
import {
  afterSourceChanged,
  pickAfterEdit,
  sourceLabel,
  TEXT_SOURCES_FAILED,
  TEXT_SOURCES_SESSION_EXPIRED,
  textSourcesFailure,
  textSourcesPath,
  type TextSource,
} from './text-source';

const SOURCE: TextSource = {
  sourceId: 'TEXT-EN-2',
  bodyHash: 'c'.repeat(64),
  sourceText: '  S 50\nM 52',
};

describe('본문 치수표 후보', () => {
  it('경로와 후보 이름 — 아이디 원문은 보이지 않는다', () => {
    expect(textSourcesPath('42')).toBe('/api/v1/admin/products/42/size-chart/text-sources');
    expect(sourceLabel('TEXT-KO-1')).toBe('국문 설명의 표 1');
    expect(sourceLabel('TEXT-EN-12')).toBe('영문 설명의 표 12');
    expect(sourceLabel('알 수 없음')).toBe('상품 설명의 표');
  });

  it('단건 패널: 고른 표를 한 글자라도 고치면 출처가 빠지고, 되돌리면 다시 그 표다', () => {
    expect(pickAfterEdit(SOURCE, SOURCE.sourceText)).toBe(SOURCE);
    expect(pickAfterEdit(SOURCE, `${SOURCE.sourceText} `)).toBeNull();
    expect(pickAfterEdit(null, SOURCE.sourceText)).toBeNull();
  });

  it('단건 패널: 017 이면 고친 데 없는 후보 텍스트는 비우고 표를 놓는다. 직접 입력은 지킨다', () => {
    expect(afterSourceChanged(SOURCE, SOURCE.sourceText)).toEqual({ text: '', picked: null });
    expect(afterSourceChanged(null, 'S 50')).toEqual({ text: 'S 50', picked: null });
  });

  it('읽기 실패 — 401 은 입력을 지키려고 안내만, 403 은 권한 안내, 그 밖은 다시 불러오기', () => {
    expect(textSourcesFailure(new ApiError('unauthorized', 401, null, null))).toEqual({
      kind: 'retry',
      message: TEXT_SOURCES_SESSION_EXPIRED,
    });
    expect(textSourcesFailure(new ApiError('forbidden', 403, null, null))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
    expect(textSourcesFailure(new ApiError('server', 500, null, null))).toEqual({
      kind: 'retry',
      message: TEXT_SOURCES_FAILED,
    });
    expect(textSourcesFailure(new Error('x'))).toEqual({
      kind: 'retry',
      message: TEXT_SOURCES_FAILED,
    });
  });

  it('후보를 고른 생성 본문은 텍스트를 다듬지 않고 출처를 싣는다. 직접 입력은 다듬고 출처가 없다', () => {
    expect(
      draftBody('5', {
        mode: 'TEXT',
        sourceText: SOURCE.sourceText,
        selection: { sourceId: SOURCE.sourceId, bodyHash: SOURCE.bodyHash },
      }),
    ).toEqual({
      templateId: '5',
      mode: 'TEXT',
      sourceText: '  S 50\nM 52',
      textSelection: { sourceId: 'TEXT-EN-2', bodyHash: SOURCE.bodyHash },
    });
    expect(
      draftBody('5', { mode: 'TEXT', sourceText: SOURCE.sourceText, selection: null }),
    ).toEqual({ templateId: '5', mode: 'TEXT', sourceText: 'S 50\nM 52' });
  });
});
