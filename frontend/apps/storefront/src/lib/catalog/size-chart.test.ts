import { describe, expect, it } from 'vitest';
import { en } from '@/lib/i18n/dictionaries/en';
import { ko } from '@/lib/i18n/dictionaries/ko';
import { sizeChartPath, sizeChartUnit, sizeChartView, type ProductSizeChart } from './size-chart';

const CHART: ProductSizeChart = {
  productId: '1',
  templateId: '7',
  templateName: '상의',
  baseUnit: { code: 'CM', name: '센티미터' },
  displayUnit: { code: 'CM', name: '센티미터' },
  note: '재는 방법에 따라 1~2cm 차이',
  columns: [
    { columnId: '12', name: '총장', description: null, displayOrder: 1 },
    { columnId: '11', name: '가슴 단면', description: '겨드랑이 끝에서 끝', displayOrder: 0 },
  ],
  rows: [
    {
      sizeLabel: 'L',
      displayOrder: 1,
      cells: [
        { columnId: '11', value: 1055 },
        { columnId: '12', value: 72.5 },
      ],
    },
    { sizeLabel: 'M', displayOrder: 0, cells: [{ columnId: '12', value: 70 }] },
  ],
};

describe('sizeChartView', () => {
  it('항목 · 사이즈를 순서대로 놓고 첫 칸은 사이즈 열이다', () => {
    const view = sizeChartView(CHART, 'ko', ko.product.sizeChart);
    expect(view.columns).toEqual(['사이즈', '가슴 단면', '총장']);
    expect(view.rows).toEqual([
      ['M', '', '70'],
      ['L', '1,055', '72.5'],
    ]);
  });

  it('단위를 안내 첫머리에, 설명이 있는 항목을 재는 방법으로 둔다', () => {
    const view = sizeChartView(CHART, 'ko', ko.product.sizeChart);
    expect(view.note).toBe('단위 cm · 재는 방법에 따라 1~2cm 차이');
    expect(view.measures).toEqual([{ name: '가슴 단면', description: '겨드랑이 끝에서 끝' }]);
    const plain = sizeChartView({ ...CHART, note: null }, 'ko', ko.product.sizeChart);
    expect(plain.note).toBe('단위 cm');
  });

  it('영문은 영문 머리 · 단위 문구이고, 단위는 응답의 displayUnit 이다', () => {
    const view = sizeChartView(
      { ...CHART, note: null, displayUnit: { code: 'INCH', name: '인치' } },
      'en',
      en.product.sizeChart,
    );
    expect(view.columns[0]).toBe('Size');
    expect(view.note).toBe('Measurements in inches');
  });
});

describe('sizeChartUnit · sizeChartPath', () => {
  it('영문 스토어(#31)를 열기 전에는 모든 언어가 저장 단위다(unit 을 싣지 않는다)', () => {
    expect(sizeChartUnit('ko')).toBeNull();
    expect(sizeChartUnit('en')).toBeNull();
    expect(sizeChartPath('42', null)).toBe('/api/v1/products/42/size-chart');
    expect(sizeChartPath('42', 'INCH')).toBe('/api/v1/products/42/size-chart?unit=INCH');
  });
});
