import { describe, expect, it } from 'vitest';
import { PRACTICE_ORDERS, PRACTICE_REFUND } from './fixtures';
import { createPracticeTransport, PRACTICE_REASON_MAX } from './transport';

const SHIP = PRACTICE_ORDERS.ship.orderId;
const CANCEL = PRACTICE_ORDERS.cancel.orderId;

describe('createPracticeTransport', () => {
  it('확인 → 준비 → 송장으로 샘플 주문이 배송 중이 된다', async () => {
    const transport = createPracticeTransport();
    expect((await transport.send(SHIP, { type: 'confirm' })).ok).toBe(true);
    const prepared = await transport.send(SHIP, { type: 'prepare' });
    expect(prepared.ok && prepared.order.status).toBe('PREPARING');
    const shipped = await transport.send(SHIP, {
      type: 'ship',
      carrier: 'PRACTICE_A',
      trackingNumber: ' PRACTICE-123 ',
    });
    expect(shipped).toMatchObject({
      ok: true,
      order: {
        status: 'SHIPPING',
        shipment: { carrierLabel: '연습 택배 A', trackingNumber: 'PRACTICE-123' },
      },
      refund: null,
    });
  });

  it('확인 → 취소 → 환불 결과는 고정된 가짜 환불이다', async () => {
    const transport = createPracticeTransport();
    await transport.send(CANCEL, { type: 'confirm' });
    const canceled = await transport.send(CANCEL, { type: 'cancel', reason: '고객 요청' });
    expect(canceled.ok && canceled.order.status).toBe('CANCELED');
    const refund = await transport.send(CANCEL, { type: 'refund-result' });
    expect(refund.ok && refund.refund).toEqual(PRACTICE_REFUND);
  });

  it('순서를 건너뛰거나 입력이 틀리면 실패하고 상태를 바꾸지 않는다', async () => {
    const transport = createPracticeTransport();
    expect(await transport.send(SHIP, { type: 'prepare' })).toEqual({
      ok: false,
      message: '주문 내용을 먼저 확인해 주세요.',
    });
    expect((await transport.send(SHIP, { type: 'refund-result' })).ok).toBe(false);
    await transport.send(SHIP, { type: 'confirm' });
    await transport.send(SHIP, { type: 'prepare' });
    expect(
      await transport.send(SHIP, { type: 'ship', carrier: 'CJ_LOGISTICS', trackingNumber: '1' }),
    ).toEqual({ ok: false, message: '택배사를 골라 주세요.' });
    expect(
      await transport.send(SHIP, { type: 'ship', carrier: 'PRACTICE_B', trackingNumber: '12 34' }),
    ).toMatchObject({ ok: false });
    expect(transport.order(SHIP)?.status).toBe('PREPARING');

    await transport.send(CANCEL, { type: 'confirm' });
    expect(await transport.send(CANCEL, { type: 'cancel', reason: '  ' })).toEqual({
      ok: false,
      message: '취소 사유를 적어 주세요.',
    });
    expect(
      (
        await transport.send(CANCEL, {
          type: 'cancel',
          reason: 'x'.repeat(PRACTICE_REASON_MAX + 1),
        })
      ).ok,
    ).toBe(false);
    expect(transport.order(CANCEL)?.status).toBe('PAID');
  });

  it('샘플이 아닌 주문 · 모르는 동작은 실패로만 돌려준다(실서비스로 넘기지 않는다)', async () => {
    const transport = createPracticeTransport();
    expect(await transport.send('4611686018427387904', { type: 'confirm' })).toEqual({
      ok: false,
      message: '샘플 주문이 아닙니다.',
    });
    // 타입 밖의 값이 와도 던지지 않고 실패한다
    const unknown = { type: 'download' } as unknown as Parameters<typeof transport.send>[1];
    expect(await transport.send(SHIP, unknown)).toEqual({
      ok: false,
      message: '연습에서 할 수 없는 동작입니다.',
    });
  });

  it('처음부터 다시 하면 샘플 주문이 처음 상태로 돌아가고, fixture 는 바뀌지 않는다', async () => {
    const transport = createPracticeTransport();
    await transport.send(SHIP, { type: 'confirm' });
    const prepared = await transport.send(SHIP, { type: 'prepare' });
    // 돌려받은 주문을 바꿔도 transport 안의 주문은 그대로다
    if (prepared.ok) prepared.order.status = 'CANCELED';
    expect(transport.order(SHIP)?.status).toBe('PREPARING');
    transport.reset();
    expect(transport.order(SHIP)).toEqual(PRACTICE_ORDERS.ship);
    expect((await transport.send(SHIP, { type: 'prepare' })).ok).toBe(false);
    expect(PRACTICE_ORDERS.ship.status).toBe('PAID');
    expect(PRACTICE_ORDERS.ship.histories).toHaveLength(1);
  });
});
