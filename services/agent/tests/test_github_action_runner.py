"""Tests for diffguard_agent.github_action_runner helpers."""

from __future__ import annotations

from unittest.mock import patch

from diffguard_agent import github_action_runner as runner


class TestEnvTimeoutSeconds:

    def test_default_timeout(self):
        with patch.dict("os.environ", {}, clear=False):
            assert runner._env_timeout_seconds() == 300

    def test_valid_timeout_minutes(self):
        with patch.dict("os.environ", {"DIFFGUARD_TIMEOUT_MINUTES": "12"}, clear=False):
            assert runner._env_timeout_seconds() == 720

    def test_invalid_timeout_minutes(self):
        with patch.dict("os.environ", {"DIFFGUARD_TIMEOUT_MINUTES": "oops"}, clear=False):
            assert runner._env_timeout_seconds() == 300

    def test_non_positive_timeout_minutes(self):
        with patch.dict("os.environ", {"DIFFGUARD_TIMEOUT_MINUTES": "0"}, clear=False):
            assert runner._env_timeout_seconds() == 300


class TestEnvBool:

    def test_true_values(self):
        with patch.dict("os.environ", {"DIFFGUARD_ENABLE_FP_FILTER": "true"}, clear=False):
            assert runner._env_bool("DIFFGUARD_ENABLE_FP_FILTER", False) is True
        with patch.dict("os.environ", {"DIFFGUARD_ENABLE_FP_FILTER": "1"}, clear=False):
            assert runner._env_bool("DIFFGUARD_ENABLE_FP_FILTER", False) is True
        with patch.dict("os.environ", {"DIFFGUARD_ENABLE_FP_FILTER": "on"}, clear=False):
            assert runner._env_bool("DIFFGUARD_ENABLE_FP_FILTER", False) is True

    def test_false_values(self):
        with patch.dict("os.environ", {"DIFFGUARD_ENABLE_FP_FILTER": "false"}, clear=False):
            assert runner._env_bool("DIFFGUARD_ENABLE_FP_FILTER", True) is False
        with patch.dict("os.environ", {"DIFFGUARD_ENABLE_FP_FILTER": "0"}, clear=False):
            assert runner._env_bool("DIFFGUARD_ENABLE_FP_FILTER", True) is False


class TestBuildReviewRequestToolServer:

    @patch("diffguard_agent.github_action_runner._env_timeout_seconds", return_value=300)
    @patch("diffguard_agent.github_action_runner._env", side_effect=lambda key, default="": {
        "DIFFGUARD_PROVIDER": "claude",
        "DIFFGUARD_MODEL": "claude-sonnet-4-20250514",
        "DIFFGUARD_API_KEY": "test-key",
        "GITHUB_REPOSITORY": "o/r",
        "PR_NUMBER": "1",
        "REPO_PATH": "/repo",
        "DIFFGUARD_LANGUAGE": "zh",
        "DIFFGUARD_USE_JAVA_TOOL_SERVER": "false",
        "DIFFGUARD_TOOL_SERVER_URL": "http://127.0.0.1:9090",
    }.get(key, default))
    @patch("diffguard_agent.utils.diff_utils.split_diff", return_value=[])
    def test_tool_server_disabled(self, _mock_split, _mock_env, _mock_timeout):
        req = runner._build_review_request("diff")
        assert req.tool_server_url == ""

    @patch("diffguard_agent.github_action_runner._env_timeout_seconds", return_value=300)
    @patch("diffguard_agent.github_action_runner._env", side_effect=lambda key, default="": {
        "DIFFGUARD_PROVIDER": "claude",
        "DIFFGUARD_MODEL": "claude-sonnet-4-20250514",
        "DIFFGUARD_API_KEY": "test-key",
        "GITHUB_REPOSITORY": "o/r",
        "PR_NUMBER": "1",
        "REPO_PATH": "/repo",
        "DIFFGUARD_LANGUAGE": "zh",
        "DIFFGUARD_USE_JAVA_TOOL_SERVER": "true",
        "DIFFGUARD_TOOL_SERVER_URL": "http://127.0.0.1:9090",
    }.get(key, default))
    @patch("diffguard_agent.utils.diff_utils.split_diff", return_value=[])
    def test_tool_server_enabled(self, _mock_split, _mock_env, _mock_timeout):
        req = runner._build_review_request("diff")
        assert req.tool_server_url == "http://127.0.0.1:9090"
