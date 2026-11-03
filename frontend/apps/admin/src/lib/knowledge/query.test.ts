import { describe, expect, it } from 'vitest';
import {
  DEFAULT_QUERY,
  hasFilter,
  knowledgeApiPath,
  knowledgeEditHref,
  knowledgeHref,
  knowledgeListHref,
  parseKnowledgeQuery,
} from './query';

describe('parseKnowledgeQuery', () => {
  it('허용 값만 읽는다', () => {
    expect(parseKnowledgeQuery({ type: 'POLICY', locale: 'EN', page: '2' })).toEqual({
      type: 'POLICY',
      locale: 'EN',
      page: 2,
    });
  });

  it('모르는 값 · 소문자 · 이상한 페이지는 전체 · 0 이다', () => {
    expect(parseKnowledgeQuery({ type: 'faq', locale: 'JA', page: '-1' })).toEqual(DEFAULT_QUERY);
    expect(parseKnowledgeQuery({ page: '1234567' }).page).toBe(0);
  });

  it('여러 값이면 첫 값을 쓴다', () => {
    expect(parseKnowledgeQuery({ type: ['TEMPLATE', 'FAQ'] }).type).toBe('TEMPLATE');
  });
});

describe('주소', () => {
  it('기본값은 빼고 화면 주소를 만든다', () => {
    expect(knowledgeListHref(DEFAULT_QUERY)).toBe('/support/knowledge');
    expect(knowledgeListHref({ type: 'FAQ', locale: 'KO', page: 1 })).toBe(
      '/support/knowledge?type=FAQ&locale=KO&page=1',
    );
  });

  it('BFF 경로는 페이지 · 크기를 늘 보낸다', () => {
    expect(knowledgeApiPath(DEFAULT_QUERY)).toBe('/api/v1/admin/support/knowledge?page=0&size=20');
    expect(knowledgeApiPath({ type: null, locale: 'EN', page: 3 })).toBe(
      '/api/v1/admin/support/knowledge?locale=EN&page=3&size=20',
    );
  });

  it('상세 · 고치기 주소', () => {
    expect(knowledgeHref('101')).toBe('/support/knowledge/101');
    expect(knowledgeEditHref('101')).toBe('/support/knowledge/101/edit');
  });

  it('조건 여부는 페이지를 보지 않는다', () => {
    expect(hasFilter({ ...DEFAULT_QUERY, page: 4 })).toBe(false);
    expect(hasFilter({ ...DEFAULT_QUERY, locale: 'EN' })).toBe(true);
  });
});
