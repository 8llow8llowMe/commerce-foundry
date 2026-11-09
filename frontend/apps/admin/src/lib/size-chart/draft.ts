import { ApiError } from '@cf/api-client';
import { accessDeniedOf, type AccessDenied, type FormErrors } from '@/lib/api-errors';
import { CHECK_FIELDS, chartFieldKey, withSummary } from './errors';
import { rowKey, valueText, type ChartForm } from './form';
import type { CodeName, TemplateColumn } from './types';

/**
 * AI 사이즈표 초안(BE #217, product-service `SizeChartDraftAdminController`). 정본은 `backend/docs/ai-size-chart-guide.md`.
 * - 생성 `POST /products/{id}/size-chart/drafts` — UUID `Idempotency-Key` 필수, `{templateId, mode:'TEXT', sourceText}` → 202 `{draftId, jobId}`
 * - 상세 `GET /size-chart-drafts/{id}` — 저장된 상태만 읽는다(작업 결과를 가져오지 않는다)
 * - 목록 `GET /size-chart-drafts?productId=&requestedByMe=&page=&size=` — 최신순, 만료 초안 제외
 * - 새로고침 `POST /{id}/refresh` — 끝난 작업 결과를 검증해 초안에 반영(감사 기록이 남아 반복 호출하지 않는다)
 * - 적용 `POST /{id}/apply` — `{expectedDraftVersion, correctedChart, acknowledgedWarningIds}` → 상품 표 전체 교체
 * 지금은 TEXT 만 열려 있다. IMAGE · 일괄 이관(batches)은 BE 후속 범위라 화면에 없다.
 */
export const SOURCE_TEXT_MAX = 12000;

const DRAFTS_PATH = '/api/v1/admin/size-chart-drafts';
export const createDraftPath = (productId: string) =>
  `/api/v1/admin/products/${encodeURIComponent(productId)}/size-chart/drafts`;
export const draftPath = (draftId: string) => `${DRAFTS_PATH}/${encodeURIComponent(draftId)}`;
export const refreshDraftPath = (draftId: string) => `${draftPath(draftId)}/refresh`;
export const applyDraftPath = (draftId: string) => `${draftPath(draftId)}/apply`;
/** 이 상품에서 내가 요청한 최근 초안 — 새로 고침 · 다른 화면에 다녀와도 이어 보게 */
export const recentDraftsPath = (productId: string) =>
  `${DRAFTS_PATH}?productId=${encodeURIComponent(productId)}&requestedByMe=true&page=0&size=5`;

export type DraftStatusCode = 'REQUESTED' | 'READY' | 'FAILED' | 'CANCELLED' | 'APPLIED';

export type DraftAccepted = { draftId: string; jobId: string };

export type DraftWarning = {
  warningId: string;
  type: CodeName;
  /** 0부터 센 후보 행 위치 */
  rowIndex: number;
  /** 행 전체 경고면 null */
  columnId: string | null;
};

export type SizeChartDraft = {
  draftId: string;
  productId: string;
  jobId: string | null;
  status: CodeName;
  version: number;
  expiresAt: string;
  template: {
    templateId: string;
    revision: number;
    unit: CodeName;
    columns: { columnId: string; name: string }[];
  };
  /** 생성 전에는 null. 빠진 치수는 value null(0으로 채우지 않는다) */
  candidate: {
    unit: CodeName;
    rows: { sizeLabel: string; cells: { columnId: string; value: number | null }[] }[];
    warnings: DraftWarning[];
  } | null;
};

export type DraftSummary = {
  draftId: string;
  productId: string;
  templateId: string;
  jobId: string | null;
  status: CodeName;
  version: number;
  expiresAt: string;
};

export type DraftApplyResult = {
  draftId: string;
  productId: string;
  chartId: string;
  chartRevision: number;
  appliedAt: string;
};

export const statusOf = (draft: Pick<SizeChartDraft, 'status'>) =>
  draft.status.code as DraftStatusCode;

export const isExpired = (draft: Pick<SizeChartDraft, 'expiresAt'>, now: number) =>
  Date.parse(draft.expiresAt) <= now;

