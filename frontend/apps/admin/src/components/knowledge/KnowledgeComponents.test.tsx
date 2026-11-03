import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { approved, archived, detail, published, summary } from '@/lib/knowledge/test-fixtures';
import { ContentFields } from './ContentFields';
import { ActionBar, RevisionFacts } from './KnowledgeDetailScreen';
import { KnowledgeTable, publishedText } from './KnowledgeList';

const noop = () => {};
const NOW = new Date('2026-11-03T00:00:00Z');

describe('KnowledgeTable', () => {
  it('상세 링크 · 서버 name · 최신 리비전 상태 · 게시 표시를 보인다. 제목은 이스케이프한다', () => {
    const html = renderToStaticMarkup(
      <KnowledgeTable
        items={[
          summary({ title: '<b>굵게</b>' }),
          summary({ documentId: '102', latestRevision: 3, publishedRevision: 2 }),
        ]}
      />,
    );
    expect(html).toContain('href="/support/knowledge/101"');
    expect(html).toContain('&lt;b&gt;굵게&lt;/b&gt;');
    expect(html).not.toContain('<b>굵게');
    expect(html).toContain('자주 묻는 질문');
    expect(html).toContain('1번 · 초안');
    expect(html).toContain('2번 게시 중');
  });

  it('게시 칸 문구', () => {
    expect(publishedText({ latestRevision: 1, publishedRevision: null })).toBe('게시 안 됨');
    expect(publishedText({ latestRevision: 2, publishedRevision: 2 })).toBe('게시 중');
    expect(publishedText({ latestRevision: 3, publishedRevision: 2 })).toBe('2번 게시 중');
  });
});

describe('ActionBar', () => {
  const render = (
    props: Partial<Parameters<typeof ActionBar>[0]> & {
      detail: Parameters<typeof ActionBar>[0]['detail'];
    },
  ) =>
    renderToStaticMarkup(
      <ActionBar
        pending={null}
        confirming={null}
        onConfirm={noop}
        onRun={noop}
        now={NOW}
        {...props}
      />,
    );

  it('초안 — 고치기 · 승인 · 보관', () => {
    const html = render({ detail: detail() });
    expect(html).toContain('초안 고치기');
    expect(html).toContain('href="/support/knowledge/101/edit"');
    expect(html).toContain('>승인<');
    expect(html).toContain('>보관<');
    expect(html).not.toContain('>게시<');
  });

  it('승인 — 새 초안 · 게시. 유효기간 밖이면 게시 대신 이유', () => {
    expect(render({ detail: approved() })).toContain('>게시<');
    const early = render({ detail: approved({ validFrom: '2026-11-05T00:00:00Z' }) });
    expect(early).not.toContain('>게시<');
    expect(early).toContain('아직 게시할 수 없습니다');
  });

  it('게시 — 게시 해제(보관), 보관 — 새 초안만', () => {
    expect(render({ detail: published() })).toContain('게시 해제(보관)');
    const html = render({ detail: archived() });
    expect(html).toContain('새 초안 만들기');
    expect(html).not.toContain('>보관<');
  });

  it('요청 중에는 버튼을 막고 고치기 링크를 숨긴다(더블 클릭)', () => {
    const html = render({ detail: detail(), pending: 'approve' });
    expect(html).toContain('승인 중…');
    expect(html).toContain('disabled=""');
    expect(html).not.toContain('/edit');
  });

  it('게시 확인 — 지금 게시본이 보관된다고 알린다', () => {
    const html = render({
      detail: approved({ revisionNumber: 2, latestRevision: 2, publishedRevision: 1 }),
      confirming: 'publish',
    });
    expect(html).toContain('지금 게시 중인 1번은 보관됩니다');
  });
});

describe('RevisionFacts', () => {
  it('다른 리비전이 게시 중이면 그 번호를 알리고, 승인자 아이디는 보이지 않는다', () => {
    const html = renderToStaticMarkup(
      <RevisionFacts detail={approved({ revisionNumber: 2, publishedRevision: 1 })} />,
    );
    expect(html).toContain('1번 리비전이 게시 중입니다');
    expect(html).not.toContain('>7<');
  });
});

describe('ContentFields', () => {
  it('칸 오류와 글자 수를 보인다', () => {
    const html = renderToStaticMarkup(
      <ContentFields
        form={{ title: '', body: '가나다', category: '', validFrom: '', validTo: '' }}
        errors={{ title: ['제목을 입력해 주세요.'], category: ['카테고리를 골라 주세요.'] }}
        onChange={noop}
      />,
    );
    expect(html).toContain('제목을 입력해 주세요.');
    expect(html).toContain('카테고리를 골라 주세요.');
    expect(html).toContain('3 / 8,000자');
    expect(html).toContain('교환환불');
  });
});
