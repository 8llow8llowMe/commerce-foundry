'use client';

import { ApiError, apiFetch } from '@cf/api-client';
import { Button } from '@cf/ui';
import { useCallback, useEffect, useMemo, useState } from 'react';
import { useDictionary, useLocale } from '@/components/i18n/LocaleProvider';
import {
  accountErrorText,
  DOMESTIC_COUNTRY,
  addressErrors,
  draftFrom,
  emptyDraft,
  formatPhone,
  isStaleAddress,
  isUnknownOutcome,
  toAddressBody,
  type AddressDraft,
  type AddressErrors,
} from '@/lib/account/address-form';
import { countryOptions, defaultCountry, type CountryOption } from '@/lib/account/countries';
import type { ShippingAddress, ShippingAddressList } from '@/lib/account/types';
import { AddressForm } from './AddressForm';
import { SignedOutNotice } from './SignedOutNotice';

const ADDRESSES_PATH = '/api/v1/members/me/shipping-addresses';
const RETURN_PATH = '/mypage/addresses';

type Load = { status: 'loading' } | { status: 'failed' } | { status: 'signedOut' };
type Editing = { kind: 'add' } | { kind: 'edit'; address: ShippingAddress } | null;

/**
 * 배송지 관리(#28 · BE #7). 목록 · 추가 · 수정 · 삭제 · 기본 지정을 한 화면에서 한다. 쓰기가 끝나면 목록을 다시 읽는다 —
 * 기본 배송지 승격(기본을 지우면 최근 주소가 기본) 같은 서버 규칙을 화면에서 흉내 내지 않는다.
 * 다른 요청이 먼저 바꿨거나(409 `AUTH_022`) 이미 없는 주소(404 `AUTH_020`)면 목록을 다시 읽고 알린다. 401 이면 다시 로그인을 안내한다.
 */
export function AddressBook({ shippableCountries }: { shippableCountries: readonly string[] }) {
  const dict = useDictionary().account;
  const t = dict.address;
  const locale = useLocale();
  const [list, setList] = useState<ShippingAddressList | Load>({ status: 'loading' });
  const [editing, setEditing] = useState<Editing>(null);
  const [removing, setRemoving] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [notice, setNotice] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = useCallback(
    () =>
      apiFetch<ShippingAddressList>(ADDRESSES_PATH).then(setList, (cause: unknown) => {
        setList({
          status:
            cause instanceof ApiError && cause.kind === 'unauthorized' ? 'signedOut' : 'failed',
        });
      }),
    [],
  );

  useEffect(() => {
    void load();
  }, [load]);

  // 정책에서 빠진 국가의 옛 주소도 고칠 수 있게 저장된 주소의 국가를 더한다
  const countries = useMemo(() => {
    const saved = 'addresses' in list ? list.addresses.map((address) => address.countryCode) : [];
    return countryOptions([...shippableCountries, ...saved], locale);
  }, [shippableCountries, list, locale]);

  if (!('addresses' in list)) {
    if (list.status === 'signedOut') return <SignedOutNotice returnPath={RETURN_PATH} />;
    if (list.status === 'failed') {
      return (
        <div className="flex flex-col items-center gap-4 py-16 text-center">
          <p role="alert" className="text-sm text-muted-fg">
            {dict.loadFailed}
          </p>
          <Button
            variant="secondary"
            onClick={() => {
              setList({ status: 'loading' });
              void load();
            }}
          >
            {dict.reload}
          </Button>
        </div>
      );
    }
    return (
      <p role="status" className="py-16 text-center text-sm text-muted-fg">
        {dict.loading}
      </p>
    );
  }

  /** 쓰기 실패 공통 처리. 처리했으면 true — 칸 오류처럼 폼에 남길 것은 false */
  const handled = async (cause: unknown): Promise<boolean> => {
    if (cause instanceof ApiError && cause.kind === 'unauthorized') {
      setList({ status: 'signedOut' });
      return true;
    }
    if (isStaleAddress(cause)) {
      setEditing(null);
      setRemoving(null);
      await load();
      setNotice(t.stale);
      return true;
    }
    // 결과를 모른다(502 · 504 · 연결 끊김) — 서버에 반영됐을 수 있다. 다시 저장하면 주소가 하나 더 생기므로(멱등 키 없음) 목록을 읽어 보인다
    if (isUnknownOutcome(cause)) {
      setEditing(null);
      setRemoving(null);
      await load();
      setNotice(t.unknownOutcome);
      return true;
    }
    return false;
  };

  const save = async (draft: AddressDraft): Promise<AddressErrors | null> => {
    const target = editing;
    if (!target) return null;
    try {
      if (target.kind === 'add') {
        await apiFetch(ADDRESSES_PATH, { method: 'POST', json: toAddressBody(draft, true) });
      } else {
        await apiFetch(`${ADDRESSES_PATH}/${target.address.addressId}`, {
          method: 'PUT',
          json: toAddressBody(draft, false),
        });
      }
    } catch (cause) {
      return (await handled(cause)) ? null : addressErrors(cause, dict);
    }
    setEditing(null);
    await load();
    setNotice(target.kind === 'add' ? t.added : t.updated);
    return null;
  };

  const act = async (run: () => Promise<unknown>, done: string) => {
    if (busy) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await run();
      setRemoving(null);
      await load();
      setNotice(done);
    } catch (cause) {
      if (!(await handled(cause))) setError(accountErrorText(cause, dict, t.actionFailed));
    } finally {
      setBusy(false);
    }
  };

  const startEditing = (next: Editing) => {
    setEditing(next);
    setRemoving(null);
    setNotice(null);
    setError(null);
  };

  if (editing) {
    return (
      <AddressForm
        key={editing.kind === 'edit' ? editing.address.addressId : 'new'}
        title={editing.kind === 'add' ? t.addTitle : t.editTitle}
        initial={
          editing.kind === 'add'
            ? emptyDraft(defaultCountry(countries, locale))
            : draftFrom(editing.address)
        }
        countries={countries}
        showDefault={editing.kind === 'add'}
        firstAddress={list.addresses.length === 0}
        onSave={save}
        onCancel={() => setEditing(null)}
      />
    );
  }

  const full = list.addresses.length >= list.maxCount;

  return (
    <div className="flex flex-col gap-6">
      <div className="flex items-center justify-between gap-4">
        <p className="text-sm text-muted-fg">
          {t.count
            .replace('{count}', String(list.addresses.length))
            .replace('{max}', String(list.maxCount))}
        </p>
        <Button onClick={() => startEditing({ kind: 'add' })} disabled={full || busy}>
          {t.add}
        </Button>
      </div>
      {full && (
        <p className="text-sm text-muted-fg">
          {t.limitReached.replace('{max}', String(list.maxCount))}
        </p>
      )}
      {notice && (
        <p role="status" className="text-sm text-muted-fg">
          {notice}
        </p>
      )}
      {error && (
        <p role="alert" className="text-sm text-danger">
          {error}
        </p>
      )}

      {list.addresses.length === 0 ? (
        <p className="py-12 text-center text-sm text-muted-fg">{t.empty}</p>
      ) : (
        <ul className="flex flex-col gap-2">
          {list.addresses.map((address) => (
            <AddressItem
              key={address.addressId}
              address={address}
              countries={countries}
              busy={busy}
              confirming={removing === address.addressId}
              onEdit={() => startEditing({ kind: 'edit', address })}
              onAskRemove={() => setRemoving(address.addressId)}
              onCancelRemove={() => setRemoving(null)}
              onRemove={() =>
                void act(
                  () => apiFetch(`${ADDRESSES_PATH}/${address.addressId}`, { method: 'DELETE' }),
                  t.removed,
                )
              }
              onSetDefault={() =>
                void act(
                  () =>
                    apiFetch(`${ADDRESSES_PATH}/${address.addressId}/default`, { method: 'PUT' }),
                  t.defaultChanged,
                )
              }
            />
          ))}
        </ul>
      )}
    </div>
  );
}

