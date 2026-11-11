import { ApiError } from '@cf/api-client';
import { describe, expect, it } from 'vitest';
import { SAVE_FAILED, SESSION_EXPIRED_ON_SAVE } from '@/lib/api-errors';
import {
  AMOUNT_FORMAT,
  AMOUNT_TOO_LARGE,
  CATEGORY_INVALID,
  ALT_TEXT_INVALID,
  CHECK_FIELDS,
  GROUP_NAME_INVALID,
  HS_CODE_INVALID,
  INITIAL_STOCK_INVALID,
  QUANTITY_INVALID,
  SHIP_DATE_PAST,
  SHIP_DATE_WITHOUT_PREORDER,
  SKU_OPTION_INVALID,
  VALUE_INVALID,
} from './create';
import { IMAGE_FORMAT_NOT_ALLOWED, UPLOAD_DISABLED } from '@/lib/upload/image-upload';
import { LABEL_INVALID, LEAD_TIME_INVALID } from './custom-order';
import {
  BUSY,
  CATEGORY_NOT_FOUND,
  CUSTOM_ORDER_INVALID,
  createFailure,
  createFieldKey,
  errorSummary,
  IMAGE_CONFIGURATION,
  IMAGE_STORAGE_FAILED,
  IMAGE_UPLOAD_GONE,
  IMAGES_INVALID,
  OPTION_INVALID,
  PRICE_RULE,
  UNKNOWN_OUTCOME,
} from './create-errors';

const field = (code: string, path: string) => ({ field: path, code, message: '서버 문구' });
const validation = (...items: ReturnType<typeof field>[]) =>
  new ApiError('validation', 400, 'PRODUCT_100', '요청 값이 올바르지 않습니다.', items);
const business = (status: number, code: string, message: string | null = null) =>
  new ApiError(status === 409 ? 'conflict' : 'server', status, code, message);

describe('createFieldKey', () => {
  it.each([
    ['name', 'name'],
    ['salePrice.amount', 'salePrice'],
    ['listPrice.currency', 'listPrice'],
    ['categoryIds[3]', 'categoryIds'],
    ['expectedShipDateWithPreorder', 'expectedShipDate'],
    ['skus[1].additionalPrice.amount', 'skus.1.additionalPrice'],
    ['skus[0].hsCodeValid', 'skus.0.hsCode'],
    ['skus[2].initialStock.quantity', 'skus.2.initialStock'],
    ['skus[2].initialStock', 'skus.2.initialStock'],
    ['skus[0].optionValues[1]', 'skus'],
    ['skus[4]', 'skus'],
    ['optionGroups[0].values[2]', 'optionGroups.0.values'],
    ['optionGroups[1].name', 'optionGroups.1.name'],
    ['optionGroups[1]', 'optionGroups'],
    ['customOrderFields', 'form'],
    ['customOrder.notice', 'customOrder.notice'],
    ['customOrder.leadTimeDays', 'customOrder.leadTimeDays'],
    ['customOrder.fields', 'customOrder.fields'],
    ['customOrder.fields[2].label', 'customOrder.fields.2.label'],
    ['customOrder.fields[0].required', 'customOrder.fields'],
    ['customOrder.fields[1]', 'customOrder.fields'],
    ['customOrder', 'customOrder.fields'],
  ])('%s → %s', (path, key) => {
    expect(createFieldKey(path)).toBe(key);
  });
});

