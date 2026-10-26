import { describe, expect, it } from 'vitest';
import { driveSteps, escapeHtml } from './steps';
import { ALL_TOURS, CONSOLE_TOUR, helpTopicFor, toursFor, type Tour } from './tours';

describe('helpTopicFor · toursFor', () => {
  it('경로마다 도움말을 고르고, 없는 경로는 기본 도움말이다', () => {
    expect(helpTopicFor('/size-charts/42').title).toBe('사이즈표 입력');
    expect(helpTopicFor('/size-charts').title).toBe('상품 사이즈표');
    expect(helpTopicFor('/stocks/7').title).toBe('옵션별 재고');
    expect(helpTopicFor('/jobs/9').title).toBe('백그라운드 작업');
    expect(helpTopicFor('/nope').title).toBe('도움말');
  });

  it('콘솔 투어가 먼저, 화면 투어가 있으면 그다음이다', () => {
    expect(toursFor('/members').map((tour) => tour.id)).toEqual([CONSOLE_TOUR.id]);
    expect(toursFor('/stocks').map((tour) => tour.id)).toEqual([CONSOLE_TOUR.id, 'stocks-v1']);
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
