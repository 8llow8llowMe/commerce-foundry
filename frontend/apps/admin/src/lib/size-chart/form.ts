import type { FormErrors } from '@/lib/api-errors';
import {
  LIMITS,
  type ProductSizeChart,
  type ProductSizeChartRequest,
  type Template,
  type TemplateColumn,
  type TemplateRequest,
  type UnitCode,
} from './types';

// 사이즈표 입력 규칙 — BE `SizeChartTextValidator` · `SizeChartValueValidator` 와 같은 판정이다(§2-10).
// 정본은 서버다. 화면은 같은 규칙으로 미리 막아 저장 버튼을 누른 뒤에야 알게 되는 일을 줄인다.

export type BuildResult<Request> =
  { ok: true; request: Request } | { ok: false; errors: FormErrors };

/** 제어 문자(\p{Cc}) · 서식 문자(\p{Cf}, 제로폭 공백 등) — 개행 · 탭도 쓸 수 없다 */
const REJECTED = /[\p{Cc}\p{Cf}]/u;

export const REJECTED_CHARACTER = '줄바꿈 · 탭 · 보이지 않는 문자는 쓸 수 없습니다.';
export const VALUE_RULE = '0보다 크고 9999.99 이하인 숫자(소수 둘째 자리까지)로 입력해 주세요.';
export const VALUE_REQUIRED = '치수를 입력해 주세요.';
export const SIZE_LABEL_REQUIRED = '사이즈 이름을 입력해 주세요.';
export const SIZE_LABEL_DUPLICATED = '같은 사이즈 이름이 있습니다.';
export const COLUMN_NAME_REQUIRED = '항목 이름을 입력해 주세요.';
export const COLUMN_NAME_DUPLICATED = '같은 항목 이름이 있습니다.';
export const TEMPLATE_NAME_REQUIRED = '템플릿 이름을 입력해 주세요.';
export const TEMPLATE_REQUIRED = '템플릿을 골라 주세요.';
export const tooLong = (max: number) => `${max}자 이하로 입력해 주세요.`;

/** 앞뒤 공백을 뺀 코드 포인트 수(BE `codePointCount`) */
export const lengthOf = (value: string) => [...value.trim()].length;

/** 텍스트 칸 하나 판정. 빈 값 · 공백만이면 선택 칸은 통과(null 로 보낸다), 필수 칸은 requiredMessage */
export function textError(value: string, max: number, requiredMessage?: string): string | null {
  if (value.trim() === '') return requiredMessage ?? null;
  if (REJECTED.test(value)) return REJECTED_CHARACTER;
  if (lengthOf(value) > max) return tooLong(max);
  return null;
}

const VALUE_PATTERN = /^\d{1,4}(\.\d{1,2})?$/;

/** 치수 칸 글자 → 숫자. 0 초과 9999.99 이하 · 소수 둘째 자리까지가 아니면 null */
export function parseValue(raw: string): number | null {
  const value = raw.trim();
  if (!VALUE_PATTERN.test(value)) return null;
  const number = Number(value);
  return number > 0 && number <= LIMITS.maxValue ? number : null;
}

/** 저장된 치수 → 칸 글자. 서버는 scale 을 다시 매기지 않으므로 숫자 그대로(12.5 → "12.5") */
export const valueText = (value: number) => String(value);

let keySeed = 0;
/** 줄 · 항목 React key. 아이디가 없는 새 줄에도 붙인다 */
export function rowKey(): string {
  keySeed += 1;
  return `k${keySeed}`;
}

function addError(errors: FormErrors, key: string, message: string) {
  if (!(errors[key] ?? []).includes(message)) errors[key] = [...(errors[key] ?? []), message];
}

// ── 템플릿 ──────────────────────────────────────────────

export type ColumnRow = { key: string; columnId: string | null; name: string; description: string };

export type TemplateForm = {
  templateId: string | null;
  name: string;
  baseUnit: UnitCode;
  columns: ColumnRow[];
};

export function emptyColumn(): ColumnRow {
  return { key: rowKey(), columnId: null, name: '', description: '' };
}

const byOrder = <T extends { displayOrder: number }>(items: readonly T[]) =>
  [...items].sort((a, b) => a.displayOrder - b.displayOrder);

export function toTemplateForm(template: Template | null): TemplateForm {
  if (!template) return { templateId: null, name: '', baseUnit: 'CM', columns: [emptyColumn()] };
  return {
    templateId: template.templateId,
    name: template.name,
    baseUnit: template.baseUnit.code === 'INCH' ? 'INCH' : 'CM',
    columns: byOrder(template.columns).map((column) => ({
      key: rowKey(),
      columnId: column.columnId,
      name: column.name,
      description: column.description ?? '',
    })),
  };
}

