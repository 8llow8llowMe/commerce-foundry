'use client';

import { Button, FieldError, TextField } from '@cf/ui';
import { useId } from 'react';
import {
  emptyRate,
  emptyZone,
  internationalForm,
  internationalRequest,
  type InternationalForm,
  type RateRow,
  type ZoneRow,
} from '@/lib/shipping/policy-form';
import { INTERNATIONAL_POLICY_PATH } from '@/lib/shipping/types';
import { usePolicyEditor } from '@/lib/shipping/use-policy-editor';
import { CurrencyField, errorsOf, MoneyField, PolicyEditorFrame, Section } from './PolicyParts';

const CUSTOMS_NOTICE_MAX = 1000;

/**
 * 해외 배송비 정책 편집(#187) — `GET/PUT /api/v1/admin/shipping-policies/international`.
 * 기본료 · 무료배송 기준(선택) · 관세 안내 · 국가 그룹별 무게 구간 요금. 국내(KR)는 넣을 수 없다(DELIVERY_009).
 * 해외 배송을 켜고 끄는 것은 서버 기능 플래그다 — 이 화면은 정책 값만 고친다.
 */
export function InternationalPolicyEditor() {
  const noticeId = useId();
  const editor = usePolicyEditor({
    path: INTERNATIONAL_POLICY_PATH,
    toForm: internationalForm,
    toRequest: internationalRequest,
  });

  return (
    <PolicyEditorFrame title="해외 배송비 정책" editor={editor}>
      {(form) => {
        const { errors } = editor;
        const set = (patch: Partial<InternationalForm>, rowsChanged = false) =>
          editor.update({ ...form, ...patch }, { rowsChanged });
        const setZone = (index: number, patch: Partial<ZoneRow>, rowsChanged = false) =>
          set(
            { zones: form.zones.map((zone, i) => (i === index ? { ...zone, ...patch } : zone)) },
            rowsChanged,
          );
        const setRate = (zoneIndex: number, rateIndex: number, patch: Partial<RateRow>) =>
          setZone(zoneIndex, {
            rates: form.zones[zoneIndex]!.rates.map((rate, i) =>
              i === rateIndex ? { ...rate, ...patch } : rate,
            ),
          });

        return (
          <>
            <Section title="기본 요금">
              <CurrencyField
                currency={form.currency}
                editable={form.version === null}
                errors={errorsOf(errors, 'currency')}
                onChange={(currency) => set({ currency })}
              />
              <div className="grid gap-4 sm:grid-cols-2">
                <MoneyField
                  currency={form.currency}
                  label="기본료"
                  value={form.baseFee}
                  onChange={(event) => set({ baseFee: event.currentTarget.value })}
                  errors={errorsOf(errors, 'baseFee')}
                />
                <MoneyField
                  currency={form.currency}
                  label="무료배송 기준"
                  placeholder="비우면 무료배송 없음"
                  value={form.freeShippingThreshold}
                  onChange={(event) => set({ freeShippingThreshold: event.currentTarget.value })}
                  errors={errorsOf(errors, 'freeShippingThreshold')}
                />
              </div>
              <div className="flex flex-col gap-1">
                <label htmlFor={noticeId} className="text-sm font-medium">
                  관세 안내 (선택)
                </label>
                <textarea
                  id={noticeId}
                  aria-invalid={errorsOf(errors, 'customsNotice').length > 0 || undefined}
                  aria-describedby={`${noticeId}-hint ${noticeId}-error`}
                  rows={4}
                  maxLength={CUSTOMS_NOTICE_MAX}
                  value={form.customsNotice}
                  onChange={(event) => set({ customsNotice: event.currentTarget.value })}
                  className="rounded-md border border-border bg-bg px-3 py-2"
                />
                <p id={`${noticeId}-hint`} className="text-xs text-muted-fg">
                  영문 스토어 상품 상세에 그대로 보입니다. {form.customsNotice.length} /{' '}
                  {CUSTOMS_NOTICE_MAX}
                </p>
                <FieldError id={`${noticeId}-error`} messages={errorsOf(errors, 'customsNotice')} />
              </div>
            </Section>

            <Section
              title="국가 그룹"
              description="국가를 묶어 무게 구간별 요금을 정합니다. 한 국가는 한 그룹에만 넣을 수 있고, 한국(KR)은 넣을 수 없습니다."
            >
              <ul className="flex flex-col gap-4">
                {form.zones.map((zone, zoneIndex) => {
                  const key = `zones.${zoneIndex}`;
                  return (
                    <li key={zone.key} className="flex flex-col gap-4 rounded-md bg-bg p-4">
                      <div className="grid gap-3 sm:grid-cols-[1fr_2fr]">
                        <TextField
                          label="그룹 이름"
                          value={zone.name}
                          maxLength={50}
                          onChange={(event) =>
                            setZone(zoneIndex, { name: event.currentTarget.value })
                          }
                          errors={errorsOf(errors, `${key}.name`)}
                        />
                        <TextField
                          label="국가 코드 (쉼표로 구분, 예: US, CA)"
                          value={zone.countryCodes}
                          autoCapitalize="characters"
                          onChange={(event) =>
                            setZone(zoneIndex, { countryCodes: event.currentTarget.value })
                          }
                          errors={errorsOf(errors, `${key}.countryCodes`)}
                        />
                      </div>

                      <div className="flex flex-col gap-2">
                        <p className="text-sm font-medium">무게 구간별 요금</p>
                        <ul className="flex flex-col gap-2">
                          {zone.rates.map((rate, rateIndex) => {
                            const rateKey = `${key}.rates.${rateIndex}`;
                            return (
                              <li
                                key={rate.key}
                                className="grid gap-3 sm:grid-cols-[1fr_1fr_auto] sm:items-start"
                              >
                                <TextField
                                  label="최대 무게 (g, 이하)"
                                  inputMode="numeric"
                                  value={rate.maxWeightGrams}
                                  onChange={(event) =>
                                    setRate(zoneIndex, rateIndex, {
                                      maxWeightGrams: event.currentTarget.value,
                                    })
                                  }
                                  errors={errorsOf(errors, `${rateKey}.maxWeightGrams`)}
                                />
                                <MoneyField
                                  currency={form.currency}
                                  label="요금"
                                  value={rate.fee}
                                  onChange={(event) =>
                                    setRate(zoneIndex, rateIndex, {
                                      fee: event.currentTarget.value,
                                    })
                                  }
                                  errors={errorsOf(errors, `${rateKey}.fee`)}
                                />
                                <Button
                                  variant="secondary"
                                  className="sm:mt-6"
                                  disabled={zone.rates.length === 1}
                                  aria-label={`${rateIndex + 1}번째 무게 구간 삭제`}
                                  onClick={() =>
                                    setZone(
                                      zoneIndex,
                                      {
                                        rates: zone.rates.filter((_, i) => i !== rateIndex),
                                      },
                                      true,
                                    )
                                  }
                                >
                                  삭제
                                </Button>
                              </li>
                            );
                          })}
                        </ul>
                        <FieldError messages={errorsOf(errors, `${key}.rates`)} />
                        <Button
                          variant="secondary"
                          className="self-start"
                          onClick={() =>
                            setZone(zoneIndex, { rates: [...zone.rates, emptyRate()] }, true)
                          }
                        >
                          무게 구간 추가
                        </Button>
                      </div>

                      <Button
                        variant="secondary"
                        className="self-end"
                        disabled={form.zones.length === 1}
                        aria-label={`${zone.name || `${zoneIndex + 1}번째`} 그룹 삭제`}
                        onClick={() =>
                          set({ zones: form.zones.filter((_, i) => i !== zoneIndex) }, true)
                        }
                      >
                        그룹 삭제
                      </Button>
                    </li>
                  );
                })}
              </ul>
              <FieldError messages={errorsOf(errors, 'zones')} />
              <Button
                variant="secondary"
                className="self-start"
                onClick={() => set({ zones: [...form.zones, emptyZone()] }, true)}
              >
                그룹 추가
              </Button>
            </Section>
          </>
        );
      }}
    </PolicyEditorFrame>
  );
}
