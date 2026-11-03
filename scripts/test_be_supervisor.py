import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

import be_supervisor as supervisor


class ReconcileTests(unittest.TestCase):
    def test_only_exact_checked_pr_items_are_applied(self):
        issue = "## 작업 상세\n- [ ] 첫 기능\n- [ ] 둘째 기능\n## 완료 기준\n- [ ] 검증 완료\n"
        pr = "## 완료된 이슈 항목\n- [x] 첫 기능\n- [ ] 둘째 기능\n- [x] 다른 기능\n\n## 참고 사항\n- [x] 검증 완료\n"
        items = supervisor.completed_items(issue, pr)
        self.assertEqual(["첫 기능"], items)
        changed = supervisor.checked_body(issue, items)
        self.assertIn("- [x] 첫 기능", changed)
        self.assertIn("- [ ] 둘째 기능", changed)
        self.assertIn("- [ ] 검증 완료", changed)

    def test_missing_completion_section_does_not_claim_work(self):
        self.assertEqual([], supervisor.completed_items("- [ ] 첫 기능", "Issue Number: #1"))

    def test_issue_and_branch_patterns_exclude_fe(self):
        self.assertIsNone(supervisor.ISSUE_RE.match("[FE] feat: 화면"))
        self.assertIsNotNone(supervisor.ISSUE_RE.match("[BE] feat: API"))
        self.assertIsNone(supervisor.BRANCH_RE.match("feature/fe/12-page"))
        self.assertIsNotNone(supervisor.BRANCH_RE.match("feature/be/12-api-work"))

    def test_merged_pr_requires_branch_base_and_issue_reference(self):
        record = {"issue": 12, "branch": "feature/be/12-api-work"}
        wrong = {"baseRefName": "main", "headRefName": record["branch"],
                 "body": "Issue Number: #12"}
        correct = {**wrong, "baseRefName": "develop"}
        self.assertIsNone(supervisor.matching_merged_pr(record, [wrong]))
        self.assertIsNone(supervisor.matching_merged_pr(record, [{**correct, "body": "Issue Number: #13"}]))
        self.assertEqual(correct, supervisor.matching_merged_pr(record, [wrong, correct]))

    def test_session_is_finished_only_after_successful_process_exit(self):
        with TemporaryDirectory() as directory:
            marker = Path(directory) / "exit"
            record = {"exit": str(marker), "log": str(Path(directory) / "events")}
            with patch.object(supervisor, "events", return_value=[{"type": "turn.completed"}]):
                self.assertEqual("running", supervisor.session_state(record)[0])
                marker.write_text("0", encoding="ascii")
                self.assertEqual("finished", supervisor.session_state(record)[0])
                marker.write_text("1", encoding="ascii")
                self.assertEqual("failed", supervisor.session_state(record)[0])


if __name__ == "__main__":
    unittest.main()
