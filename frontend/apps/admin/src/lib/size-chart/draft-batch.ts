import { ApiError } from '@cf/api-client';
import { accessDeniedOf, type AccessDenied } from '@/lib/api-errors';
import { DRAFT_SOURCE_LENGTH, DRAFT_SOURCE_UNSAFE, SOURCE_TEXT_MAX } from './draft';
import {
  findKey,
  fingerprintDigest,
  forgetKey,
  rememberKey,
  type KeyBook,
} from './draft-batch-keys';
import { draftBody, inputFingerprint, type DraftInput } from './draft-image';
import { selectionOf, TEXT_SOURCE_CHANGED, type TextSource } from './text-source';

/**
 * 여러 상품의 AI 사이즈표 초안 일괄 요청(BE #737, `POST /api/v1/admin/size-chart-drafts/batches`, CATALOG_MANAGE).
 * 정본은 `backend/docs/ai-size-chart-guide.md` §2.
 * - 본문 `{items:[{productId, idempotencyKey(UUID), input}]}` — 1~10개, 상품 · 키가 겹치지 않는다. `input` 은 단건 생성 본문과 같다.
 *   키가 항목 안에 있어 `Idempotency-Key` 헤더는 보내지 않는다(컨트롤러가 받지 않는다).
 * - 200 `{items:[{productId, idempotencyKey(소문자), draftId, jobId, errorCode, retryable}]}` — 요청 순서. 성공이면 errorCode null.
 * - 0개 · 10개 초과 · 중복은 400 `SIZE_CHART_DRAFT_118`, 항목 하나의 필드 오류도 배치 전체 400 — 아무것도 만들어지지 않았다.
 * - 504 · 연결 실패처럼 결과를 모르면 **같은 본문(항목별 같은 키) 전체를 그대로** 다시 보낸다. 이미 접수된 항목은 기존 초안을 돌려받는다.
 *   키를 새로 만들면 유료 작업이 중복되므로 그때까지 목록 · 입력을 잠근다(`unresolved`). 항목 하나의 등록 불명(501 · 응답 누락)도
 *   그 항목의 입력과 템플릿을 잠근다(`unknown`).
 * - 재요청은 접수되지 않은 항목만 같은 키 · 같은 입력으로 보낸다. 접수된 항목의 키는 바꾸지 않는다.
 *   입력(템플릿 · 텍스트)이 바뀐 항목과 같은 키에 다른 입력이 있던 항목(002)만 새 키다.
 * - 키는 상품별 "지문 → 키" 장부로 이 탭의 sessionStorage 에 둔다(`./draft-batch-keys`, 7일이 지나면 버린다).
 * 이미지 일괄은 이 화면 범위가 아니다(상품별 편집기에서 이미지를 확인 · 승인해야 한다).
 */
export const BATCH_MAX_ITEMS = 10;
export const DRAFT_BATCHES_PATH = '/api/v1/admin/size-chart-drafts/batches';

/** 항목 하나의 치수 입력 — 본문 후보 그대로 · 직접 입력 · 아직 없음 */
export type BatchItemInput =
  { kind: 'none' } | { kind: 'source'; source: TextSource } | { kind: 'direct'; text: string };

export type ItemResult =
  /** 아직 보내지 않았거나 입력을 고쳐 다시 보낼 차례 */
  | { status: 'pending' }
  | { status: 'accepted'; draftId: string; jobId: string | null }
  /**
   * 다시 요청에 넣는다. `newKey` 면 같은 키에 다른 입력이 있었다(002) — 그 항목만 새 키.
   * `unknown` 이면 접수됐는지 모른다(501 · 응답 누락) — 같은 키 · 같은 입력으로 다시 보낼 때까지 입력을 잠근다.
   */
  | { status: 'retryable'; message: string; newKey: boolean; unknown: boolean }
  /** 입력을 고치거나 다시 골라야 한다. 입력이 바뀌면 대기로 돌아간다 */
  | { status: 'failed'; message: string };