describe('createFailure', () => {
  it('401 은 폼을 지키고 다시 로그인을 안내한다 · 403 은 접근 상태다', () => {
    expect(createFailure(new ApiError('unauthorized', 401, 'AUTH_001', null))).toEqual({
      kind: 'errors',
      errors: { form: [SESSION_EXPIRED_ON_SAVE] },
      reloadCategories: false,
      uncertain: false,
    });
    expect(createFailure(new ApiError('forbidden', 403, 'SECURITY_003', null))).toEqual({
      kind: 'access',
      denied: 'forbidden',
    });
  });

  it.each([
    ['network', new ApiError('network', null, null, null)],
    ['invalidResponse', new ApiError('invalidResponse', 200, null, null)],
    ['502', new ApiError('unknownOutcome', 502, null, null)],
    ['504', new ApiError('unknownOutcome', 504, null, null)],
    ['503', new ApiError('unavailable', 503, 'BFF_002', null)],
    ['500', new ApiError('server', 500, 'COMMON_500', null)],
    ['ApiError 아님', new TypeError('boom')],
  ])('%s 는 등록됐을 수 있다 — 목록 확인을 안내한다(다시 보내지 않는다)', (_, cause) => {
    expect(createFailure(cause)).toEqual({
      kind: 'errors',
      errors: { form: [UNKNOWN_OUTCOME] },
      reloadCategories: false,
      uncertain: true,
    });
  });

  it('검증 오류는 경로로 칸을 찾고 코드별 운영자 문구를 쓴다 · 같은 칸은 모은다', () => {
    const failure = createFailure(
      validation(
        field('PRODUCT_086', 'skus[1].additionalPrice.amount'),
        field('PRODUCT_037', 'salePrice.amount'),
        field('PRODUCT_099', 'skus[0].hsCodeValid'),
        field('PRODUCT_169', 'skus[0].initialStock.stockManaged'),
        field('PRODUCT_106', 'skus[0].initialStock.quantity'),
        field('PRODUCT_094', 'optionGroups[0].values[2]'),
        field('PRODUCT_092', 'optionGroups[0].name'),
        field('PRODUCT_097', 'skus[0].optionValues[0]'),
        field('PRODUCT_089', 'expectedShipDateWithPreorder'),
        field('PRODUCT_038', 'expectedShipDate'),
        field('PRODUCT_137', 'categoryIds[0]'),
        field('PRODUCT_999', 'name'),
      ),
    );
    expect(failure).toEqual({
      kind: 'errors',
      errors: {
        'skus.1.additionalPrice': [AMOUNT_FORMAT],
        salePrice: [AMOUNT_TOO_LARGE],
        'skus.0.hsCode': [HS_CODE_INVALID],
        'skus.0.initialStock': [INITIAL_STOCK_INVALID, QUANTITY_INVALID],
        'optionGroups.0.values': [VALUE_INVALID],
        'optionGroups.0.name': [GROUP_NAME_INVALID],
        skus: [SKU_OPTION_INVALID],
        expectedShipDate: [SHIP_DATE_WITHOUT_PREORDER, SHIP_DATE_PAST],
        categoryIds: [CATEGORY_INVALID],
        name: [CHECK_FIELDS],
      },
      reloadCategories: false,
      uncertain: false,
    });
  });

  it('맞춤 제작 검증 · 정의 오류는 맞춤 제작 칸에 둔다', () => {
    expect(
      createFailure(
        validation(
          field('PRODUCT_EDIT_106', 'customOrder.leadTimeDays'),
          field('PRODUCT_EDIT_109', 'customOrder.fields[1].label'),
        ),
      ),
    ).toEqual({
      kind: 'errors',
      errors: {
        'customOrder.leadTimeDays': [LEAD_TIME_INVALID],
        'customOrder.fields.1.label': [LABEL_INVALID],
      },
      reloadCategories: false,
      uncertain: false,
    });
    const failure = createFailure(
      business(400, 'PRODUCT_011', '맞춤 제작 입력 항목 설정이 올바르지 않습니다: 키가 겹칩니다'),
    );
    expect(failure).toMatchObject({
      errors: {
        'customOrder.fields': ['맞춤 제작 입력 항목 설정이 올바르지 않습니다: 키가 겹칩니다'],
      },
    });
    expect(createFailure(business(400, 'PRODUCT_011', ''))).toMatchObject({
      errors: { 'customOrder.fields': [CUSTOM_ORDER_INVALID] },
    });
  });

  it('PRODUCT_012 — 카테고리를 다시 읽고 아이디 원문을 보이지 않는다', () => {
    expect(
      createFailure(business(400, 'PRODUCT_012', '존재하지 않는 카테고리가 있습니다: 12, 13')),
    ).toEqual({
      kind: 'errors',
      errors: { categoryId: [CATEGORY_NOT_FOUND] },
      reloadCategories: true,
      uncertain: false,
    });
  });

  it.each([
    [
      'PRODUCT_013',
      400,
      '옵션 구성이 올바르지 않습니다: 옵션 이름이 겹칩니다(사이즈)',
      'optionGroups',
    ],
    ['PRODUCT_014', 400, '같은 옵션 조합의 SKU 가 두 번 있습니다: S', 'skus'],
    ['PRODUCT_016', 400, '가격 설정이 올바르지 않습니다: 정가는 판매가보다 높아야 합니다', 'form'],
  ])('%s 는 서버의 한국어 사유를 %s 칸에 그대로 보인다', (code, status, message, key) => {
    expect(createFailure(business(status, code, message))).toEqual({
      kind: 'errors',
      errors: { [key]: [message] },
      reloadCategories: false,
      uncertain: false,
    });
  });

  it('업무 문구가 비면 기본 문구 · 045 는 잠시 후 다시 · 칸 모르는 100 은 폼 전체', () => {
    const blank = new ApiError('server', 400, 'PRODUCT_013', ' ');
    expect(createFailure(blank)).toMatchObject({ errors: { optionGroups: [OPTION_INVALID] } });
    expect(createFailure(new ApiError('server', 400, 'PRODUCT_016', ''))).toMatchObject({
      errors: { form: [PRICE_RULE] },
    });
    expect(createFailure(business(409, 'PRODUCT_045'))).toMatchObject({
      errors: { form: [BUSY] },
      uncertain: false,
    });
    expect(createFailure(business(400, 'PRODUCT_100'))).toMatchObject({
      errors: { form: [CHECK_FIELDS] },
    });
  });

  it('429 · 그 밖의 4xx 는 서버 문구, 코드 없는 실패는 저장 실패 문구', () => {
    const limited = new ApiError('rateLimited', 429, 'GATEWAY_006', null, [], 3);
    expect(createFailure(limited)).toMatchObject({
      errors: { form: [limited.message] },
      uncertain: false,
    });
    expect(createFailure(business(409, 'PRODUCT_777', '다른 이유'))).toMatchObject({
      errors: { form: ['다른 이유'] },
    });
    expect(createFailure(new ApiError('server', 418, null, null))).toMatchObject({
      errors: { form: [SAVE_FAILED] },
    });
  });
});

