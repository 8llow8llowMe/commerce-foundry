import { describe, expect, it } from 'vitest';
import {
  cellKey,
  chartRequest,
  labelKey,
  parsePastedTable,
  parseValue,
  pasteIntoChart,
  REJECTED_CHARACTER,
  SIZE_LABEL_DUPLICATED,
  templateRequest,
  textError,
  toChartForm,
  toTemplateForm,
  tooLong,
  VALUE_REQUIRED,
  VALUE_RULE,
  withTemplate,
  droppedValues,
  type ChartForm,
} from './form';
import type { ProductSizeChart, Template } from './types';

const unit = (code: 'CM' | 'INCH') => ({
  code,
  name: code === 'CM' ? '센티미터' : '인치',
  description: '',
});

const TEMPLATE: Template = {
  templateId: '7',
  name: '상의',
  baseUnit: unit('CM'),
  columns: [
    { columnId: '12', name: '총장', description: null, displayOrder: 1 },
    { columnId: '11', name: '가슴 단면', description: '겨드랑이 끝에서 끝', displayOrder: 0 },
  ],
};

const CHART: ProductSizeChart = {
  productId: '1',
  templateId: '7',
  templateName: '상의',
  baseUnit: unit('CM'),
  note: null,
  columns: TEMPLATE.columns,
  rows: [
    {
      sizeLabel: 'L',
      displayOrder: 1,
      cells: [
        { columnId: '11', value: 55 },
        { columnId: '12', value: 72.5 },
      ],
    },
    {
      sizeLabel: 'M',
      displayOrder: 0,
      cells: [
        { columnId: '11', value: 52 },
        { columnId: '12', value: 70 },
      ],
    },
  ],
};

describe('textError', () => {
  it('필수 칸이 비거나 공백만이면 필수 문구, 선택 칸은 통과', () => {
    expect(textError(' \u3000', 20, '필수')).toBe('필수');
    expect(textError('', 20)).toBeNull();
  });

  it('줄바꿈 · 탭 · 제로폭 공백은 거절한다(BE 제어 · 서식 문자)', () => {
    expect(textError('M\n', 20)).toBe(REJECTED_CHARACTER);
    expect(textError('M\t1', 20)).toBe(REJECTED_CHARACTER);
    expect(textError('M\u200b', 20)).toBe(REJECTED_CHARACTER);
  });

  it('길이는 앞뒤 공백을 뺀 코드 포인트 수다', () => {
    expect(textError(`  ${'가'.repeat(20)}  `, 20)).toBeNull();
    expect(textError('😀'.repeat(20), 20)).toBeNull();
    expect(textError('가'.repeat(21), 20)).toBe(tooLong(20));
  });
});

describe('parseValue', () => {
  it('0 초과 9999.99 이하, 소수 둘째 자리까지만 받는다', () => {
    expect(parseValue(' 52 ')).toBe(52);
    expect(parseValue('72.55')).toBe(72.55);
    expect(parseValue('9999.99')).toBe(9999.99);
    for (const bad of ['0', '0.00', '10000', '1.234', '-1', '52cm', '1,000', '', '.5', '1e2']) {
      expect(parseValue(bad)).toBeNull();
    }
  });
});

describe('templateRequest', () => {
  it('순서를 화면 순서(0부터)로 매기고 기존 항목은 columnId 를 싣는다', () => {
    const form = toTemplateForm(TEMPLATE);
    expect(form.columns.map((column) => column.name)).toEqual(['가슴 단면', '총장']);
    const built = templateRequest({ ...form, name: ' 상의 ' });
    expect(built).toEqual({
      ok: true,
      request: {
        name: '상의',
        baseUnit: 'CM',
        columns: [
          { columnId: '11', name: '가슴 단면', description: '겨드랑이 끝에서 끝', displayOrder: 0 },
          { columnId: '12', name: '총장', description: null, displayOrder: 1 },
        ],
      },
    });
  });

  it('새 항목은 columnId 없이 보낸다', () => {
    const form = toTemplateForm(null);
    const built = templateRequest({
      ...form,
      name: '하의',
      columns: [{ ...form.columns[0]!, name: '허리' }],
    });
    expect(built.ok && built.request.columns[0]).toEqual({
      name: '허리',
      description: null,
      displayOrder: 0,
    });
  });

  it('이름 · 항목 이름 필수, 항목 이름 중복을 칸에 붙인다', () => {
    const form = toTemplateForm(TEMPLATE);
    const built = templateRequest({
      ...form,
      name: '',
      columns: form.columns.map((column) => ({ ...column, name: '총장' })),
    });
    expect(built.ok).toBe(false);
    if (built.ok) return;
    expect(Object.keys(built.errors).sort()).toEqual(['columns.1.name', 'name']);
  });
});

