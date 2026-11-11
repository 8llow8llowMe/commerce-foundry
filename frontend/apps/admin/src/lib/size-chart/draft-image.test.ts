import { ApiError, type ApiErrorKind, type FieldErrorItem } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import {
  approvedImages,
  confirmationKey,
  createGenerations,
  imageFollowUpPlan,
  draftBody,
  IMAGE_CHANGED,
  IMAGE_CHECK_SESSION_EXPIRED,
  IMAGE_FORMAT,
  IMAGE_INPUT_CLOSED,
  IMAGE_NOT_IN_PRODUCT,
  IMAGE_PERSONAL_INFO,
  IMAGE_RECHECK,
  IMAGE_SELECTION,
  IMAGE_SOURCES_FAILED,
  IMAGE_SOURCES_SESSION_EXPIRED,
  IMAGE_STORAGE_UNAVAILABLE,
  IMAGE_UNUSABLE,
  imageCreateFailure,
  imageSourcesFailure,
  imageSourcesPath,
  inputFingerprint,
  inspectionFailure,
  inspectionPath,
  inspectionText,
  toggleSelection,
  type InspectionState,
} from './draft-image';

const apiError = (
  kind: ApiErrorKind,
  status: number | null,
  code: string | null,
  fieldErrors: FieldErrorItem[] = [],
) => new ApiError(kind, status, code, '서버 원문', fieldErrors);

const HASH_A = 'a'.repeat(64);
const HASH_B = 'b'.repeat(64);
const ok = (sourceId: string, contentHash: string): InspectionState => ({
  status: 'ok',
  inspection: { sourceId, contentHash, contentType: 'image/jpeg', contentLength: 10 },
});

describe('경로', () => {
  it('후보 · 확인 경로는 상품 · 이미지 아이디를 인코딩한다', () => {
    expect(imageSourcesPath('3')).toBe('/api/v1/admin/products/3/size-chart/image-sources');
    expect(inspectionPath('3', 'IMG-9')).toBe(
      '/api/v1/admin/products/3/size-chart/image-sources/IMG-9/inspection',
    );
    expect(inspectionPath('3', 'a/b')).toContain('/a%2Fb/inspection');
  });
});

describe('draftBody', () => {
  it('TEXT 는 기존 모양 그대로(텍스트 앞뒤 공백 제거), IMAGE 는 이미지 순서와 개인정보 확인을 싣는다', () => {
    expect(draftBody('5', { mode: 'TEXT', sourceText: ' S 50 \n' })).toEqual({
      templateId: '5',
      mode: 'TEXT',
      sourceText: 'S 50',
    });
    expect(
      draftBody('5', {
        mode: 'IMAGE',
        images: [
          { sourceId: 'IMG-2', approvedContentHash: HASH_B },
          { sourceId: 'IMG-1', approvedContentHash: HASH_A },
        ],
      }),
    ).toEqual({
      templateId: '5',
      mode: 'IMAGE',
      images: [
        { sourceId: 'IMG-2', approvedContentHash: HASH_B },
        { sourceId: 'IMG-1', approvedContentHash: HASH_A },
      ],
      personalInfoRemovedConfirmed: true,
    });
  });
});

describe('inputFingerprint', () => {
  it('IMAGE 는 순서 · 확인값이 바뀌면 다른 지문이고, TEXT 와 겹치지 않는다', () => {
    const a = { sourceId: 'IMG-1', approvedContentHash: HASH_A };
    const b = { sourceId: 'IMG-2', approvedContentHash: HASH_B };
    const base = inputFingerprint('5', { mode: 'IMAGE', images: [a, b] });
    expect(inputFingerprint('5', { mode: 'IMAGE', images: [a, b] })).toBe(base);
    expect(inputFingerprint('5', { mode: 'IMAGE', images: [b, a] })).not.toBe(base);
    expect(
      inputFingerprint('5', {
        mode: 'IMAGE',
        images: [{ ...a, approvedContentHash: HASH_B }, b],
      }),
    ).not.toBe(base);
    expect(inputFingerprint('5', { mode: 'TEXT', sourceText: 'IMAGE' })).not.toBe(
      inputFingerprint('5', { mode: 'IMAGE', images: [] }),
    );
  });
});

