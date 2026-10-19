import { describe, expect, it } from 'vitest';
import {
  localeTarget,
  localeUrl,
  localizePath,
  parseLocaleHosts,
  routeRequest,
  stripLocalePrefix,
  switchLocaleHref,
  type LocaleRouting,
} from './routing';

const byPath: LocaleRouting = { defaultLocale: 'ko', locales: ['ko', 'en'], hosts: {} };
const byHost: LocaleRouting = { ...byPath, hosts: { en: 'en.example.com' } };

describe('parseLocaleHosts', () => {
  it('언어=호스트 쌍을 읽고 포트 · 대소문자 · 모르는 언어 · 빈 값을 정리한다', () => {
    expect(
      parseLocaleHosts(' en = EN.Example.com:443 , fr=fr.example.com, ko=', ['ko', 'en']),
    ).toEqual({ en: 'en.example.com' });
    expect(parseLocaleHosts(undefined, ['ko', 'en'])).toEqual({});
  });
});

describe('routeRequest — 경로 방식', () => {
  it('접두어 없는 주소는 기본 언어로 그린다', () => {
    expect(routeRequest(byPath, 'example.com', '/')).toEqual({ type: 'rewrite', pathname: '/ko' });
    expect(routeRequest(byPath, 'example.com', '/shop')).toEqual({
      type: 'rewrite',
      pathname: '/ko/shop',
    });
  });

  it('다른 언어 접두어는 그대로 그린다', () => {
    expect(routeRequest(byPath, 'example.com', '/en/shop')).toEqual({
      type: 'rewrite',
      pathname: '/en/shop',
    });
    expect(routeRequest(byPath, 'example.com', '/en')).toEqual({
      type: 'rewrite',
      pathname: '/en',
    });
  });

  it('기본 언어 접두어는 떼고 보낸다(쿼리 유지)', () => {
    expect(routeRequest(byPath, 'example.com', '/ko/shop', '?sort=NEWEST')).toEqual({
      type: 'redirect',
      url: '/shop?sort=NEWEST',
    });
    expect(routeRequest(byPath, 'example.com', '/ko')).toEqual({ type: 'redirect', url: '/' });
  });

  it('언어처럼 보여도 지원하지 않는 접두어는 기본 언어의 경로다', () => {
    expect(routeRequest(byPath, 'example.com', '/fr/shop')).toEqual({
      type: 'rewrite',
      pathname: '/ko/fr/shop',
    });
  });
});

describe('routeRequest — 호스트 방식', () => {
  it('언어 호스트의 접두어 없는 주소는 그 언어다', () => {
    expect(routeRequest(byHost, 'en.example.com:3000', '/shop')).toEqual({
      type: 'rewrite',
      pathname: '/en/shop',
    });
  });

  it('언어 호스트에 같은 언어 접두어로 오면 뗀다', () => {
    expect(routeRequest(byHost, 'en.example.com', '/en/shop')).toEqual({
      type: 'redirect',
      url: '/shop',
    });
  });

  it('기본 호스트에서 전용 호스트 언어를 경로로 오면 그 호스트로 보낸다', () => {
    expect(routeRequest(byHost, 'example.com', '/en/shop', '?a=1')).toEqual({
      type: 'redirect',
      url: 'https://en.example.com/shop?a=1',
    });
  });

  it('기본 호스트는 여전히 기본 언어다', () => {
    expect(routeRequest(byHost, 'example.com', '/shop')).toEqual({
      type: 'rewrite',
      pathname: '/ko/shop',
    });
  });
});

describe('localizePath · localeUrl', () => {
  it('경로 방식은 기본 언어가 아니면 접두어를 붙인다', () => {
    expect(localizePath(byPath, 'ko', '/shop')).toBe('/shop');
    expect(localizePath(byPath, 'en', '/shop?x=1')).toBe('/en/shop?x=1');
    expect(localizePath(byPath, 'en', '/')).toBe('/en');
  });

  it('호스트 방식 언어 · 외부 주소 · 프로토콜 상대 주소는 그대로다', () => {
    expect(localizePath(byHost, 'en', '/shop')).toBe('/shop');
    expect(localizePath(byPath, 'en', 'https://instagram.com/x')).toBe('https://instagram.com/x');
    expect(localizePath(byPath, 'en', '//cdn.example.com/a')).toBe('//cdn.example.com/a');
  });

  it('hreflang 절대 주소 — 전용 호스트 언어는 그 호스트로', () => {
    expect(localeUrl(byPath, 'en', '/shop', 'https://example.com')).toBe(
      'https://example.com/en/shop',
    );
    expect(localeUrl(byHost, 'en', '/shop', 'https://example.com')).toBe(
      'https://en.example.com/shop',
    );
    expect(localeUrl(byHost, 'ko', '/', 'https://example.com')).toBe('https://example.com/');
  });
});

describe('언어 전환 — 같은 화면', () => {
  it('지금 언어 접두어를 떼어 앱 경로로', () => {
    expect(stripLocalePrefix('/en/shop', '/en')).toBe('/shop');
    expect(stripLocalePrefix('/en', '/en')).toBe('/');
    expect(stripLocalePrefix('/english', '/en')).toBe('/english');
    expect(stripLocalePrefix('/shop', '')).toBe('/shop');
  });

  it('경로 방식: 같은 호스트에서 접두어만 바꾸고 쿼리를 유지한다', () => {
    const ko = localeTarget(byPath, 'ko', 'https://example.com');
    const en = localeTarget(byPath, 'en', 'https://example.com');
    expect(ko).toEqual({ origin: '', prefix: '' });
    expect(switchLocaleHref({ pathname: '/shop', search: '?sort=NEWEST' }, '', en)).toBe(
      '/en/shop?sort=NEWEST',
    );
    expect(switchLocaleHref({ pathname: '/en/products/1', search: '' }, '/en', ko)).toBe(
      '/products/1',
    );
    expect(switchLocaleHref({ pathname: '/en', search: '' }, '/en', ko)).toBe('/');
  });

  it('호스트 방식: 그 언어의 호스트 절대 주소로', () => {
    const ko = localeTarget(byHost, 'ko', 'https://example.com');
    const en = localeTarget(byHost, 'en', 'https://example.com');
    expect(en).toEqual({ origin: 'https://en.example.com', prefix: '' });
    expect(switchLocaleHref({ pathname: '/shop', search: '?a=1' }, '', en)).toBe(
      'https://en.example.com/shop?a=1',
    );
    expect(switchLocaleHref({ pathname: '/products/1', search: '' }, '', ko)).toBe(
      'https://example.com/products/1',
    );
  });
});
