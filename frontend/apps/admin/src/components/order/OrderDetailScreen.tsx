'use client';

import { ApiError, apiFetch } from '@cf/api-client';
import { Button } from '@cf/ui';
import Link from 'next/link';
import { useCallback, useEffect, useRef, useState } from 'react';
import { AccessNotice } from '@/components/AccessNotice';
import { accessDeniedOf, LOAD_FAILED, type AccessDenied } from '@/lib/api-errors';
import { adminOrderPath, type OrderDetail } from '@/lib/order/types';
import { OrderDetailView } from './OrderDetailView';
import { StatusChangePanel } from './StatusChangePanel';

type Status =
  | { kind: 'loading' }
  | { kind: 'denied'; denied: AccessDenied }
  | { kind: 'notFound' }
  | { kind: 'loadFailed' }
  | { kind: 'ready'; order: OrderDetail };

type Notice = { tone: 'ok' | 'warn'; message: string } | null;

const dateTime = new Intl.DateTimeFormat('ko-KR', {
  dateStyle: 'short',
  timeStyle: 'short',
  timeZone: 'Asia/Seoul',
});

/**
 * 어드민 주문 상세(#30) — `GET /api/v1/admin/orders/{orderId}`. 상태 변경 응답이 같은 모양의 상세라 그대로 바꿔 그린다.
 * 거절 · 경합 · 결과 모름이면 최신 상세를 다시 읽고 안내한다(덮어쓰지 않는다). 형식이 틀린 아이디(400 `ORDER_141`)도 없는 주문으로 안내한다.
 */
export function OrderDetailScreen({ orderId }: { orderId: string }) {
  const [status, setStatus] = useState<Status>({ kind: 'loading' });
  const [notice, setNotice] = useState<Notice>(null);
  /** 마지막으로 보낸 읽기 — 늦게 온 이전 응답(다시 불러오기 연타 · 경합 뒤 다시 읽기)이 최신을 덮지 않게 */
  const latest = useRef(0);
  const statusHeading = useRef<HTMLHeadingElement>(null);

  // 상태를 바꾸거나 경합 안내가 뜨면 누른 버튼이 사라진다 — 포커스를 "현재 상태" 제목으로 옮겨 안내가 이어 읽히게 한다
  useEffect(() => {
    if (notice) statusHeading.current?.focus();
  }, [notice]);

  const fail = useCallback((cause: unknown) => {
    const denied = accessDeniedOf(cause);
    if (denied) setStatus({ kind: 'denied', denied });
    else if (cause instanceof ApiError && (cause.kind === 'notFound' || cause.status === 400))
      setStatus({ kind: 'notFound' });
    else setStatus({ kind: 'loadFailed' });
  }, []);

  // 상태는 응답 콜백에서만 바꾼다 — 마운트 effect 가 부른다
  const load = useCallback(() => {
    const request = ++latest.current;
    return apiFetch<OrderDetail>(adminOrderPath(orderId)).then(
      (order) => request === latest.current && setStatus({ kind: 'ready', order }),
      (cause: unknown) => request === latest.current && fail(cause),
    );
  }, [orderId, fail]);

  useEffect(() => {
    void load();
  }, [load]);

  /** 거절 · 경합 뒤 — 이미 읽은 최신 상세가 있으면 그것으로, 없으면 다시 읽는다. 다시 읽기가 실패하면 그 화면 그대로 안내만 남긴다 */
  const refresh = (message: string, fresh: OrderDetail | null) => {
    setNotice({ tone: 'warn', message });
    const request = ++latest.current;
    if (fresh) {
      setStatus({ kind: 'ready', order: fresh });
      return;
    }
    apiFetch<OrderDetail>(adminOrderPath(orderId)).then(
      (order) => request === latest.current && setStatus({ kind: 'ready', order }),
      (cause: unknown) => {
        if (request !== latest.current) return;
        const denied = accessDeniedOf(cause);
        if (denied) setStatus({ kind: 'denied', denied });
        else setNotice({ tone: 'warn', message: `${message} ${LOAD_FAILED}` });
      },
    );
  };

  const order = status.kind === 'ready' ? status.order : null;

  return (
    <div className="flex max-w-5xl flex-col gap-6">
      <div className="flex flex-col gap-1">
        <Link href="/orders" className="text-sm text-muted-fg underline underline-offset-4">
          주문 목록
        </Link>
        <h1 className="text-xl font-semibold">주문 상세</h1>
        {order && (
          <p className="text-sm text-muted-fg">
            주문 {order.orderId} · 회원 {order.memberId} ·{' '}
            {dateTime.format(new Date(order.createdAt))} 주문
          </p>
        )}
      </div>

      {status.kind === 'loading' && <p className="text-sm text-muted-fg">불러오는 중…</p>}
      {status.kind === 'denied' && <AccessNotice denied={status.denied} />}
      {status.kind === 'notFound' && (
        <div role="status" className="flex max-w-xl flex-col gap-3 rounded-md bg-muted px-5 py-6">
          <p className="font-medium">없는 주문입니다.</p>
          <p className="text-sm text-muted-fg">
            주소가 맞는지 확인하거나 주문 목록에서 다시 찾아 주세요.
          </p>
        </div>
      )}
      {status.kind === 'loadFailed' && (
        <div role="alert" className="flex items-center gap-4">
          <p className="text-sm text-danger">{LOAD_FAILED}</p>
          <Button
            variant="secondary"
            onClick={() => {
              setStatus({ kind: 'loading' });
              void load();
            }}
          >
            다시 불러오기
          </Button>
        </div>
      )}

      {order && (
        <>
          <section
            aria-labelledby="order-status"
            data-tour="order-status"
            className="flex flex-col gap-3 rounded-md border border-border p-4"
          >
            <h2
              id="order-status"
              ref={statusHeading}
              tabIndex={-1}
              className="font-medium outline-none"
            >
              현재 상태: <span title={order.status.description}>{order.status.name}</span>
            </h2>
            {order.status.code === 'PENDING_PAYMENT' && (
              <p className="text-sm text-muted-fg">
                결제 기한 {dateTime.format(new Date(order.paymentDueAt))}
              </p>
            )}
            {notice && (
              <p
                role={notice.tone === 'warn' ? 'alert' : 'status'}
                className={notice.tone === 'warn' ? 'text-sm text-danger' : 'text-sm'}
              >
                {notice.message}
              </p>
            )}
            <StatusChangePanel
              // 상태 · 이력 줄 수가 바뀌면(바뀐 상세 · 최신 상세) 확인 단계 · 사유를 새로 시작한다
              key={`${order.status.code}-${order.histories.length}`}
              order={order}
              onChanged={(changed, target) => {
                latest.current += 1;
                setStatus({ kind: 'ready', order: changed });
                setNotice({ tone: 'ok', message: `${target.name}(으)로 바꿨습니다.` });
              }}
              onStale={refresh}
              onNotFound={() => setStatus({ kind: 'notFound' })}
              onDenied={(denied) => setStatus({ kind: 'denied', denied })}
              onSubmit={() => setNotice(null)}
            />
          </section>
          <OrderDetailView order={order} />
        </>
      )}
    </div>
  );
}