/** 이어서 볼 만한 초안 — 생성 중이거나 검토 대기이고 만료 전인 가장 최근 것 */
export function resumableDraft(list: readonly DraftSummary[], now: number): DraftSummary | null {
  return (
    list.find(
      (draft) =>
        (statusOf(draft) === 'REQUESTED' || statusOf(draft) === 'READY') && !isExpired(draft, now),
    ) ?? null
  );
}

/**
 * 생성 요청 멱등 키. "같은 템플릿 + 같은 텍스트" 한 번의 의도에 키 하나 — 응답을 못 받았거나(네트워크 · 503) 두 번 눌러도
 * 같은 키를 다시 보내 서버가 같은 초안 · 작업을 돌려준다(새 과금 없음). 입력이 바뀌면 새 키다.
 * 키는 메모리에만 둔다 — 7일이 지난 키를 자동으로 다시 보내지 않게(가이드 §3).
 */
export type DraftKey = { key: string; fingerprint: string };

export const draftFingerprint = (templateId: string, sourceText: string) =>
  `${templateId}\n${sourceText.trim()}`;

export function keyFor(
  previous: DraftKey | null,
  templateId: string,
  sourceText: string,
  create: () => string,
): DraftKey {
  const fingerprint = draftFingerprint(templateId, sourceText);
  return previous?.fingerprint === fingerprint ? previous : { key: create(), fingerprint };
}

/** 초안 후보 → 편집 표. 칸은 초안이 만든 당시 템플릿 스냅숏이다. 빠진 칸은 빈칸(운영자가 채워야 적용된다) */
export function draftToForm(
  draft: SizeChartDraft,
  base: Pick<ChartForm, 'note'>,
  templateName: string,
): ChartForm | null {
  if (!draft.candidate) return null;
  const columns: TemplateColumn[] = draft.template.columns.map((column, index) => ({
    columnId: column.columnId,
    name: column.name,
    description: null,
    displayOrder: index,
  }));
  return {
    templateId: draft.template.templateId,
    templateName,
    unitName: draft.candidate.unit.name,
    columns,
    rows: draft.candidate.rows.map((row) => ({
      key: rowKey(),
      sizeLabel: row.sizeLabel,
      values: Object.fromEntries(
        row.cells.flatMap((cell) =>
          cell.value === null ? [] : [[cell.columnId, valueText(cell.value)]],
        ),
      ),
    })),
    note: base.note,
  };
}

/** 후보 요약 — 사이즈 수 · 빠진 칸 수 · 경고 수 */
export function candidateSummary(draft: SizeChartDraft): {
  rows: number;
  missing: number;
  warnings: number;
} {
  const candidate = draft.candidate;
  if (!candidate) return { rows: 0, missing: 0, warnings: 0 };
  const columns = draft.template.columns.length;
  const missing = candidate.rows.reduce(
    (sum, row) => sum + columns - row.cells.filter((cell) => cell.value !== null).length,
    0,
  );
  return { rows: candidate.rows.length, missing, warnings: candidate.warnings.length };
}

/** 경고 한 줄 — "M 가슴 단면: 다른 치수와 차이가 큽니다…". 위치는 후보(서버) 순서 기준이다 */
export function warningText(draft: SizeChartDraft, warning: DraftWarning): string {
  const row = draft.candidate?.rows[warning.rowIndex];
  const rowName = row?.sizeLabel.trim() || `${warning.rowIndex + 1}번째 줄`;
  const column = warning.columnId
    ? draft.template.columns.find((c) => c.columnId === warning.columnId)
    : undefined;
  return `${rowName}${column ? ` ${column.name}` : ''}: ${warning.type.name}`;
}

// ── 실패 처리 ────────────────────────────────────────────

export const DRAFT_SOURCE_UNSAFE =
  '치수 텍스트만 보낼 수 있습니다. 상품 설명 · 이름 · 주소 같은 문장은 빼고 사이즈와 숫자 부분만 붙여넣어 주세요.';