export type BatchItem = {
  productId: string;
  productName: string;
  input: BatchItemInput;
  result: ItemResult;
};

export type BatchRequestItem = {
  productId: string;
  idempotencyKey: string;
  input: ReturnType<typeof draftBody>;
};
export type BatchRequest = { items: BatchRequestItem[] };

export type BatchResultItem = {
  productId: string;
  idempotencyKey: string;
  draftId: string | null;
  jobId: string | null;
  errorCode: string | null;
  retryable: boolean;
};
export type BatchResponse = { items: BatchResultItem[] };

export type BatchState = {
  templateId: string | null;
  /** 고른 순서 */
  items: BatchItem[];
  /** 상품별 "지문 요약 → 키" 장부. 목록에서 뺐다 다시 넣어도 입력이 같으면 같은 키다 */
  keys: KeyBook;
  /** 결과를 모르는 채 끝난 요청. 풀릴 때까지 이 본문 그대로만 다시 보낸다 */
  unresolved: BatchRequest | null;
  /** 화면 전체 안내(요약 · 실패) */
  notice: string | null;
};

export const initialBatchState = (keys: KeyBook = {}): BatchState => ({
  templateId: null,
  items: [],
  keys,
  unresolved: null,
  notice: null,
});

// ── 문구 ────────────────────────────────────────────────

export const BATCH_SENDING =
  '상품마다 차례로 접수해 수십 초 걸릴 수 있습니다. 끝날 때까지 이 화면을 닫지 말아 주세요.';
export const BATCH_UNKNOWN =
  '요청 결과를 끝까지 확인하지 못했습니다. 일부 상품은 이미 접수됐을 수 있습니다. 이 화면에서 "같은 내용으로 다시 요청" 을 누르면 이미 접수된 상품은 기존 초안을 이어 받고 남은 상품만 접수합니다. 그때까지 목록과 치수는 바꿀 수 없습니다.';
export const BATCH_SESSION_EXPIRED =
  '로그인이 만료되어 요청하지 못했습니다. 고른 내용은 그대로 두었습니다. 새 탭에서 다시 로그인한 뒤 다시 요청해 주세요.';
export const BATCH_INVALID = `같은 상품이 두 번 들어 있거나 상품 수가 1~${BATCH_MAX_ITEMS}개가 아니어서 요청하지 않았습니다. 목록을 확인한 뒤 다시 요청해 주세요.`;
export const BATCH_ITEMS_INVALID =
  '치수 내용을 확인해야 하는 상품이 있어 아무것도 요청하지 않았습니다. 표시한 상품을 고친 뒤 다시 요청해 주세요.';
export const BATCH_REJECTED =
  '요청을 처리하지 못했습니다. 아무것도 접수되지 않았으니 잠시 후 다시 요청해 주세요.';
export const BATCH_LIMIT = `한 번에 ${BATCH_MAX_ITEMS}개까지 요청할 수 있습니다. 다른 상품을 빼면 더 고를 수 있습니다.`;
export const BATCH_TEMPLATE_LOCKED =
  '접수됐는지 확인하지 못한 상품이 있어 템플릿을 바꿀 수 없습니다. 먼저 다시 요청해 결과를 확인해 주세요.';
export const BATCH_LEAVE =
  '접수됐는지 확인하지 못한 상품이 있습니다. 이 화면을 떠나면 목록이 사라지고, 다른 내용으로 다시 요청하면 같은 상품이 한 번 더 접수될 수 있습니다. 떠날까요?';

export const ITEM_INPUT_REQUIRED = '본문 표를 고르거나 치수 텍스트를 붙여넣어 주세요.';
export const ITEM_ACCEPTED = '초안 생성을 시작했습니다. 끝나면 알림으로 알려 드립니다.';
export const ITEM_NEW_REQUEST =
  '이 상품은 전에 다른 치수 내용으로 요청한 기록이 있어 접수하지 못했습니다. 다시 요청하면 지금 내용으로 새로 접수합니다.';