/**
 * 템플릿 폼 → 요청. 순서는 화면 순서(0부터)다. 기존 항목은 columnId 를 실어 보낸다 —
 * 쓰는 중인 템플릿은 그래야 이름 · 설명 · 순서만 바뀌고, 안 쓰는 템플릿은 서버가 무시하고 새로 만든다.
 */
export function templateRequest(form: TemplateForm): BuildResult<TemplateRequest> {
  const errors: FormErrors = {};
  const nameError = textError(form.name, LIMITS.templateName, TEMPLATE_NAME_REQUIRED);
  if (nameError) addError(errors, 'name', nameError);
  if (form.columns.length < LIMITS.minColumns || form.columns.length > LIMITS.maxColumns) {
    addError(errors, 'columns', `항목은 ${LIMITS.minColumns}~${LIMITS.maxColumns}개여야 합니다.`);
  }
  const seen = new Set<string>();
  form.columns.forEach((column, index) => {
    const error = textError(column.name, LIMITS.columnName, COLUMN_NAME_REQUIRED);
    if (error) addError(errors, `columns.${index}.name`, error);
    else {
      const name = column.name.trim();
      if (seen.has(name)) addError(errors, `columns.${index}.name`, COLUMN_NAME_DUPLICATED);
      seen.add(name);
    }
    const descriptionError = textError(column.description, LIMITS.columnDescription);
    if (descriptionError) addError(errors, `columns.${index}.description`, descriptionError);
  });
  if (Object.keys(errors).length > 0) return { ok: false, errors };
  return {
    ok: true,
    request: {
      name: form.name.trim(),
      baseUnit: form.baseUnit,
      columns: form.columns.map((column, index) => ({
        ...(column.columnId ? { columnId: column.columnId } : {}),
        name: column.name.trim(),
        description: column.description.trim() === '' ? null : column.description.trim(),
        displayOrder: index,
      })),
    },
  };
}

// ── 상품 사이즈표 ────────────────────────────────────────

export type SizeRow = {
  key: string;
  sizeLabel: string;
  /** columnId → 칸 글자 */
  values: Record<string, string>;
};

export type ChartForm = {
  templateId: string | null;
  templateName: string;
  unitName: string;
  columns: TemplateColumn[];
  rows: SizeRow[];
  note: string;
};

export function emptyRow(): SizeRow {
  return { key: rowKey(), sizeLabel: '', values: {} };
}

export function toChartForm(chart: ProductSizeChart | null): ChartForm {
  if (!chart) {
    return {
      templateId: null,
      templateName: '',
      unitName: '',
      columns: [],
      rows: [emptyRow()],
      note: '',
    };
  }
  return {
    templateId: chart.templateId,
    templateName: chart.templateName,
    unitName: chart.baseUnit.name,
    columns: byOrder(chart.columns),
    rows: byOrder(chart.rows).map((row) => ({
      key: rowKey(),
      sizeLabel: row.sizeLabel,
      values: Object.fromEntries(row.cells.map((cell) => [cell.columnId, valueText(cell.value)])),
    })),
    note: chart.note ?? '',
  };
}

/**
 * 템플릿을 바꾼다. 다른 템플릿이면 항목 아이디가 달라 치수는 비우고 사이즈 이름만 남긴다(단위도 다를 수 있다).
 * 같은 템플릿을 다시 읽으면(저장 중 `PRODUCT_066~068` — 그사이 항목이 새 아이디로 다시 만들어짐) 아이디가 같은 칸은 그대로,
 * 아이디가 바뀐 칸은 항목 **이름**이 같으면 옮겨 남긴다. 이름도 없어진 항목의 치수만 지워진다.
 */
export function withTemplate(form: ChartForm, template: Template): ChartForm {
  const columns = byOrder(template.columns);
  const sameTemplate = form.templateId === template.templateId;
  const oldIdByName = new Map(form.columns.map((column) => [column.name.trim(), column.columnId]));
  return {
    ...form,
    templateId: template.templateId,
    templateName: template.name,
    unitName: template.baseUnit.name,
    columns,
    rows: form.rows.map((row) => ({
      ...row,
      values: Object.fromEntries(
        columns.flatMap((column) => {
          const kept = row.values[column.columnId];
          const oldId = sameTemplate ? oldIdByName.get(column.name.trim()) : undefined;
          const moved = oldId === undefined ? undefined : row.values[oldId];
          const value = kept ?? moved;
          return value === undefined ? [] : [[column.columnId, value]];
        }),
      ),
    })),
  };
}

/** 같은 템플릿을 다시 읽어 치수가 일부 지워졌는가 — 안내 문구를 고른다 */
export function droppedValues(before: ChartForm, after: ChartForm): boolean {
  const count = (form: ChartForm) =>
    form.rows.reduce(
      (sum, row) => sum + Object.values(row.values).filter((v) => v.trim()).length,
      0,
    );
  return count(after) < count(before);
}

