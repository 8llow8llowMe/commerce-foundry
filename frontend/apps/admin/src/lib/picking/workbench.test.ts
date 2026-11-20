import { ApiError } from '@cf/api-client';
import { describe, expect, it, vi } from 'vitest';
import { SESSION_EXPIRED_ON_SAVE } from '@/lib/api-errors';
import { orderDetail } from '@/lib/order/test-fixtures';
import { pickingBatch } from './test-fixtures';
import {
  ALL_EXCLUDED_NOTICE,
  buildCheck,
  CHECK_CONFLICT_NOTICE,
  CHECK_UNKNOWN_NOTICE,
  checkFailure,
  checkSummary,
  COUNT_INVALID,
  CREATE_UNKNOWN_NOTICE,
  DOWNLOAD_FAILED,
  DOWNLOAD_SESSION_EXPIRED,
  downloadFailure,
  RATE_LIMITED_NOTICE,
  createFailure,
  fetchPickingList,
  latestCheckOf,
  loadFailure,
  NOTHING_COUNTED,
  orderCheckState,
  parseCount,
  pickingListFilename,
  retryableOrderIds,
  skuLabelsFrom,
} from './workbench';

const reason = (code: string) => ({ code, name: code, description: '' });

describe('parseCount · buildCheck', () => {
  it('빈 칸 · 0 은 넣지 않고, 양의 정수만 받는다', () => {
    expect(parseCount('')).toEqual({ ok: true, value: null });
    expect(parseCount(' 0 ')).toEqual({ ok: true, value: null });
    expect(parseCount('3')).toEqual({ ok: true, value: 3 });
    expect(parseCount('-1')).toEqual({ ok: false });
    expect(parseCount('1.5')).toEqual({ ok: false });
    expect(parseCount('10000')).toEqual({ ok: false });
  });

  it('센 옵션만 본문에 담고, 칸 오류는 옵션 ID 로 · 아무것도 안 셌으면 폼 오류로 돌려준다', () => {
    expect(buildCheck({ '10': '2', '11': '', '12': '0' })).toEqual({
      ok: true,
      request: { quantities: { '10': 2 } },
    });
    expect(buildCheck({ '10': 'x', '11': '1' })).toEqual({
      ok: false,
      errors: { '10': COUNT_INVALID },
    });
    expect(buildCheck({ '10': '' })).toEqual({ ok: false, errors: { form: NOTHING_COUNTED } });
  });
});

describe('주문 검수 상태', () => {
  it('서버 checked 가 정본이고, 성공 뒤 실패 기록이 와도 완료로 본다', () => {
    const batch = pickingBatch({
      orders: [
        { orderId: '1', status: 'PREPARING', countryCode: 'KR', checked: true, quantities: [] },
        { orderId: '2', status: 'PREPARING', countryCode: 'KR', checked: false, quantities: [] },
        { orderId: '3', status: 'PREPARING', countryCode: 'KR', checked: false, quantities: [] },
      ],
      checks: [
        { orderId: '1', successful: true, reason: reason('NONE'), quantities: [] },
        { orderId: '2', successful: false, reason: reason('EXCESS'), quantities: [] },
        { orderId: '1', successful: false, reason: reason('SHORTAGE'), quantities: [] },
        { orderId: '2', successful: false, reason: reason('SHORTAGE'), quantities: [] },
      ],
    });
    expect(orderCheckState(batch, '1')).toBe('checked');
    expect(orderCheckState(batch, '2')).toBe('failed');
    expect(orderCheckState(batch, '3')).toBe('pending');
    expect(latestCheckOf(batch, '2')?.reason.code).toBe('SHORTAGE');
    expect(latestCheckOf(batch, '3')).toBeNull();
    expect(checkSummary(batch)).toEqual({ checked: 1, failed: 1, pending: 1 });
  });

  it('일괄 결과에서 저장 충돌(retryable)인 실패만 다시 보낸다', () => {
    expect(
      retryableOrderIds({
        batchId: '9',
        results: [
          { orderId: '1', successful: true, reason: reason('NONE'), retryable: false },
          { orderId: '2', successful: false, reason: reason('DELIVERY_037'), retryable: true },
          { orderId: '3', successful: false, reason: reason('SHORTAGE'), retryable: false },
        ],
      }),
    ).toEqual(['2']);
  });
});

describe('skuLabelsFrom', () => {
  it('주문 상세 항목에서 "상품 · 옵션" 이름을 모으고 먼저 본 이름을 쓴다', () => {
    const base = orderDetail();
    const item = base.items[0]!;
    const labels = skuLabelsFrom([
      {
        ...base,
        items: [{ ...item, skuId: '10', productName: '저고리', optionName: '아이보리 / M' }],
      },
      { ...base, items: [{ ...item, skuId: '10', productName: '다른 이름', optionName: '' }] },
      { ...base, items: [{ ...item, skuId: '11', productName: '치마', optionName: '' }] },
    ]);
    expect(labels).toEqual({ '10': '저고리 · 아이보리 / M', '11': '치마' });
  });
});