export const ITEM_REGISTRATION_UNKNOWN =
  '접수됐는지 확인하지 못했습니다. 이 화면에서 다시 요청하면 이미 접수된 작업을 이어 받습니다. 그때까지 이 상품의 치수는 바꿀 수 없습니다.';
export const ITEM_BUSY = '다른 변경과 겹쳐 접수하지 못했습니다. 잠시 후 다시 요청해 주세요.';
export const ITEM_TEMPORARY = '일시적인 문제로 접수하지 못했습니다. 다시 요청해 주세요.';
export const ITEM_GONE =
  '상품이나 템플릿을 찾을 수 없습니다. 삭제됐는지 확인하고 목록에서 빼 주세요.';
export const ITEM_REJECTED =
  '이 상품은 초안을 요청하지 못했습니다. 치수 내용을 고치거나 목록에서 빼 주세요.';
export const ITEM_CHECK = '치수 내용을 확인해 주세요.';

const CODE = (n: string) => `SIZE_CHART_DRAFT_${n}`;

// ── 입력 ────────────────────────────────────────────────

/** 생성 입력. 후보는 텍스트 그대로 + 출처, 직접 입력은 출처 없이. 없으면 null */
export function draftInputOf(input: BatchItemInput): Extract<DraftInput, { mode: 'TEXT' }> | null {
  if (input.kind === 'source')
    return {
      mode: 'TEXT',
      sourceText: input.source.sourceText,
      selection: selectionOf(input.source),
    };
  if (input.kind === 'direct') return { mode: 'TEXT', sourceText: input.text, selection: null };
  return null;
}

/** 보낼 수 없는 이유. 보낼 수 있으면 null */
export function inputProblem(input: BatchItemInput): string | null {
  if (input.kind === 'none') return ITEM_INPUT_REQUIRED;
  const text = input.kind === 'source' ? input.source.sourceText : input.text;
  if (text.trim() === '') return ITEM_INPUT_REQUIRED;
  if (text.length > SOURCE_TEXT_MAX) return DRAFT_SOURCE_LENGTH;
  return null;
}

/** 후보를 고친다 — 후보 텍스트를 직접 입력 칸으로 옮긴다(출처는 빠진다. 서버는 후보와 같은 텍스트만 출처로 받는다) */
export function editSource(input: BatchItemInput): BatchItemInput {
  return input.kind === 'source' ? { kind: 'direct', text: input.source.sourceText } : input;
}

const textOf = (input: BatchItemInput) => draftInputOf(input)?.sourceText.trim() ?? null;

/**
 * 키 지문의 요약값. 지문은 앞뒤 공백을 뺀 텍스트이고 본문은 후보 원문 그대로일 수 있지만,
 * BE 가 NFKC · 공백 압축 · 앞뒤 공백 제거 뒤 해시하므로 같은 지문이면 서버에서도 같은 입력이다.
 */
const digestOf = (templateId: string, input: Extract<DraftInput, { mode: 'TEXT' }>) =>
  fingerprintDigest(inputFingerprint(templateId, input));

/** 보낸 본문 항목의 지문 요약값 */
function digestOfSent(sent: BatchRequestItem): string | null {
  const input = sent.input;
  return input.mode === 'TEXT'
    ? digestOf(input.templateId, { mode: 'TEXT', sourceText: input.sourceText })
    : null;
}

// ── 목록 ────────────────────────────────────────────────

const locked = (state: BatchState) => state.unresolved !== null;

/** 접수됐는지 모르는 항목 — 다시 요청으로 풀릴 때까지 입력 · 빼기 · 템플릿 변경을 막는다 */
export const isUnknown = (item: BatchItem) =>
  item.result.status === 'retryable' && item.result.unknown;

export const hasUnknown = (state: BatchState) => state.items.some(isUnknown);

