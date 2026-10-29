'use client';

import { Button, FieldError, TextField } from '@cf/ui';
import { useEffect } from 'react';
import { onboardingTasks } from '@/lib/onboarding/tasks-store';
import {
  domesticForm,
  domesticRequest,
  emptyRemoteArea,
  type DomesticForm,
  type RemoteAreaRow,
} from '@/lib/shipping/policy-form';
import { DOMESTIC_POLICY_PATH } from '@/lib/shipping/types';
import { usePolicyEditor } from '@/lib/shipping/use-policy-editor';
import { DomesticPolicyHistory } from './DomesticPolicyHistory';
import { CurrencyField, errorsOf, MoneyField, PolicyEditorFrame, Section } from './PolicyParts';

/**
 * 국내 배송비 정책 편집(#187) — `GET/PUT /api/v1/admin/shipping-policies/domestic`.
 * 기본 배송비 · 무료배송 기준(선택) · 도서산간 우편번호 구간별 추가 배송비. 실제 배송비는 서버 견적이 계산한다.
 * 저장하면 시작 체크리스트(#358)의 업무 상태를 서버에서 다시 읽게 한다.
 * 폼 아래에 변경 이력(#319)을 둔다 — 저장 뒤 서버 값이 바뀌었을 수 있으면(`revision`) 첫 페이지부터 다시 읽는다.
 */
export function DomesticPolicyEditor() {
  const editor = usePolicyEditor({
    path: DOMESTIC_POLICY_PATH,
    toForm: domesticForm,
    toRequest: domesticRequest,
  });
  const { revision } = editor;

  // 저장 뒤 서버 값이 바뀌었을 수 있다 — 시작 체크리스트 업무 상태를 서버에서 다시 읽는다(#358). 화면에서 완료로 바꾸지 않는다
  useEffect(() => {
    if (revision > 0) onboardingTasks.invalidate();
  }, [revision]);

  return (
    <PolicyEditorFrame
      title="국내 배송비 정책"
      editor={editor}
      after={<DomesticPolicyHistory key={editor.revision} />}
    >
      {(form) => {
        const { errors } = editor;
        const set = (patch: Partial<DomesticForm>, rowsChanged = false) =>
          editor.update({ ...form, ...patch }, { rowsChanged });
        const setArea = (index: number, patch: Partial<RemoteAreaRow>) =>
          set({
            remoteAreas: form.remoteAreas.map((area, i) =>
              i === index ? { ...area, ...patch } : area,
            ),
          });

        return (
          <>
            <Section title="기본 배송비" tour="shipping-base-fee">
              <CurrencyField
                currency={form.currency}
                editable={form.version === null}
                errors={errorsOf(errors, 'currency')}
                onChange={(currency) => set({ currency })}
              />
              <div className="grid gap-4 sm:grid-cols-2">
                <MoneyField
                  currency={form.currency}
                  label="기본 배송비"
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
            </Section>

            <Section
              title="도서산간 추가 배송비"
              tour="shipping-remote-areas"
              description="우편번호(5자리) 구간별로 더 받는 배송비입니다. 무료배송이어도 붙습니다. 구간은 겹칠 수 없습니다."
            >
              {form.remoteAreas.length === 0 && (
                <p className="text-sm text-muted-fg">등록한 구간이 없습니다.</p>
              )}
              <ul className="flex flex-col gap-3">
                {form.remoteAreas.map((area, index) => {
                  const key = `remoteAreas.${index}`;
                  return (
                    <li
                      key={area.key}
                      className="grid gap-3 rounded-md bg-bg p-4 sm:grid-cols-2 xl:grid-cols-[1.4fr_1fr_1fr_1fr_auto] xl:items-start"
                    >
                      <TextField
                        label="구간 이름"
                        value={area.name}
                        maxLength={50}
                        onChange={(event) => setArea(index, { name: event.currentTarget.value })}
                        errors={errorsOf(errors, `${key}.name`)}
                      />
                      <TextField
                        label="우편번호 시작"
                        inputMode="numeric"
                        maxLength={5}
                        value={area.postalCodeFrom}
                        onChange={(event) =>
                          setArea(index, { postalCodeFrom: event.currentTarget.value })
                        }
                        errors={errorsOf(errors, `${key}.postalCodeFrom`)}
                      />
                      <TextField
                        label="우편번호 끝"
                        inputMode="numeric"
                        maxLength={5}
                        value={area.postalCodeTo}
                        onChange={(event) =>
                          setArea(index, { postalCodeTo: event.currentTarget.value })
                        }
                        errors={errorsOf(errors, `${key}.postalCodeTo`)}
                      />
                      <MoneyField
                        currency={form.currency}
                        label="추가 배송비"
                        value={area.surcharge}
                        onChange={(event) =>
                          setArea(index, { surcharge: event.currentTarget.value })
                        }
                        errors={errorsOf(errors, `${key}.surcharge`)}
                      />
                      <Button
                        variant="secondary"
                        className="self-start xl:mt-6"
                        aria-label={`${area.name || `${index + 1}번째`} 구간 삭제`}
                        onClick={() =>
                          set({ remoteAreas: form.remoteAreas.filter((_, i) => i !== index) }, true)
                        }
                      >
                        삭제
                      </Button>
                    </li>
                  );
                })}
              </ul>
              <FieldError messages={errorsOf(errors, 'remoteAreas')} />
              <Button
                variant="secondary"
                className="self-start"
                onClick={() => set({ remoteAreas: [...form.remoteAreas, emptyRemoteArea()] }, true)}
              >
                구간 추가
              </Button>
            </Section>
          </>
        );
      }}
    </PolicyEditorFrame>
  );
}
