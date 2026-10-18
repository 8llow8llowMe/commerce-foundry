import { describe, expect, it } from 'vitest';
import { ApiError, classifyFailure, messagesFor } from './api-error';
import type { DataHeader } from './envelope';

const header = (overrides: Partial<DataHeader> = {}): DataHeader => ({
  success: false,
  resultCode: 'X_001',
  resultMessage: '메시지',
  fieldErrors: null,
  ...overrides,
});

describe('classifyFailure', () => {
  it.each([
    [401, 'unauthorized'],
    [403, 'forbidden'],
    [404, 'notFound'],
    [409, 'conflict'],
    [429, 'rateLimited'],
    [502, 'unknownOutcome'],
    [504, 'unknownOutcome'],
    [503, 'unavailable'],
    [500, 'server'],
    [400, 'server'],
  ] as const)('status %i → %s', (status, kind) => {
    expect(classifyFailure(status, header())).toBe(kind);
  });

  it('fieldErrors 가 있으면 상태와 무관하게 validation 이다', () => {
    const fieldErrors = [{ code: 'M_1', field: 'phone', message: '형식 오류' }];
    expect(classifyFailure(400, header({ fieldErrors }))).toBe('validation');
    expect(classifyFailure(409, header({ fieldErrors }))).toBe('validation');
  });

  it('빈 fieldErrors 배열은 validation 이 아니다', () => {
    expect(classifyFailure(400, header({ fieldErrors: [] }))).toBe('server');
  });
});

describe('ApiError', () => {
  it('서버 메시지가 없으면 kind 기본 문구를 쓴다', () => {
    expect(new ApiError('unavailable', 503, 'GATEWAY_002', null).message).toBe(
      '서비스가 잠시 응답하지 않습니다. 잠시 후 다시 시도해 주세요.',
    );
  });

  it('rateLimited 기본 문구는 잠시 후 다시 시도하라는 안내다', () => {
    expect(new ApiError('rateLimited', 429, 'GATEWAY_006', null).message).toBe(
      '요청이 너무 많습니다. 잠시 후 다시 시도해 주세요.',
    );
  });

  it('서버 메시지를 우선한다', () => {
    expect(new ApiError('notFound', 404, 'ORDER_001', '존재하지 않는 주문입니다.').message).toBe(
      '존재하지 않는 주문입니다.',
    );
  });
});

describe('messagesFor', () => {
  it('해당 필드의 메시지만 모은다', () => {
    const error = new ApiError('validation', 400, 'M_1', '입력 오류', [
      { code: 'M_1', field: 'phone', message: '형식 오류' },
      { code: 'M_2', field: 'email', message: '이메일 오류' },
      { code: 'M_3', field: 'phone', message: '필수' },
    ]);
    expect(messagesFor(error, 'phone')).toEqual(['형식 오류', '필수']);
    expect(messagesFor(error, 'name')).toEqual([]);
  });
});
