'use client';

import { apiFetch, createIdempotencyKey, type PageResponse } from '@cf/api-client';
import { Button, Checkbox, cx } from '@cf/ui';
import Link from 'next/link';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import {
  emptyProductsText,
  ProductPager,
  ProductSearchForm,
  useProductSearch,
} from '@/components/product/ProductSearch';
import { leavesScreen } from '@/components/store/StoreNavigationParts';
import { LOAD_FAILED, accessDeniedOf, type AccessDenied } from '@/lib/api-errors';
import { isJobId } from '@/lib/job/detail';
import { trackedJobs } from '@/lib/job/tracked-jobs';
import { SOURCE_TEXT_MAX } from '@/lib/size-chart/draft';
import {
  addProduct,
  applyBatchFailure,
  applyBatchResponse,
  BATCH_LEAVE,
  BATCH_LIMIT,
  BATCH_MAX_ITEMS,
  BATCH_SENDING,
  BATCH_TEMPLATE_LOCKED,
  clearAccepted,
  DRAFT_BATCHES_PATH,
  editSource,
  hasUnknown,
  initialBatchState,
  inputProblem,
  isUnknown,
  ITEM_ACCEPTED,
  itemTone,
  needsLeaveGuard,
  prepareBatch,
  removeProduct,
  requestLabel,
  sendableItems,
  setItemInput,
  setTemplate,
  type BatchItem,
  type BatchRequest,
  type BatchResponse,
  type BatchState,
  type ItemTone,
} from '@/lib/size-chart/draft-batch';
import { loadKeyBook, saveKeyBook, sessionKeyStorage } from '@/lib/size-chart/draft-batch-keys';
import type { TextSourcesState } from '@/lib/size-chart/text-source';
import { TEMPLATES_PATH, type TemplateSummary } from '@/lib/size-chart/types';
import type { ProductSummary } from '@/lib/stock/types';
import { TextSourceOptions } from './TextSourceOptions';
import { useTextSources } from './useTextSources';

/** 템플릿 고르기 목록은 편집기와 같이 한 번에 최대치(100)를 읽는다 */
const TEMPLATE_PAGE_SIZE = 100;

type Templates =
  { kind: 'loading' } | { kind: 'ok'; list: PageResponse<TemplateSummary> } | { kind: 'failed' };

/** 행 왼쪽 상태 띠 — 화면에서 유일하게 눈에 띄는 요소다. 같은 뜻의 글자를 늘 함께 보인다 */
const STRIPE: Record<ItemTone, string> = {
  pending: 'bg-border',
  sending: 'bg-muted-fg',
  accepted: 'bg-primary',
  failed: 'bg-danger',
  retryable: 'bg-danger/40',
};
const TONE_TEXT: Record<ItemTone, string> = {
  pending: 'text-muted-fg',
  sending: 'text-muted-fg',
  accepted: 'text-fg',
  failed: 'text-danger',
  retryable: 'text-fg',
};

/** 처음 그릴 때 이 탭에 남은 키 장부를 읽는다(서버 렌더에서는 빈 장부 — 화면에 그리지 않는 값이다) */
const startState = (): BatchState =>
  initialBatchState(loadKeyBook(sessionKeyStorage(), Date.now()));

/**
 * 여러 상품 AI 사이즈표 초안 일괄 요청(#218 · BE #737). 템플릿 하나 → 상품 최대 10개 → 상품마다 본문 표 후보나 직접 입력 →
 * 한 번에 요청. 항목별 결과(접수됨 · 실패 · 다시 시도 가능)를 보이고, 실패한 항목만 같은 키로 다시 보낸다.
 * 결과를 모르는 실패(504 · 연결 끊김)는 같은 본문 전체를 그대로 다시 보낼 때까지 목록을 잠그고, 그동안 화면을 떠나려 하면 묻는다.
 * 항목별 키는 이 탭의 sessionStorage 에 둔다(`draft-batch-keys`). 이미지 일괄은 범위 밖이다(상품별 편집기).
 */