/** 화면을 떠나면 다시 같은 본문을 보낼 수 없는 상태 — 떠나기 전에 묻는다 */
export const needsLeaveGuard = (state: BatchState, sending: boolean) =>
  sending || locked(state) || hasUnknown(state);

/** 항목 입력 · 빼기를 막는가 */
export const itemLocked = (state: BatchState, item: BatchItem) =>
  locked(state) || item.result.status === 'accepted' || isUnknown(item);

export function addProduct(
  state: BatchState,
  product: { productId: string; name: string },
): BatchState {
  if (locked(state)) return state;
  if (state.items.some((item) => item.productId === product.productId)) return state;
  if (state.items.length >= BATCH_MAX_ITEMS) return state;
  return {
    ...state,
    items: [
      ...state.items,
      {
        productId: product.productId,
        productName: product.name,
        input: { kind: 'none' },
        result: { status: 'pending' },
      },
    ],
  };
}

/** 목록에서 뺀다. 접수된 항목 · 접수 여부를 모르는 항목은 빼지 않는다. 키는 남긴다 — 다시 넣었을 때 같은 입력이면 같은 키 */
export function removeProduct(state: BatchState, productId: string): BatchState {
  if (locked(state)) return state;
  return {
    ...state,
    items: state.items.filter((item) => item.productId !== productId || itemLocked(state, item)),
  };
}

/** 접수된 항목을 목록에서 정리한다(키는 남긴다) */
export function clearAccepted(state: BatchState): BatchState {
  if (locked(state)) return state;
  return { ...state, items: state.items.filter((item) => item.result.status !== 'accepted') };
}

/**
 * 항목 입력을 바꾼다. 접수된 항목 · 접수 여부를 모르는 항목 · 잠긴 동안은 바꾸지 않는다.
 * 실패(고쳐야 함)는 대기로 돌아간다. 다시 시도 가능은 텍스트가 같으면 그대로(002 의 새 키 표시도 지킨다), 다르면 대기다.
 */
export function setItemInput(
  state: BatchState,
  productId: string,
  input: BatchItemInput,
): BatchState {
  if (locked(state)) return state;
  return {
    ...state,
    items: state.items.map((item) => {
      if (item.productId !== productId || itemLocked(state, item)) return item;
      const keep = item.result.status === 'retryable' && textOf(item.input) === textOf(input);
      return { ...item, input, result: keep ? item.result : { status: 'pending' } };
    }),
  };
}

/**
 * 템플릿을 바꾼다. 접수 여부를 모르는 항목이 있으면 바꾸지 않는다(지문이 바뀌어 새 키가 되면 중복 접수될 수 있다).
 * 접수된 항목은 그대로, 나머지는 대기로 — 지문이 바뀌어 새 키다.
 */
export function setTemplate(state: BatchState, templateId: string | null): BatchState {
  if (locked(state) || hasUnknown(state) || state.templateId === templateId) return state;
  return {
    ...state,
    templateId,
    items: state.items.map((item) =>
      item.result.status === 'accepted' ? item : { ...item, result: { status: 'pending' } },
    ),
  };
}

/** 이번 요청에 넣을 항목 — 대기 · 다시 시도 가능이고 입력이 있는 것 */
export function sendableItems(state: BatchState): BatchItem[] {
  return state.items.filter(
    (item) =>
      (item.result.status === 'pending' || item.result.status === 'retryable') &&
      inputProblem(item.input) === null,
  );
}

/**
 * 보낼 본문과 갱신한 키 장부. 결과를 모르는 요청이 있으면 그 본문 그대로다(키를 새로 만들지 않는다).
 * 같은 상품 · 같은 지문의 살아 있는 키가 있으면 그 키를, 없거나 002 뒤면 새 키를 쓴다. 템플릿이 없거나 보낼 항목이 없으면 null.
 */
