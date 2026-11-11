const test = require('node:test');
const assert = require('node:assert/strict');
const crypto = require('crypto');

function generateDistinct6DigitCode() {
  const firstDigit = crypto.randomInt(1, 10);
  const remainingPool = [0, 1, 2, 3, 4, 5, 6, 7, 8, 9].filter(d => d !== firstDigit);
  for (let i = remainingPool.length - 1; i > 0; i--) {
    const j = crypto.randomInt(0, i + 1);
    [remainingPool[i], remainingPool[j]] = [remainingPool[j], remainingPool[i]];
  }
  const digits = [firstDigit, ...remainingPool.slice(0, 5)];
  return digits.join('');
}

test('generateDistinct6DigitCode generates exactly 6 digits', () => {
  for (let i = 0; i < 500; i++) {
    const code = generateDistinct6DigitCode();
    assert.equal(code.length, 6);
    assert.match(code, /^[0-9]{6}$/);
  }
});

test('generateDistinct6DigitCode never starts with 0', () => {
  for (let i = 0; i < 500; i++) {
    const code = generateDistinct6DigitCode();
    assert.notEqual(code[0], '0');
    assert.match(code[0], /^[1-9]$/);
  }
});

test('generateDistinct6DigitCode has all 6 digits strictly distinct', () => {
  for (let i = 0; i < 500; i++) {
    const code = generateDistinct6DigitCode();
    const uniqueDigits = new Set(code.split(''));
    assert.equal(uniqueDigits.size, 6, `Code ${code} must not contain duplicate digits`);
  }
});

test('generateDistinct6DigitCode has high entropy and low collision rate', () => {
  const codes = new Set();
  const iterations = 2000;
  for (let i = 0; i < iterations; i++) {
    codes.add(generateDistinct6DigitCode());
  }
  // With 136,080 distinct permutations, 2,000 samples should produce > 1,960 unique codes
  assert.ok(codes.size >= iterations * 0.98, `Expected >= 98% unique, got ${codes.size}/${iterations}`);
});
