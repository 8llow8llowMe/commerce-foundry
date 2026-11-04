import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { revision, summary, template } from '@/lib/legal/test-fixtures';
import {
  ConflictCompare,
  LegalPicker,
  PlaceholderNotice,
  PublishConfirm,
  RevisionRow,
  TemplateGuide,
  TextPreview,
} from './LegalParts';

const noop = () => {};

describe('LegalPicker', () => {
  it('유형 × 언어 네 칸과 공개 상태를 보인다', () => {
    const html = renderToStaticMarkup(
      <LegalPicker
        current={{ type: 'PRIVACY', locale: 'en' }}
        summaries={[
          summary({ latestRevision: 3, publishedRevision: 2 }),
          summary({ type: 'PRIVACY', locale: 'en', latestRevision: 1, publishedRevision: null }),
        ]}
      />,
    );
    expect(html).toContain('href="/store/legal-documents?type=TERMS&amp;locale=ko"');
    expect(html).toContain('href="/store/legal-documents?type=PRIVACY&amp;locale=en"');
    expect(html).toContain('2번 공개 · 3번 초안');
    expect(html).toContain('초안 1번 · 미게시');
    expect(html).toContain('없음');
    expect(html.match(/aria-current="page"/g)).toHaveLength(1);
  });

  it('목록을 못 받으면 상태 없이 링크만', () => {
    const html = renderToStaticMarkup(
      <LegalPicker current={{ type: 'TERMS', locale: 'ko' }} summaries={null} />,
    );
    expect(html).not.toContain('없음');
    expect(html.match(/<a /g)).toHaveLength(4);
  });
});

describe('TextPreview', () => {
  it('본문을 HTML 로 해석하지 않고 빈칸만 표시한다', () => {
    const html = renderToStaticMarkup(
      <TextPreview form={{ title: '<script>x</script>', body: '<b>굵게</b> {{상호}}' }} />,
    );
    expect(html).toContain('&lt;script&gt;x&lt;/script&gt;');
    expect(html).toContain('&lt;b&gt;굵게&lt;/b&gt;');
    expect(html).not.toContain('<b>');
    expect(html).toContain('<mark');
    expect(html).toContain('{{상호}}</mark>');
  });
});

describe('PlaceholderNotice', () => {
  it('빈칸이 없으면 그리지 않는다', () => {
    expect(renderToStaticMarkup(<PlaceholderNotice form={{ title: 'a', body: 'b' }} />)).toBe('');
  });

  it('빈칸 목록과 게시 불가 안내', () => {
    const html = renderToStaticMarkup(
      <PlaceholderNotice form={{ title: '{{상호}}', body: '{{시행일}} }}' }} />,
    );
    expect(html).toContain('바꾸지 않은 빈칸 2개');
    expect(html).toContain('짝이 맞지 않는');
    expect(html).toContain('게시할 수 없습니다');
  });
});

describe('TemplateGuide', () => {
  it('법률 검토가 끝난 문서가 아니라고 알리고 확인 항목을 보인다', () => {
    const html = renderToStaticMarkup(<TemplateGuide template={template()} />);
    expect(html).toContain('법률 검토를 마친 완성 문서가');
    expect(html).toContain('시행일 확인');
  });
});

describe('PublishConfirm', () => {
  const render = (checked: boolean, pending = false) =>
    renderToStaticMarkup(
      <PublishConfirm
        type="PRIVACY"
        locale="en"
        revision={revision({ revisionNumber: 4, title: '테스트 방침' })}
        checked={checked}
        pending={pending}
        errors={[]}
        onCheck={noop}
        onCancel={noop}
        onPublish={noop}
      />,
    );

  it('공개 언어 · 공개 주소 · 최종 본문을 보이고 검토 확인 전에는 게시 버튼을 막는다', () => {
    const html = render(false);
    expect(html).toContain('영문 개인정보처리방침 페이지(/privacy)');
    expect(html).toContain('테스트 방침');
    expect(html).toContain('type="checkbox"');
    expect(html).toMatch(/<button[^>]*disabled=""[^>]*>4번 게시 — 고객에게 공개/);
  });

  it('확인하면 게시할 수 있고 요청 중에는 막는다', () => {
    expect(render(true)).not.toMatch(/<button[^>]*disabled=""[^>]*>4번 게시/);
    expect(render(true, true)).toContain('게시 중…');
  });
});

describe('RevisionRow', () => {
  it('공개 중 · 게시 시각, 최신이 아니면 불러오기 버튼', () => {
    const html = renderToStaticMarkup(
      <RevisionRow
        revision={revision({ revisionNumber: 1, publishedAt: '2026-11-02T00:00:00Z' })}
        latest={false}
        published
        onRestore={noop}
      />,
    );
    expect(html).toContain('공개 중');
    expect(html).toContain('게시 2026');
    expect(html).toContain('편집기로 불러오기');
  });

  it('최신 초안 — 게시 안 됨, 불러오기 없음', () => {
    const html = renderToStaticMarkup(
      <RevisionRow revision={revision()} latest published={false} onRestore={noop} />,
    );
    expect(html).toContain('게시 안 됨');
    expect(html).toContain('최신');
    expect(html).not.toContain('편집기로 불러오기');
  });
});

describe('ConflictCompare', () => {
  it('내 입력과 지금 최신본을 나란히 보인다', () => {
    const html = renderToStaticMarkup(
      <ConflictCompare
        mine={{ title: '내 제목', body: '내 본문' }}
        latest={revision({ revisionNumber: 5, title: '남의 제목' })}
        onTakeLatest={noop}
      />,
    );
    expect(html).toContain('내 입력(저장 안 됨)');
    expect(html).toContain('지금 최신 5번');
    expect(html).toContain('남의 제목');
  });
});
