import { describe, expect, it } from 'vitest';
import { en } from '@/lib/i18n/dictionaries/en';
import { ko } from '@/lib/i18n/dictionaries/ko';
import {
  sizeChartPath,
  sizeChartSet,
  sizeChartUnit,
  sizeChartView,
  type ProductSizeChart,
} from './size-chart';

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
  it('국문은 cm, 영문은 inch 를 먼저 보이고 단위를 query 로 싣는다', () => {
    expect(sizeChartUnit('ko')).toBe('CM');
    expect(sizeChartUnit('en')).toBe('INCH');
    expect(sizeChartPath('42', null)).toBe('/api/v1/products/42/size-chart');
    expect(sizeChartPath('42', 'INCH')).toBe('/api/v1/products/42/size-chart?unit=INCH');
  });
});

describe('sizeChartSet', () => {
  const INCH: ProductSizeChart = {
    ...CHART,
    displayUnit: { code: 'INCH', name: '인치' },
    rows: [{ sizeLabel: 'M', displayOrder: 0, cells: [{ columnId: '12', value: 27.56 }] }],
  };

  it('두 단위를 cm · inch 순서로 두고, 처음 단위는 언어 기본이다', () => {
    const ko_ = sizeChartSet({ CM: CHART, INCH }, 'ko', ko.product.sizeChart);
    expect(ko_?.initial).toBe('CM');
    expect(ko_?.views.map((entry) => [entry.unit, entry.label])).toEqual([
      ['CM', 'cm'],
      ['INCH', 'inch'],
    ]);
    expect(ko_?.views[1]?.view.rows).toEqual([['M', '', '27.56']]);
    expect(sizeChartSet({ CM: CHART, INCH }, 'en', en.product.sizeChart)?.initial).toBe('INCH');
  });

  it('한 단위만 받았으면 그 단위 하나이고 처음 단위도 그것이다', () => {
    const only = sizeChartSet({ CM: CHART, INCH: null }, 'en', en.product.sizeChart);
    expect(only?.initial).toBe('CM');
    expect(only?.views).toHaveLength(1);
  });

  it('표가 없거나(null) 비었거나 둘 다 실패하면 null — 기존 표시를 둔다', () => {
    expect(sizeChartSet({ CM: null, INCH: null }, 'ko', ko.product.sizeChart)).toBeNull();
    expect(sizeChartSet({}, 'ko', ko.product.sizeChart)).toBeNull();
    const empty = { ...CHART, rows: [] };
    expect(sizeChartSet({ CM: empty, INCH: empty }, 'ko', ko.product.sizeChart)).toBeNull();
  });
});
