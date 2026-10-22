'use client';

import { ApiError, apiFetch } from '@cf/api-client';
import { Button } from '@cf/ui';
import { useEffect, useMemo, useRef, useState } from 'react';
import { AddressDetails } from '@/components/account/AddressDetails';
import { AddressForm } from '@/components/account/AddressForm';
import { useDictionary, useFormatMoney, useLocale } from '@/components/i18n/LocaleProvider';
import { Link } from '@/components/i18n/Link';
import { emptyDraft, type AddressDraft } from '@/lib/account/address-form';
import { countryOptions, defaultCountry } from '@/lib/account/countries';
import { ADDRESSES_PATH, type ShippingAddressList } from '@/lib/account/types';
import { CART_PATH, type CartResponse } from '@/lib/cart/types';
import {
  orderLines,
  quoteDestination,
  quoteFailure,
  quoteRequest,
  QUOTES_PATH,
  type OrderQuote,
  type QuoteFailure,
  type QuoteRequest,
} from '@/lib/checkout/quote';
import { fill } from '@/lib/i18n/format';
import { useRetryCooldown } from '@/lib/use-retry-cooldown';
import { QuoteSummary } from './QuoteSummary';

const RETURN_PATH = '/checkout';
/** 배송지 선택 — 저장된 배송지 id 또는 이 주문에만 쓰는 새 배송지 */
const NEW_ADDRESS = 'new';
/** 포커스 대상(`data-focus`) */
const FOCUS_NEW_SUMMARY = 'new-address-summary';
const FOCUS_NEW_FORM = 'new-address-form';

type Loaded = { cart: CartResponse; addresses: ShippingAddressList };
type Quoted = { key: string; quote: OrderQuote } | { key: string; failure: QuoteFailure };

/**
 * 주문서(#26) — 회원 장바구니의 구매 가능한 줄을 배송지와 함께 견적(`POST /api/v1/orders/quotes`)해 결제 금액을 보인다.
 * 장바구니 · 배송지는 이 화면이 직접 읽는다(장바구니 화면에서 넘겨받지 않는다 — 새로 고쳐도 같은 주문서다).
 * 배송지는 저장된 배송지에서 고르거나(기본 배송지가 먼저 선택된다) 이 주문에만 쓰는 새 배송지를 입력한다(배송지 목록에 저장하지 않는다).
 * 배송지가 바뀔 때마다 다시 견적한다. 금액은 서버 값만 보이고, 주문하기는 주문 생성 API(BE #13 D2) 전까지 막아 둔다.
 */
