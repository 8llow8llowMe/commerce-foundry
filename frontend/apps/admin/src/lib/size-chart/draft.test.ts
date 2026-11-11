import { ApiError, type ApiErrorKind, type FieldErrorItem } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { CHECK_FIELDS } from './errors';
import { cellKey, labelKey } from './form';
import {
  candidateSummary,
  createDraftPath,
  draftApplyFailure,
  draftCreateFailure,
  draftReadFailure,
  draftToForm,
  DRAFT_CHART_CHANGED,
  DRAFT_EXPIRED,
  DRAFT_IMAGE_UNSAFE,
  DRAFT_INPUT_CONFLICT,
  DRAFT_REGISTRATION_UNKNOWN,
  DRAFT_RESULT_EXPIRED,
  DRAFT_SESSION_EXPIRED,
  DRAFT_SOURCE_LENGTH,
  DRAFT_SOURCE_UNSAFE,
  DRAFT_STATE_CHANGED,
  DRAFT_TEMPLATE_CHANGED,
  DRAFT_WARNINGS_UNACKED,
  keyFor,
  recentDraftsPath,
  resumableDraft,
  warningText,
  type DraftSummary,
  type SizeChartDraft,
} from './draft';
import { IMAGE_CHANGED, IMAGE_FORMAT, IMAGE_INPUT_CLOSED } from './draft-image';

const apiError = (
  kind: ApiErrorKind,
  status: number | null,
  code: string | null,
  fieldErrors: FieldErrorItem[] = [],
) => new ApiError(kind, status, code, '서버 원문', fieldErrors);

const meta = (code: string, name = code) => ({ code, name, description: `${name} 안내` });

const DRAFT: SizeChartDraft = {
  draftId: '7',
  productId: '3',
  jobId: '99',
  status: meta('READY', '검토 대기'),
  version: 2,
  expiresAt: '2026-11-16T00:00:00Z',
  template: {
    templateId: '5',
    revision: 1,
    unit: meta('CM', '센티미터'),
    columns: [
      { columnId: '11', name: '가슴 단면' },
      { columnId: '12', name: '총장' },
    ],
  },
  candidate: {
    unit: meta('CM', '센티미터'),
    rows: [
      {
        sizeLabel: 'S',
        cells: [
          { columnId: '11', value: 50 },
          { columnId: '12', value: null },
        ],
      },
      {
        sizeLabel: 'M',
        cells: [
          { columnId: '11', value: 52.5 },
          { columnId: '12', value: 62 },
        ],
      },
    ],
    warnings: [
      {
        warningId: 'MISSING_CELL:0:12',
        type: meta('MISSING_CELL', '빠진 치수'),
        rowIndex: 0,
        columnId: '12',
      },
      {
        warningId: 'SIZE_ORDER:1:null',
        type: meta('SIZE_ORDER', '순서 확인'),
        rowIndex: 1,
        columnId: null,
      },
    ],
  },
};

const COLUMNS = [
  { columnId: '11', name: '가슴 단면', description: null, displayOrder: 0 },
  { columnId: '12', name: '총장', description: null, displayOrder: 1 },
];

describe('경로', () => {
  it('상품 아이디를 인코딩하고 최근 초안은 내 요청만 5건 읽는다', () => {
    expect(createDraftPath('3')).toBe('/api/v1/admin/products/3/size-chart/drafts');
    expect(recentDraftsPath('3')).toBe(
      '/api/v1/admin/size-chart-drafts?productId=3&requestedByMe=true&page=0&size=5',
    );
  });
});

describe('keyFor', () => {
  let n = 0;
  const create = () => `key-${++n}`;

  it('같은 템플릿 · 텍스트(앞뒤 공백 무시)는 같은 키를, 바뀌면 새 키를 쓴다', () => {
    const text = (sourceText: string) => ({ mode: 'TEXT' as const, sourceText });
    const first = keyFor(null, '5', text('S 50'), create);
    expect(keyFor(first, '5', text('  S 50\n'), create)).toBe(first);
    expect(keyFor(first, '5', text('S 51'), create).key).not.toBe(first.key);
    expect(keyFor(first, '6', text('S 50'), create).key).not.toBe(first.key);
  });
});