export const DRAFT_SOURCE_LENGTH = `치수 텍스트를 1~${SOURCE_TEXT_MAX.toLocaleString('ko-KR')}자로 입력해 주세요.`;
export const DRAFT_INPUT_CONFLICT =
  '같은 요청 번호로 다른 내용이 접수돼 있었습니다. 새 요청 번호로 다시 보내 주세요.';
export const DRAFT_REGISTRATION_UNKNOWN =
  '생성 작업이 접수됐는지 확인하지 못했습니다. 같은 내용으로 다시 요청하면 이미 접수된 작업을 이어 받습니다(중복 과금 없음).';
export const DRAFT_REQUEST_FAILED = '초안을 요청하지 못했습니다. 잠시 후 다시 시도해 주세요.';
export const DRAFT_SESSION_EXPIRED =
  '로그인이 만료되어 요청하지 못했습니다. 입력한 값은 그대로 두었습니다. 새 탭에서 다시 로그인한 뒤 다시 시도해 주세요.';
export const DRAFT_EXPIRED =
  '초안이 만료되었거나 더 이상 볼 수 없습니다. 필요하면 새 초안을 요청해 주세요.';
export const DRAFT_RESULT_EXPIRED = '생성 결과 보관 기간이 지났습니다. 새 초안을 요청해 주세요.';
export const DRAFT_RESULT_UNAVAILABLE =
  '생성 결과를 아직 가져오지 못했습니다. 잠시 후 결과 확인을 다시 눌러 주세요.';
export const DRAFT_TEMPLATE_CHANGED =
  '초안을 만든 뒤 템플릿이 바뀌어 적용할 수 없습니다. 새 초안을 요청해 주세요.';
export const DRAFT_CHART_CHANGED =
  '초안을 만든 뒤 상품 사이즈표가 바뀌어 적용하지 않았습니다. 현재 표를 다시 불러왔으니 확인한 뒤 새 초안을 요청해 주세요.';
export const DRAFT_STATE_CHANGED =
  '초안 상태가 그사이 바뀌었습니다. 최신 초안을 다시 불러왔으니 확인한 뒤 다시 적용해 주세요.';
export const DRAFT_WARNINGS_UNACKED = '남은 경고를 모두 확인한 뒤 적용해 주세요.';
export const DRAFT_APPLY_UNKNOWN =
  '적용 결과를 확인하지 못했습니다. 같은 내용으로 다시 적용하면 이미 적용된 결과를 돌려받습니다.';
export const DRAFT_APPLY_FAILED = '적용하지 못했습니다. 잠시 후 다시 시도해 주세요.';

const CODE = (n: string) => `SIZE_CHART_DRAFT_${n}`;

export type DraftCreateFailure =
  | { kind: 'access'; denied: AccessDenied }
  /** 입력 칸 오류 — 키는 그대로 둔다(입력을 고치면 어차피 새 키다) */
  | { kind: 'source'; message: string }
  /** 같은 키 다른 입력(002) — 키를 버리고 새로 만든다 */
  | { kind: 'newKey'; message: string }
  /** 결과 모름 · 일시 장애 — 같은 키로 다시 보내면 된다 */
  | { kind: 'retry'; message: string };

export function draftCreateFailure(cause: unknown): DraftCreateFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized') return { kind: 'retry', message: DRAFT_SESSION_EXPIRED };
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'retry', message: DRAFT_REQUEST_FAILED };
  if (cause.code === CODE('030')) return { kind: 'source', message: DRAFT_SOURCE_UNSAFE };
  if (cause.fieldErrors.some((item) => item.code === CODE('104')))
    return { kind: 'source', message: DRAFT_SOURCE_LENGTH };
  if (cause.code === CODE('002')) return { kind: 'newKey', message: DRAFT_INPUT_CONFLICT };
  if (cause.code === CODE('501') || cause.kind === 'unknownOutcome' || cause.kind === 'network')
    return { kind: 'retry', message: DRAFT_REGISTRATION_UNKNOWN };
  if (cause.kind === 'rateLimited') return { kind: 'retry', message: cause.message };
  // 템플릿 · 상품이 없어진 경우(404 → 001 로 접힘)는 BE 가 템플릿 아이디로 거른 뒤라 같은 안내로 둔다
  return { kind: 'retry', message: DRAFT_REQUEST_FAILED };
}

