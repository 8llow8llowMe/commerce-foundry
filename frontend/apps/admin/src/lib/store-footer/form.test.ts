import { describe, expect, it } from 'vitest';
import {
  blankRow,
  contentOf,
  draftErrors,
  formOf,
  isDirty,
  LABEL_INVALID,
  publicStateText,
  publishState,
  URL_FORMAT,
  URL_INSTAGRAM,
  URL_KAKAO,
  urlProblem,
  type FooterForm,
} from './form';
import type { FooterDetail } from './types';

// 실정보가 아닌 자리 값만 쓴다 (공개 저장소)
const detail = (patch: Partial<FooterDetail> = {}): FooterDetail => ({
  version: 3,
  latestRevision: 2,
  publishedRevision: 1,
  revision: {
    revisionNumber: 2,
    copyrightText: { ko: '© 테스트', en: null },
    links: [
      {
        type: 'KAKAO_CHANNEL',
        labels: { ko: '카카오', en: 'Kakao' },
        url: 'https://pf.kakao.com/_abc',
        displayOrder: 5,
        visible: false,
      },
      {
        type: 'INSTAGRAM',
        labels: { ko: '인스타', en: 'Insta' },
        url: 'https://www.instagram.com/example_shop',
        displayOrder: 1,
        visible: true,
      },
    ],
    createdBy: '1',
    createdAt: '2026-11-01T00:00:00Z',
    publishedBy: null,
    publishedAt: null,
  },
  ...patch,
});

describe('urlProblem', () => {
  it.each([
    ['INSTAGRAM', 'https://www.instagram.com/example_shop'],
    ['INSTAGRAM', 'https://instagram.com/a.b_c/'],
    ['KAKAO_CHANNEL', 'https://pf.kakao.com/_xYz-1'],
    ['KAKAO_CHANNEL', 'https://pf.kakao.com/_xYz/chat'],
    ['CUSTOM', 'https://blog.example.com/path/%7Euser'],
    ['CUSTOM', 'HTTPS://Example.COM'],
  ] as const)('%s %s 는 통과한다', (type, url) => {
    expect(urlProblem(type, url)).toBeNull();
  });

  it.each([
    'http://example.com',
    'javascript:alert(1)',
    'https://example.com/a?b=1',
    'https://example.com/#top',
    'https://user@example.com',
    'https://example.com:8443/a',
    'https://127.0.0.1/a',
    'https://localhost/a',
    'https://shop.internal/a',
    'https://example.com/a b',
    'https://example.com\\a',
    'https://example.com/%2F',
    'https://example.com/a/../b',
    'https://한글.com',
    '//example.com',
  ])('형식이 틀린 %s 는 거절한다', (url) => {
    expect(urlProblem('CUSTOM', url)).toBe(URL_FORMAT);
  });

  it('유형별 호스트 · 경로를 확인한다', () => {
    expect(urlProblem('INSTAGRAM', 'https://example.com/shop')).toBe(URL_INSTAGRAM);
    expect(urlProblem('INSTAGRAM', 'https://www.instagram.com/a/b')).toBe(URL_INSTAGRAM);
    expect(urlProblem('KAKAO_CHANNEL', 'https://pf.kakao.com/abc')).toBe(URL_KAKAO);
    expect(urlProblem('KAKAO_CHANNEL', 'https://pf.kakao.com/_abc/home')).toBe(URL_KAKAO);
  });
});

describe('formOf · contentOf · isDirty', () => {
  it('displayOrder 오름차순으로 펼치고, 저장 본문은 화면 순서를 0부터 다시 매긴다', () => {
    const form = formOf(detail());
    expect(form.links.map((row) => row.labelKo)).toEqual(['인스타', '카카오']);
    const content = contentOf(form);
    expect(content.links.map((link) => link.displayOrder)).toEqual([0, 1]);
    expect(content.copyrightText).toEqual({ ko: '© 테스트', en: null });
    expect(content.links[1]).toMatchObject({ visible: false, type: 'KAKAO_CHANNEL' });
  });

  it('줄 순서 · 노출만 바꿔도 저장 전 입력이다', () => {
    const base = detail();
    const form = formOf(base);
    expect(isDirty(form, base)).toBe(false);
    expect(isDirty({ ...form, links: [...form.links].reverse() }, base)).toBe(true);
    const hidden: FooterForm = {
      ...form,
      links: form.links.map((row, index) => (index === 0 ? { ...row, visible: false } : row)),
    };
    expect(isDirty(hidden, base)).toBe(true);
  });

  it('미등록이면 빈 폼이고 빈 저작권은 null 로 보낸다', () => {
    const form = formOf(null);
    expect(contentOf({ ...form, copyrightKo: '   ' }).copyrightText).toEqual({
      ko: null,
      en: null,
    });
    expect(isDirty(form, null)).toBe(false);
  });
});

describe('draftErrors', () => {
  it('숨김 줄도 표시명 · 주소를 검사하고 서버 fieldKey 와 같은 키를 쓴다', () => {
    const row = { ...blankRow('KAKAO_CHANNEL'), visible: false, labelEn: 'x'.repeat(81) };
    const errors = draftErrors({ copyrightKo: 'a\nb', copyrightEn: '', links: [row] });
    expect(errors['links.0.labels.ko']).toEqual([LABEL_INVALID]);
    expect(errors['links.0.labels.en']).toEqual([LABEL_INVALID]);
    expect(errors['links.0.url']).toEqual([URL_FORMAT]);
    expect(errors['copyrightText.ko']).toBeDefined();
    expect(errors['copyrightText.en']).toBeUndefined();
  });

  it('21개면 목록 오류', () => {
    const links = Array.from({ length: 21 }, () => ({
      ...blankRow('CUSTOM'),
      labelKo: '가',
      labelEn: 'a',
      url: 'https://example.com',
    }));
    expect(draftErrors({ copyrightKo: '', copyrightEn: '', links }).links).toBeDefined();
  });
});

describe('publicStateText · publishState', () => {
  it('공개 · 초안 상태를 알린다', () => {
    expect(publicStateText(null)).toContain('아직 저장한 푸터 설정이 없습니다');
    expect(publicStateText(detail({ publishedRevision: null }))).toContain('아직 게시하지 않아');
    expect(publicStateText(detail({ publishedRevision: 2 }))).toBe(
      '2번이 공개 중입니다(최신 리비전).',
    );
    expect(publicStateText(detail())).toContain('최신 2번 초안은 게시 전');
  });

  it('저장 전 입력이 있으면 게시하지 않는다', () => {
    const base = detail();
    const form = formOf(base);
    expect(publishState(form, null)).toBe('none');
    expect(publishState(form, base)).toBe('ready');
    expect(publishState({ ...form, copyrightEn: 'new' }, base)).toBe('dirty');
    expect(publishState(form, detail({ publishedRevision: 2 }))).toBe('published');
  });
});