describe('resumableDraft', () => {
  const summary = (draftId: string, code: string, expiresAt: string): DraftSummary => ({
    draftId,
    productId: '3',
    templateId: '5',
    jobId: '1',
    status: meta(code),
    version: 0,
    expiresAt,
  });
  const now = Date.parse('2026-11-09T00:00:00Z');

  it('만료 전 생성 중 · 검토 대기 중 가장 최근 것을 고른다', () => {
    const list = [
      summary('9', 'APPLIED', '2026-11-15T00:00:00Z'),
      summary('8', 'READY', '2026-11-08T00:00:00Z'),
      summary('7', 'REQUESTED', '2026-11-15T00:00:00Z'),
      summary('6', 'READY', '2026-11-15T00:00:00Z'),
    ];
    expect(resumableDraft(list, now)?.draftId).toBe('7');
    expect(resumableDraft([summary('1', 'FAILED', '2026-11-15T00:00:00Z')], now)).toBeNull();
  });
});

describe('draftToForm', () => {
  it('스냅숏 항목 순서대로 칸을 만들고 빠진 치수는 빈칸으로 둔다(0으로 채우지 않는다)', () => {
    const form = draftToForm(DRAFT, { note: '기존 안내' }, '상의');
    expect(form).toMatchObject({
      templateId: '5',
      templateName: '상의',
      unitName: '센티미터',
      note: '기존 안내',
      columns: COLUMNS,
    });
    expect(form?.rows.map((row) => [row.sizeLabel, row.values])).toEqual([
      ['S', { '11': '50' }],
      ['M', { '11': '52.5', '12': '62' }],
    ]);
  });

  it('후보가 없으면(생성 전) null', () => {
    expect(draftToForm({ ...DRAFT, candidate: null }, { note: '' }, '')).toBeNull();
  });
});

describe('candidateSummary · warningText', () => {
  it('사이즈 · 빈 칸 · 경고 수를 센다', () => {
    expect(candidateSummary(DRAFT)).toEqual({ rows: 2, missing: 1, warnings: 2 });
    expect(candidateSummary({ ...DRAFT, candidate: null })).toEqual({
      rows: 0,
      missing: 0,
      warnings: 0,
    });
  });

  it('경고를 사이즈 · 항목 이름으로 적는다. 행 전체 경고는 항목 없이', () => {
    const [cell, row] = DRAFT.candidate!.warnings;
    expect(warningText(DRAFT, cell!)).toBe('S 총장: 빠진 치수');
    expect(warningText(DRAFT, row!)).toBe('M: 순서 확인');
  });
});

