import unittest
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


if __name__ == "__main__":
    unittest.main()
