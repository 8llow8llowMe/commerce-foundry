import { describe, expect, it } from 'vitest';
import { driveSteps, escapeHtml } from './steps';
import {
  ALL_TOURS,
  CONSOLE_TOUR,
  helpTopicFor,
  PRODUCT_NEW_TOUR,
  toursFor,
  type Tour,
} from './tours';

describe('helpTopicFor · toursFor', () => {
  it('경로마다 도움말을 고르고, 없는 경로는 기본 도움말이다', () => {
    expect(helpTopicFor('/size-charts/42').title).toBe('사이즈표 입력');
    expect(helpTopicFor('/size-charts').title).toBe('상품 사이즈표');
    expect(helpTopicFor('/stocks/7').title).toBe('옵션별 재고');
    expect(helpTopicFor('/jobs/9').title).toBe('백그라운드 작업');
    expect(helpTopicFor('/practice').title).toBe('샘플 주문 업무 연습');
    expect(helpTopicFor('/practice').tour).toBeNull();
    expect(helpTopicFor('/nope').title).toBe('도움말');
  });

  it('콘솔 투어가 먼저, 화면 투어가 있으면 그다음이다', () => {
    expect(toursFor('/members').map((tour) => tour.id)).toEqual([CONSOLE_TOUR.id]);
    expect(toursFor('/stocks').map((tour) => tour.id)).toEqual([CONSOLE_TOUR.id, 'stocks-v1']);
    expect(toursFor('/orders').map((tour) => tour.id)).toEqual([CONSOLE_TOUR.id, 'orders-v2']);
    expect(toursFor('/orders/42').map((tour) => tour.id)).toEqual([
      CONSOLE_TOUR.id,
      'order-detail-v2',
    ]);
    expect(toursFor('/products/new').map((tour) => tour.id)).toEqual([
      CONSOLE_TOUR.id,
      PRODUCT_NEW_TOUR.id,
    ]);
    expect(toursFor('/products').map((tour) => tour.id)).toEqual([CONSOLE_TOUR.id]);
    expect(toursFor('/products/42').map((tour) => tour.id)).toEqual([CONSOLE_TOUR.id]);
    expect(toursFor('/products/42/edit').map((tour) => tour.id)).toEqual([
      CONSOLE_TOUR.id,
      'product-edit-v1',
    ]);
    expect(toursFor('/payments').map((tour) => tour.id)).toEqual([CONSOLE_TOUR.id, 'payments-v1']);
  });

  it('상품 상세 · 수정 · 결제 확인 화면에 도움말이 있다(상품 등록 경로가 상세 패턴에 먹히지 않는다)', () => {
    expect(helpTopicFor('/products/new').title).toBe('상품 등록');
    expect(helpTopicFor('/products/42').title).toBe('상품 상세');
    expect(helpTopicFor('/products/42/edit').title).toBe('상품 기본 정보 수정');
    expect(helpTopicFor('/products/42/edit/pricing').title).toBe('상품 구역 수정');
    expect(toursFor('/products/42/edit/options').map((tour) => tour.id)).toEqual([CONSOLE_TOUR.id]);
    expect(helpTopicFor('/payments').title).toBe('결제 확인');
  });

  it('결제 확인 투어는 표가 빈 목록이면 없어 선택이고, 읽기 전용 안내는 가운데에 뜬다', () => {
    expect(
      toursFor('/payments')[1]?.steps.map((step) => [step.target, step.optional ?? false]),
    ).toEqual([
      ['payment-filters', false],
      ['payment-table', true],
      [null, false],
    ]);
  });

  it('송장 단계는 주문 투어의 상태 변경 뒤 · 이력 앞에 있고, 상세의 송장은 준비중 주문에만 있어 선택이다', () => {
    const targets = (pathname: string) =>
      toursFor(pathname)[1]?.steps.map((step) => [step.target, step.optional ?? false]);
    expect(targets('/orders')).toEqual([
      ['order-filters', false],
      ['order-table', true],
      ['order-bulk', false],
      ['order-shipment-bulk', false],
    ]);
    expect(targets('/orders/42')).toEqual([
      ['order-status', false],
      ['order-status-actions', false],
      ['order-shipment', true],
      ['order-histories', false],
    ]);
  });

  it('상품 등록 투어(v2)는 폼 구역 순서대로 이미지 칸을 포함한 필수 앵커만 가리키고, 문구는 평문이다', () => {
    expect(PRODUCT_NEW_TOUR.id).toBe('product-new-v2');
    expect(PRODUCT_NEW_TOUR.steps.map((step) => [step.target, step.optional ?? false])).toEqual([
      ['product-basic', false],
      ['product-images', false],
      ['product-price', false],
      ['product-options', false],
      ['product-skus', false],
      ['product-submit', false],
    ]);
    for (const step of PRODUCT_NEW_TOUR.steps) {
      expect(`${step.title}${step.body}`).not.toMatch(/[<>&]/);
    }
  });

  it('투어 아이디는 겹치지 않는다', () => {
    const ids = ALL_TOURS.map((tour) => tour.id);
    expect(new Set(ids).size).toBe(ids.length);
  });

  it('투어 문구에는 HTML 로 읽힐 글자가 없다(driver.js 가 innerHTML 로 넣는다)', () => {
    for (const step of ALL_TOURS.flatMap((tour) => tour.steps)) {
      expect(`${step.title}${step.body}`).not.toMatch(/[<>&]/);
    }
  });

  it('투어마다 필수 요소를 가리키는 단계가 하나 이상 있거나, 가리키는 요소가 없다', () => {
    for (const tour of ALL_TOURS) {
      const targeted = tour.steps.filter((step) => step.target !== null);
      if (targeted.length > 0) expect(targeted.some((step) => !step.optional)).toBe(true);
    }
  });
});

describe('driveSteps', () => {
  const tour: Tour = {
    id: 't',
    steps: [
      { target: null, title: '처음', body: '<b>안내</b>' },
      { target: 'a', title: 'A', body: 'a', side: 'right' },
      { target: 'b', title: 'B', body: 'b', optional: true },
    ],
  };

  it('없는 선택 요소 단계를 빼고 문구를 이스케이프한다', () => {
    const steps = driveSteps(tour, (target) => target === 'a');
    expect(steps).toEqual([
      { popover: { title: '처음', description: '&lt;b&gt;안내&lt;/b&gt;' } },
      { element: '[data-tour="a"]', popover: { title: 'A', description: 'a', side: 'right' } },
    ]);
    expect(driveSteps(tour, () => true)).toHaveLength(3);
  });

  it('필수 요소가 없으면 시작하지 않는다', () => {
    expect(driveSteps(tour, (target) => target === 'b')).toBeNull();
    const centered: Tour = { id: 'c', steps: [{ target: null, title: 'x', body: 'y' }] };
    expect(driveSteps(centered, () => false)).toHaveLength(1);
  });

  it('escapeHtml', () => {
    expect(escapeHtml(`a&b<"'>`)).toBe('a&amp;b&lt;&quot;&#39;&gt;');
  });
});
