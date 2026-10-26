/**
 * 어드민 사이즈표 화면이 쓰는 API 계약 — product-service `SizeChartTemplateAdminController` ·
 * `ProductSizeChartAdminController`(#216). 정본은 `backend/docs/api-design-guide.md` §2-10.
 * 아이디는 문자열이다. 치수는 금액이 아니라 JSON number(소수 둘째 자리까지)다.
 */

export const TEMPLATES_PATH = '/api/v1/admin/size-chart-templates';
export const productSizeChartPath = (productId: string) =>
  `/api/v1/admin/products/${encodeURIComponent(productId)}/size-chart`;
export const templatePath = (templateId: string) =>
  `${TEMPLATES_PATH}/${encodeURIComponent(templateId)}`;

/** 입력 상한 — BE `SizeChartLimits` 와 같다(앞뒤 공백을 뺀 코드 포인트 수) */
export const LIMITS = {
  templateName: 50,
  columnName: 30,
  columnDescription: 200,
  sizeLabel: 20,
  note: 500,
  minColumns: 1,
  maxColumns: 20,
  minRows: 1,
  maxRows: 30,
  maxValue: 9999.99,
} as const;

export const UNITS = ['CM', 'INCH'] as const;
export type UnitCode = (typeof UNITS)[number];

/** 새 템플릿의 단위 고르기에만 쓰는 사본. 저장된 값은 서버 `baseUnit.name` 을 보인다 */
export const UNIT_LABELS: Record<UnitCode, string> = { CM: '센티미터(cm)', INCH: '인치(in)' };

export type CodeName = { code: string; name: string; description: string };

export type TemplateSummary = {
  templateId: string;
  name: string;
  baseUnit: CodeName;
  columnCount: number;
};

export type TemplateColumn = {
  columnId: string;
  name: string;
  description: string | null;
  displayOrder: number;
};

export type Template = {
  templateId: string;
  name: string;
  baseUnit: CodeName;
  columns: TemplateColumn[];
};

export type TemplateRequest = {
  name: string;
  baseUnit: UnitCode;
  /** 쓰는 중인 템플릿은 기존 columnId 를 실어야 이름 · 설명 · 순서만 바뀐다(아니면 409 PRODUCT_062) */
  columns: { columnId?: string; name: string; description: string | null; displayOrder: number }[];
};

export type SizeChartRow = {
  sizeLabel: string;
  displayOrder: number;
  cells: { columnId: string; value: number }[];
};

/** 어드민 상품 사이즈표 — 값은 저장 단위 그대로. 표가 없으면 GET 이 null 이다 */
export type ProductSizeChart = {
  productId: string;
  templateId: string;
  templateName: string;
  baseUnit: CodeName;
  note: string | null;
  columns: TemplateColumn[];
  rows: SizeChartRow[];
};

export type ProductSizeChartRequest = {
  templateId: string;
  note: string | null;
  /** 행마다 그 템플릿 항목을 빠짐없이 한 번씩 */
  rows: SizeChartRow[];
};
