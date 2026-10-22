import type { CountryOption } from '@/lib/account/countries';
import { DOMESTIC_COUNTRY, formatPhone } from '@/lib/account/address-form';
import type { ShippingAddress } from '@/lib/account/types';

/**
 * 배송지 한 건의 받는 사람 · 연락처 · 주소 줄. 배송지 관리와 주문서가 같이 쓴다.
 * 별칭이 있으면 제목(호출자)이 별칭이라 받는 사람을 여기 한 줄 더 보인다. 해외는 도시 · 주 · 국가 줄을 더한다.
 */
export function AddressDetails({
  address,
  countries,
}: {
  address: Pick<
    ShippingAddress,
    | 'label'
    | 'recipientName'
    | 'recipientPhone'
    | 'countryCode'
    | 'postalCode'
    | 'addressLine1'
    | 'addressLine2'
    | 'city'
    | 'stateProvince'
  >;
  countries: readonly CountryOption[];
}) {
  const country = countries.find((option) => option.code === address.countryCode)?.name;
  const region = [address.city, address.stateProvince].filter(Boolean).join(', ');
  return (
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
  );
}
