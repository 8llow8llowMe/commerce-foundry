import type { PageResponse } from '@cf/api-client';
import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { historyItem, krw, snapshot } from '@/lib/shipping/history-fixtures';
import type { DomesticPolicyHistoryItem } from '@/lib/shipping/types';
import { HistoryEntry, HistoryPage } from './DomesticPolicyHistory';

const pageOf = (
  contents: DomesticPolicyHistoryItem[],
  patch: Partial<PageResponse<DomesticPolicyHistoryItem>> = {},
): PageResponse<DomesticPolicyHistoryItem> => ({
  contents,
  page: 0,
  size: 20,
  totalElements: contents.length,
  totalPages: contents.length === 0 ? 0 : 1,
  ...patch,
});

const noop = () => {};

describe('HistoryEntry', () => {
  it('교체 시각과 관리자를 보이고 회원 아이디 원문은 내지 않는다', () => {
    const html = renderToStaticMarkup(<HistoryEntry item={historyItem()} />);
    expect(html).toContain('dateTime="2026-10-18T03:00:00Z"');
    expect(html).toContain('>관리자<');
    expect(html).not.toContain('4611686018427387904');
    expect(html).toContain('값 변경 없음');
  });

  it('교체한 사람의 마스킹 이름이 있으면 이름을 보인다 — 아이디는 여전히 내지 않는다', () => {
    const html = renderToStaticMarkup(
      <HistoryEntry item={historyItem({ adminDisplayName: '김*수' })} />,
    );
    expect(html).toContain('>김*수<');
    expect(html).not.toContain('4611686018427387904');
  });

  it('처음 만든 교체는 처음 설정 — 이전 칸 없이 설정 값만 보인다', () => {
    const html = renderToStaticMarkup(<HistoryEntry item={historyItem({ before: null })} />);
    expect(html).toContain('처음 설정');
    expect(html).toContain('설정 값');
    expect(html).not.toContain('이전');
    expect(html).not.toContain('(바뀜)');
  });

  it('바뀐 칸만 강조하고 화면 낭독용 표시를 붙인다', () => {
    const html = renderToStaticMarkup(
      <HistoryEntry item={historyItem({ after: snapshot({ baseFee: krw(4000) }) })} />,
    );
    expect(html).toContain('>변경<');
    expect(html.match(/\(바뀜\)/g)).toHaveLength(1);
    expect(html).toContain('3,000원');
    expect(html).toContain('4,000원');
  });
});

describe('HistoryPage', () => {
  it('이력이 없으면 안내한다', () => {
    const html = renderToStaticMarkup(<HistoryPage data={pageOf([])} page={0} onPage={noop} />);
    expect(html).toContain('아직 변경 이력이 없습니다.');
  });

  it('범위를 넘은 페이지면 첫 페이지 버튼을 보인다', () => {
    const html = renderToStaticMarkup(
      <HistoryPage
        data={pageOf([], { totalElements: 3, totalPages: 1, page: 4 })}
        page={4}
        onPage={noop}
      />,
    );
    expect(html).toContain('이 페이지에는 이력이 없습니다.');
    expect(html).toContain('첫 페이지로');
  });

  it('여러 페이지면 페이지 이동을 보이고 한 페이지면 숨긴다', () => {
    const many = renderToStaticMarkup(
      <HistoryPage
        data={pageOf([historyItem()], { totalElements: 25, totalPages: 2 })}
        page={0}
        onPage={noop}
      />,
    );
    expect(many).toContain('1 / 2');
    expect(many).toContain('다음');
    const one = renderToStaticMarkup(
      <HistoryPage data={pageOf([historyItem()])} page={0} onPage={noop} />,
    );
    expect(one).not.toContain('다음');
  });
});