export function prepareBatch(
  state: BatchState,
  create: () => string,
  now: number,
): { request: BatchRequest; keys: KeyBook } | null {
  if (state.unresolved) return { request: state.unresolved, keys: state.keys };
  const templateId = state.templateId;
  if (!templateId) return null;
  let keys = state.keys;
  const items: BatchRequestItem[] = [];
  for (const item of sendableItems(state)) {
    const input = draftInputOf(item.input);
    if (!input) continue;
    const digest = digestOf(templateId, input);
    const forceNew = item.result.status === 'retryable' && item.result.newKey;
    let entry = forceNew ? null : findKey(keys, item.productId, digest, now);
    if (!entry) {
      entry = { digest, key: create(), createdAt: now };
      keys = rememberKey(keys, item.productId, entry);
    }
    items.push({
      productId: item.productId,
      idempotencyKey: entry.key,
      input: draftBody(templateId, input),
    });
  }
  return items.length > 0 ? { request: { items }, keys } : null;
}

// ── 결과 ────────────────────────────────────────────────

export type ItemOutcome = {
  result: ItemResult;
  /** 본문 후보를 다시 읽고 다시 고르게 한다(017) */
  reloadSources: boolean;
};

const unknownResult = (): ItemResult => ({
  status: 'retryable',
  message: ITEM_REGISTRATION_UNKNOWN,
  newKey: false,
  unknown: true,
});

/** 항목 결과 → 상태 · 문구. 오류 코드는 단건 생성과 같은 체계다 */
export function itemOutcome(item: BatchResultItem): ItemOutcome {
  if (item.errorCode === null && item.draftId)
    return {
      result: { status: 'accepted', draftId: item.draftId, jobId: item.jobId },
      reloadSources: false,
    };
  switch (item.errorCode) {
    case CODE('002'):
      return {
        result: { status: 'retryable', message: ITEM_NEW_REQUEST, newKey: true, unknown: false },
        reloadSources: false,
      };
    case CODE('017'):
      return { result: { status: 'failed', message: TEXT_SOURCE_CHANGED }, reloadSources: true };
    case CODE('030'):
      return { result: { status: 'failed', message: DRAFT_SOURCE_UNSAFE }, reloadSources: false };
    case CODE('001'):
      return { result: { status: 'failed', message: ITEM_GONE }, reloadSources: false };
  }
  // 등록 불명(501)은 BE 가 복구용 draftId 를 남긴다. 성공인데 초안 아이디가 없는 응답(계약 밖)도 결과를 모르는 것으로 본다
  if (item.errorCode === CODE('501') || item.errorCode === null || item.draftId !== null)
    return { result: unknownResult(), reloadSources: false };
  if (item.retryable)
    return {
      result: {
        status: 'retryable',
        message: item.errorCode === CODE('003') ? ITEM_BUSY : ITEM_TEMPORARY,
        newKey: false,
        unknown: false,
      },
      reloadSources: false,
    };
  return { result: { status: 'failed', message: ITEM_REJECTED }, reloadSources: false };
}

export type BatchApplied = {
  state: BatchState;
  /** 본문 후보를 다시 읽을 상품 */
  reload: string[];
  /** 새로 접수된 작업 — 상단 작업 표시에 올린다 */
  acceptedJobs: string[];
};

/** 결과 요약 — "3개 중 2개를 접수했습니다. 1개는 다시 요청할 수 있습니다." */
export function batchSummary(results: readonly ItemResult[]): string {
  const count = (status: ItemResult['status']) =>
    results.filter((result) => result.status === status).length;
  const accepted = count('accepted');
  const retryable = count('retryable');
  const failed = count('failed');
  const parts = [`요청한 ${results.length}개 중 ${accepted}개를 접수했습니다.`];
  if (retryable > 0) parts.push(`${retryable}개는 다시 요청할 수 있습니다.`);
  if (failed > 0) parts.push(`${failed}개는 치수를 고치거나 다시 골라야 합니다.`);
  return parts.join(' ');
}

