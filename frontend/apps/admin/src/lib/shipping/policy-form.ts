import { moneyToDecimalString, parseMoneyInput, type Currency, type Money } from '@cf/api-client';
import type {
  DomesticPolicy,
  DomesticPolicyRequest,
  InternationalPolicy,
  InternationalPolicyRequest,
  MoneyRequest,
} from './types';

/**
 * 배송비 정책 폼 상태 ↔ API 값 변환. 입력칸은 글자로 들고, 보낼 때 최소 단위 정수로 바꾼다(표기 변환만, 계산하지 않는다).
 * 정책 안의 금액은 모두 한 통화다(다르면 DELIVERY_003) — 그래서 통화를 폼에 하나만 둔다. 저장된 정책의 통화는 바꿀 수 없다(DELIVERY_012).
 * 형식 검증 정본은 서버다. 화면은 서버에 보낼 수 없는 글자(금액 · 무게 형식)만 막는다.
 */

/** 칸 이름 → 오류 문구. 키는 `remoteAreas.0.postalCodeFrom` 처럼 점으로 잇는다(`fieldKey`), 폼 전체는 `form` */
export type FormErrors = Record<string, string[]>;

export const MONEY_FORMAT_ERROR = '금액 형식이 올바르지 않습니다.';
export const WEIGHT_FORMAT_ERROR = '무게는 g 단위 정수로 입력해 주세요.';

let nextRowKey = 0;
/** 줄 추가 · 삭제 때 React key 로 쓴다. 서버 id 가 없는 새 줄도 있어 따로 둔다 */
export function rowKey(): string {
  nextRowKey += 1;
  return `row-${nextRowKey}`;
}

export type RemoteAreaRow = {
  key: string;
  name: string;
  postalCodeFrom: string;
  postalCodeTo: string;
  surcharge: string;
};

export type DomesticForm = {
  currency: Currency;
  baseFee: string;
  /** 빈 값이면 무료배송 기준 없음 */
  freeShippingThreshold: string;
  remoteAreas: RemoteAreaRow[];
  /** null 이면 아직 정책이 없다(처음 만들기) */
  version: number | null;
  /** 마지막 저장 시각(표시용). 처음 만들기면 null */
  updatedAt: string | null;
};

export type RateRow = { key: string; maxWeightGrams: string; fee: string };

export type ZoneRow = {
  key: string;
  name: string;
  /** 쉼표 · 공백으로 나눈 국가 코드(ISO 3166-1 alpha-2) */
  countryCodes: string;
  rates: RateRow[];
};

export type InternationalForm = {
  currency: Currency;
  baseFee: string;
  freeShippingThreshold: string;
  customsNotice: string;
  zones: ZoneRow[];
  version: number | null;
  updatedAt: string | null;
};

const moneyText = (money: Money | null): string => (money ? moneyToDecimalString(money) : '');

export function emptyRemoteArea(): RemoteAreaRow {
  return { key: rowKey(), name: '', postalCodeFrom: '', postalCodeTo: '', surcharge: '' };
}

export function emptyRate(): RateRow {
  return { key: rowKey(), maxWeightGrams: '', fee: '' };
}

export function emptyZone(): ZoneRow {
  return { key: rowKey(), name: '', countryCodes: '', rates: [emptyRate()] };
}

export function domesticForm(policy: DomesticPolicy | null): DomesticForm {
  if (!policy) {
    return {
      currency: 'KRW',
      baseFee: '',
      freeShippingThreshold: '',
      remoteAreas: [],
      version: null,
      updatedAt: null,
    };
  }
  return {
    currency: policy.baseFee.currency,
    baseFee: moneyText(policy.baseFee),
    freeShippingThreshold: moneyText(policy.freeShippingThreshold),
    remoteAreas: policy.remoteAreas.map((area) => ({
      key: rowKey(),
      name: area.name,
      postalCodeFrom: area.postalCodeFrom,
      postalCodeTo: area.postalCodeTo,
      surcharge: moneyText(area.surcharge),
    })),
    version: policy.version,
    updatedAt: policy.updatedAt,
  };
}

