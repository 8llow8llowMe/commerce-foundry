import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { ko } from '@/lib/i18n/dictionaries/ko';
import type { LegalLoad } from '@/lib/legal/document';
import { LegalDocumentView } from './LegalDocumentView';

// 가짜 값만 쓴다(공개 저장소)
const OK: LegalLoad = {
  kind: 'ok',
  document: {
    type: 'PRIVACY',
    locale: 'ko',
    revisionNumber: 3,
    title: '테스트 방침 <i>',
    body: '제1조\n<script>alert(1)</script><img src=x onerror=alert(1)>',
    publishedAt: '2026-11-02T15:30:00Z',
  },
};

const render = (load: LegalLoad, revision: number | null = null) =>
  renderToStaticMarkup(
    <LegalDocumentView
      name="개인정보처리방침"
      path="/privacy"
      formAction="/privacy"
      locale="ko"
      revision={revision}
      load={load}
      labels={ko.legal}
    />,
  );

describe('LegalDocumentView', () => {
  it('제목 · 본문을 HTML 로 실행하지 않고 글자로 그린다', () => {
    const html = render(OK);
    expect(html).toContain('테스트 방침 &lt;i&gt;');
    expect(html).toContain(
      '&lt;script&gt;alert(1)&lt;/script&gt;&lt;img src=x onerror=alert(1)&gt;',
    );
    expect(html).not.toContain('<script');
    expect(html).not.toContain('<img');
    expect(html).toContain('whitespace-pre-wrap');
  });

  it('버전 · 게시일(한국 시각)과 이전 게시본 GET 폼', () => {
    const html = render(OK);
    expect(html).toContain('버전 3');
    expect(html).toContain('게시일 2026년 11월 3일');
    expect(html).toContain('action="/privacy"');
    expect(html).toContain('method="get"');
    expect(html).toContain('name="revision"');
  });

  it('게시본이 없으면 문서를 만들거나 다른 언어로 대신 보이지 않는다', () => {
    const html = render({ kind: 'missing' });
    expect(html).toContain('<h1');
    expect(html).toContain('개인정보처리방침');
    expect(html).toContain(ko.legal.missing);
    expect(html).not.toContain('name="revision"');
  });

  it('이전 게시본 — 보고 있는 번호 · 현재 문서 링크, 게시한 적 없는 번호는 없다고만', () => {
    const viewing = render({ ...OK, document: { ...OK.document, revisionNumber: 2 } }, 2);
    expect(viewing).toContain('이전 게시본(버전 2)을 보고 있습니다.');
    expect(viewing).toContain('href="/privacy"');
    expect(viewing).toContain('value="2"');
    const missing = render({ kind: 'missing' }, 9);
    expect(missing).toContain('버전 9은(는) 게시된 적이 없거나 없는 버전입니다.');
    expect(missing).not.toContain('제1조');
  });

  it('불러오기 실패는 다시 받기 링크', () => {
    const html = render({ kind: 'failed' }, 2);
    expect(html).toContain(ko.legal.loadFailed);
    expect(html).toContain('href="/privacy?revision=2"');
  });
});