/** 200 응답 반영. 응답 항목은 키(소문자)로 맞추고, 없으면 같은 순서 · 상품으로 맞춘다. 빠진 항목은 등록 불명이다 */
export function applyBatchResponse(
  state: BatchState,
  request: BatchRequest,
  response: BatchResponse,
): BatchApplied {
  const byKey = new Map(response.items.map((item) => [item.idempotencyKey.toLowerCase(), item]));
  const outcomes = new Map<string, ItemOutcome>();
  let keys = state.keys;
  request.items.forEach((sent, index) => {
    const positional = response.items[index];
    const found =
      byKey.get(sent.idempotencyKey.toLowerCase()) ??
      (positional?.productId === sent.productId ? positional : undefined);
    const outcome = found ? itemOutcome(found) : { result: unknownResult(), reloadSources: false };
    outcomes.set(sent.productId, outcome);
    // 같은 키에 다른 입력이 있었다(002) — 그 지문의 키를 버려 입력을 바꿨다 돌아와도 다시 쓰지 않는다
    if (outcome.result.status === 'retryable' && outcome.result.newKey) {
      const digest = digestOfSent(sent);
      if (digest) keys = forgetKey(keys, sent.productId, digest);
    }
  });
  const reload: string[] = [];
  const acceptedJobs: string[] = [];
  const items = state.items.map((item) => {
    const outcome = outcomes.get(item.productId);
    if (!outcome) return item;
    if (outcome.result.status === 'accepted' && outcome.result.jobId)
      acceptedJobs.push(outcome.result.jobId);
    if (outcome.reloadSources) reload.push(item.productId);
    // 후보가 바뀌었으면 고른 후보를 놓는다 — 다시 읽은 목록에서 다시 고른다
    const input: BatchItemInput =
      outcome.reloadSources && item.input.kind === 'source' ? { kind: 'none' } : item.input;
    return { ...item, input, result: outcome.result };
  });
  return {
    state: {
      ...state,
      items,
      keys,
      unresolved: null,
      notice: batchSummary([...outcomes.values()].map((outcome) => outcome.result)),
    },
    reload,
    acceptedJobs,
  };
}

/** `items[3].input.sourceText` → 3 */
const itemIndexOf = (path: string) => {
  const match = /^items\[(\d+)\]/.exec(path);
  return match ? Number(match[1]) : null;
};

/**
 * 결과를 모르는 실패인가(`docs/api-client.md` §3 · §4) — 연결 실패 · 봉투가 아닌 응답 · 502/504 · 503 · 그 밖의 5xx · 429.
 * 그 밖의 4xx(필드 오류 없는 400 · 404 · 405 · 409 · 413 …)는 처리되지 않은 확정 실패다. ApiError 가 아닌 예외는 모르는 것으로 본다.
 */
export function outcomeUnknown(cause: unknown): boolean {
  if (!(cause instanceof ApiError)) return true;
  switch (cause.kind) {
    case 'network':
    case 'invalidResponse':
    case 'unknownOutcome':
    case 'unavailable':
    case 'rateLimited':
      return true;
    case 'server':
      return cause.status === null || cause.status >= 500;
    default:
      return false;
  }
}

export type BatchFailed = {
  state: BatchState;
  /** 403 — 화면이 권한 안내를 그린다 */
  denied: AccessDenied | null;
  reload: string[];
};

/**
 * 요청 전체가 실패했다. `state` 는 보내기 직전 상태다(다시 보낸 경우 `unresolved` 가 그대로 들어 있다).
 * - 400(118 · 항목 필드 오류)은 아무것도 만들어지지 않았다 — 표시한 항목을 고치게 하고 잠그지 않는다(키는 입력이 같으면 그대로).
 * - 401 은 서버가 처리하지 않았다 — 입력을 지키고 다시 로그인을 안내한다. 이미 잠겨 있었으면 잠금도 그대로다.
 * - 결과를 모르는 실패(`outcomeUnknown`)는 같은 본문을 잠가 두고 그대로 다시 보내게 한다.
 * - 그 밖의 4xx 는 처리되지 않은 확정 실패 — 안내만 하고, 이미 잠겨 있었으면(앞선 요청의 결과를 여전히 모른다) 잠금을 그대로 둔다.
 */