export function internationalForm(policy: InternationalPolicy | null): InternationalForm {
  if (!policy) {
    return {
      currency: 'KRW',
      baseFee: '',
      freeShippingThreshold: '',
      customsNotice: '',
      zones: [emptyZone()],
      version: null,
      updatedAt: null,
    };
  }
  return {
    currency: policy.baseFee.currency,
    baseFee: moneyText(policy.baseFee),
    freeShippingThreshold: moneyText(policy.freeShippingThreshold),
    customsNotice: policy.customsNotice ?? '',
    zones: policy.zones.map((zone) => ({
      key: rowKey(),
      name: zone.name,
      countryCodes: zone.countryCodes.join(', '),
      rates: zone.rates.map((rate) => ({
        key: rowKey(),
        maxWeightGrams: String(rate.maxWeightGrams),
        fee: moneyText(rate.fee),
      })),
    })),
    version: policy.version,
    updatedAt: policy.updatedAt,
  };
}

export type BuildResult<T> = { ok: true; request: T } | { ok: false; errors: FormErrors };

/** 글자 → 요청 금액. 형식이 틀리면 오류를 쌓고 amount null 로 둔다(보내지 않는다) */
function moneyCollector(currency: Currency, errors: FormErrors) {
  return (key: string, text: string): MoneyRequest => {
    const amount = parseMoneyInput(text, currency);
    if (Number.isNaN(amount)) {
      errors[key] = [MONEY_FORMAT_ERROR];
      return { amount: null, currency };
    }
    return { amount, currency };
  };
}

export function domesticRequest(form: DomesticForm): BuildResult<DomesticPolicyRequest> {
  const errors: FormErrors = {};
  const money = moneyCollector(form.currency, errors);
  const request: DomesticPolicyRequest = {
    baseFee: money('baseFee', form.baseFee),
    freeShippingThreshold:
      form.freeShippingThreshold.trim() === ''
        ? null
        : money('freeShippingThreshold', form.freeShippingThreshold),
    remoteAreas: form.remoteAreas.map((area, index) => ({
      name: area.name.trim(),
      postalCodeFrom: area.postalCodeFrom.trim(),
      postalCodeTo: area.postalCodeTo.trim(),
      surcharge: money(`remoteAreas.${index}.surcharge`, area.surcharge),
    })),
    version: form.version,
  };
  return Object.keys(errors).length > 0 ? { ok: false, errors } : { ok: true, request };
}

/** 국가 코드 글자를 목록으로 — 대문자로 맞추고 빈 칸은 버린다. 형식(두 글자)은 서버가 본다(DELIVERY_106) */
export function splitCountryCodes(text: string): string[] {
  return text
    .split(/[\s,]+/)
    .map((code) => code.trim().toUpperCase())
    .filter((code) => code !== '');
}

export function internationalRequest(
  form: InternationalForm,
): BuildResult<InternationalPolicyRequest> {
  const errors: FormErrors = {};
  const money = moneyCollector(form.currency, errors);
  const request: InternationalPolicyRequest = {
    baseFee: money('baseFee', form.baseFee),
    freeShippingThreshold:
      form.freeShippingThreshold.trim() === ''
        ? null
        : money('freeShippingThreshold', form.freeShippingThreshold),
    customsNotice: form.customsNotice.trim() === '' ? null : form.customsNotice.trim(),
    zones: form.zones.map((zone, zoneIndex) => ({
      name: zone.name.trim(),
      countryCodes: splitCountryCodes(zone.countryCodes),
      rates: zone.rates.map((rate, rateIndex) => {
        const key = `zones.${zoneIndex}.rates.${rateIndex}`;
        const weight = rate.maxWeightGrams.replace(/,/g, '').trim();
        let maxWeightGrams: number | null = null;
        if (weight !== '') {
          if (/^\d+$/.test(weight)) maxWeightGrams = Number(weight);
          else errors[`${key}.maxWeightGrams`] = [WEIGHT_FORMAT_ERROR];
        }
        return { maxWeightGrams, fee: money(`${key}.fee`, rate.fee) };
      }),
    })),
    version: form.version,
  };
  return Object.keys(errors).length > 0 ? { ok: false, errors } : { ok: true, request };
}