export function SizeChartBatch() {
  const id = useId();
  const [state, setState] = useState<BatchState>(startState);
  const [templates, setTemplates] = useState<Templates>({ kind: 'loading' });
  const [denied, setDenied] = useState<AccessDenied | null>(null);
  const [inFlight, setInFlight] = useState<BatchRequest | null>(null);
  const sendingRef = useRef(false);
  const onDenied = useCallback((next: AccessDenied) => setDenied(next), []);
  const sources = useTextSources(onDenied);
  const search = useProductSearch();
  const sending = inFlight !== null;
  const locked = state.unresolved !== null;
  const unknown = hasUnknown(state);
  const guard = needsLeaveGuard(state, sending);

  const loadTemplates = useCallback(
    () =>
      apiFetch<PageResponse<TemplateSummary>>(
        `${TEMPLATES_PATH}?page=0&size=${TEMPLATE_PAGE_SIZE}`,
      ).then(
        (list) => setTemplates({ kind: 'ok', list }),
        (cause: unknown) => {
          const next = accessDeniedOf(cause);
          if (next) setDenied(next);
          else setTemplates({ kind: 'failed' });
        },
      ),
    [],
  );

  useEffect(() => {
    void loadTemplates();
  }, [loadTemplates]);

  // 장부가 바뀌면(새 키 · 002 로 버린 키) 이 탭 저장소에 남긴다. 보내기 직전에는 submit 이 먼저 쓴다
  useEffect(() => {
    saveKeyBook(sessionKeyStorage(), state.keys);
  }, [state.keys]);

  // 결과를 모르는 동안 떠나면 같은 본문을 다시 보낼 수 없다 — 창 닫기 · 새로 고침은 beforeunload,
  // 셸 메뉴 · 상품 목록 · 템플릿 관리 같은 앱 안 링크는 문서 클릭(캡처)에서 묻는다(StoreNavigationEditor 와 같은 방식).
  // 새 탭으로 여는 "검토하러 가기" 는 묻지 않는다. 브라우저 뒤로 가기는 막지 못한다
  useEffect(() => {
    if (!guard) return;
    const warn = (event: BeforeUnloadEvent) => event.preventDefault();
    const confirmLeave = (event: MouseEvent) => {
      if (event.defaultPrevented || event.button !== 0 || !(event.target instanceof Element))
        return;
      const anchor = event.target.closest('a[href]');
      if (!(anchor instanceof HTMLAnchorElement)) return;
      const modified = event.metaKey || event.ctrlKey || event.shiftKey || event.altKey;
      if (!leavesScreen(anchor, window.location, modified)) return;
      if (!window.confirm(BATCH_LEAVE)) event.preventDefault();
    };
    window.addEventListener('beforeunload', warn);
    document.addEventListener('click', confirmLeave, true);
    return () => {
      window.removeEventListener('beforeunload', warn);
      document.removeEventListener('click', confirmLeave, true);
    };
  }, [guard]);

  const toggleProduct = (product: ProductSummary, checked: boolean) => {
    if (!checked) {
      setState((current) => removeProduct(current, product.productId));
      return;
    }
    if (state.items.length >= BATCH_MAX_ITEMS) return;
    setState((current) => addProduct(current, product));
    if (sources.stateOf(product.productId).status !== 'ok') void sources.load(product.productId);
  };

  const submit = async () => {
    // 두 번 눌러도 한 번만 — 상태 갱신보다 먼저 막는다
    if (sendingRef.current) return;
    const prepared = prepareBatch(state, createIdempotencyKey, Date.now());
    if (!prepared) return;
    sendingRef.current = true;
    // 키는 보내기 전에 남긴다 — 응답을 못 받고 새로 고쳐도 같은 탭 · 같은 입력의 다음 요청이 같은 키를 쓴다
    saveKeyBook(sessionKeyStorage(), prepared.keys);
    const base: BatchState = { ...state, keys: prepared.keys, notice: null };
    setState(base);
    setInFlight(prepared.request);
    let reload: string[] = [];
    let jobs: string[] = [];
    try {
      // 키가 항목마다 본문에 있어 Idempotency-Key 헤더는 보내지 않는다
      const response = await apiFetch<BatchResponse>(DRAFT_BATCHES_PATH, {
        method: 'POST',
        json: prepared.request,
      });
      const applied = applyBatchResponse(base, prepared.request, response);
      setState(applied.state);
      reload = applied.reload;
      jobs = applied.acceptedJobs;
    } catch (cause) {
      const failed = applyBatchFailure(base, prepared.request, cause);
      if (failed.denied) setDenied(failed.denied);
      setState(failed.state);
      reload = failed.reload;
    } finally {
      sendingRef.current = false;
      setInFlight(null);
    }
    for (const jobId of jobs) if (isJobId(jobId)) trackedJobs.track(jobId);
    for (const productId of reload) void sources.load(productId);
  };

  const heading = (
    <div className="flex flex-col gap-1">
      <Link href="/size-charts" className="text-sm text-muted-fg underline underline-offset-4">
        상품 목록
      </Link>
      <h1 className="text-xl font-semibold">여러 상품 AI 초안 요청</h1>
      <p className="text-sm text-muted-fg">
        템플릿 하나로 상품 최대 {BATCH_MAX_ITEMS}개의 사이즈표 초안을 한 번에 요청합니다. 초안은
        상품별 사이즈표 화면에서 검토하고 적용하기 전까지 스토어에 보이지 않습니다.
      </p>
      <p className="text-xs text-muted-fg">
        이 화면은 치수 텍스트로만 요청합니다. 사이즈표 이미지로 요청하려면 상품별 사이즈표 화면을
        이용해 주세요.
      </p>
    </div>
  );

  if (denied)
    return (
      <div className="flex max-w-6xl flex-col gap-6">
        {heading}
        <AccessNotice denied={denied} />
      </div>
    );

  const selectedIds = new Set(state.items.map((item) => item.productId));
  const full = state.items.length >= BATCH_MAX_ITEMS;
  const sendable = sendableItems(state);
  const waiting = state.items.filter(
    (item) => item.result.status !== 'accepted' && inputProblem(item.input) !== null,
  ).length;
  const acceptedCount = state.items.filter((item) => item.result.status === 'accepted').length;
  const canSend = locked || (state.templateId !== null && sendable.length > 0);
  const sendingIds = new Set(inFlight?.items.map((item) => item.productId) ?? []);
  const { current } = search;

  return (
    <div className="flex max-w-6xl flex-col gap-8">
      {heading}

      {/* 요청 중 · 결과를 모르는 동안은 목록 · 치수를 바꾸지 않는다(같은 본문을 다시 보내야 한다) */}
      <fieldset disabled={sending || locked} className="flex min-w-0 flex-col gap-8">
        <section aria-labelledby={`${id}-template`} className="flex flex-col gap-2">
          <h2 id={`${id}-template`} className="text-base font-semibold">
            1. 템플릿 고르기
          </h2>
          <p className="text-xs text-muted-fg">
            고른 템플릿 항목에 맞춰 모든 상품의 초안을 만듭니다.
          </p>
          <div className="flex flex-wrap items-center gap-2">
            <select
              aria-labelledby={`${id}-template`}
              aria-describedby={unknown ? `${id}-template-locked` : undefined}
              value={state.templateId ?? ''}
              disabled={templates.kind !== 'ok' || unknown}
              onChange={(event) => {
                const next = event.currentTarget.value;
                setState((s) => setTemplate(s, next || null));
              }}
              className="h-10 w-full min-w-0 rounded-md border border-border bg-bg px-3 md:w-80"
            >
              <option value="" disabled>
                {templates.kind === 'loading' ? '불러오는 중…' : '템플릿을 고르세요'}
              </option>
              {templates.kind === 'ok' &&
                templates.list.contents.map((template) => (
                  <option key={template.templateId} value={template.templateId}>
                    {template.name} ({template.baseUnit.name})
                  </option>
                ))}
            </select>
            <Link
              href="/size-chart-templates"
              className="text-sm text-muted-fg underline underline-offset-4"
            >
              템플릿 관리
            </Link>
          </div>
          {unknown && (
            <p id={`${id}-template-locked`} className="text-xs text-muted-fg">
              {BATCH_TEMPLATE_LOCKED}
            </p>
          )}
          {templates.kind === 'failed' && (
            <div className="flex items-center gap-3">
              <p className="text-sm text-danger">템플릿 목록을 불러오지 못했습니다.</p>
              <Button variant="secondary" className="h-8 px-3" onClick={() => void loadTemplates()}>
                다시 불러오기
              </Button>
            </div>
          )}
          {templates.kind === 'ok' && templates.list.contents.length === 0 && (
            <p className="text-sm text-muted-fg">
              템플릿이 없습니다. 템플릿 관리에서 먼저 만들어 주세요.
            </p>
          )}
        </section>

        <div className="grid min-w-0 gap-8 lg:grid-cols-[minmax(0,2fr)_minmax(0,3fr)]">
          <section aria-labelledby={`${id}-products`} className="flex min-w-0 flex-col gap-3">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <h2 id={`${id}-products`} className="text-base font-semibold">
                2. 상품 고르기
              </h2>
              <p role="status" className="text-sm text-muted-fg">
                {state.items.length}/{BATCH_MAX_ITEMS}개 선택
              </p>
            </div>
            {full && <p className="text-xs text-muted-fg">{BATCH_LIMIT}</p>}
            <ProductSearchForm search={search} />
            {!current && <p className="text-sm text-muted-fg">불러오는 중…</p>}
            {current?.kind === 'denied' && <AccessNotice denied={current.denied} />}
            {current?.kind === 'failed' && (
              <div role="alert" className="flex items-center gap-4">
                <p className="text-sm text-danger">{LOAD_FAILED}</p>
                <Button variant="secondary" onClick={search.retry}>
                  다시 불러오기
                </Button>
              </div>
            )}
            {current?.kind === 'ok' && current.data.contents.length === 0 && (
              <p className="text-sm text-muted-fg">{emptyProductsText(search.query.keyword)}</p>
            )}
            {current?.kind === 'ok' && current.data.contents.length > 0 && (
              <>
                <ul className="flex flex-col gap-1">
                  {current.data.contents.map((product) => {
                    const selected = selectedIds.has(product.productId);
                    const item = state.items.find((row) => row.productId === product.productId);
                    // 접수된 상품 · 접수 여부를 모르는 상품은 결과를 지키려고 빼지 않는다
                    const pinned =
                      item !== undefined && (item.result.status === 'accepted' || isUnknown(item));
                    return (
                      <li key={product.productId} className="rounded-md bg-muted/50 px-3 py-2">
                        <Checkbox
                          checked={selected}
                          disabled={pinned || (!selected && full)}
                          title={!selected && full ? BATCH_LIMIT : undefined}
                          onChange={(event) => toggleProduct(product, event.currentTarget.checked)}
                          label={
                            <span className="flex flex-wrap gap-x-2">
                              <span className="font-medium break-all">{product.name}</span>
                              <span className="text-muted-fg">
                                {product.visible ? product.availability.name : '숨김'}
                              </span>
                            </span>
                          }
                        />
                      </li>
                    );
                  })}
                </ul>
                <ProductPager search={search} totalPages={current.data.totalPages} />
              </>
            )}
          </section>

          <section aria-labelledby={`${id}-items`} className="flex min-w-0 flex-col gap-3">
            <h2 id={`${id}-items`} className="text-base font-semibold">
              3. 상품별 치수 고르기
            </h2>
            {state.items.length === 0 ? (
              <p className="text-sm text-muted-fg">
                상품을 고르면 여기에 나옵니다. 상품마다 설명에서 찾은 표를 고르거나 치수 텍스트를
                붙여넣어 주세요.
              </p>
            ) : (
              <ul className="flex min-w-0 flex-col gap-3">
                {state.items.map((item) => (
                  <BatchItemRow
                    key={item.productId}
                    item={item}
                    sending={sendingIds.has(item.productId)}
                    sources={sources.stateOf(item.productId)}
                    onInput={(input) => setState((s) => setItemInput(s, item.productId, input))}
                    onReload={() => void sources.load(item.productId)}
                    onRemove={() => setState((s) => removeProduct(s, item.productId))}
                  />
                ))}
              </ul>
            )}
          </section>
        </div>
      </fieldset>

      <section aria-labelledby={`${id}-request`} className="flex flex-col gap-3">
        <h2 id={`${id}-request`} className="text-base font-semibold">
          4. 초안 요청
        </h2>
        {!locked && state.templateId === null && (
          <p className="text-sm text-muted-fg">먼저 템플릿을 골라 주세요.</p>
        )}
        {!locked && waiting > 0 && (
          <p className="text-sm text-muted-fg">
            치수를 고르지 않은 상품 {waiting}개는 이번 요청에서 빠집니다.
          </p>
        )}
        <div className="flex flex-wrap items-center gap-3">
          {/* 요청 중에는 disabled 대신 aria-disabled — 포커스가 body 로 빠지지 않게(카테고리 화면과 같은 규칙) */}
          <Button
            disabled={!sending && !canSend}
            aria-disabled={sending || undefined}
            aria-describedby={`${id}-status`}
            onClick={() => {
              if (sending || !canSend) return;
              void submit();
            }}
          >
            {sending ? '요청 중…' : requestLabel(state)}
          </Button>
          {acceptedCount > 0 && !locked && (
            <Button
              variant="secondary"
              disabled={sending}
              onClick={() => setState((s) => clearAccepted(s))}
            >
              접수된 상품 {acceptedCount}개 목록에서 정리
            </Button>
          )}
        </div>
        {/* live region 은 비어도 렌더 트리에 남기고 내용만 바꾼다 */}
        <p id={`${id}-status`} role="status" className="text-sm">
          {sending ? BATCH_SENDING : (state.notice ?? '')}
        </p>
      </section>
    </div>
  );
}

