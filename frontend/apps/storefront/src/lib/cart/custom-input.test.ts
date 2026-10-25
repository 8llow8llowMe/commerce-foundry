import { describe, expect, it } from 'vitest';
import { hasRejectedCharacters, stripRejectedCharacters } from './custom-input';

// BE `CartWebControllerTest` 의 CART_115 사례와 같은 값 (#232)
describe('hasRejectedCharacters — BE NoControlCharactersValidator 와 같은 판정', () => {
  it.each([
    ['NUL', 'a\u0000b'],
    ['탭', 'a\tb'],
    ['개행', 'a\nb'],
    ['CR', 'a\rb'],
    ['DEL', 'a\u007fb'],
    ['C1 NEL', 'a\u0085b'],
    ['C1 끝(U+009F)', 'a\u009fb'],
    ['짝 없는 상위 서로게이트', 'a\ud800b'],
    ['짝 없는 하위 서로게이트', 'a\udc00b'],
    ['끝에 남은 상위 서로게이트', 'a\ud83d'],
  ])('%s 는 거절한다', (_, value) => {
    expect(hasRejectedCharacters(value)).toBe(true);
  });

  it.each([
    ['이모지 ZWJ 시퀀스 · 피부색', '\u{1F468}‍\u{1F469}‍\u{1F467} \u{1F44D}\u{1F3FD}'],
    ['전각 공백 · 한글', '소매　길게'],
    ['서식 문자(BOM · ZWNJ)', 'a﻿b‌c'],
    ['일반 공백 · 숫자', ' 96.5 '],
    ['U+00A0(Cc 바로 다음)', 'a b'],
  ])('%s 는 통과한다', (_, value) => {
    expect(hasRejectedCharacters(value)).toBe(false);
  });
});

describe('stripRejectedCharacters', () => {
  it('탭 · 개행은 공백 하나로, 그 밖의 제어 문자 · 짝 없는 서로게이트는 지운다', () => {
    expect(stripRejectedCharacters('소매\t길게')).toBe('소매 길게');
    expect(stripRejectedCharacters('첫 줄\r\n둘째 줄\n')).toBe('첫 줄 둘째 줄 ');
    expect(stripRejectedCharacters('a\u0000b\u007f\u009fc\ud800')).toBe('abc');
  });

  it('거절할 문자가 없으면 그대로 돌려준다(이모지 보존)', () => {
    const value = '\u{1F468}‍\u{1F469} 96.5';
    expect(stripRejectedCharacters(value)).toBe(value);
  });

  it('지운 결과는 다시 판정해도 통과한다', () => {
    const value = stripRejectedCharacters('\u0001\t\ud800x\u0085y\udfff');
    expect(hasRejectedCharacters(value)).toBe(false);
    expect(value).toBe(' x y');
  });
});