function AddressItem({
  address,
  countries,
  busy,
  confirming,
  onEdit,
  onAskRemove,
  onCancelRemove,
  onRemove,
  onSetDefault,
}: {
  address: ShippingAddress;
  countries: readonly CountryOption[];
  busy: boolean;
  confirming: boolean;
  onEdit: () => void;
  onAskRemove: () => void;
  onCancelRemove: () => void;
  onRemove: () => void;
  onSetDefault: () => void;
}) {
  const t = useDictionary().account.address;
  const name = address.label ?? address.recipientName;
  const country = countries.find((option) => option.code === address.countryCode)?.name;
  const region = [address.city, address.stateProvince].filter(Boolean).join(', ');
  const action = 'text-sm underline-offset-4 hover:underline disabled:opacity-50';

  return (
    <li className="flex flex-col gap-3 bg-muted px-5 py-5">
      <div className="flex flex-wrap items-center gap-2">
        <span className="font-medium">{name}</span>
        {address.isDefault && (
          <span className="bg-accent px-2 py-0.5 text-xs text-accent-fg">{t.default}</span>
        )}
      </div>
      <div className="flex flex-col gap-0.5 text-sm text-muted-fg">
        {address.label && <span>{address.recipientName}</span>}
        <span>{formatPhone(address.recipientPhone, address.countryCode)}</span>
        <span>
          {address.postalCode && `[${address.postalCode}] `}
          {address.addressLine1}
          {address.addressLine2 && ` ${address.addressLine2}`}
        </span>
        {address.countryCode !== DOMESTIC_COUNTRY && (
          <span>{[region, country ?? address.countryCode].filter(Boolean).join(', ')}</span>
        )}
      </div>
      {confirming ? (
        <div className="flex flex-wrap items-center gap-4">
          <span role="alert" className="text-sm">
            {t.confirmRemove}
          </span>
          <button
            type="button"
            onClick={onRemove}
            disabled={busy}
            className={`${action} text-danger`}
          >
            {t.confirm}
          </button>
          <button type="button" onClick={onCancelRemove} disabled={busy} className={action}>
            {t.cancel}
          </button>
        </div>
      ) : (
        <div className="flex flex-wrap gap-4">
          <button
            type="button"
            onClick={onEdit}
            disabled={busy}
            aria-label={t.editItem.replace('{name}', name)}
            className={action}
          >
            {t.edit}
          </button>
          <button
            type="button"
            onClick={onAskRemove}
            disabled={busy}
            aria-label={t.removeItem.replace('{name}', name)}
            className={action}
          >
            {t.remove}
          </button>
          {!address.isDefault && (
            <button
              type="button"
              onClick={onSetDefault}
              disabled={busy}
              aria-label={t.setDefaultItem.replace('{name}', name)}
              className={action}
            >
              {t.setDefault}
            </button>
          )}
        </div>
      )}
    </li>
  );
}