/** 요청할 상품 한 줄 — 상태 띠 · 상태 글자 · 결과 문구 · 본문 후보 / 직접 입력(접수되면 검토 링크) */
export function BatchItemRow({
  item,
  sending,
  sources,
  onInput,
  onReload,
  onRemove,
}: {
  item: BatchItem;
  sending: boolean;
  sources: TextSourcesState;
  onInput: (input: BatchItem['input']) => void;
  onReload: () => void;
  onRemove: () => void;
}) {
  const id = useId();
  const { tone, label } = itemTone(item, sending);
  const { input, result } = item;
  const accepted = result.status === 'accepted';
  // 접수 여부를 모르는 항목은 같은 입력으로 다시 보낼 때까지 고치거나 빼지 않는다
  const frozen = isUnknown(item);
  const tooLong = input.kind === 'direct' && input.text.length > SOURCE_TEXT_MAX;

  return (
    <li className="relative min-w-0 overflow-hidden rounded-md border border-border">
      <span aria-hidden="true" className={cx('absolute inset-y-0 left-0 w-1', STRIPE[tone])} />
      <div className="flex min-w-0 flex-col gap-3 py-3 pr-3 pl-5">
        <div className="flex min-w-0 flex-wrap items-start justify-between gap-2">
          <h3 className="min-w-0 text-sm font-semibold break-words">{item.productName}</h3>
          <span className={cx('shrink-0 text-xs font-medium', TONE_TEXT[tone])}>{label}</span>
        </div>

        {result.status === 'failed' && <p className="text-sm text-danger">{result.message}</p>}
        {result.status === 'retryable' && <p className="text-sm">{result.message}</p>}

        {accepted ? (
          <p className="text-sm">
            {ITEM_ACCEPTED}{' '}
            <Link
              href={`/size-charts/${encodeURIComponent(item.productId)}`}
              target="_blank"
              rel="noopener noreferrer"
              className="font-medium underline underline-offset-4"
            >
              검토하러 가기<span className="sr-only"> (새 탭)</span>
            </Link>
          </p>
        ) : (
          <fieldset disabled={frozen} className="flex min-w-0 flex-col gap-2">
            <legend className="sr-only">{item.productName} 치수 텍스트</legend>
            <TextSourceOptions
              state={sources}
              name={`${id}-source`}
              selectedId={input.kind === 'source' ? input.source.sourceId : null}
              onPick={(source) => onInput({ kind: 'source', source })}
              onReload={onReload}
              disabled={frozen}
            />
            {input.kind === 'source' && (
              <Button
                variant="secondary"
                className="ml-6 h-8 self-start px-3"
                onClick={() => onInput(editSource(input))}
              >
                고른 표 고쳐 쓰기
              </Button>
            )}
            <label className="flex cursor-pointer items-center gap-2 text-sm">
              <input
                type="radio"
                name={`${id}-source`}
                value="direct"
                checked={input.kind === 'direct'}
                onChange={() => onInput({ kind: 'direct', text: '' })}
                className="size-4 cursor-pointer accent-primary"
              />
              직접 붙여넣기
            </label>
            {input.kind === 'direct' && (
              <div className="ml-6 flex min-w-0 flex-col gap-1">
                <label htmlFor={`${id}-text`} className="sr-only">
                  {item.productName} 치수 텍스트
                </label>
                <textarea
                  id={`${id}-text`}
                  value={input.text}
                  rows={5}
                  placeholder={'예:\nS 가슴단면 50 총장 60\nM 가슴단면 52 총장 62'}
                  aria-invalid={tooLong || undefined}
                  aria-describedby={`${id}-help`}
                  onChange={(event) => onInput({ kind: 'direct', text: event.currentTarget.value })}
                  className="w-full min-w-0 rounded-md border border-border bg-bg px-3 py-2 text-sm text-fg outline-none focus:ring-2 focus:ring-primary"
                />
                <p
                  id={`${id}-help`}
                  className={cx('text-xs', tooLong ? 'text-danger' : 'text-muted-fg')}
                >
                  {input.text.length.toLocaleString('ko-KR')} /{' '}
                  {SOURCE_TEXT_MAX.toLocaleString('ko-KR')}자. 사이즈와 숫자 부분만 넣어 주세요.
                </p>
              </div>
            )}
          </fieldset>
        )}

        {!accepted && !frozen && (
          <Button variant="secondary" className="h-8 self-start px-3" onClick={onRemove}>
            목록에서 빼기
          </Button>
        )}
      </div>
    </li>
  );
}
