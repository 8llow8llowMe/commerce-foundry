import { describe, expect, it } from 'vitest';
import { isoToLocalInput } from '@/lib/display/text';
import {
  BODY_REQUIRED,
  BODY_TOO_LONG,
  CATEGORY_REQUIRED,
  contentFormOf,
  contentRequest,
  emptyContentForm,
  PERIOD_INVERTED,
  TITLE_REQUIRED,
  TITLE_TOO_LONG,
  VALID_FROM_REQUIRED,
  VALID_TO_FORMAT,
  type ContentForm,
} from './form';
import { detail } from './test-fixtures';

const FROM = isoToLocalInput('2026-11-01T00:00:00Z');
const valid: ContentForm = {
  title: '  배송 기간  ',
  body: '  평균 3일\n',
  category: 'SHIPPING',
  validFrom: FROM,
  validTo: '',
};

describe('contentRequest', () => {
  it('제목은 앞뒤 공백을 빼고, 본문은 그대로 보낸다. 종료가 비면 무기한(null)이다', () => {
    expect(contentRequest(valid)).toEqual({
      kind: 'ok',
      content: {
        title: '배송 기간',
        body: '  평균 3일\n',
        category: 'SHIPPING',
        validFrom: '2026-11-01T00:00:00.000Z',
        validTo: null,
      },
    });
  });

  it('빈 칸 · 공백뿐인 칸은 칸 오류다', () => {
    const result = contentRequest({
      title: ' ',
      body: ' \n',
      category: '',
      validFrom: '',
      validTo: '',
    });
    expect(result).toEqual({
      kind: 'errors',
      errors: {
        title: [TITLE_REQUIRED],
        body: [BODY_REQUIRED],
        category: [CATEGORY_REQUIRED],
        validFrom: [VALID_FROM_REQUIRED],
      },
    });
  });

  it('상한을 넘으면 칸 오류다(제목 200 · 본문 8000)', () => {
    const result = contentRequest({ ...valid, title: 'a'.repeat(201), body: 'b'.repeat(8001) });
    expect(result).toEqual({
      kind: 'errors',
      errors: { title: [TITLE_TOO_LONG], body: [BODY_TOO_LONG] },
    });
    expect(contentRequest({ ...valid, title: 'a'.repeat(200), body: 'b'.repeat(8000) }).kind).toBe(
      'ok',
    );
  });

  it('종료는 시작보다 뒤여야 한다', () => {
    expect(contentRequest({ ...valid, validTo: FROM })).toEqual({
      kind: 'errors',
      errors: { validTo: [PERIOD_INVERTED] },
    });
    expect(contentRequest({ ...valid, validTo: 'nope' })).toEqual({
      kind: 'errors',
      errors: { validTo: [VALID_TO_FORMAT] },
    });
  });
});

describe('폼 시작값', () => {
  it('새 문서는 지금부터 무기한이다', () => {
    const now = new Date('2026-11-03T05:06:00Z');
    expect(emptyContentForm(now)).toEqual({
      title: '',
      body: '',
      category: '',
      validFrom: isoToLocalInput(now.toISOString()),
      validTo: '',
    });
  });

  it('최신 리비전 내용으로 채운다. 모르는 카테고리는 다시 고르게 한다', () => {
    expect(contentFormOf(detail({ validTo: '2026-12-01T00:00:00Z' }))).toEqual({
      title: '배송은 얼마나 걸리나요?',
      body: '평균 3일 걸립니다.\n주말은 제외합니다.',
      category: 'SHIPPING',
      validFrom: FROM,
      validTo: isoToLocalInput('2026-12-01T00:00:00Z'),
    });
    const unknown = detail({ category: { code: 'NEW', name: '새 값', description: '' } });
    expect(contentFormOf(unknown).category).toBe('');
  });
});