describe('approvedImages · toggleSelection', () => {
  it('고른 이미지가 모두 확인돼야 고른 순서대로 승인 목록을 만든다', () => {
    expect(approvedImages([], {})).toBeNull();
    expect(approvedImages(['IMG-1', 'IMG-2'], { 'IMG-1': ok('IMG-1', HASH_A) })).toBeNull();
    expect(
      approvedImages(['IMG-1', 'IMG-2'], {
        'IMG-1': ok('IMG-1', HASH_A),
        'IMG-2': { status: 'checking' },
      }),
    ).toBeNull();
    expect(
      approvedImages(['IMG-2', 'IMG-1'], {
        'IMG-1': ok('IMG-1', HASH_A),
        'IMG-2': ok('IMG-2', HASH_B),
      }),
    ).toEqual([
      { sourceId: 'IMG-2', approvedContentHash: HASH_B },
      { sourceId: 'IMG-1', approvedContentHash: HASH_A },
    ]);
  });

  it('토글은 순서를 지키고 4장을 넘기지 않는다', () => {
    expect(toggleSelection(['IMG-1'], 'IMG-2')).toEqual(['IMG-1', 'IMG-2']);
    expect(toggleSelection(['IMG-1', 'IMG-2'], 'IMG-1')).toEqual(['IMG-2']);
    expect(toggleSelection(['IMG-1', 'IMG-2', 'IMG-3', 'IMG-4'], 'IMG-5')).toEqual([
      'IMG-1',
      'IMG-2',
      'IMG-3',
      'IMG-4',
    ]);
  });
});

describe('inspectionText', () => {
  it('형식과 크기를 짧게 보인다', () => {
    const base = { sourceId: 'IMG-1', contentHash: HASH_A };
    expect(inspectionText({ ...base, contentType: 'image/webp', contentLength: 300 })).toBe(
      'WEBP · 1KB',
    );
    expect(
      inspectionText({ ...base, contentType: 'image/jpeg', contentLength: 3 * 1024 * 1024 }),
    ).toBe('JPEG · 3.0MB');
  });
});

