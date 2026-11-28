import { ApiError, type ApiErrorKind, type FieldErrorItem } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { DRAFT_SOURCE_LENGTH, DRAFT_SOURCE_UNSAFE } from './draft';
import {
  addProduct,
  applyBatchFailure,
  applyBatchResponse,
  BATCH_INVALID,
  BATCH_ITEMS_INVALID,
  BATCH_MAX_ITEMS,
  BATCH_REJECTED,
  BATCH_SESSION_EXPIRED,
  BATCH_UNKNOWN,
  clearAccepted,
  draftInputOf,
  editSource,
  hasUnknown,
  initialBatchState,
  inputProblem,
  ITEM_GONE,
  ITEM_INPUT_REQUIRED,
  ITEM_NEW_REQUEST,
  ITEM_REGISTRATION_UNKNOWN,
  ITEM_TEMPORARY,
  itemOutcome,
  itemTone,
  needsLeaveGuard,
  outcomeUnknown,
  prepareBatch,
  removeProduct,
  requestLabel,
  setItemInput,
  setTemplate,
  type BatchRequest,
  type BatchResultItem,
  type BatchState,
} from './draft-batch';
import { KEY_TTL_MS } from './draft-batch-keys';
import { TEXT_SOURCE_CHANGED, type TextSource } from './text-source';

const NOW = Date.parse('2026-11-28T00:00:00Z');

const apiError = (
  kind: ApiErrorKind,
  status: number | null,
  code: string | null,
  fieldErrors: FieldErrorItem[] = [],
) => new ApiError(kind, status, code, '서버 원문', fieldErrors);

/** 차례로 다른 키를 만든다 — 몇 번 만들었는지로 새 키 여부를 본다 */
function keyMaker() {
  let n = 0;
  const create = () => {
    n += 1;
    return `00000000-0000-4000-8000-${String(n).padStart(12, '0')}`;
  };
  return { create, count: () => n };
}

const SOURCE: TextSource = {
  sourceId: 'TEXT-KO-1',
  bodyHash: 'a'.repeat(64),
  sourceText: 'S 가슴 50 총장 60\nM 가슴 52 총장 62',
};

/** 템플릿 5, 상품 1(후보) · 2(직접 입력) · 3(입력 없음) */
function filled(): BatchState {
  let state = setTemplate(initialBatchState(), '5');
  state = addProduct(state, { productId: '1', name: '상품 하나' });
  state = addProduct(state, { productId: '2', name: '상품 둘' });
  state = addProduct(state, { productId: '3', name: '상품 셋' });
  state = setItemInput(state, '1', { kind: 'source', source: SOURCE });
  state = setItemInput(state, '2', { kind: 'direct', text: '  L 가슴 54  ' });
  return state;
}

const ok = (
  sent: BatchRequest['items'][number],
  overrides: Partial<BatchResultItem> = {},
): BatchResultItem => ({
  productId: sent.productId,
  idempotencyKey: sent.idempotencyKey.toLowerCase(),
  draftId: `d${sent.productId}`,
  jobId: `9${sent.productId}`,
  errorCode: null,
  retryable: false,
  ...overrides,
});

const fail = (
  sent: BatchRequest['items'][number],
  errorCode: string,
  retryable = false,
  draftId: string | null = null,
): BatchResultItem => ok(sent, { draftId, jobId: null, errorCode, retryable });

/** 보낼 본문과 키를 남긴 상태 */
function sendWith(state: BatchState, create: () => string, now = NOW) {
  const prepared = prepareBatch(state, create, now);
  if (!prepared) throw new Error('보낼 항목이 없다');
  return { base: { ...state, keys: prepared.keys }, request: prepared.request };
}

/** 첫 요청 뒤 두 항목 결과를 반영한 상태 */
function afterFirst(results: (sent: BatchRequest['items']) => BatchResultItem[]) {
  const keys = keyMaker();
  const first = sendWith(filled(), keys.create);
  const applied = applyBatchResponse(first.base, first.request, {
    items: results(first.request.items),
  });
  return { keys, request: first.request, applied };
}

const at = (items: BatchRequest['items'], index: number) => {
  const item = items[index];
  if (!item) throw new Error(`항목 ${index}`);
  return item;
};

