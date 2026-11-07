import unittest
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

import be_queue_supervisor as supervisor


class WorkforceTests(unittest.TestCase):
    def test_merged_pr_requires_develop_branch_and_issue_reference(self):
        job = {"issue": 20, "branch": "feature/be/20-usd-price-work"}
        pr = {"baseRefName": "develop", "headRefName": job["branch"],
              "body": "Refs #20\n## 완료된 이슈 항목\n- [x] 가격 저장"}
        self.assertEqual(pr, supervisor.merged_pr(job, [pr]))
        self.assertIsNone(supervisor.merged_pr(job, [{**pr, "baseRefName": "main"}]))
        self.assertIsNone(supervisor.merged_pr(job, [{**pr, "headRefName": "feature/be/20-other"}]))
        self.assertIsNone(supervisor.merged_pr(job, [{**pr, "body": "Refs #200"}]))

    def test_branch_path_and_prompt_keep_scope(self):
        path = supervisor.branch_worktree("feature/be/589-admin-password-reset")
        self.assertEqual(supervisor.WORKTREES.resolve(), path.parent)
        prompt = supervisor.worker_prompt(589, "[BE] feat: 재설정", "feature/be/589-admin-password-reset", None)
        self.assertIn(str(path), prompt)
        self.assertIn("$dev-orchestrator", prompt)
        self.assertIn("$pr", prompt)
        self.assertIn("CI가 끝날 때까지 확인", prompt)
        with self.assertRaises(ValueError):
            supervisor.branch_worktree("feature/fe/589-admin-password-reset")

    def test_service_labels_exclude_type_and_docs(self):
        info = {"labels": [{"name": "backend-order-service"},
                           {"name": "backend-docs"}, {"name": "enhancement"}]}
        self.assertEqual({"backend-order-service"}, supervisor.services(info))

    def test_queue_waits_while_another_slot_owns_service(self):
        state = {"workers": {
            "S1": {"thread": "one", "job": {"issue": 20, "reconciled": False}},
            "S2": {"thread": "two", "job": None}},
            "queue": [{"slot": "S2", "issue": 217, "slug": "ai-size-chart"}]}
        def fake_issue(number):
            return {"labels": [{"name": "backend-product-service"}]}
        with patch.object(supervisor, "read_state", return_value=state), \
             patch.object(supervisor.shared, "gh_json", return_value=fake_issue(20)), \
             patch.object(supervisor, "issue", side_effect=fake_issue), \
             patch.object(supervisor, "assign_new") as assign, \
             patch.object(supervisor, "write_state") as save:
            supervisor.dispatch_queue()
        assign.assert_not_called()
        self.assertEqual(state["queue"], save.call_args.args[0]["queue"])

    def test_state_recovers_from_backup_and_rewrites_valid_json(self):
        with TemporaryDirectory() as directory, patch.object(supervisor, "STATE", Path(directory)):
            current = Path(directory) / "workers.json"
            backup = Path(directory) / "workers.json.bak"
            current.write_bytes(b"\0" * 48)
            backup.write_text('{"workers": {}, "queue": []}', encoding="utf-8")
            state = supervisor.read_state()
            self.assertEqual([], state["queue"])
            supervisor.write_state(state)
            self.assertEqual(state, json.loads(current.read_text(encoding="utf-8")))

    def test_followup_branch_preserves_same_issue_worktree(self):
        path = (supervisor.WORKTREES / "20-usd-legacy-price-writers").resolve()
        job = {"issue": 20, "branch": "feature/be/20-usd-legacy-price-writers",
               "worktree": str(path)}
        with patch.object(supervisor.shared, "worktree_map", return_value={
                "feature/be/20-usd-legacy-price-writers-b": path}):
            self.assertEqual("feature/be/20-usd-legacy-price-writers-b",
                             supervisor.continued_branch(job))

    def test_ci_alert_only_for_completed_failed_checks(self):
        pr = {"statusCheckRollup": [
            {"name": "test (rest)", "status": "COMPLETED", "conclusion": "FAILURE"},
            {"name": "check", "status": "IN_PROGRESS", "conclusion": ""},
            {"name": "label", "status": "COMPLETED", "conclusion": "SUCCESS"}]}
        self.assertEqual(["test (rest)"], supervisor.failed_checks(pr))

    def test_merged_pr_without_completed_items_cleans_branch_but_keeps_issue_open(self):
        with TemporaryDirectory() as directory:
            job = {"issue": 589, "branch": "feature/be/589-admin-password-reset",
                   "worktree": str(Path(directory) / "already-removed"), "reconciled": False}
            state = {"workers": {"S5": {"thread": "worker", "job": job}}, "queue": []}
            pr = {"number": 653, "baseRefName": "develop", "headRefName": job["branch"],
                  "body": "Issue Number: #589"}
            with patch.object(supervisor, "run", return_value=""), \
                 patch.object(supervisor, "read_state", return_value=state), \
                 patch.object(supervisor, "write_state"), \
                 patch.object(supervisor.shared, "pr_list", return_value=[pr]), \
                 patch.object(supervisor.shared, "gh_json", return_value={
                     "body": "- [ ] 아직 완료 전", "state": "OPEN"}), \
                 patch.object(supervisor.shared, "worktree_map", return_value={}), \
                 patch.object(supervisor, "remove_merged_branch") as remove:
                supervisor.reconcile()
            remove.assert_called_once_with(job["branch"])
            self.assertTrue(job["reconciled"])
            self.assertTrue(job["cleaned"])

    def test_windows_orphan_does_not_block_other_reconciliation(self):
        with TemporaryDirectory() as directory:
            job = {"issue": 477, "branch": "feature/be/477-anomaly-collector-sse",
                   "worktree": directory, "reconciled": False}
            state = {"workers": {"S3": {"thread": "worker", "job": job}}, "queue": []}
            pr = {"number": 652, "baseRefName": "develop", "headRefName": job["branch"],
                  "body": "Issue Number: #477"}
            with patch.object(supervisor, "run", return_value=""), \
                 patch.object(supervisor, "read_state", return_value=state), \
                 patch.object(supervisor, "write_state"), \
                 patch.object(supervisor.shared, "pr_list", return_value=[pr]), \
                 patch.object(supervisor.shared, "gh_json", return_value={
                     "body": "- [ ] 다음 범위", "state": "OPEN"}), \
                 patch.object(supervisor.shared, "worktree_map", return_value={}), \
                 patch.object(supervisor.shared, "safe_cleanup", side_effect=RuntimeError("Invalid argument")), \
                 patch.object(supervisor, "remove_merged_branch") as remove:
                supervisor.reconcile()
            remove.assert_called_once_with(job["branch"])
            self.assertEqual(directory, job["cleanup_pending_path"])
            self.assertTrue(job["reconciled"])
            self.assertFalse(job.get("cleaned", False))


if __name__ == "__main__":
    unittest.main()
