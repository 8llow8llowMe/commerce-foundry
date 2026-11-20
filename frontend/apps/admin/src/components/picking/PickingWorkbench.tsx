'use client';

import { apiFetch } from '@cf/api-client';
import { Button, FieldError } from '@cf/ui';
import Link from 'next/link';
import { useCallback, useEffect, useId, useRef, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import { ordersHref, DEFAULT_QUERY } from '@/lib/order/query';
import { adminOrderPath, type OrderDetail } from '@/lib/order/types';
import {
  pickingBatchPath,
  pickingBulkCheckPath,
  pickingOrderCheckPath,
  type BulkCheckRequest,
  type BulkCheckResponse,
  type CheckRequest,
  type PickingBatch,
} from '@/lib/picking/types';
import {
  buildCheck,
  BATCH_NOT_FOUND_NOTICE,
  checkFailure,
  checkSummary,
  fetchPickingList,
  downloadFailure,
  loadFailure,
  orderCheckState,
  orderHeadingId,
  parseCount,
  retryableOrderIds,
  skuLabelsFrom,
} from '@/lib/picking/workbench';
import { OrderCheckCard } from './OrderCheckCard';

type Loaded =
  | { kind: 'ok'; batch: PickingBatch }
  | { kind: 'failed' }
  | { kind: 'notFound' }
  | { kind: 'denied'; denied: AccessDenied };

/** 보낸 일괄 검수와 그 결과. "충돌한 주문만 다시 보내기" 는 같은 수량을 다시 쓴다 */
type BulkOutcome = { request: BulkCheckRequest; response: BulkCheckResponse };

/**
 * 출고 작업대(#364) — 묶음 상세(`GET .../picking-batches/{batchId}`)를 정본으로 피킹 목록 · 주문별 포장 검수를 한다.
 * 묶음 ID 가 주소에 있어 새로고침 · 다른 화면에서 돌아와도 서버 상태(검수 기록)를 다시 읽는다. 입력한 수량은 메모리에만 둔다.
 * 검수를 모두 마치면 기존 송장 등록(주문 목록 · 상세)으로 보낸다 — 송장 등록이 출고 시점의 주문 상태를 다시 확인한다.
 */
export function PickingWorkbench({ batchId }: { batchId: string }) {
  const id = useId();
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [labels, setLabels] = useState<Record<string, string>>({});
  const [counts, setCounts] = useState<Record<string, Record<string, string>>>({});
  const [extras, setExtras] = useState<Record<string, string[]>>({});
  const [errors, setErrors] = useState<Record<string, Record<string, string>>>({});
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [bulk, setBulk] = useState<BulkOutcome | null>(null);
  const [downloading, setDownloading] = useState(false);
  const [downloadError, setDownloadError] = useState<string | null>(null);
  const downloadInFlight = useRef(false);
  // 같은 렌더의 두 번째 클릭은 상태가 아직 false 다 — 보내는 중은 ref 로 막는다
  const inFlight = useRef(false);

  /** 검수 · 실패 뒤 다시 읽기. 읽은 상세를 돌려주고, 실패하면 화면을 안내로 바꾸고 null */
  const load = useCallback(async (): Promise<PickingBatch | null> => {
    try {
      const batch = await apiFetch<PickingBatch>(pickingBatchPath(batchId), { cache: 'no-store' });
      setLoaded({ kind: 'ok', batch });
      return batch;
    } catch (cause) {
      setLoaded(loadedFromFailure(cause));
      return null;
    }
  }, [batchId]);

  // 처음 읽기 — 상태는 응답 콜백에서만 바꾼다(늦게 온 이전 응답은 버린다)
  useEffect(() => {
    let alive = true;
    apiFetch<PickingBatch>(pickingBatchPath(batchId), { cache: 'no-store' }).then(
      (batch) => alive && setLoaded({ kind: 'ok', batch }),
      (cause: unknown) => alive && setLoaded(loadedFromFailure(cause)),
    );
    return () => {
      alive = false;
    };
  }, [batchId]);

  // 옵션 이름은 주문 상세에서 — 못 읽은 주문은 옵션 ID 로만 보인다. "다시 불러오기" 로 처음 열려도 읽도록 묶음의 주문 목록에 건다
  const labelKey = loaded?.kind === 'ok' ? loaded.batch.orders.map((o) => o.orderId).join(',') : '';
  useEffect(() => {
    if (labelKey === '') return;
    let alive = true;
    void (async () => {
      const settled = await Promise.allSettled(
        labelKey.split(',').map((orderId) => apiFetch<OrderDetail>(adminOrderPath(orderId))),
      );
      if (!alive) return;
      setLabels(
        skuLabelsFrom(
          settled.flatMap((result) => (result.status === 'fulfilled' ? [result.value] : [])),
        ),
      );
    })();
    return () => {
      alive = false;
    };
  }, [labelKey]);

  if (!loaded) return <p className="text-sm text-muted-fg">불러오는 중…</p>;
  if (loaded.kind === 'denied') return <AccessNotice denied={loaded.denied} />;
  if (loaded.kind === 'notFound')
    return (
      <p role="status" className="text-sm text-muted-fg">
        {BATCH_NOT_FOUND_NOTICE}{' '}
        <Link href="/orders" className="text-fg underline underline-offset-4">
          주문 목록으로
        </Link>
      </p>
    );
  if (loaded.kind === 'failed')
    return (
      <div role="alert" className="flex items-center gap-4">
        <p className="text-sm text-danger">{LOAD_FAILED}</p>
        <Button variant="secondary" onClick={() => void load()}>
          다시 불러오기
        </Button>
      </div>
    );

  const { batch } = loaded;
  const summary = checkSummary(batch);

  const setOrderErrors = (orderId: string, next: Record<string, string>) =>
    setErrors((current) => ({ ...current, [orderId]: next }));

  /** 실패를 화면 처리로. 다시 읽어야 하면 상세를 새로 읽는다(자동으로 다시 보내지 않는다) */
  const handleFailure = async (cause: unknown, orderId: string | null) => {
    const failure = checkFailure(cause);
    if (failure.kind === 'notFound') setLoaded({ kind: 'notFound' });
    else if (failure.kind === 'access') setLoaded({ kind: 'denied', denied: failure.denied });
    else if (failure.kind === 'reload') {
      setNotice(failure.message);
      await load();
    } else if (orderId) setOrderErrors(orderId, { form: failure.message });
    else setNotice(failure.message);
  };

  const run = async (task: () => Promise<void>) => {
    if (inFlight.current) return;
    inFlight.current = true;
    setBusy(true);
    setNotice(null);
    try {
      await task();
    } finally {
      inFlight.current = false;
      setBusy(false);
    }
  };

  const checkOne = (orderId: string) =>
    run(async () => {
      const built = buildCheck(counts[orderId] ?? {});
      if (!built.ok) {
        setOrderErrors(orderId, built.errors);
        return;
      }
      setOrderErrors(orderId, {});
      try {
        const next = await apiFetch<PickingBatch>(pickingOrderCheckPath(batch.batchId, orderId), {
          method: 'POST',
          json: built.request satisfies CheckRequest,
        });
        setLoaded({ kind: 'ok', batch: next });
        // 성공하면 입력 칸 · 버튼이 사라진다 — 키보드 위치를 잃지 않게 그 주문 제목으로 포커스를 옮긴다
        setTimeout(() => document.getElementById(orderHeadingId(orderId))?.focus(), 0);
      } catch (cause) {
        await handleFailure(cause, orderId);
      }
    });

  /** `previous` 가 있으면(충돌 건 다시 보내기) 앞선 결과에 다시 보낸 주문의 결과만 바꿔 끼운다 */
  const sendBulk = async (request: BulkCheckRequest, previous: BulkOutcome | null = null) => {
    try {
      const response = await apiFetch<BulkCheckResponse>(pickingBulkCheckPath(batch.batchId), {
        method: 'POST',
        json: request,
      });
      setBulk(previous ? mergeBulk(previous, request, response) : { request, response });
      // 일괄 응답에는 상세가 없다 — 성공 표시 · 기록을 새로 읽는다
      await load();
    } catch (cause) {
      setBulk(null);
      await handleFailure(cause, null);
    }
  };

  /** 수량을 넣은 미검수 · 실패 주문만 모아 한 번에 검수한다(#740) */
  const checkAll = () =>
    run(async () => {
      const items: BulkCheckRequest['items'] = [];
      const nextErrors: Record<string, Record<string, string>> = {};
      for (const order of batch.orders) {
        if (orderCheckState(batch, order.orderId) === 'checked') continue;
        const orderCounts = counts[order.orderId] ?? {};
        // 빈 칸 · 0 만 있는 주문은 센 것이 없다 — 건너뛴다(잘못 넣은 칸은 오류로 잡는다)
        const touched = Object.values(orderCounts).some((text) => {
          const parsed = parseCount(text);
          return !parsed.ok || parsed.value !== null;
        });
        if (!touched) continue;
        const built = buildCheck(orderCounts);
        if (built.ok) items.push({ orderId: order.orderId, check: built.request });
        else nextErrors[order.orderId] = built.errors;
      }
      setErrors(nextErrors);
      if (Object.keys(nextErrors).length > 0) {
        setNotice('수량을 잘못 넣은 주문이 있어 보내지 않았습니다. 표시된 칸을 고쳐 주세요.');
        return;
      }
      if (items.length === 0) {
        setNotice('수량을 넣은 미검수 주문이 없습니다.');
        return;
      }
      await sendBulk({ items });
    });

  /** 저장 충돌로 끝난 주문만 새로 읽은 상세 뒤에 같은 수량으로 다시 보낸다 */
  const retryConflicts = () =>
    run(async () => {
      if (!bulk) return;
      const retry = new Set(retryableOrderIds(bulk.response));
      const items = bulk.request.items.filter((item) => retry.has(item.orderId));
      if (items.length === 0) return;
      // 상세를 다시 읽지 못하면 보내지 않는다(화면이 오류 안내로 바뀐다)
      if (!(await load())) return;
      await sendBulk({ items }, bulk);
    });

  const download = async () => {
    if (downloadInFlight.current) return;
    downloadInFlight.current = true;
    setDownloading(true);
    setDownloadError(null);
    try {
      const { blob, filename } = await fetchPickingList(batch.batchId);
      const url = URL.createObjectURL(blob);
      const anchor = document.createElement('a');
      anchor.href = url;
      anchor.download = filename;
      document.body.append(anchor);
      anchor.click();
      anchor.remove();
      // 바로 해제하면 일부 브라우저(Safari 등)에서 다운로드가 시작되기 전에 URL 이 사라진다
      setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (cause) {
      const failure = downloadFailure(cause);
      if (failure.kind === 'access') setLoaded({ kind: 'denied', denied: failure.denied });
      else if (failure.kind === 'notFound') setLoaded({ kind: 'notFound' });
      else if (failure.kind === 'message' || failure.kind === 'reload')
        setDownloadError(failure.message);
    } finally {
      downloadInFlight.current = false;
      setDownloading(false);
    }
  };

  const conflicts = bulk ? retryableOrderIds(bulk.response) : [];

  return (
    <div className="flex max-w-5xl flex-col gap-6">
      <div className="flex flex-col gap-1">
        <h1 className="text-xl font-semibold">출고 작업대</h1>
        <p className="text-sm text-muted-fg">
          출고 묶음 {batch.batchId} · 주문 {batch.orders.length}건 · 꺼낼 수량 {batch.totalQuantity}
          개
        </p>
        <p className="text-sm text-muted-fg print:hidden" aria-live="polite">
          검수 완료 {summary.checked}건 · 실패 {summary.failed}건 · 미검수 {summary.pending}건
        </p>
      </div>

      {batch.inspectionComplete && (
        <div role="status" className="rounded-md bg-muted px-5 py-4 print:hidden">
          <p className="font-medium">모든 주문의 포장 검수를 마쳤습니다.</p>
          <p className="mt-1 text-sm text-muted-fg">
            이제 송장을 등록해 출고합니다. 송장 등록 때 주문 상태를 다시 확인하니, 그사이 취소된
            주문은 등록되지 않습니다.{' '}
            <Link
              href={ordersHref({ ...DEFAULT_QUERY, status: 'PREPARING' })}
              className="text-fg underline underline-offset-4"
            >
              송장 등록하러 가기
            </Link>
          </p>
        </div>
      )}

      <section
        aria-labelledby={`${id}-list`}
        data-tour="picking-list"
        className="flex flex-col gap-3 rounded-md border border-border p-4"
      >
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 id={`${id}-list`} className="font-medium">
            피킹 목록
          </h2>
          <div className="flex gap-2 print:hidden">
            <Button variant="secondary" onClick={() => window.print()}>
              인쇄
            </Button>
            <Button variant="secondary" onClick={() => void download()} disabled={downloading}>
              {downloading ? '받는 중…' : 'CSV 받기'}
            </Button>
          </div>
        </div>
        <table className="w-full text-sm">
          <thead>
            <tr className="border-b border-border text-left text-muted-fg">
              <th scope="col" className="w-8 py-2 pr-3 font-normal">
                <span className="sr-only">꺼냄 표시</span>
              </th>
              <th scope="col" className="py-2 pr-3 font-normal">
                상품 옵션
              </th>
              <th scope="col" className="w-24 py-2 text-right font-normal">
                꺼낼 수량
              </th>
            </tr>
          </thead>
          <tbody>
            {batch.pickingQuantities.map((quantity) => (
              <tr key={quantity.skuId} className="border-b border-border">
                <td className="py-2 pr-3" aria-hidden="true">
                  ☐
                </td>
                <td className="py-2 pr-3">
                  <span className="block">
                    {labels[quantity.skuId] ?? '이름을 불러오지 못한 옵션'}
                  </span>
                  <span className="text-xs text-muted-fg">옵션 ID {quantity.skuId}</span>
                </td>
                <td className="py-2 text-right text-base font-semibold tabular-nums">
                  {quantity.quantity}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        <p className="text-xs text-muted-fg">
          인쇄물 · 파일에는 받는 분 · 연락처 · 주소가 들어가지 않습니다.
        </p>
        <FieldError messages={downloadError ? [downloadError] : []} />
      </section>

      {batch.exclusions.length > 0 && (
        <section
          aria-labelledby={`${id}-excluded`}
          className="flex flex-col gap-2 rounded-md border border-border p-4 print:hidden"
        >
          <h2 id={`${id}-excluded`} className="font-medium">
            묶음에서 빠진 주문 {batch.exclusions.length}건
          </h2>
          <ul className="flex flex-col gap-1 text-sm">
            {batch.exclusions.map((exclusion) => (
              <li key={exclusion.orderId}>
                <Link
                  href={`/orders/${encodeURIComponent(exclusion.orderId)}`}
                  className="underline underline-offset-4"
                >
                  주문 {exclusion.orderId}
                </Link>{' '}
                — {exclusion.reason.name}
                {exclusion.reason.description ? ` (${exclusion.reason.description})` : ''}
              </li>
            ))}
          </ul>
        </section>
      )}

      <section
        aria-labelledby={`${id}-checks`}
        data-tour="picking-checks"
        className="flex flex-col gap-3 print:hidden"
      >
        <div className="flex flex-wrap items-end justify-between gap-2">
          <div>
            <h2 id={`${id}-checks`} className="font-medium">
              주문별 포장 검수
            </h2>
            <p className="mt-1 text-sm text-muted-fg">
              포장한 상품을 옵션별로 세어 넣습니다. 비워 둔 옵션은 부족으로 기록됩니다.
            </p>
          </div>
          <Button onClick={() => void checkAll()} disabled={busy} data-tour="picking-check-all">
            {busy ? '검수 중…' : '수량 넣은 주문 모두 검수'}
          </Button>
        </div>
        {notice && (
          <p role="alert" className="text-sm text-danger">
            {notice}
          </p>
        )}
        {bulk && (
          <div role="status" className="flex flex-col gap-2 rounded-md bg-muted px-4 py-3 text-sm">
            <p>
              일괄 검수 {bulk.response.results.length}건 — 성공{' '}
              {bulk.response.results.filter((result) => result.successful).length}건 · 실패{' '}
              {bulk.response.results.filter((result) => !result.successful).length}건
            </p>
            <ul className="flex flex-col gap-0.5">
              {bulk.response.results
                .filter((result) => !result.successful)
                .map((result) => (
                  <li key={result.orderId}>
                    주문 {result.orderId}: {result.reason.name}
                  </li>
                ))}
            </ul>
            {conflicts.length > 0 && (
              <div>
                <Button variant="secondary" onClick={() => void retryConflicts()} disabled={busy}>
                  저장이 겹친 {conflicts.length}건만 다시 보내기
                </Button>
              </div>
            )}
          </div>
        )}
        <ul className="flex flex-col gap-3">
          {batch.orders.map((order) => (
            <OrderCheckCard
              key={order.orderId}
              batch={batch}
              order={order}
              labels={labels}
              counts={counts[order.orderId] ?? {}}
              extraSkuIds={extras[order.orderId] ?? []}
              errors={errors[order.orderId] ?? {}}
              busy={busy}
              onCount={(skuId, text) =>
                setCounts((current) => ({
                  ...current,
                  [order.orderId]: { ...current[order.orderId], [skuId]: text },
                }))
              }
              onAddExtra={(skuId) =>
                setExtras((current) => ({
                  ...current,
                  [order.orderId]: [...(current[order.orderId] ?? []), skuId],
                }))
              }
              onCheck={() => void checkOne(order.orderId)}
            />
          ))}
        </ul>
      </section>
    </div>
  );
}

/** 일괄 결과에 다시 보낸 주문의 결과만 바꿔 끼운다 — 앞선 부족 · 초과 결과가 요약에서 사라지지 않게 */
function mergeBulk(
  previous: BulkOutcome,
  request: BulkCheckRequest,
  response: BulkCheckResponse,
): BulkOutcome {
  const resent = new Map(response.results.map((result) => [result.orderId, result]));
  const items = new Map(request.items.map((item) => [item.orderId, item]));
  return {
    request: {
      items: previous.request.items.map((item) => items.get(item.orderId) ?? item),
    },
    response: {
      ...response,
      results: previous.response.results.map((result) => resent.get(result.orderId) ?? result),
    },
  };
}

function loadedFromFailure(cause: unknown): Loaded {
  const failure = loadFailure(cause);
  if (failure.kind === 'access') return { kind: 'denied', denied: failure.denied };
  if (failure.kind === 'notFound') return { kind: 'notFound' };
  return { kind: 'failed' };
}
