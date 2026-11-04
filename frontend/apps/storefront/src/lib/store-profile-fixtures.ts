import type { StoreProfile } from './store-profile';

/** 테스트용 쇼핑몰 공개 정보. 실정보가 아닌 자리 값만 쓴다(공개 저장소) */
export const STORE_PROFILE_FIXTURE: StoreProfile = {
  storeName: '테스트몰',
  companyName: '테스트상점',
  representativeName: '홍길동',
  countryCode: 'KR',
  businessRegistrationNumber: '000-00-00001',
  mailOrderRegistrationNumber: '제0000-테스트-0000호',
  address: '테스트시 테스트로 1',
  customerServiceEmail: 'help@example.com',
  customerServicePhone: '00-0000-0000',
  customerServiceHours: '평일 10:00-17:00',
};
