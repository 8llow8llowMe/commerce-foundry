import { renderToStaticMarkup } from 'react-dom/server';
import { describe, expect, it } from 'vitest';
import { costItem, currentOf, packagingItem } from '@/lib/sku-cost/test-fixtures';
import { CostHistoryTable } from './CostHistory';
import { CurrentCostList } from './CurrentCostList';

describe('CurrentCostList', () => {
  it('응답에 없는 유형은 "미입력", 0원은 금액으로 보인다', () => {
    const html = renderToStaticMarkup(<CurrentCostList current={currentOf(packagingItem())} />);
    expect(html).toContain('매입 · 제작 원가');
    expect(html).toContain('미입력');
    expect(html).toContain('포장 변동비');
    expect(html).toContain('0원');
  });

  it('두 유형 모두 있으면 미입력이 없고 금액은 formatMoney 표기다', () => {
    const html = renderToStaticMarkup(
      <CurrentCostList
        current={currentOf(costItem(), packagingItem({ amount: { amount: 350, currency: 'USD' } }))}
      />,
    );
    expect(html).not.toContain('미입력');
    expect(html).toContain('12,000원');
    expect(html).toContain('$3.50');
    expect(html).toContain('10월 원단 단가 인상');
  });
});

describe('CostHistoryTable', () => {
  it('유형 · 금액 · 사유를 보이고 기록한 사람의 회원 아이디 원문은 내지 않는다', () => {
    const html = renderToStaticMarkup(<CostHistoryTable items={[costItem(), packagingItem()]} />);
    expect(html).toContain('매입 · 제작 원가');
    expect(html).toContain('12,000원');
    expect(html).toContain('0원');
    expect(html).toContain('관리자');
    expect(html).not.toContain('4611686018427387001');
  });
});