describe('입력', () => {
  it('후보는 텍스트 그대로 + 출처, 직접 입력은 출처 없이 다듬어 보낸다', () => {
    const { request } = sendWith(filled(), keyMaker().create);
    expect(request.items.map((item) => item.productId)).toEqual(['1', '2']);
    expect(request.items[0]?.input).toEqual({
      templateId: '5',
      mode: 'TEXT',
      sourceText: SOURCE.sourceText,
      textSelection: { sourceId: 'TEXT-KO-1', bodyHash: SOURCE.bodyHash },
    });
    expect(request.items[1]?.input).toEqual({
      templateId: '5',
      mode: 'TEXT',
      sourceText: 'L 가슴 54',
    });
    expect(request.items[0]?.idempotencyKey).toMatch(/^[0-9a-f-]{36}$/);
  });

  it('고른 표를 고쳐 쓰면 출처가 빠진 직접 입력이 된다', () => {
    const edited = editSource({ kind: 'source', source: SOURCE });
    expect(edited).toEqual({ kind: 'direct', text: SOURCE.sourceText });
    expect(draftInputOf(edited)).toEqual({
      mode: 'TEXT',
      sourceText: SOURCE.sourceText,
      selection: null,
    });
  });

  it('입력이 없거나 길면 보내지 않는다 — 후보 텍스트도 길이를 본다', () => {
    expect(inputProblem({ kind: 'none' })).toBe(ITEM_INPUT_REQUIRED);
    expect(inputProblem({ kind: 'direct', text: '   ' })).toBe(ITEM_INPUT_REQUIRED);
    expect(inputProblem({ kind: 'direct', text: 'x'.repeat(12001) })).toBe(DRAFT_SOURCE_LENGTH);
    expect(inputProblem({ kind: 'source', source: SOURCE })).toBeNull();
    expect(
      inputProblem({ kind: 'source', source: { ...SOURCE, sourceText: 'x'.repeat(12001) } }),
    ).toBe(DRAFT_SOURCE_LENGTH);
  });

  it('템플릿이 없으면 본문을 만들지 않는다', () => {
    expect(prepareBatch({ ...filled(), templateId: null }, keyMaker().create, NOW)).toBeNull();
  });

  it(`상품은 ${BATCH_MAX_ITEMS}개까지, 같은 상품은 한 번만 넣는다`, () => {
    let state = initialBatchState();
    for (let i = 0; i < BATCH_MAX_ITEMS + 2; i += 1)
      state = addProduct(state, { productId: String(i), name: `상품 ${i}` });
    expect(state.items).toHaveLength(BATCH_MAX_ITEMS);
    expect(addProduct(state, { productId: '0', name: '중복' }).items).toHaveLength(BATCH_MAX_ITEMS);
  });
});

describe('키 장부', () => {
  it('목록에서 뺐다 다시 넣어도 같은 입력이면 같은 키다', () => {
    const keys = keyMaker();
    const first = sendWith(filled(), keys.create);
    let state = removeProduct(first.base, '2');
    state = addProduct(state, { productId: '2', name: '상품 둘' });
    state = setItemInput(state, '2', { kind: 'direct', text: 'L 가슴 54' });
    const again = prepareBatch(state, keys.create, NOW);
    expect(again?.request.items[1]?.idempotencyKey).toBe(first.request.items[1]?.idempotencyKey);
    expect(keys.count()).toBe(2);
  });

  it('입력을 바꿨다가 원래대로 돌리면 원래 키를 다시 쓴다', () => {
    const keys = keyMaker();
    const first = sendWith(filled(), keys.create);
    const changed = setItemInput(first.base, '2', { kind: 'direct', text: 'L 가슴 55' });
    const second = sendWith(changed, keys.create);
    expect(second.request.items[1]?.idempotencyKey).not.toBe(
      first.request.items[1]?.idempotencyKey,
    );
    const back = setItemInput(second.base, '2', { kind: 'direct', text: 'L 가슴 54' });
    expect(prepareBatch(back, keys.create, NOW)?.request.items[1]?.idempotencyKey).toBe(
      first.request.items[1]?.idempotencyKey,
    );
  });

  it('7일이 지난 키는 다시 쓰지 않는다', () => {
    const keys = keyMaker();
    const first = sendWith(filled(), keys.create);
    const later = prepareBatch(first.base, keys.create, NOW + KEY_TTL_MS);
    expect(later?.request.items[0]?.idempotencyKey).not.toBe(
      first.request.items[0]?.idempotencyKey,
    );
  });
});