export function applyBatchFailure(
  state: BatchState,
  request: BatchRequest,
  cause: unknown,
): BatchFailed {
  const denied = accessDeniedOf(cause);
  if (denied === 'forbidden') return { state, denied, reload: [] };
  if (denied === 'unauthorized')
    return { state: { ...state, notice: BATCH_SESSION_EXPIRED }, denied: null, reload: [] };
  if (cause instanceof ApiError && (cause.code === CODE('118') || cause.fieldErrors.length > 0)) {
    const messages = new Map<string, { message: string; reload: boolean }>();
    let whole = cause.code === CODE('118');
    for (const field of cause.fieldErrors) {
      const index = itemIndexOf(field.field);
      const sent = index === null ? undefined : request.items[index];
      if (!sent || field.code === CODE('118')) {
        whole = true;
        continue;
      }
      if (messages.has(sent.productId)) continue;
      if (field.code === CODE('104'))
        messages.set(sent.productId, { message: DRAFT_SOURCE_LENGTH, reload: false });
      else if (field.code === CODE('119'))
        messages.set(sent.productId, { message: TEXT_SOURCE_CHANGED, reload: true });
      else messages.set(sent.productId, { message: ITEM_CHECK, reload: false });
    }
    const reload: string[] = [];
    const items = state.items.map((item) => {
      const found = messages.get(item.productId);
      if (!found || item.result.status === 'accepted') return item;
      if (found.reload) reload.push(item.productId);
      return {
        ...item,
        input:
          found.reload && item.input.kind === 'source' ? ({ kind: 'none' } as const) : item.input,
        result: { status: 'failed' as const, message: found.message },
      };
    });
    return {
      state: {
        ...state,
        items,
        // 형식 거절은 결정적이다 — 잠겨 있던 본문도 처리되지 않았다
        unresolved: null,
        notice: whole || messages.size === 0 ? BATCH_INVALID : BATCH_ITEMS_INVALID,
      },
      denied: null,
      reload,
    };
  }
  if (outcomeUnknown(cause)) {
    const notice =
      cause instanceof ApiError && cause.kind === 'rateLimited'
        ? `${cause.message} 다시 요청할 때는 같은 내용을 그대로 보냅니다.`
        : BATCH_UNKNOWN;
    return { state: { ...state, unresolved: request, notice }, denied: null, reload: [] };
  }
  return {
    state: { ...state, notice: state.unresolved ? BATCH_UNKNOWN : BATCH_REJECTED },
    denied: null,
    reload: [],
  };
}

// ── 화면 ────────────────────────────────────────────────

/** 요청 버튼 문구 — 실제 동작 그대로 */
export function requestLabel(state: BatchState): string {
  if (state.unresolved) return '같은 내용으로 다시 요청';
  const sendable = sendableItems(state);
  if (sendable.length === 0) return '초안 요청';
  if (sendable.every((item) => item.result.status === 'retryable'))
    return `실패한 ${sendable.length}개 다시 요청`;
  return `선택한 상품 ${sendable.length}개 초안 요청`;
}

export type ItemTone = 'pending' | 'sending' | 'accepted' | 'failed' | 'retryable';

/** 행 상태 — 띠 색과 같은 뜻의 글자를 함께 보인다(색만으로 전하지 않는다) */
export function itemTone(item: BatchItem, sending: boolean): { tone: ItemTone; label: string } {
  if (sending && item.result.status !== 'accepted' && item.result.status !== 'failed')
    return { tone: 'sending', label: '요청 중' };
  switch (item.result.status) {
    case 'accepted':
      return { tone: 'accepted', label: '접수됨' };
    case 'retryable':
      return { tone: 'retryable', label: '다시 시도 가능' };
    case 'failed':
      return { tone: 'failed', label: '실패' };
    case 'pending':
      return { tone: 'pending', label: '대기' };
  }
}
