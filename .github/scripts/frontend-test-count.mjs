// Vitest JSON 리포트에서 패키지별 통과 테스트 수를 세고,
// 테스트가 있어야 하는 패키지가 0 건이면 실패한다 ("초록불인데 테스트 0건" 방지 — backend-ci 와 같은 취지).
// 사용: node ../.github/scripts/frontend-test-count.mjs test-results/vitest.json  (frontend/ 에서 실행)
import { readFileSync } from 'node:fs';
import path from 'node:path';

const REQUIRED = ['packages/api-client/', 'packages/ui/'];

const reportPath = process.argv[2];
if (!reportPath) {
  console.error('usage: frontend-test-count.mjs <vitest-json-report>');
  process.exit(2);
}

const report = JSON.parse(readFileSync(reportPath, 'utf8'));
const counts = new Map(REQUIRED.map((prefix) => [prefix, 0]));

for (const file of report.testResults ?? []) {
  const relative = path.relative(process.cwd(), file.name).split(path.sep).join('/');
  const passed = (file.assertionResults ?? []).filter((a) => a.status === 'passed').length;
  for (const prefix of REQUIRED) {
    if (relative.startsWith(prefix)) counts.set(prefix, counts.get(prefix) + passed);
  }
}

let failed = false;
for (const [prefix, count] of counts) {
  console.log(`${prefix.padEnd(24)} ${count} passed`);
  if (count === 0) failed = true;
}
console.log(`total ${report.numPassedTests}/${report.numTotalTests}`);

if (failed) {
  console.error('테스트가 있어야 하는 패키지에서 통과한 테스트가 0 건이다.');
  process.exit(1);
}