describe('결과를 모르는 실패', () => {
  it('504 · 연결 실패 · 503 · 5xx · 봉투 아님이면 같은 본문을 잠가 두고 그대로 다시 보낸다(새 키를 만들지 않는다)', () => {
    for (const cause of [
      apiError('unknownOutcome', 504, null),
      apiError('network', null, null),
      apiError('unavailable', 503, null),
      apiError('server', 500, null),
      apiError('invalidResponse', 200, null),
    ]) {
      const keys = keyMaker();
      const { base, request } = sendWith(filled(), keys.create);
      const failed = applyBatchFailure(base, request, cause);
      expect(failed.state.unresolved).toBe(request);
      expect(failed.state.notice).toBe(BATCH_UNKNOWN);
      expect(requestLabel(failed.state)).toBe('같은 내용으로 다시 요청');
      expect(needsLeaveGuard(failed.state, false)).toBe(true);

      // 잠긴 동안은 입력 · 목록 · 템플릿을 바꾸지 않는다
      const tried = setTemplate(
        removeProduct(
          addProduct(setItemInput(failed.state, '1', { kind: 'direct', text: '다른 내용' }), {
            productId: '9',
            name: '새 상품',
          }),
          '2',
        ),
        '6',
      );
      expect(tried).toBe(failed.state);

      const again = prepareBatch(tried, keys.create, NOW);
      expect(again?.request).toBe(request);
      expect(keys.count()).toBe(2);
    }
  });

  it('요청 제한도 같은 본문으로 다시 보낸다', () => {
    const { base, request } = sendWith(filled(), keyMaker().create);
    const failed = applyBatchFailure(base, request, apiError('rateLimited', 429, 'GATEWAY_006'));
    expect(failed.state.unresolved).toBe(request);
  });

  it('그 밖의 4xx 는 처리되지 않은 확정 실패 — 잠그지 않고 안내만 한다', () => {
    for (const cause of [
      apiError('server', 400, 'COMMON_001'),
      apiError('notFound', 404, 'BFF_001'),
      apiError('server', 405, null),
      apiError('conflict', 409, null),
      apiError('server', 413, null),
    ]) {
      expect(outcomeUnknown(cause)).toBe(false);
      const { base, request } = sendWith(filled(), keyMaker().create);
      const failed = applyBatchFailure(base, request, cause);
      expect(failed.state.unresolved).toBeNull();
      expect(failed.state.notice).toBe(BATCH_REJECTED);
      expect(needsLeaveGuard(failed.state, false)).toBe(false);
    }
  });

  it('잠긴 채 다시 보낸 요청이 401 · 그 밖의 4xx 를 받아도 잠금은 그대로다', () => {
    const { base, request } = sendWith(filled(), keyMaker().create);
    const locked = applyBatchFailure(base, request, apiError('unknownOutcome', 504, null)).state;
    const expired = applyBatchFailure(locked, request, apiError('unauthorized', 401, null));
    expect(expired.state.unresolved).toBe(request);
    expect(expired.state.notice).toBe(BATCH_SESSION_EXPIRED);
    const conflict = applyBatchFailure(locked, request, apiError('conflict', 409, null));
    expect(conflict.state.unresolved).toBe(request);
    expect(conflict.state.notice).toBe(BATCH_UNKNOWN);
  });

  it('다시 보낸 뒤 응답을 받으면 풀린다', () => {
    const { base, request } = sendWith(filled(), keyMaker().create);
    const locked = applyBatchFailure(base, request, apiError('unknownOutcome', 504, null)).state;
    const applied = applyBatchResponse(locked, request, {
      items: request.items.map((sent) => ok(sent)),
    });
    expect(applied.state.unresolved).toBeNull();
    expect(applied.state.items.map((item) => item.result.status)).toEqual([
      'accepted',
      'accepted',
      'pending',
    ]);
  });

  it('401 은 처리되지 않았다 — 잠그지 않고 다시 로그인을 안내한다. 403 은 권한 안내로 올린다', () => {
    const { base, request } = sendWith(filled(), keyMaker().create);
    const expired = applyBatchFailure(base, request, apiError('unauthorized', 401, null));
    expect(expired.state.unresolved).toBeNull();
    expect(expired.state.notice).toBe(BATCH_SESSION_EXPIRED);
    expect(expired.denied).toBeNull();
    expect(applyBatchFailure(base, request, apiError('forbidden', 403, null)).denied).toBe(
      'forbidden',
    );
  });
});