describe('draftCreateFailure', () => {
  it('안전하지 않은 입력(030) · 길이(104)는 텍스트 칸 오류다', () => {
    expect(draftCreateFailure(apiError('server', 400, 'SIZE_CHART_DRAFT_030'))).toEqual({
      kind: 'source',
      message: DRAFT_SOURCE_UNSAFE,
    });
    expect(
      draftCreateFailure(
        apiError('validation', 400, 'SIZE_CHART_DRAFT_100', [
          { field: 'sourceText', code: 'SIZE_CHART_DRAFT_104', message: 'x' },
        ]),
      ),
    ).toEqual({ kind: 'source', message: DRAFT_SOURCE_LENGTH });
  });

  it('IMAGE 입력의 030 은 이미지 안내로, 이미지 전용 코드는 후속 동작으로 접는다', () => {
    expect(draftCreateFailure(apiError('server', 400, 'SIZE_CHART_DRAFT_030'), 'IMAGE')).toEqual({
      kind: 'source',
      message: DRAFT_IMAGE_UNSAFE,
    });
    expect(draftCreateFailure(apiError('conflict', 409, 'SIZE_CHART_DRAFT_015'), 'IMAGE')).toEqual({
      kind: 'image',
      action: 'reinspect',
      message: IMAGE_CHANGED,
    });
    expect(draftCreateFailure(apiError('server', 400, 'SIZE_CHART_DRAFT_011'), 'IMAGE')).toEqual({
      kind: 'image',
      action: 'closed',
      message: IMAGE_INPUT_CLOSED,
    });
    expect(draftCreateFailure(apiError('server', 400, 'SIZE_CHART_DRAFT_014'), 'IMAGE')).toEqual({
      kind: 'source',
      message: IMAGE_FORMAT,
    });
    // 저장소 장애(504)는 같은 키로 다시 보내면 된다
    expect(
      draftCreateFailure(apiError('unavailable', 503, 'SIZE_CHART_DRAFT_504'), 'IMAGE').kind,
    ).toBe('retry');
  });

  it('같은 키 다른 입력(002)은 새 키, 등록 불명(501) · 결과 모름은 같은 키로 다시 보낸다', () => {
    expect(draftCreateFailure(apiError('conflict', 409, 'SIZE_CHART_DRAFT_002'))).toEqual({
      kind: 'newKey',
      message: DRAFT_INPUT_CONFLICT,
    });
    expect(draftCreateFailure(apiError('unavailable', 503, 'SIZE_CHART_DRAFT_501'))).toEqual({
      kind: 'retry',
      message: DRAFT_REGISTRATION_UNKNOWN,
    });
    expect(draftCreateFailure(apiError('unknownOutcome', 504, null)).kind).toBe('retry');
  });

  it('401 은 입력을 지키고 다시 로그인을 안내한다. 403 은 권한 안내', () => {
    expect(draftCreateFailure(apiError('unauthorized', 401, null))).toEqual({
      kind: 'retry',
      message: DRAFT_SESSION_EXPIRED,
    });
    expect(draftCreateFailure(apiError('forbidden', 403, null))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
  });
});

describe('draftReadFailure', () => {
  it('없음 · 만료(410)는 놓고, 결과 보관 만료(010)는 따로 안내한다', () => {
    expect(draftReadFailure(apiError('server', 410, 'SIZE_CHART_DRAFT_005'))).toEqual({
      kind: 'gone',
      message: DRAFT_EXPIRED,
    });
    expect(draftReadFailure(apiError('notFound', 404, 'SIZE_CHART_DRAFT_001')).kind).toBe('gone');
    expect(draftReadFailure(apiError('server', 410, 'SIZE_CHART_DRAFT_010'))).toEqual({
      kind: 'gone',
      message: DRAFT_RESULT_EXPIRED,
    });
  });

  it('결과 조회 장애(502 · 503)는 마지막 상태를 두고 다시 시도하게 한다', () => {
    expect(draftReadFailure(apiError('unavailable', 503, 'SIZE_CHART_DRAFT_502')).kind).toBe(
      'retry',
    );
    expect(draftReadFailure(apiError('unknownOutcome', 502, 'SIZE_CHART_DRAFT_503')).kind).toBe(
      'retry',
    );
  });
});

describe('draftApplyFailure', () => {
  it('correctedChart 경로의 칸 오류를 표 칸에 붙이고 요약을 둔다', () => {
    const failure = draftApplyFailure(
      apiError('validation', 400, 'SIZE_CHART_DRAFT_100', [
        {
          field: 'correctedChart.rows[0].cells[1].value',
          code: 'SIZE_CHART_DRAFT_107',
          message: 'x',
        },
        { field: 'correctedChart.rows[1].sizeLabel', code: 'SIZE_CHART_DRAFT_107', message: 'x' },
        { field: 'acknowledgedWarningIds', code: 'SIZE_CHART_DRAFT_108', message: 'x' },
      ]),
      COLUMNS,
    );
    expect(failure).toEqual({
      kind: 'errors',
      errors: {
        [cellKey(0, '12')]: [CHECK_FIELDS],
        [labelKey(1)]: [CHECK_FIELDS],
        form: [CHECK_FIELDS],
      },
    });
  });

  it('코드별로 초안을 놓거나 · 표를 다시 읽거나 · 초안을 다시 읽는다', () => {
    const of = (status: number, code: string) =>
      draftApplyFailure(apiError('conflict', status, code), COLUMNS);
    expect(of(410, 'SIZE_CHART_DRAFT_005')).toEqual({ kind: 'gone', message: DRAFT_EXPIRED });
    expect(of(409, 'SIZE_CHART_DRAFT_006')).toEqual({
      kind: 'gone',
      message: DRAFT_TEMPLATE_CHANGED,
    });
    expect(of(409, 'SIZE_CHART_DRAFT_007')).toEqual({
      kind: 'chartChanged',
      message: DRAFT_CHART_CHANGED,
    });
    expect(of(409, 'SIZE_CHART_DRAFT_008').kind).toBe('chartChanged');
    expect(of(409, 'SIZE_CHART_DRAFT_003')).toEqual({
      kind: 'reloadDraft',
      message: DRAFT_STATE_CHANGED,
    });
    expect(of(409, 'SIZE_CHART_DRAFT_004').kind).toBe('reloadDraft');
    expect(of(409, 'SIZE_CHART_DRAFT_009')).toEqual({
      kind: 'errors',
      errors: { form: [DRAFT_WARNINGS_UNACKED] },
    });
  });

  it('결과를 모르면 같은 내용으로 다시 적용하게 안내한다(서버가 원래 결과를 돌려준다)', () => {
    const failure = draftApplyFailure(apiError('unknownOutcome', 504, null), COLUMNS);
    expect(failure.kind).toBe('errors');
  });
});