export type DraftReadFailure =
  | { kind: 'access'; denied: AccessDenied }
  /** 없음 · 만료 — 초안을 놓아 버린다 */
  | { kind: 'gone'; message: string }
  /** 일시 장애 — 마지막 상태를 두고 다시 시도하게 한다 */
  | { kind: 'retry'; message: string };

/** 상세 · 새로고침 실패 */
export function draftReadFailure(cause: unknown): DraftReadFailure {
  const denied = accessDeniedOf(cause);
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError)) return { kind: 'retry', message: DRAFT_RESULT_UNAVAILABLE };
  if (cause.code === CODE('010')) return { kind: 'gone', message: DRAFT_RESULT_EXPIRED };
  if (cause.code === CODE('001') || cause.code === CODE('005') || cause.status === 410)
    return { kind: 'gone', message: DRAFT_EXPIRED };
  if (cause.kind === 'rateLimited') return { kind: 'retry', message: cause.message };
  return { kind: 'retry', message: DRAFT_RESULT_UNAVAILABLE };
}

export type DraftApplyFailure =
  | { kind: 'access'; denied: AccessDenied }
  | { kind: 'errors'; errors: FormErrors }
  /** 초안을 더 쓸 수 없다(만료 · 템플릿 변경) — 검토를 끝내고 새 초안을 안내한다 */
  | { kind: 'gone'; message: string }
  /** 상품 표가 그사이 바뀌었다 — 현재 표를 다시 읽는다(AI 가 사람 편집을 덮지 않는다) */
  | { kind: 'chartChanged'; message: string }
  /** 초안 상태 · 버전이 바뀌었다 — 초안을 다시 읽는다 */
  | { kind: 'reloadDraft'; message: string };

/** 적용 본문 경로(`correctedChart.rows[0].cells[1].value`) → 폼 칸 키 */
const applyKey = (path: string, columns: readonly TemplateColumn[]) =>
  path.startsWith('correctedChart.')
    ? chartFieldKey(path.slice('correctedChart.'.length), columns)
    : 'form';

export function draftApplyFailure(
  cause: unknown,
  columns: readonly TemplateColumn[],
): DraftApplyFailure {
  const denied = accessDeniedOf(cause);
  if (denied === 'unauthorized')
    return { kind: 'errors', errors: { form: [DRAFT_SESSION_EXPIRED] } };
  if (denied) return { kind: 'access', denied };
  if (!(cause instanceof ApiError))
    return { kind: 'errors', errors: { form: [DRAFT_APPLY_FAILED] } };
  if (cause.fieldErrors.length > 0) {
    const errors: FormErrors = {};
    for (const item of cause.fieldErrors) {
      const key = applyKey(item.field, columns);
      errors[key] = [CHECK_FIELDS];
    }
    return { kind: 'errors', errors: withSummary(errors) };
  }
  switch (cause.code) {
    case CODE('001'):
    case CODE('005'):
      return { kind: 'gone', message: DRAFT_EXPIRED };
    case CODE('010'):
      return { kind: 'gone', message: DRAFT_RESULT_EXPIRED };
    case CODE('006'):
      return { kind: 'gone', message: DRAFT_TEMPLATE_CHANGED };
    case CODE('007'):
    case CODE('008'):
      return { kind: 'chartChanged', message: DRAFT_CHART_CHANGED };
    case CODE('003'):
    case CODE('004'):
      return { kind: 'reloadDraft', message: DRAFT_STATE_CHANGED };
    case CODE('009'):
      return { kind: 'errors', errors: { form: [DRAFT_WARNINGS_UNACKED] } };
  }
  if (cause.kind === 'unknownOutcome' || cause.kind === 'unavailable' || cause.kind === 'network')
    return { kind: 'errors', errors: { form: [DRAFT_APPLY_UNKNOWN] } };
  if (cause.kind === 'rateLimited') return { kind: 'errors', errors: { form: [cause.message] } };
  return { kind: 'errors', errors: { form: [DRAFT_APPLY_FAILED] } };
}