describe('항목별 결과와 재요청', () => {
  it('접수 · 다시 시도 가능 · 실패를 나누고, 실패한 항목만 같은 키로 다시 보낸다', () => {
    const keys = keyMaker();
    const first = sendWith(
      setItemInput(filled(), '3', { kind: 'direct', text: 'XL 가슴 56' }),
      keys.create,
    );
    const items = first.request.items;
    const applied = applyBatchResponse(first.base, first.request, {
      items: [
        ok(at(items, 0)),
        fail(at(items, 1), 'SIZE_CHART_DRAFT_505', true),
        fail(at(items, 2), 'SIZE_CHART_DRAFT_030'),
      ],
    });
    const state = applied.state;
    expect(state.items.map((item) => itemTone(item, false).label)).toEqual([
      '접수됨',
      '다시 시도 가능',
      '실패',
    ]);
    expect(applied.acceptedJobs).toEqual(['91']);
    expect(state.notice).toBe(
      '요청한 3개 중 1개를 접수했습니다. 1개는 다시 요청할 수 있습니다. 1개는 치수를 고치거나 다시 골라야 합니다.',
    );
    expect(requestLabel(state)).toBe('실패한 1개 다시 요청');

    const retry = prepareBatch(state, keys.create, NOW);
    expect(retry?.request.items).toEqual([at(items, 1)]);
    expect(keys.count()).toBe(3);
  });

  it('002 면 그 항목만 새 키로 다시 보낸다', () => {
    const { keys, request, applied } = afterFirst((items) => [
      fail(at(items, 0), 'SIZE_CHART_DRAFT_002'),
      fail(at(items, 1), 'SIZE_CHART_DRAFT_502', true),
    ]);
    expect(applied.state.items[0]?.result).toEqual({
      status: 'retryable',
      message: ITEM_NEW_REQUEST,
      newKey: true,
      unknown: false,
    });
    const retry = prepareBatch(applied.state, keys.create, NOW);
    expect(retry?.request.items[0]?.idempotencyKey).not.toBe(request.items[0]?.idempotencyKey);
    expect(retry?.request.items[1]?.idempotencyKey).toBe(request.items[1]?.idempotencyKey);
    expect(keys.count()).toBe(3);
  });

  it('입력이 바뀐 항목만 새 키다', () => {
    const { keys, request, applied } = afterFirst((items) =>
      items.map((sent) => fail(sent, 'SIZE_CHART_DRAFT_505', true)),
    );
    const state = setItemInput(applied.state, '2', { kind: 'direct', text: 'L 가슴 55' });
    const retry = prepareBatch(state, keys.create, NOW);
    expect(retry?.request.items[0]?.idempotencyKey).toBe(request.items[0]?.idempotencyKey);
    expect(retry?.request.items[1]?.idempotencyKey).not.toBe(request.items[1]?.idempotencyKey);
  });

  it('후보를 골랐다가 같은 텍스트의 직접 입력으로 바꿔도 같은 키다(출처는 키에 넣지 않는다)', () => {
    const { keys, request, applied } = afterFirst((items) =>
      items.map((sent) => fail(sent, 'SIZE_CHART_DRAFT_505', true)),
    );
    const state = setItemInput(applied.state, '1', editSource({ kind: 'source', source: SOURCE }));
    expect(state.items[0]?.result.status).toBe('retryable');
    const retry = prepareBatch(state, keys.create, NOW);
    expect(retry?.request.items[0]?.idempotencyKey).toBe(request.items[0]?.idempotencyKey);
    expect(retry?.request.items[0]?.input).not.toHaveProperty('textSelection');
  });

  it('017 이면 고른 후보를 놓고 후보를 다시 읽게 한다 — 다시 고르면 다시 보낸다', () => {
    const { keys, request, applied } = afterFirst((items) => [
      fail(at(items, 0), 'SIZE_CHART_DRAFT_017'),
      ok(at(items, 1)),
    ]);
    expect(applied.reload).toEqual(['1']);
    expect(applied.state.items[0]?.input).toEqual({ kind: 'none' });
    expect(applied.state.items[0]?.result).toEqual({
      status: 'failed',
      message: TEXT_SOURCE_CHANGED,
    });
    expect(prepareBatch(applied.state, keys.create, NOW)).toBeNull();

    // 본문만 바뀌고 표 텍스트가 같으면 같은 키(아무것도 만들어지지 않았다), 표가 달라졌으면 새 키
    const same = setItemInput(applied.state, '1', {
      kind: 'source',
      source: { ...SOURCE, bodyHash: 'b'.repeat(64) },
    });
    expect(prepareBatch(same, keys.create, NOW)?.request.items[0]?.idempotencyKey).toBe(
      request.items[0]?.idempotencyKey,
    );
    const changed = setItemInput(applied.state, '1', {
      kind: 'source',
      source: { ...SOURCE, sourceText: 'S 가슴 49' },
    });
    expect(prepareBatch(changed, keys.create, NOW)?.request.items[0]?.idempotencyKey).not.toBe(
      request.items[0]?.idempotencyKey,
    );
  });

  it('등록 불명(501 · 복구 아이디 · 응답 누락)은 입력 · 빼기 · 템플릿 변경을 막고, 같은 키로 다시 보낸다', () => {
    const { keys, request, applied } = afterFirst((items) => [
      fail(at(items, 0), 'SIZE_CHART_DRAFT_501', true, 'd1'),
    ]);
    const state = applied.state;
    expect(state.items[0]?.result).toEqual({
      status: 'retryable',
      message: ITEM_REGISTRATION_UNKNOWN,
      newKey: false,
      unknown: true,
    });
    // 응답에 빠진 항목(상품 2)도 등록 불명이다
    expect(state.items[1]?.result).toMatchObject({ status: 'retryable', unknown: true });
    expect(hasUnknown(state)).toBe(true);
    expect(needsLeaveGuard(state, false)).toBe(true);
    expect(setItemInput(state, '1', { kind: 'direct', text: '다른 내용' })).toEqual(state);
    expect(removeProduct(state, '1').items).toHaveLength(3);
    expect(setTemplate(state, '6')).toBe(state);

    const retry = prepareBatch(state, keys.create, NOW);
    expect(retry?.request.items.map((item) => item.idempotencyKey)).toEqual(
      request.items.map((item) => item.idempotencyKey),
    );
    // 다시 보내 결과를 받으면 풀린다
    const resolved = applyBatchResponse(
      { ...state, keys: retry?.keys ?? state.keys },
      retry?.request ?? request,
      { items: request.items.map((sent) => ok(sent)) },
    ).state;
    expect(hasUnknown(resolved)).toBe(false);
    expect(setTemplate(resolved, '6').templateId).toBe('6');
  });

  it('템플릿을 바꾸면 접수된 항목은 그대로, 나머지는 대기로 돌아가 새 키다', () => {
    const { keys, request, applied } = afterFirst((items) => [
      ok(at(items, 0)),
      fail(at(items, 1), 'SIZE_CHART_DRAFT_505', true),
    ]);
    const state = setTemplate(applied.state, '6');
    expect(state.items.map((item) => item.result.status)).toEqual([
      'accepted',
      'pending',
      'pending',
    ]);
    const next = prepareBatch(state, keys.create, NOW);
    expect(next?.request.items.map((item) => item.productId)).toEqual(['2']);
    expect(next?.request.items[0]?.idempotencyKey).not.toBe(request.items[1]?.idempotencyKey);
  });

  it('응답 키는 소문자로 맞춘다', () => {
    const upper = () => 'ABCDEF00-0000-4000-8000-000000000001';
    let state = setTemplate(initialBatchState(), '5');
    state = addProduct(state, { productId: '1', name: '상품' });
    state = setItemInput(state, '1', { kind: 'direct', text: 'S 50' });
    const { base, request } = sendWith(state, upper);
    const applied = applyBatchResponse(base, request, {
      items: [
        {
          productId: '1',
          idempotencyKey: 'abcdef00-0000-4000-8000-000000000001',
          draftId: '7',
          jobId: '99',
          errorCode: null,
          retryable: false,
        },
      ],
    });
    expect(applied.state.items[0]?.result).toEqual({
      status: 'accepted',
      draftId: '7',
      jobId: '99',
    });
  });

  it('오류 코드 → 상태 · 문구', () => {
    const base = { productId: '1', idempotencyKey: 'k', draftId: null, jobId: null };
    expect(
      itemOutcome({ ...base, errorCode: 'SIZE_CHART_DRAFT_030', retryable: false }).result,
    ).toEqual({ status: 'failed', message: DRAFT_SOURCE_UNSAFE });
    expect(
      itemOutcome({ ...base, errorCode: 'SIZE_CHART_DRAFT_001', retryable: false }).result,
    ).toEqual({ status: 'failed', message: ITEM_GONE });
    expect(
      itemOutcome({ ...base, errorCode: 'SIZE_CHART_DRAFT_505', retryable: true }).result,
    ).toEqual({ status: 'retryable', message: ITEM_TEMPORARY, newKey: false, unknown: false });
    expect(
      itemOutcome({ ...base, errorCode: 'SIZE_CHART_DRAFT_003', retryable: false }).result.status,
    ).toBe('failed');
  });

  it('실패 항목은 입력을 고치면 대기로 돌아가고, 접수된 항목은 정리할 때까지 남는다', () => {
    const { request, applied } = afterFirst((items) => [
      ok(at(items, 0)),
      fail(at(items, 1), 'SIZE_CHART_DRAFT_030'),
    ]);
    const state = setItemInput(applied.state, '2', { kind: 'direct', text: 'L 54' });
    expect(state.items[1]?.result.status).toBe('pending');
    expect(removeProduct(state, '1').items).toHaveLength(3);
    expect(setItemInput(state, '1', { kind: 'none' }).items[0]?.input.kind).toBe('source');
    expect(clearAccepted(state).items.map((item) => item.productId)).toEqual(['2', '3']);
    // 정리해도 키는 남는다 — 같은 입력으로 다시 넣으면 같은 키(중복 접수 없음)
    expect(clearAccepted(state).keys['1']?.[0]?.key).toBe(request.items[0]?.idempotencyKey);
  });
});