describe('chartRequest', () => {
  it('줄 순서대로 displayOrder 를 매기고 칸은 항목 순서다', () => {
    const form = toChartForm(CHART);
    expect(form.rows.map((row) => row.sizeLabel)).toEqual(['M', 'L']);
    expect(form.rows[1]?.values).toEqual({ '11': '55', '12': '72.5' });
    const built = chartRequest({ ...form, note: '  ' });
    expect(built).toEqual({
      ok: true,
      request: {
        templateId: '7',
        note: null,
        rows: [
          {
            sizeLabel: 'M',
            displayOrder: 0,
            cells: [
              { columnId: '11', value: 52 },
              { columnId: '12', value: 70 },
            ],
          },
          {
            sizeLabel: 'L',
            displayOrder: 1,
            cells: [
              { columnId: '11', value: 55 },
              { columnId: '12', value: 72.5 },
            ],
          },
        ],
      },
    });
  });

  it('빈 칸 · 형식 오류 · 사이즈 이름 중복을 그 칸에 붙인다', () => {
    const form = toChartForm(CHART);
    const broken: ChartForm = {
      ...form,
      rows: [
        { ...form.rows[0]!, values: { '11': '', '12': '70cm' } },
        { ...form.rows[1]!, sizeLabel: 'M' },
      ],
    };
    const built = chartRequest(broken);
    expect(built.ok).toBe(false);
    if (built.ok) return;
    expect(built.errors[cellKey(0, '11')]).toEqual([VALUE_REQUIRED]);
    expect(built.errors[cellKey(0, '12')]).toEqual([VALUE_RULE]);
    expect(built.errors[labelKey(1)]).toEqual([SIZE_LABEL_DUPLICATED]);
  });

  it('템플릿을 고르지 않으면 보내지 않는다', () => {
    const built = chartRequest(toChartForm(null));
    expect(built.ok).toBe(false);
    if (!built.ok) expect(built.errors.templateId).toBeDefined();
  });
});

describe('withTemplate', () => {
  it('다른 템플릿이면 치수를 비우고 사이즈 이름은 남긴다', () => {
    const other: Template = {
      ...TEMPLATE,
      templateId: '8',
      columns: [{ columnId: '21', name: '허리', description: null, displayOrder: 0 }],
    };
    const next = withTemplate(toChartForm(CHART), other);
    expect(next.templateId).toBe('8');
    expect(next.rows.map((row) => [row.sizeLabel, row.values])).toEqual([
      ['M', {}],
      ['L', {}],
    ]);
  });

  it('같은 템플릿을 다시 읽어 항목 아이디가 바뀌면 이름이 같은 칸으로 옮기고, 없어진 항목만 지운다', () => {
    const form = toChartForm(CHART);
    const reread: Template = {
      ...TEMPLATE,
      columns: [
        { columnId: '31', name: '가슴 단면', description: null, displayOrder: 0 },
        { columnId: '32', name: '소매', description: null, displayOrder: 1 },
      ],
    };
    const next = withTemplate(form, reread);
    expect(next.rows[0]?.values).toEqual({ '31': '52' });
    expect(droppedValues(form, next)).toBe(true);
    expect(droppedValues(form, withTemplate(form, TEMPLATE))).toBe(false);
  });

  it('같은 항목이면 치수를 그대로 둔다', () => {
    const next = withTemplate(toChartForm(CHART), TEMPLATE);
    expect(next.rows[0]?.values).toEqual({ '11': '52', '12': '70' });
  });
});

describe('붙여넣기', () => {
  it('시트 복사(탭 · CRLF)와 CSV 를 나누고 끝 빈 줄을 버린다', () => {
    expect(parsePastedTable('S\t50\t68\r\nM\t52\t70\r\n')).toEqual([
      ['S', '50', '68'],
      ['M', '52', '70'],
    ]);
    expect(parsePastedTable('S, 50\nM,52\n\n')).toEqual([
      ['S', '50'],
      ['M', '52'],
    ]);
  });

  it('사이즈 칸부터 붙이면 줄을 더해 채우고, 머리줄은 건너뛴다', () => {
    const form = withTemplate(toChartForm(null), TEMPLATE);
    const table = parsePastedTable('사이즈\t가슴 단면\t총장\nS\t50\t68\nM\t52\t70');
    const { form: next, notice } = pasteIntoChart(form, 0, -1, table);
    expect(notice).toBeNull();
    expect(next.rows.map((row) => [row.sizeLabel, row.values])).toEqual([
      ['S', { '11': '50', '12': '68' }],
      ['M', { '11': '52', '12': '70' }],
    ]);
  });

  it('치수 칸부터 붙이면 그 칸부터 오른쪽으로, 넘치는 칸 · 줄은 버리고 알린다', () => {
    const form = toChartForm(CHART);
    const { form: next, notice } = pasteIntoChart(form, 1, 1, [['73', '99']]);
    expect(next.rows[1]?.values).toEqual({ '11': '55', '12': '73' });
    expect(next.rows[1]?.sizeLabel).toBe('L');
    expect(notice).toContain('1개');

    const many = Array.from({ length: 31 }, (_, i) => [`S${i}`]);
    const filled = pasteIntoChart(form, 0, -1, many);
    expect(filled.form.rows).toHaveLength(30);
    expect(filled.notice).toContain('1줄');
  });
});