/** 오류 칸 키 — 화면 칸과 서버 경로를 잇는다 */
export const cellKey = (rowIndex: number, columnId: string) => `rows.${rowIndex}.${columnId}`;
export const labelKey = (rowIndex: number) => `rows.${rowIndex}.sizeLabel`;

export function chartRequest(form: ChartForm): BuildResult<ProductSizeChartRequest> {
  const errors: FormErrors = {};
  if (!form.templateId) addError(errors, 'templateId', TEMPLATE_REQUIRED);
  if (form.rows.length < LIMITS.minRows || form.rows.length > LIMITS.maxRows) {
    addError(errors, 'rows', `사이즈는 ${LIMITS.minRows}~${LIMITS.maxRows}개여야 합니다.`);
  }
  const noteError = textError(form.note, LIMITS.note);
  if (noteError) addError(errors, 'note', noteError);

  const seen = new Set<string>();
  const rows = form.rows.map((row, rowIndex) => {
    const labelError = textError(row.sizeLabel, LIMITS.sizeLabel, SIZE_LABEL_REQUIRED);
    if (labelError) addError(errors, labelKey(rowIndex), labelError);
    else {
      const label = row.sizeLabel.trim();
      if (seen.has(label)) addError(errors, labelKey(rowIndex), SIZE_LABEL_DUPLICATED);
      seen.add(label);
    }
    const cells = form.columns.map((column) => {
      const raw = row.values[column.columnId] ?? '';
      const value = parseValue(raw);
      if (value === null) {
        addError(
          errors,
          cellKey(rowIndex, column.columnId),
          raw.trim() ? VALUE_RULE : VALUE_REQUIRED,
        );
      }
      return { columnId: column.columnId, value: value ?? 0 };
    });
    return { sizeLabel: row.sizeLabel.trim(), displayOrder: rowIndex, cells };
  });

  if (Object.keys(errors).length > 0 || !form.templateId) return { ok: false, errors };
  return {
    ok: true,
    request: {
      templateId: form.templateId,
      note: form.note.trim() === '' ? null : form.note.trim(),
      rows,
    },
  };
}

// ── 붙여넣기 ────────────────────────────────────────────

/**
 * 엑셀 · 구글 시트에서 복사한 글자(줄은 개행, 칸은 탭)를 표로 나눈다. 탭이 없으면 쉼표로 나눈다(CSV).
 * 끝의 빈 줄은 버린다. 칸 앞뒤 공백은 뺀다. 숫자의 단위 꼬리(`52cm`)와 천 단위 쉼표는 남겨 두고 칸 판정에 맡긴다.
 */
export function parsePastedTable(text: string): string[][] {
  const lines = text.replace(/\r\n?/g, '\n').split('\n');
  while (lines.length > 0 && lines.at(-1)?.trim() === '') lines.pop();
  const separator = text.includes('\t') ? '\t' : ',';
  return lines.map((line) => line.split(separator).map((cell) => cell.trim()));
}

export type PasteResult = { form: ChartForm; notice: string | null };

/**
 * 붙여넣은 표를 (rowIndex, 시작 칸)부터 오른쪽 · 아래로 채운다. 시작 칸 -1 은 사이즈 이름 칸이다.
 * - 첫 줄이 머리줄(항목 이름과 같은 칸이 있다)이면 건너뛴다.
 * - 줄이 모자라면 새 줄을 더한다(최대 30줄). 넘치는 줄 · 칸은 버리고 알린다.
 */
export function pasteIntoChart(
  form: ChartForm,
  rowIndex: number,
  startColumn: number,
  table: string[][],
): PasteResult {
  const names = new Set(form.columns.map((column) => column.name.trim()));
  const body =
    table.length > 1 && table[0]?.some((cell) => names.has(cell)) ? table.slice(1) : table;
  const rows = [...form.rows];
  let droppedRows = 0;
  let droppedCells = 0;

  body.forEach((cells, offset) => {
    const target = rowIndex + offset;
    if (target >= LIMITS.maxRows) {
      droppedRows += 1;
      return;
    }
    const row = rows[target] ?? emptyRow();
    const values = { ...row.values };
    let sizeLabel = row.sizeLabel;
    cells.forEach((cell, index) => {
      const position = startColumn + index;
      if (position === -1) sizeLabel = cell;
      else {
        const column = form.columns[position];
        if (column) values[column.columnId] = cell;
        else droppedCells += 1;
      }
    });
    rows[target] = { ...row, sizeLabel, values };
  });

  const notices: string[] = [];
  if (droppedRows > 0)
    notices.push(`사이즈는 ${LIMITS.maxRows}개까지라 ${droppedRows}줄은 넣지 않았습니다.`);
  if (droppedCells > 0) notices.push(`항목 수를 넘는 칸 ${droppedCells}개는 넣지 않았습니다.`);
  return { form: { ...form, rows }, notice: notices.length > 0 ? notices.join(' ') : null };
}