describe('요청 전체 거절(400)', () => {
  it('118 은 아무것도 만들지 않았다 — 잠그지 않고 목록을 확인하게 한다', () => {
    const { base, request } = sendWith(filled(), keyMaker().create);
    const failed = applyBatchFailure(
      base,
      request,
      apiError('validation', 400, 'SIZE_CHART_DRAFT_118', [
        { field: 'selectionUnique', code: 'SIZE_CHART_DRAFT_118', message: '원문' },
      ]),
    );
    expect(failed.state.unresolved).toBeNull();
    expect(failed.state.notice).toBe(BATCH_INVALID);
  });

  it('항목 필드 오류는 그 항목에 표시하고, 다시 보내면 입력이 같은 항목은 같은 키다', () => {
    const keys = keyMaker();
    const { base, request } = sendWith(filled(), keys.create);
    const failed = applyBatchFailure(
      base,
      request,
      apiError('validation', 400, 'COMMON_001', [
        { field: 'items[1].input.sourceText', code: 'SIZE_CHART_DRAFT_104', message: '원문' },
        {
          field: 'items[0].input.textSelection.bodyHash',
          code: 'SIZE_CHART_DRAFT_119',
          message: '원문',
        },
      ]),
    );
    expect(failed.state.notice).toBe(BATCH_ITEMS_INVALID);
    expect(failed.reload).toEqual(['1']);
    expect(failed.state.items[0]?.input).toEqual({ kind: 'none' });
    expect(failed.state.items[1]?.result).toEqual({
      status: 'failed',
      message: DRAFT_SOURCE_LENGTH,
    });

    const fixed = setItemInput(failed.state, '2', { kind: 'direct', text: 'L 가슴 54' });
    const again = prepareBatch(fixed, keys.create, NOW);
    // 텍스트가 같으면(앞뒤 공백만 다름) 같은 키
    expect(again?.request.items[0]?.idempotencyKey).toBe(request.items[1]?.idempotencyKey);
  });
});

describe('화면 문구', () => {
  it('보낼 항목이 대기면 선택한 상품 수, 모두 재시도면 실패한 수로 쓴다', () => {
    expect(requestLabel(filled())).toBe('선택한 상품 2개 초안 요청');
    expect(requestLabel(initialBatchState())).toBe('초안 요청');
  });

  it('요청 중인 항목은 요청 중으로, 그 밖은 상태 그대로 보인다', () => {
    const item = filled().items[0];
    if (!item) throw new Error('항목');
    expect(itemTone(item, true)).toEqual({ tone: 'sending', label: '요청 중' });
    expect(itemTone(item, false)).toEqual({ tone: 'pending', label: '대기' });
    expect(needsLeaveGuard(filled(), true)).toBe(true);
    expect(needsLeaveGuard(filled(), false)).toBe(false);
  });
});
