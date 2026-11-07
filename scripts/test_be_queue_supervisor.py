import unittest
import json
from pathlib import Path
from tempfile import TemporaryDirectory
from unittest.mock import patch

import be_queue_supervisor as supervisor


class WorkforceTests(unittest.TestCase):
    def test_auto_assignment_requires_explicit_tick_opt_in(self):
        with patch.object(supervisor, "reconcile"), patch.object(supervisor, "check_ci"), \
             patch.object(supervisor, "dispatch_queue"), \
             patch.object(supervisor, "dispatch_auto") as auto:
            supervisor.tick()
            auto.assert_not_called()
            supervisor.tick(auto_assign=True)
            auto.assert_called_once_with()

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

    def test_auto_assignment_waits_for_explicit_queue(self):
        state = {"workers": {"S1": {"thread": "one", "job": None}},
                 "queue": [{"slot": "S1", "issue": 217, "slug": "ai-size-chart"}]}
        with patch.object(supervisor, "read_state", return_value=state), \
             patch.object(supervisor.shared, "gh_json") as gh, \
             patch.object(supervisor, "assign_new") as assign:
            supervisor.dispatch_auto()
        gh.assert_not_called()
        assign.assert_not_called()

    def test_auto_assignment_skips_dependencies_duplicates_external_work_and_service_overlap(self):
        def item(number, service, body="- [ ] 코드 구현", blocked=None):
            return {"number": number, "title": f"[BE] feat: 작업 {number}", "body": body,
                    "labels": [{"name": f"backend-{service}-service"}],
                    "blockedBy": {"nodes": blocked or [], "totalCount": len(blocked or [])}}

        issues = [
            item(11, "product"),  # S5 already owns product
            item(12, "order", blocked=[{"number": 99, "state": "OPEN"}]),
            item(13, "delivery", "- [ ] 코드 구현\n- 선행: #88"),
            item(14, "delivery"),  # S1 takes delivery in this tick
            item(15, "auth", "- [ ] Vault 실값 등록"),
            item(16, "operations"),
            item(17, "payment"),  # open PR
            item(18, "support"),  # remote branch
            item(19, "support"),  # local branch
            item(20, "product"),
        ]
        state = {"workers": {
            "S1": {"thread": "one", "job": None},
            "S2": {"thread": "two", "job": None},
            "S5": {"thread": "five", "job": {"issue": 20, "reconciled": False}},
            "S6": {"thread": "six", "job": None}}, "queue": []}

        def gh_json(*args):
            if args[:2] == ("issue", "list"):
                return issues
            if args[:2] == ("issue", "view"):
                return {"state": "CLOSED" if args[2] == "88" else "OPEN"}
            raise AssertionError(args)

        branches = {"git fetch --prune origin": "",
                    "git branch --format=%(refname:short)": "wip/19-local-task",
                    "git branch -r --format=%(refname:short)": "origin/chore/infra/18-remote-task"}
        with patch.object(supervisor, "read_state", return_value=state), \
             patch.object(supervisor.shared, "gh_json", side_effect=gh_json), \
             patch.object(supervisor.shared, "pr_list", return_value=[{
                 "headRefName": "feature/be/17-open-pr", "body": "Issue Number: #17"}]), \
             patch.object(supervisor, "run", side_effect=lambda *args: branches[" ".join(args)]), \
             patch.object(supervisor, "assign_new") as assign:
            supervisor.dispatch_auto()
        self.assertEqual([("S1", 13, "delivery-task"), ("S2", 16, "operations-task")],
                         [call.args for call in assign.call_args_list])

    def test_dependency_formats_fail_closed_when_unresolved(self):
        states = {99: "OPEN"}
        examples = ["- 선행:#99", "- 선행: https://github.com/org/repo/issues/99",
                    "### 선행 이슈\n- #99", "- 선행: #98\n  - #99",
                    "### 선행 이슈\n- #98\n- https://github.com/other/repo/issues/99"]
        for body in examples:
            with self.subTest(body=body):
                self.assertFalse(supervisor.predecessors_closed({"number": 100, "body": body},
                                                                {98: "CLOSED", 99: "OPEN"}))
        self.assertFalse(supervisor.predecessors_closed({
            "number": 100, "body": "- 선행: https://github.com/other/repo/issues/99"}, {99: "CLOSED"}))
        self.assertFalse(supervisor.predecessors_closed({"number": 100, "body": "- 선행 작업 확인 필요"}, states))

    def test_existing_pr_local_and_remote_branches_reserve_issue_numbers(self):
        branches = {"wip/19-local-task", "origin/chore/infra/18-remote-task"}
        prs = [{"headRefName": "feature/be/17-open-pr", "body": "Issue Number: #17"}]
        self.assertEqual({17, 18, 19}, supervisor.occupied_issues(branches, prs))

    def test_remote_refs_are_refreshed_before_occupancy_snapshot(self):
        commands = []
        def run(*args):
            commands.append(args)
            return "origin/feature/be/99-order-task" if args[:3] == ("git", "branch", "-r") else ""
        with patch.object(supervisor, "run", side_effect=run), \
             patch.object(supervisor.shared, "pr_list", return_value=[]):
            self.assertEqual({99}, supervisor.observed_issues())
        self.assertEqual(("git", "fetch", "--prune", "origin"), commands[0])

    def test_nested_external_checkbox_is_not_auto_eligible(self):
        body = "- [ ] 운영 전환\n  - [ ] Vault 실값 등록\n- [x] 코드 구현"
        self.assertTrue(any(supervisor.EXTERNAL_PENDING_RE.search(item)
                            for item in supervisor.pending_items(body)))

    def test_unknown_existing_be_service_blocks_automatic_assignment(self):
        with self.assertRaisesRegex(ValueError, "서비스 소유 범위"):
            supervisor.occupied_services({99}, {99: {
                "number": 99, "title": "[BE] feat: 기존 작업", "labels": []}})

    def test_s6_is_reserved_even_when_other_slots_are_busy(self):
        state = {"workers": {"S1": {"thread": "one", "job": {"issue": 1, "reconciled": False}},
                             "S6": {"thread": "six", "job": None}}, "queue": []}
        with patch.object(supervisor, "read_state", return_value=state), \
             patch.object(supervisor.shared, "gh_json") as gh, \
             patch.object(supervisor, "assign_new") as assign:
            supervisor.dispatch_auto()
        gh.assert_not_called()
        assign.assert_not_called()

    def test_new_remote_service_owner_is_rechecked_before_dispatch(self):
        def info(number, service):
            return {"number": number, "title": f"[BE] feat: 작업 {number}",
                    "body": "- [ ] 코드 구현", "labels": [{"name": f"backend-{service}-service"}],
                    "blockedBy": {"nodes": [], "totalCount": 0}}

        state = {"workers": {"S1": {"thread": "one", "job": None}}, "queue": []}
        def gh_json(*args):
            if args[:2] == ("issue", "list"):
                return [info(100, "order"), info(101, "delivery")]
            if args[:3] == ("issue", "view", "99"):
                return info(99, "order")
            raise AssertionError(args)

        with patch.object(supervisor, "read_state", return_value=state), \
             patch.object(supervisor.shared, "gh_json", side_effect=gh_json), \
             patch.object(supervisor, "observed_issues", side_effect=[set(), {99}, {99}]), \
             patch.object(supervisor, "assign_new") as assign:
            supervisor.dispatch_auto()
        assign.assert_called_once_with("S1", 101, "delivery-task")

    def test_failed_message_keeps_slot_reserved_and_stops_auto_tick(self):
        with TemporaryDirectory() as directory:
            path = Path(directory) / "100-order-task"
            state = {"workers": {"S1": {"thread": "one", "job": None}}, "queue": []}
            saved = []
            with patch.object(supervisor, "read_state", return_value=state), \
                 patch.object(supervisor, "issue", return_value={"title": "[BE] feat: 주문 작업"}), \
                 patch.object(supervisor, "branch_worktree", return_value=path), \
                 patch.object(supervisor.shared, "worktree_map", return_value={}), \
                 patch.object(supervisor.shared, "pr_list", return_value=[]), \
                 patch.object(supervisor, "run", return_value=""), \
                 patch.object(supervisor, "write_state", side_effect=lambda value: saved.append(json.loads(json.dumps(value)))), \
                 patch.object(supervisor, "queue_message", side_effect=RuntimeError("delivery uncertain")):
                with self.assertRaisesRegex(RuntimeError, "delivery uncertain"):
                    supervisor.assign_new("S1", 100, "order-task")
            self.assertTrue(saved[-1]["workers"]["S1"]["job"]["dispatch_pending"])

        issues = [{"number": number, "title": f"[BE] feat: 작업 {number}", "body": "- [ ] 구현",
                   "labels": [{"name": "backend-order-service"}],
                   "blockedBy": {"nodes": [], "totalCount": 0}} for number in (100, 101)]
        state = {"workers": {"S1": {"thread": "one", "job": None}}, "queue": []}
        with patch.object(supervisor, "read_state", return_value=state), \
             patch.object(supervisor.shared, "gh_json", return_value=issues), \
             patch.object(supervisor, "observed_issues", return_value=set()), \
             patch.object(supervisor, "assign_new", side_effect=RuntimeError("delivery uncertain")) as assign:
            supervisor.dispatch_auto()
        assign.assert_called_once_with("S1", 100, "order-task")

    def test_dependency_relation_and_text_must_be_closed(self):
        info = {"number": 217, "title": "[BE] feat: 사이즈표 (#214 후속)",
                "body": "- 선행 #216, #215",
                "blockedBy": {"nodes": [{"number": 212, "state": "CLOSED"}], "totalCount": 1}}
        states = {212: "CLOSED", 214: "CLOSED", 215: "CLOSED"}
        with patch.object(supervisor.shared, "gh_json", return_value={"state": "CLOSED"}) as gh:
            self.assertTrue(supervisor.predecessors_closed(info, states))
        gh.assert_called_once_with("issue", "view", "216", "--json", "state")
        info["blockedBy"]["totalCount"] = 2
        self.assertFalse(supervisor.predecessors_closed(info, states))
        info["blockedBy"]["totalCount"] = 1
        info["blockedBy"]["nodes"][0]["state"] = "OPEN"
        self.assertFalse(supervisor.predecessors_closed(info, states))
        info["blockedBy"]["nodes"][0]["state"] = "CLOSED"
        states[214] = "OPEN"
        self.assertFalse(supervisor.predecessors_closed(info, states))

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
