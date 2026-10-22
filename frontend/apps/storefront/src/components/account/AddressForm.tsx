'use client';

import { Button, Checkbox, FieldError, TextField, cx } from '@cf/ui';
import { useEffect, useId, useState, type FormEvent } from 'react';
import { useDictionary } from '@/components/i18n/LocaleProvider';
import {
  DOMESTIC_COUNTRY,
  NO_ADDRESS_ERRORS,
  requiredFields,
  type AddressDraft,
  type AddressErrors,
} from '@/lib/account/address-form';
import type { CountryOption } from '@/lib/account/countries';
import { openPostcode, preloadPostcode } from '@/lib/account/postcode';

/**
 * 배송지 추가 · 수정 폼. 저장(`onSave`)은 호출자가 하고, 칸 오류를 돌려주면 그 자리에 붙인다(null 이면 끝났다 — 호출자가 폼을 닫는다).
 * 국내는 우편번호가 필수이고 주소 검색(카카오 우편번호)으로 채울 수 있다. 해외는 도시가 필수다. 형식 검증은 BE 가 정본이다.
 */
export function AddressForm({
  title,
  initial,
  countries,
  showDefault,
  firstAddress,
  onSave,
  onCancel,
  submitLabel,
  cancelable = true,
}: {
  title: string;
  initial: AddressDraft;
  countries: readonly CountryOption[];
  /** 추가에서만 — 수정(PUT)은 기본 여부를 바꾸지 않는다 */
  showDefault: boolean;
  /** 첫 주소는 값과 무관하게 기본이 된다 */
  firstAddress: boolean;
  onSave: (draft: AddressDraft) => Promise<AddressErrors | null>;
  onCancel: () => void;
  /** 저장 버튼 문구 — 주문서처럼 저장하지 않고 쓰기만 할 때 바꾼다. 기본은 "저장" */
  submitLabel?: string;
  /** false 면 취소 버튼을 두지 않는다 — 돌아갈 곳이 없을 때(주문서에 저장된 배송지가 없음) */
  cancelable?: boolean;
}) {
  const t = useDictionary().account.address;
  const [draft, setDraft] = useState(initial);
  const [errors, setErrors] = useState<AddressErrors>(NO_ADDRESS_ERRORS);
  const [saving, setSaving] = useState(false);
  const [searchFailed, setSearchFailed] = useState(false);
  const countryId = useId();
  const countryErrorId = `${countryId}-error`;
  const phoneNoteId = useId();
  const domestic = draft.countryCode === DOMESTIC_COUNTRY;
  const required = requiredFields(draft.countryCode);
  const countryInvalid = errors.countryCode.length > 0;
  const showPhoneNote = errors.recipientPhone.length === 0 && !domestic;

  useEffect(() => {
    if (domestic) preloadPostcode();
  }, [domestic]);

  const update = (patch: Partial<AddressDraft>) =>
    setDraft((current) => ({ ...current, ...patch }));
  const text = (field: keyof Omit<AddressDraft, 'isDefault'>) => ({
    name: field,
    value: draft[field],
    onChange: (event: { currentTarget: HTMLInputElement }) =>
      update({ [field]: event.currentTarget.value }),
    errors: errors[field],
    required: required.has(field),
  });

  const onSubmit = async (event: FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    if (saving) return;
    setSaving(true);
    setErrors(NO_ADDRESS_ERRORS);
    const result = await onSave(draft);
    // 성공하면 호출자가 이 폼을 내린다
    if (result) {
      setErrors(result);
      setSaving(false);
    }
  };

  const search = () => {
    setSearchFailed(false);
    openPostcode(({ postalCode, address }) => update({ postalCode, addressLine1: address })).catch(
      () => setSearchFailed(true),
    );
  };

  return (
    <form onSubmit={onSubmit} className="flex flex-col gap-5" aria-label={title}>
      <h2 className="text-lg">{title}</h2>
      <TextField
        label={t.label}
        placeholder={t.labelPlaceholder}
        maxLength={30}
        {...text('label')}
      />
      <TextField
        label={t.recipientName}
        autoComplete="name"
        maxLength={50}
        {...text('recipientName')}
      />
      <div className="flex flex-col gap-1">
        <TextField
          label={t.recipientPhone}
          type="tel"
          autoComplete="tel"
          inputMode="tel"
          maxLength={20}
          // 안내는 오류가 없을 때만 보인다 — 오류가 있으면 TextField 가 오류 문구를 연결한다
          aria-describedby={showPhoneNote ? phoneNoteId : undefined}
          {...text('recipientPhone')}
        />
        {showPhoneNote && (
          <p id={phoneNoteId} className="text-sm text-muted-fg">
            {t.recipientPhoneNote}
          </p>
        )}
      </div>

      <div className="flex flex-col gap-1">
        <label htmlFor={countryId} className="text-sm font-medium text-fg">
          {t.country}
        </label>
        <select
          id={countryId}
          name="countryCode"
          autoComplete="country"
          value={draft.countryCode}
          onChange={(event) => update({ countryCode: event.currentTarget.value })}
          aria-invalid={countryInvalid || undefined}
          aria-describedby={countryInvalid ? countryErrorId : undefined}
          className={cx(
            'h-10 rounded-md border bg-bg px-3 text-fg outline-none focus:ring-2 focus:ring-primary',
            countryInvalid ? 'border-danger' : 'border-border',
          )}
        >
          {countries.map((country) => (
            <option key={country.code} value={country.code}>
              {country.name}
            </option>
          ))}
        </select>
        <FieldError id={countryErrorId} messages={errors.countryCode} />
      </div>

      <div className="flex items-end gap-2">
        <TextField
          label={domestic ? t.postalCode : t.postalCodeOptional}
          autoComplete="postal-code"
          inputMode={domestic ? 'numeric' : undefined}
          maxLength={domestic ? 5 : 20}
          className="flex-1"
          {...text('postalCode')}
        />
        {domestic && (
          // 오류 문구가 붙어도 입력 칸 줄에 맞도록 버튼은 위쪽 정렬을 따로 두지 않는다
          <Button
            variant="secondary"
            onClick={search}
            className={cx('h-10 shrink-0 px-4', errors.postalCode.length > 0 && 'mb-7')}
          >
            {t.search}
          </Button>
        )}
      </div>
      {searchFailed && (
        <p role="alert" className="-mt-3 text-sm text-danger">
          {t.searchFailed}
        </p>
      )}
      <TextField
        label={t.addressLine1}
        autoComplete="address-line1"
        maxLength={200}
        {...text('addressLine1')}
      />
      <TextField
        label={t.addressLine2}
        autoComplete="address-line2"
        maxLength={200}
        {...text('addressLine2')}
      />
      {!domestic && (
        <>
          <TextField
            label={t.city}
            autoComplete="address-level2"
            maxLength={100}
            {...text('city')}
          />
          <TextField
            label={t.stateProvince}
            autoComplete="address-level1"
            maxLength={100}
            {...text('stateProvince')}
          />
        </>
      )}

      {showDefault &&
        (firstAddress ? (
          <p className="text-sm text-muted-fg">{t.firstIsDefault}</p>
        ) : (
          <Checkbox
            label={t.makeDefault}
            checked={draft.isDefault}
            onChange={(event) => update({ isDefault: event.currentTarget.checked })}
          />
        ))}

      <FieldError messages={errors.form} />
      <div className="flex gap-2">
        <Button type="submit" disabled={saving} className="h-12 flex-1 tracking-[0.15em]">
          {saving ? t.saving : (submitLabel ?? t.save)}
        </Button>
        {cancelable && (
          <Button variant="secondary" onClick={onCancel} disabled={saving} className="h-12 px-6">
            {t.cancel}
          </Button>
        )}
      </div>
    </form>
  );
}