describe('errorSummary', () => {
  it('폼 전체 문구와, 어느 칸이 왜 틀렸는지 한 줄씩 보인다', () => {
    expect(errorSummary({})).toEqual([]);
    expect(errorSummary({ form: ['a'], name: ['b'], 'skus.0.hsCode': ['c'], skus: [] })).toEqual([
      'a',
      CHECK_FIELDS,
      '상품명 — b',
      'SKU — c',
    ]);
  });

  it('카테고리 미선택도 이름으로 알리고, 같은 칸은 한 줄 · 모르는 칸은 수로 센다', () => {
    expect(
      errorSummary({
        categoryId: ['대표 카테고리를 골라 주세요.'],
        'optionGroups.0.name': ['x'],
        'optionGroups.1.name': ['y'],
        mystery: ['z'],
      }),
    ).toEqual([
      CHECK_FIELDS,
      '카테고리 — 대표 카테고리를 골라 주세요.',
      '옵션 — x',
      '그 밖의 칸 1곳',
    ]);
  });
});

describe('createFailure — 이미지 첨부 (BE #9 F2)', () => {
  it('대체 텍스트는 그 칸, 그 밖의 images 검증은 이미지 칸 전체다', () => {
    expect(createFieldKey('images[2].altText')).toBe('images.2.altText');
    expect(createFieldKey('images[0].uploadKey')).toBe('images');
    expect(createFieldKey('images')).toBe('images');
    expect(
      createFailure(
        validation(field('PRODUCT_019', 'images[1].altText'), field('PRODUCT_019', 'images')),
      ),
    ).toEqual({
      kind: 'errors',
      errors: { 'images.1.altText': [ALT_TEXT_INVALID], images: [IMAGES_INVALID] },
      reloadCategories: false,
      uncertain: false,
    });
  });

  it.each([
    [400, 'PRODUCT_018', { images: [IMAGE_CONFIGURATION] }],
    [400, 'PRODUCT_032', { images: [IMAGE_UPLOAD_GONE] }],
    [400, 'PRODUCT_033', { images: [IMAGE_FORMAT_NOT_ALLOWED] }],
    [503, 'PRODUCT_035', { form: [IMAGE_STORAGE_FAILED] }],
    [503, 'PRODUCT_036', { form: [UPLOAD_DISABLED] }],
  ])('%i %s — 저장 전에 난 오류라 등록되지 않았다(uncertain 아님)', (status, code, errors) => {
    const cause = new ApiError(
      status === 503 ? 'unavailable' : 'server',
      status,
      code,
      '서버 문구',
    );
    expect(createFailure(cause)).toEqual({
      kind: 'errors',
      errors,
      reloadCategories: false,
      uncertain: false,
    });
  });

  it('코드 없는 503 은 여전히 결과 모름이다', () => {
    const failure = createFailure(new ApiError('unavailable', 503, null, null));
    expect(failure).toMatchObject({ uncertain: true });
  });
});
