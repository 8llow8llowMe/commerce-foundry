import { describe, expect, it } from 'vitest';
import {
  BODY_INVALID,
  draftErrors,
  formOf,
  isDirty,
  PLACEHOLDER_LEFT,
  placeholdersOf,
  previewParts,
  publicStateText,
  publishBlocker,
  TITLE_INVALID,
} from './form';
import { parseLegalQuery, legalHref } from './query';
import { detail, summary } from './test-fixtures';

describe('draftErrors', () => {
  it('공백만 · 상한 초과 · 제어 문자를 막는다', () => {
    expect(draftErrors({ title: '  ', body: '\n' })).toEqual({
      title: [TITLE_INVALID],
      body: [BODY_INVALID],
    });
    expect(draftErrors({ title: 'a'.repeat(201), body: 'b'.repeat(50001) })).toEqual({
      title: [TITLE_INVALID],
      body: [BODY_INVALID],
    });
    expect(draftErrors({ title: '제목\u0007', body: '본문\u0000' })).toEqual({
      title: [TITLE_INVALID],
      body: [BODY_INVALID],
    });
  });

  it('본문의 탭 · 줄바꿈은 허용한다', () => {
    expect(draftErrors({ title: '제목', body: '제1조\r\n\t내용' })).toEqual({});
  });
});

describe('placeholdersOf', () => {
  it('제목 · 본문의 빈칸을 순서대로 한 번씩, 짝 없는 표시는 따로', () => {
    expect(placeholdersOf({ title: '{{상호}} 약관', body: '{{시행일}} {{상호}}' })).toEqual({
      tokens: ['{{상호}}', '{{시행일}}'],
      strayMarks: false,
    });
    expect(placeholdersOf({ title: '제목', body: '끝 }}' })).toEqual({
      tokens: [],
      strayMarks: true,
    });
    expect(placeholdersOf({ title: '제목', body: '본문' })).toEqual({
      tokens: [],
      strayMarks: false,
    });
  });
});

describe('previewParts', () => {
  it('빈칸만 표시 조각으로 나눈다', () => {
    expect(previewParts('a {{b}} <c>')).toEqual([
      { text: 'a ', placeholder: false },
      { text: '{{b}}', placeholder: true },
      { text: ' <c>', placeholder: false },
    ]);
  });
});

describe('isDirty · publishBlocker', () => {
  const base = detail({ latestRevision: 2, publishedRevision: 1 });

  it('최신 리비전과 같으면 저장할 것이 없다', () => {
    expect(isDirty(formOf(base), base)).toBe(false);
    expect(isDirty({ ...formOf(base), body: '바뀐 본문' }, base)).toBe(true);
    expect(isDirty({ title: '', body: '' }, null)).toBe(false);
  });

  it('최신 초안이 게시 전이면 게시할 수 있다', () => {
    expect(publishBlocker(formOf(base), base)).toEqual({ kind: 'ready' });
  });

  it('저장 안 한 입력 · 빈칸이 있으면 막는다', () => {
    expect(publishBlocker({ ...formOf(base), title: '새 제목' }, base).kind).toBe('blocked');
    const withMarks = detail({}, { body: '{{시행일}}' });
    expect(publishBlocker(formOf(withMarks), withMarks)).toEqual({
      kind: 'blocked',
      reason: PLACEHOLDER_LEFT,
    });
  });

  it('최신이 이미 공개 중이면 게시 버튼이 없다 · 문서가 없으면 없다', () => {
    const live = detail({ latestRevision: 2, publishedRevision: 2 });
    expect(publishBlocker(formOf(live), live)).toEqual({ kind: 'published' });
    expect(publishBlocker({ title: '', body: '' }, null)).toEqual({ kind: 'none' });
  });
});

describe('publicStateText', () => {
  it('저장과 공개를 구분해 알린다', () => {
    expect(publicStateText(null)).toContain('아직 저장한 문서가 없습니다');
    expect(publicStateText(summary({ latestRevision: 1, publishedRevision: null }))).toContain(
      '고객에게 보이지 않습니다',
    );
    expect(publicStateText(summary({ latestRevision: 2, publishedRevision: 2 }))).toBe(
      '2번이 공개 중입니다(최신 리비전).',
    );
    expect(publicStateText(summary({ latestRevision: 3, publishedRevision: 2 }))).toContain(
      '최신 3번 초안은 게시 전',
    );
  });
});

describe('parseLegalQuery', () => {
  it('허용 값만 받고 나머지는 이용약관 · 국문', () => {
    expect(parseLegalQuery({ type: 'PRIVACY', locale: 'en' })).toEqual({
      type: 'PRIVACY',
      locale: 'en',
    });
    expect(parseLegalQuery({ type: 'privacy', locale: ['EN'] })).toEqual({
      type: 'TERMS',
      locale: 'ko',
    });
    expect(legalHref({ type: 'PRIVACY', locale: 'en' })).toBe(
      '/store/legal-documents?type=PRIVACY&locale=en',
    );
  });
});