export function CheckoutView({ shippableCountries }: { shippableCountries: string[] }) {
  const t = useDictionary().checkout;
  const accountText = useDictionary().account;
  const locale = useLocale();
  const formatMoney = useFormatMoney();
  const cooldown = useRetryCooldown();
  const { startFrom } = cooldown;
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [loadFailed, setLoadFailed] = useState(false);
  const [loadKey, setLoadKey] = useState(0);
  // 401 — 세션이 끝났다(장바구니 · 배송지 · 견적 어느 쪽이든)
  const [signedOut, setSignedOut] = useState(false);
  const [selection, setSelection] = useState<string | null>(null);
  // 새 배송지를 고르기 직전의 선택 — 새 배송지 입력을 취소하면 여기로 돌아간다
  const [previousSelection, setPreviousSelection] = useState<string | null>(null);
  const [newAddress, setNewAddress] = useState<AddressDraft | null>(null);
  const [editingNew, setEditingNew] = useState(false);
  const [quoted, setQuoted] = useState<Quoted | null>(null);
  const [quoteRetry, setQuoteRetry] = useState(0);
  // 마지막 배송지 실패 — 새 배송지를 고치는 동안(견적이 멈춰 있어도) 이유를 계속 보인다. 배송지를 바꾸거나 견적이 되면 지운다
  const [destinationError, setDestinationError] = useState<string | null>(null);

  // 옮길 포커스 — 다음 커밋 뒤(아래 effect) `data-focus` 가 같은 요소로 옮긴다. 한 번 쓰고 비운다
  const pendingFocus = useRef<string | null>(null);
  useEffect(() => {
    const key = pendingFocus.current;
    if (!key) return;
    pendingFocus.current = null;
    const target = document.querySelector<HTMLElement>(`[data-focus="${CSS.escape(key)}"]`);
    // 폼이면 첫 입력 칸으로
    (target?.matches('input, button')
      ? target
      : target?.querySelector<HTMLElement>('input')
    )?.focus();
  });

  // 장바구니 · 배송지를 같이 읽는다. 상태는 응답 콜백에서만 바꾼다
  useEffect(() => {
    let alive = true;
    Promise.all([
      apiFetch<CartResponse>(CART_PATH),
      apiFetch<ShippingAddressList>(ADDRESSES_PATH),
    ]).then(
      ([cart, addresses]) => {
        if (!alive) return;
        setLoaded({ cart, addresses });
        setLoadFailed(false);
        const preferred =
          addresses.addresses.find((address) => address.isDefault) ?? addresses.addresses[0];
        setSelection((current) => current ?? preferred?.addressId ?? NEW_ADDRESS);
      },
      (cause: unknown) => {
        if (!alive) return;
        if (cause instanceof ApiError && cause.kind === 'unauthorized') setSignedOut(true);
        else setLoadFailed(true);
      },
    );
    return () => {
      alive = false;
    };
  }, [loadKey]);

  const countries = useMemo(
    () =>
      countryOptions(
        [
          ...shippableCountries,
          ...(loaded?.addresses.addresses.map((address) => address.countryCode) ?? []),
        ],
        locale,
      ),
    [shippableCountries, loaded, locale],
  );

  const { lines, excluded } = loaded ? orderLines(loaded.cart) : { lines: [], excluded: [] };
  const savedAddress =
    loaded && selection !== NEW_ADDRESS
      ? (loaded.addresses.addresses.find((address) => address.addressId === selection) ?? null)
      : null;
  const destinationSource =
    selection === NEW_ADDRESS ? (editingNew ? null : newAddress) : savedAddress;
  const request =
    lines.length > 0 && destinationSource
      ? quoteRequest(lines, quoteDestination(destinationSource))
      : null;
  const key = request ? JSON.stringify(request) : null;

  // 배송지 · 줄이 바뀔 때마다 견적한다. 늦게 온 이전 견적은 `quoted.key !== key` 로 버린다
  // 본문은 key(직렬화한 본문)에서 다시 만든다 — 매 렌더 새 객체인 request 로는 부르지 않는다
  useEffect(() => {
    if (!key) return;
    let alive = true;
    apiFetch<OrderQuote>(QUOTES_PATH, {
      method: 'POST',
      json: JSON.parse(key) as QuoteRequest,
    }).then(
      (quote) => {
        if (!alive) return;
        setQuoted({ key, quote });
        setDestinationError(null);
      },
      (cause: unknown) => {
        if (!alive) return;
        startFrom(cause);
        const failure = quoteFailure(cause, t, locale);
        if (failure.kind === 'signedOut') {
          setSignedOut(true);
          return;
        }
        setQuoted({ key, failure });
        setDestinationError(failure.kind === 'destination' ? failure.message : null);
      },
    );
    return () => {
      alive = false;
    };
  }, [key, quoteRetry, startFrom, t, locale]);

  if (signedOut) {
    return (
      <div className="flex flex-col items-center gap-4 py-16 text-center">
        <p role="alert" className="text-sm text-muted-fg">
          {t.signInRequired}
        </p>
        <Link
          href={`/login?next=${encodeURIComponent(RETURN_PATH)}`}
          className="text-sm underline underline-offset-4"
        >
          {t.login}
        </Link>
      </div>
    );
  }
  if (loadFailed) {
    return (
      <div className="flex flex-col items-center gap-4 py-16 text-center">
        <p role="alert" className="text-sm text-muted-fg">
          {t.loadFailed}
        </p>
        <Button variant="secondary" onClick={() => setLoadKey((n) => n + 1)}>
          {t.reload}
        </Button>
      </div>
    );
  }
  if (!loaded) {
    return (
      <p role="status" className="py-16 text-center text-sm text-muted-fg">
        {t.loading}
      </p>
    );
  }

  const excludedNote = excluded.length > 0 && (
    <p className="text-sm text-muted-fg">
      {fill(t.excluded, { count: excluded.length })}{' '}
      <Link href="/cart" className="text-fg underline underline-offset-4">
        {t.backToCart}
      </Link>
    </p>
  );

  if (lines.length === 0) {
    return (
      <div className="flex flex-col items-center gap-4 py-16 text-center">
        <p className="text-sm text-muted-fg">{t.noLines}</p>
        {excludedNote}
        <Link href="/cart" className="text-sm underline underline-offset-4">
          {t.backToCart}
        </Link>
      </div>
    );
  }

  const current = quoted && quoted.key === key ? quoted : null;
  const failure = current && 'failure' in current ? current.failure : null;
  const quote = current && 'quote' in current ? current.quote : null;
  const saved = loaded.addresses.addresses;

  const choose = (next: string) => {
    setDestinationError(null);
    if (next === NEW_ADDRESS) {
      setPreviousSelection(selection);
      if (!newAddress) {
        setEditingNew(true);
        pendingFocus.current = FOCUS_NEW_FORM;
      }
    }
    setSelection(next);
  };

  const cancelNewAddress = () => {
    if (newAddress) {
      setEditingNew(false);
      pendingFocus.current = FOCUS_NEW_SUMMARY;
      return;
    }
    const back =
      previousSelection && previousSelection !== NEW_ADDRESS
        ? previousSelection
        : (saved.find((address) => address.isDefault) ?? saved[0])?.addressId;
    if (!back) return;
    setEditingNew(false);
    setSelection(back);
    pendingFocus.current = `address:${back}`;
  };

  const radioCard =
    'flex cursor-pointer gap-3 bg-muted px-5 py-4 has-[:checked]:outline has-[:checked]:outline-1 has-[:checked]:outline-fg';

  return (
    <div className="grid gap-10 lg:grid-cols-[minmax(0,1fr)_20rem]">
      <div className="flex min-w-0 flex-col gap-10">
        <section aria-labelledby="checkout-destination" className="flex flex-col gap-4">
          <div className="flex items-baseline justify-between gap-4">
            <h2 id="checkout-destination" className="text-lg">
              {t.destination}
            </h2>
            <Link href="/mypage/addresses" className="text-sm underline-offset-4 hover:underline">
              {t.manageAddresses}
            </Link>
          </div>

          {saved.length === 0 ? (
            <p className="text-sm text-muted-fg">{t.noSavedAddress}</p>
          ) : (
            <fieldset className="flex min-w-0 flex-col gap-2">
              <legend className="sr-only">{t.chooseAddress}</legend>
              {saved.map((address) => (
                <label key={address.addressId} className={radioCard}>
                  <input
                    type="radio"
                    name="destination"
                    value={address.addressId}
                    checked={selection === address.addressId}
                    onChange={() => choose(address.addressId)}
                    data-focus={`address:${address.addressId}`}
                    className="mt-1"
                  />
                  <span className="flex min-w-0 flex-col gap-1">
                    <span className="flex flex-wrap items-center gap-2">
                      <span className="font-medium">{address.label ?? address.recipientName}</span>
                      {address.isDefault && (
                        <span className="bg-accent px-2 py-0.5 text-xs text-accent-fg">
                          {accountText.address.default}
                        </span>
                      )}
                    </span>
                    <AddressDetails address={address} countries={countries} />
                  </span>
                </label>
              ))}
              <label className={`${radioCard} items-center`}>
                <input
                  type="radio"
                  name="destination"
                  value={NEW_ADDRESS}
                  checked={selection === NEW_ADDRESS}
                  onChange={() => choose(NEW_ADDRESS)}
                />
                <span className="font-medium">{t.newAddress}</span>
              </label>
            </fieldset>
          )}

          {selection === NEW_ADDRESS &&
            (editingNew || !newAddress ? (
              <div data-focus={FOCUS_NEW_FORM} className="flex flex-col gap-3">
                <p className="text-xs text-muted-fg">{t.newAddressNote}</p>
                <AddressForm
                  title={t.newAddressTitle}
                  initial={newAddress ?? emptyDraft(defaultCountry(countries, locale))}
                  countries={countries}
                  showDefault={false}
                  firstAddress={false}
                  submitLabel={t.useAddress}
                  // 돌아갈 곳(저장된 배송지 · 이미 입력한 새 배송지)이 없으면 취소를 두지 않는다
                  cancelable={saved.length > 0 || newAddress !== null}
                  // 저장하지 않는다 — 이 주문의 배송지로만 쓴다. 필수 칸은 폼(required)이, 형식은 견적 · 주문 생성이 본다
                  onSave={async (draft) => {
                    setNewAddress(draft);
                    setEditingNew(false);
                    setDestinationError(null);
                    pendingFocus.current = FOCUS_NEW_SUMMARY;
                    return null;
                  }}
                  onCancel={cancelNewAddress}
                />
              </div>
            ) : (
              <div className="flex flex-col gap-2 bg-muted px-5 py-4">
                <div className="flex items-center justify-between gap-4">
                  <span className="font-medium">{newAddress.recipientName}</span>
                  <button
                    type="button"
                    data-focus={FOCUS_NEW_SUMMARY}
                    onClick={() => {
                      setEditingNew(true);
                      pendingFocus.current = FOCUS_NEW_FORM;
                    }}
                    className="text-sm underline-offset-4 hover:underline"
                  >
                    {t.editNewAddress}
                  </button>
                </div>
                <AddressDetails
                  address={{
                    ...newAddress,
                    label: null,
                    postalCode: newAddress.postalCode || null,
                    addressLine2: newAddress.addressLine2 || null,
                    city: newAddress.city || null,
                    stateProvince: newAddress.stateProvince || null,
                  }}
                  countries={countries}
                />
                <p className="text-xs text-muted-fg">{t.newAddressNote}</p>
              </div>
            ))}

          {destinationError && (
            <p role="alert" className="text-sm text-danger">
              {destinationError}
            </p>
          )}
        </section>

        <section aria-labelledby="checkout-items" className="flex flex-col gap-4">
          <h2 id="checkout-items" className="text-lg">
            {t.items}
          </h2>
          <ul className="flex flex-col gap-2">
            {lines.map((line, index) => {
              // 견적 줄은 요청 순서다 — 금액은 견적이 있을 때만 보인다
              const quotedLine = quote?.items[index];
              return (
                <li
                  key={line.itemId}
                  className="flex flex-wrap items-start justify-between gap-x-4 gap-y-1 bg-muted px-5 py-4 text-sm"
                >
                  <div className="flex min-w-0 flex-col gap-0.5">
                    <span className="font-medium">{line.productName}</span>
                    {line.optionName && <span className="text-muted-fg">{line.optionName}</span>}
                    {line.customOptions.map((option) => (
                      <span key={option.fieldKey} className="text-muted-fg">
                        {option.fieldKey}: {option.value}
                      </span>
                    ))}
                    <span className="text-muted-fg">
                      {fill(t.quantity, { count: line.quantity })}
                    </span>
                  </div>
                  {quotedLine && <span>{formatMoney(quotedLine.lineAmount)}</span>}
                </li>
              );
            })}
          </ul>
          {excludedNote}
        </section>
      </div>

      <aside
        aria-labelledby="checkout-amounts"
        className="flex flex-col gap-4 lg:sticky lg:top-24 lg:self-start"
      >
        <h2 id="checkout-amounts" className="text-lg">
          {t.amounts}
        </h2>
        {/* 금액이 바뀌면 총액 한 줄만 읽는다(표 전체를 읽지 않게). 실패는 아래 role="alert" 가 읽는다 */}
        <p role="status" className="sr-only">
          {quote ? fill(t.totalAnnounce, { amount: formatMoney(quote.amounts.totalAmount) }) : ''}
        </p>
        {!request &&
          (destinationError ? (
            <p className="text-sm text-muted-fg">{t.destinationRetry}</p>
          ) : (
            <p className="text-sm text-muted-fg">{t.selectDestination}</p>
          ))}
        {request && !current && <p className="text-sm text-muted-fg">{t.calculating}</p>}
        {quote && <QuoteSummary quote={quote} />}
        {failure?.kind === 'destination' && (
          <p className="text-sm text-muted-fg">{t.destinationRetry}</p>
        )}
        {failure?.kind === 'cart' && (
          <div role="alert" className="flex flex-col gap-2 text-sm">
            <p className="text-danger">{failure.message}</p>
            <Link href="/cart" className="underline underline-offset-4">
              {t.backToCart}
            </Link>
          </div>
        )}
        {failure?.kind === 'retry' && (
          <div role="alert" className="flex flex-col items-start gap-3 text-sm">
            <p className="text-danger">{failure.message}</p>
            <Button
              variant="secondary"
              disabled={cooldown.coolingDown}
              onClick={() => {
                // 실패를 지워 "계산하는 중" 으로 보인다(같은 key 라 결과가 오면 바뀐다)
                setQuoted(null);
                setQuoteRetry((n) => n + 1);
              }}
            >
              {t.recalculate}
            </Button>
          </div>
        )}
        {/* 주문 생성 · Idempotency-Key 는 주문 생성 API(BE #13 D2) 뒤에 붙인다 */}
        <Button disabled className="h-12 w-full" aria-describedby="checkout-place-order-pending">
          {t.placeOrder}
        </Button>
        <p id="checkout-place-order-pending" className="text-center text-xs text-muted-fg">
          {t.placeOrderPending}
        </p>
      </aside>
    </div>
  );
}
