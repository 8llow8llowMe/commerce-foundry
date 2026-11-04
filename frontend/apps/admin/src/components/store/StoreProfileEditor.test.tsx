import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { ComparisonPanel } from './StoreProfileEditor';

const noop = () => {};

describe('ComparisonPanel', () => {
  it('다른 칸만 입력한 값 · 최신 값으로 나란히 보이고, 빈 값은 (비움)으로 둔다', () => {
    const html = renderToStaticMarkup(
      <ComparisonPanel
        comparison={{
          latest: null,
          diffs: [
            { key: 'address', label: '공개 주소', mine: '새 주소', latest: '옛 주소' },
            { key: 'customerServiceHours', label: '고객센터 운영 시간', mine: '', latest: '평일' },
          ],
        }}
        onUseLatest={noop}
      />,
    );
    expect(html).toContain('<th scope="row" class="py-2 pr-4 font-medium">공개 주소</th>');
    expect(html).toContain('>새 주소</td>');
    expect(html).toContain('>옛 주소</td>');
    expect(html).toContain('>(비움)</td>');
    expect(html).toContain('최신 값으로 바꾸기');
    // 내부 version 은 보이지 않는다
    expect(html).not.toMatch(/version|버전/i);
  });

  it('다른 칸이 없으면 같다고 안내한다', () => {
    const html = renderToStaticMarkup(
      <ComparisonPanel comparison={{ latest: null, diffs: [] }} onUseLatest={noop} />,
    );
    expect(html).toContain('최신 값과 입력한 값이 같습니다.');
    expect(html).not.toContain('<table');
  });
});