describe('imageSourcesFailure', () => {
  it('기능 닫힘(011)은 이미지 입력을 닫고, 401 은 로그인 화면으로 보내지 않는다', () => {
    expect(imageSourcesFailure(apiError('validation', 400, 'SIZE_CHART_DRAFT_011'))).toEqual({
      kind: 'closed',
      message: IMAGE_INPUT_CLOSED,
    });
    expect(imageSourcesFailure(apiError('unauthorized', 401, 'AUTH_001'))).toEqual({
      kind: 'retry',
      message: IMAGE_SOURCES_SESSION_EXPIRED,
    });
    expect(imageSourcesFailure(apiError('forbidden', 403, 'AUTH_002'))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
    expect(imageSourcesFailure(apiError('unavailable', 503, null))).toEqual({
      kind: 'retry',
      message: IMAGE_SOURCES_FAILED,
    });
  });
});

describe('inspectionFailure', () => {
  it.each([
    ['SIZE_CHART_DRAFT_011', 'closed', IMAGE_INPUT_CLOSED],
    ['SIZE_CHART_DRAFT_012', 'reloadSources', IMAGE_NOT_IN_PRODUCT],
    ['SIZE_CHART_DRAFT_013', 'image', IMAGE_UNUSABLE],
    ['SIZE_CHART_DRAFT_014', 'image', IMAGE_FORMAT],
    ['SIZE_CHART_DRAFT_504', 'image', IMAGE_STORAGE_UNAVAILABLE],
  ] as const)('%s → %s', (code, kind, message) => {
    expect(inspectionFailure(apiError('server', 400, code))).toEqual({ kind, message });
  });

  it('401 은 그 이미지 칸에 다시 로그인을 안내하고, 서버 원문은 보이지 않는다', () => {
    expect(inspectionFailure(apiError('unauthorized', 401, 'AUTH_001'))).toEqual({
      kind: 'image',
      message: IMAGE_CHECK_SESSION_EXPIRED,
    });
    expect(inspectionFailure(new Error('x'))).toEqual({
      kind: 'image',
      message: IMAGE_STORAGE_UNAVAILABLE,
    });
  });
});

describe('imageCreateFailure', () => {
  it.each([
    ['SIZE_CHART_DRAFT_011', 'closed', IMAGE_INPUT_CLOSED],
    ['SIZE_CHART_DRAFT_012', 'reloadSources', IMAGE_NOT_IN_PRODUCT],
    ['SIZE_CHART_DRAFT_013', 'image', IMAGE_UNUSABLE],
    ['SIZE_CHART_DRAFT_014', 'image', IMAGE_FORMAT],
    ['SIZE_CHART_DRAFT_015', 'reinspect', IMAGE_CHANGED],
    ['SIZE_CHART_DRAFT_504', 'retry', IMAGE_STORAGE_UNAVAILABLE],
  ] as const)('%s → %s', (code, kind, message) => {
    expect(imageCreateFailure(apiError('server', 400, code))).toEqual({ kind, message });
  });

  it.each([
    ['SIZE_CHART_DRAFT_115', 'image', IMAGE_PERSONAL_INFO],
    ['SIZE_CHART_DRAFT_112', 'resetSelection', IMAGE_SELECTION],
    ['SIZE_CHART_DRAFT_113', 'resetSelection', IMAGE_SELECTION],
    ['SIZE_CHART_DRAFT_114', 'reinspect', IMAGE_RECHECK],
  ] as const)('필드 검증 %s → %s', (code, kind, message) => {
    const error = apiError('validation', 400, 'SIZE_CHART_DRAFT_100', [
      { field: 'images', code, message: 'x' },
    ]);
    expect(imageCreateFailure(error)).toEqual({ kind, message });
  });

  it('IMAGE 와 무관한 실패는 공통 처리로 넘긴다', () => {
    expect(imageCreateFailure(apiError('conflict', 409, 'SIZE_CHART_DRAFT_002'))).toBeNull();
    expect(imageCreateFailure(new Error('x'))).toBeNull();
  });
});

describe('confirmationKey', () => {
  it('확인 전이면 null(체크할 수 없음)이고, 015 로 다시 확인해 확인값이 바뀌면 체크가 풀린다', () => {
    expect(confirmationKey(null)).toBeNull();
    const before = { 'IMG-1': ok('IMG-1', HASH_A), 'IMG-2': ok('IMG-2', HASH_B) };
    const confirmed = confirmationKey(approvedImages(['IMG-1', 'IMG-2'], before));
    expect(confirmed).not.toBeNull();
    // 다시 확인 중에는 승인 목록이 없어 체크가 풀린 상태다
    const checking = { ...before, 'IMG-1': { status: 'checking' } as const };
    expect(confirmationKey(approvedImages(['IMG-1', 'IMG-2'], checking))).toBeNull();
    // 바이트가 바뀌어 새 확인값이 오면 이전 체크와 다른 키다
    const after = { ...before, 'IMG-1': ok('IMG-1', 'c'.repeat(64)) };
    expect(confirmationKey(approvedImages(['IMG-1', 'IMG-2'], after))).not.toBe(confirmed);
    // 순서만 바꿔도 다른 승인이다
    expect(confirmationKey(approvedImages(['IMG-2', 'IMG-1'], before))).not.toBe(confirmed);
  });
});

describe('createGenerations', () => {
  it('늦게 온 이전 응답은 버리고, 빼낸 이미지의 응답은 선택을 되살리지 않게 버린다', () => {
    const generations = createGenerations();
    const first = generations.start('IMG-1');
    const second = generations.start('IMG-1');
    expect(generations.isCurrent('IMG-1', first)).toBe(false);
    expect(generations.isCurrent('IMG-1', second)).toBe(true);
    // 확인 중에 빼면(bump) 그 응답은 더 이상 현재가 아니다
    generations.bump(['IMG-1']);
    expect(generations.isCurrent('IMG-1', second)).toBe(false);
    // 키마다 따로 센다 — 목록 다시 읽기는 마지막 응답만 이긴다
    const listA = generations.start('list');
    const listB = generations.start('list');
    expect(generations.isCurrent('list', listA)).toBe(false);
    expect(generations.isCurrent('list', listB)).toBe(true);
  });
});

describe('imageFollowUpPlan', () => {
  it('기능 닫힘은 입력 칸 오류를 지우고 목록 닫힘 안내만 남긴다', () => {
    expect(imageFollowUpPlan({ action: 'closed', message: IMAGE_INPUT_CLOSED })).toEqual({
      clearSourceError: true,
      resetConfirmation: true,
      images: { kind: 'close', message: IMAGE_INPUT_CLOSED },
    });
  });

  it('다시 확인은 체크를 풀고 이유(입력 칸 오류)는 남긴다', () => {
    expect(imageFollowUpPlan({ action: 'reinspect', message: IMAGE_CHANGED })).toEqual({
      clearSourceError: false,
      resetConfirmation: true,
      images: { kind: 'reinspect' },
    });
  });

  it('목록 다시 읽기는 받은 문구를 그대로 넘기고, 112 · 113 은 선택을 비운다', () => {
    expect(
      imageFollowUpPlan({ action: 'reloadSources', message: IMAGE_NOT_IN_PRODUCT }).images,
    ).toEqual({ kind: 'reload', message: IMAGE_NOT_IN_PRODUCT, clearSelection: false });
    expect(
      imageFollowUpPlan({ action: 'resetSelection', message: IMAGE_SELECTION }).images,
    ).toEqual({ kind: 'reload', message: IMAGE_SELECTION, clearSelection: true });
  });
});