describe('실패 처리', () => {
  it('생성 — 전부 제외 · 결과 모름 · 세션 만료 · 권한을 가른다', () => {
    expect(createFailure(new ApiError('conflict', 409, 'DELIVERY_033', '제외'))).toEqual({
      kind: 'message',
      message: ALL_EXCLUDED_NOTICE,
    });
    expect(createFailure(new ApiError('network', null, null, null))).toEqual({
      kind: 'message',
      message: CREATE_UNKNOWN_NOTICE,
    });
    expect(createFailure(new ApiError('unauthorized', 401, null, null))).toEqual({
      kind: 'message',
      message: SESSION_EXPIRED_ON_SAVE,
    });
    expect(createFailure(new ApiError('forbidden', 403, null, null))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
    expect(createFailure(new ApiError('server', 400, 'DELIVERY_155', '중복된 주문'))).toEqual({
      kind: 'message',
      message: '중복된 주문',
    });
  });

  it('검수 — 충돌 · 결과 모름은 다시 읽고, 없는 묶음은 안내로, 400 은 서버 문구로', () => {
    expect(checkFailure(new ApiError('conflict', 409, 'DELIVERY_037', '충돌'))).toEqual({
      kind: 'reload',
      message: CHECK_CONFLICT_NOTICE,
    });
    expect(checkFailure(new ApiError('unknownOutcome', 504, null, null))).toEqual({
      kind: 'reload',
      message: CHECK_UNKNOWN_NOTICE,
    });
    expect(checkFailure(new ApiError('notFound', 404, 'DELIVERY_034', '없음'))).toEqual({
      kind: 'notFound',
    });
    expect(checkFailure(new ApiError('server', 400, 'DELIVERY_154', '수량'))).toEqual({
      kind: 'message',
      message: '수량',
    });
  });

  it('fieldErrors 없는 업무 400 · 429 는 처리 전 거절이라 결과 모름 · 다시 읽기로 가지 않는다', () => {
    expect(createFailure(new ApiError('server', 400, 'DELIVERY_032', '형식'))).toEqual({
      kind: 'message',
      message: '형식',
    });
    expect(checkFailure(new ApiError('server', 400, 'DELIVERY_038', '중복 주문'))).toEqual({
      kind: 'message',
      message: '중복 주문',
    });
    expect(checkFailure(new ApiError('rateLimited', 429, null, null))).toEqual({
      kind: 'message',
      message: RATE_LIMITED_NOTICE,
    });
    // 5xx 는 여전히 결과 모름
    expect(createFailure(new ApiError('server', 500, null, null))).toEqual({
      kind: 'message',
      message: CREATE_UNKNOWN_NOTICE,
    });
  });

  it('다운로드 — 401 은 화면을 그대로 두고 문구로, 없는 묶음은 안내로', () => {
    expect(downloadFailure(new ApiError('unauthorized', 401, null, null))).toEqual({
      kind: 'message',
      message: DOWNLOAD_SESSION_EXPIRED,
    });
    expect(downloadFailure(new ApiError('notFound', 404, 'DELIVERY_034', '없음')).kind).toBe(
      'notFound',
    );
    expect(downloadFailure(new ApiError('network', null, null, null))).toEqual({
      kind: 'message',
      message: DOWNLOAD_FAILED,
    });
  });

  it('조회 — 404 는 없는 묶음, 403 은 권한 안내', () => {
    expect(loadFailure(new ApiError('notFound', 404, 'DELIVERY_034', '없음')).kind).toBe(
      'notFound',
    );
    expect(loadFailure(new ApiError('forbidden', 403, null, null))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
  });
});

describe('피킹 리스트 CSV', () => {
  it('파일 이름은 Content-Disposition 에서, 없으면 묶음 ID 로', () => {
    expect(pickingListFilename('attachment; filename="picking-list-9.csv"', '9')).toBe(
      'picking-list-9.csv',
    );
    expect(pickingListFilename(null, '9')).toBe('picking-list-9.csv');
  });

  it('200 CSV 는 파일로, 오류는 봉투를 풀어 ApiError 로 던진다', async () => {
    const csv = vi.fn<typeof fetch>().mockResolvedValue(
      new Response('﻿구분,출고 묶음\r\n', {
        status: 200,
        headers: {
          'content-type': 'text/csv;charset=UTF-8',
          'content-disposition': 'attachment; filename="picking-list-9.csv"',
        },
      }),
    );
    const file = await fetchPickingList('9', csv);
    expect(file.filename).toBe('picking-list-9.csv');
    expect(csv).toHaveBeenCalledWith(
      '/api/v1/admin/deliveries/picking-batches/9/picking-list',
      expect.objectContaining({ headers: { accept: 'text/csv, application/json' } }),
    );

    const missing = vi.fn<typeof fetch>().mockResolvedValue(
      Response.json(
        {
          dataHeader: { success: false, resultCode: 'DELIVERY_034', resultMessage: '없음' },
          dataBody: null,
        },
        { status: 404 },
      ),
    );
    await expect(fetchPickingList('9', missing)).rejects.toMatchObject({ code: 'DELIVERY_034' });
  });
});
