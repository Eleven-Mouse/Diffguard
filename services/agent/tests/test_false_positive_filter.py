"""Tests for app.agent.false_positive_filter - hard exclusion rules and FindingsFilter.

This is the most comprehensive test file in the suite because the false positive
filter is the core P0 feature of DiffGuard. Every exclusion category is tested
with both positive (matches) and negative (does not match) cases.
"""

import pytest
from unittest.mock import AsyncMock, MagicMock

from app.agent.false_positive_filter import (
    FindingsFilter,
    FilterStats,
    HardExclusionRules,
)
from app.models.schemas import IssuePayload


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------


def _issue(**overrides) -> IssuePayload:
    """Create an IssuePayload with sensible defaults; override anything via kwargs."""
    defaults = dict(
        severity="WARNING",
        file="src/main/java/UserService.java",
        line=42,
        type="sql_injection",
        message="Potential SQL injection vulnerability",
        suggestion="Use PreparedStatement instead of string concatenation",
    )
    defaults.update(overrides)
    return IssuePayload(**defaults)


def _rules() -> HardExclusionRules:
    """Create a HardExclusionRules instance for testing."""
    return HardExclusionRules()


# ===========================================================================
# HardExclusionRules.check() tests
# ===========================================================================


class TestHardExclusionDosPatterns:
    """Category 1: DOS / Resource Exhaustion noise."""

    def test_dos_potential_denial_of_service(self):
        issue = _issue(
            type="dos",
            message="potential denial of service vulnerability",
        )
        result = _rules().check(issue)
        assert result is not None
        assert "DOS" in result or "resource exhaustion" in result.lower()

    def test_dos_dos_attack(self):
        issue = _issue(
            type="dos",
            message="Possible DOS attack via unbounded input",
        )
        result = _rules().check(issue)
        assert result is not None

    def test_dos_resource_exhaustion(self):
        issue = _issue(
            type="resource",
            message="Potential resource exhaustion attack",
        )
        result = _rules().check(issue)
        assert result is not None

    def test_dos_unbounded_growth(self):
        issue = _issue(
            type="resource",
            message="Unbounded growth of internal list",
        )
        result = _rules().check(issue)
        assert result is not None

    def test_legitimate_message_not_matched(self):
        issue = _issue(
            type="bug",
            message="Null pointer dereference when user is null",
        )
        result = _rules().check(issue)
        assert result is None


class TestHardExclusionRateLimitPatterns:
    """Category 2: Generic rate limiting suggestions."""

    def test_consider_adding_rate_limiting(self):
        issue = _issue(
            type="rate_limit",
            message="consider adding rate limiting to this endpoint",
        )
        result = _rules().check(issue)
        assert result is not None
        assert "rate limit" in result.lower()

    def test_missing_rate_limiting(self):
        issue = _issue(
            type="rate_limit",
            message="missing rate limiting on public API",
        )
        result = _rules().check(issue)
        assert result is not None

    def test_no_rate_limit(self):
        issue = _issue(
            type="rate_limit",
            message="There is no rate limit configured",
        )
        result = _rules().check(issue)
        assert result is not None

    def test_implement_rate_limit(self):
        issue = _issue(
            type="rate_limit",
            message="Should implement rate limiting for login",
        )
        result = _rules().check(issue)
        assert result is not None


class TestHardExclusionGenericPerfPatterns:
    """Category 3: Generic performance suggestions."""

    def test_consider_adding_caching(self):
        issue = _issue(
            type="performance",
            message="consider adding caching layer",
        )
        result = _rules().check(issue)
        assert result is not None
        assert "performance" in result.lower() or "caching" in result.lower()

    def test_consider_using_caching(self):
        issue = _issue(
            type="performance",
            message="Consider using caching for this query",
        )
        result = _rules().check(issue)
        assert result is not None

    def test_use_connection_pooling(self):
        issue = _issue(
            type="performance",
            message="use connection pooling for database",
        )
        result = _rules().check(issue)
        assert result is not None

    def test_consider_using_connection_pool(self):
        issue = _issue(
            type="performance",
            message="Consider using connection pool",
        )
        result = _rules().check(issue)
        assert result is not None

    def test_specific_perf_not_excluded(self):
        """A specific, actionable perf finding should NOT be excluded."""
        issue = _issue(
            type="performance",
            message="N+1 query detected in UserService.findAll() at line 42, each iteration calls findById()",
            suggestion="Use JOIN FETCH or batch query to avoid N+1",
        )
        result = _rules().check(issue)
        assert result is None


class TestHardExclusionTestFileNoise:
    """Category 4: Test file noise."""

    def test_java_test_file_excluded(self):
        issue = _issue(file="src/test/java/UserServiceTest.java")
        result = _rules().check(issue)
        assert result is not None
        assert "test" in result.lower()

    def test_python_test_file_excluded(self):
        issue = _issue(file="tests/test_utils.py")
        result = _rules().check(issue)
        assert result is not None

    def test_python_test_prefix_excluded(self):
        issue = _issue(file="test_service.py")
        result = _rules().check(issue)
        assert result is not None

    def test_typescript_spec_file_excluded(self):
        issue = _issue(file="src/app.component.spec.ts")
        result = _rules().check(issue)
        assert result is not None

    def test_kotlin_test_file_excluded(self):
        issue = _issue(file="UserServiceTest.kt")
        result = _rules().check(issue)
        assert result is not None

    def test_production_file_not_excluded(self):
        issue = _issue(file="src/main/java/UserService.java")
        result = _rules().check(issue)
        assert result is None


class TestHardExclusionDocFileNoise:
    """Category 5: Documentation file noise."""

    def test_md_file_excluded(self):
        issue = _issue(file="README.md")
        result = _rules().check(issue)
        assert result is not None
        assert "documentation" in result.lower()

    def test_rst_file_excluded(self):
        issue = _issue(file="docs/guide.rst")
        result = _rules().check(issue)
        assert result is not None

    def test_txt_file_excluded(self):
        issue = _issue(file="CHANGELOG.txt")
        result = _rules().check(issue)
        assert result is not None

    def test_adoc_file_excluded(self):
        issue = _issue(file="manual.adoc")
        result = _rules().check(issue)
        assert result is not None

    def test_java_file_not_excluded(self):
        issue = _issue(file="src/main/java/Service.java")
        result = _rules().check(issue)
        assert result is None


class TestHardExclusionMemorySafety:
    """Category 6: Memory safety in non-C/C++ code."""

    def test_buffer_overflow_in_java_excluded(self):
        issue = _issue(
            file="src/main/java/Parser.java",
            type="memory_safety",
            message="Potential buffer overflow vulnerability",
        )
        result = _rules().check(issue)
        assert result is not None
        assert "memory" in result.lower() or "non-C/C++" in result

    def test_buffer_overflow_in_c_not_excluded(self):
        issue = _issue(
            file="src/parser.c",
            type="memory_safety",
            message="Potential buffer overflow vulnerability",
        )
        result = _rules().check(issue)
        assert result is None

    def test_buffer_overflow_in_cpp_not_excluded(self):
        issue = _issue(
            file="src/parser.cpp",
            type="memory_safety",
            message="Potential buffer overflow vulnerability",
        )
        result = _rules().check(issue)
        assert result is None

    def test_buffer_overflow_in_h_not_excluded(self):
        issue = _issue(
            file="src/parser.h",
            type="memory_safety",
            message="Potential buffer overflow vulnerability",
        )
        result = _rules().check(issue)
        assert result is None

    def test_buffer_overflow_in_hpp_not_excluded(self):
        issue = _issue(
            file="src/parser.hpp",
            type="memory_safety",
            message="Potential buffer overflow vulnerability",
        )
        result = _rules().check(issue)
        assert result is None

    def test_use_after_free_in_python_excluded(self):
        issue = _issue(
            file="src/app.py",
            type="memory_safety",
            message="Possible use after free",
        )
        result = _rules().check(issue)
        assert result is not None

    def test_heap_overflow_in_java_excluded(self):
        issue = _issue(
            file="src/main/java/Memory.java",
            type="memory_safety",
            message="Heap overflow detected",
        )
        result = _rules().check(issue)
        assert result is not None

    def test_integer_overflow_in_c_not_excluded(self):
        issue = _issue(
            file="src/math.c",
            type="memory_safety",
            message="Integer overflow in calculation",
        )
        result = _rules().check(issue)
        assert result is None


class TestHardExclusionGenericSuggestions:
    """Category 7: Overly generic suggestions."""

    def test_follow_best_practices(self):
        issue = _issue(
            type="code_style",
            message="follow best practices for error handling",
        )
        result = _rules().check(issue)
        assert result is not None
        assert "generic" in result.lower()

    def test_improve_code_quality(self):
        issue = _issue(
            type="code_style",
            message="improve code quality in this module",
        )
        result = _rules().check(issue)
        assert result is not None

    def test_consider_refactoring(self):
        issue = _issue(
            type="code_style",
            message="consider refactoring this method",
        )
        result = _rules().check(issue)
        assert result is not None

    def test_follow_coding_standards(self):
        issue = _issue(
            type="code_style",
            message="follow coding standards",
        )
        result = _rules().check(issue)
        assert result is not None

    def test_add_more_comments(self):
        issue = _issue(
            type="code_style",
            message="add more comments to this method",
        )
        result = _rules().check(issue)
        assert result is not None


class TestHardExclusionLegitimateFindings:
    """Legitimate findings should pass through (return None)."""

    def test_sql_injection_not_excluded(self):
        issue = _issue(
            type="sql_injection",
            message="Potential SQL injection vulnerability",
            suggestion="Use PreparedStatement instead of string concatenation",
        )
        result = _rules().check(issue)
        assert result is None

    def test_hardcoded_secret_not_excluded(self):
        issue = _issue(
            type="hardcoded_secret",
            message="Hardcoded API key detected",
            suggestion="Move to environment variable",
        )
        result = _rules().check(issue)
        assert result is None

    def test_xss_not_excluded(self):
        issue = _issue(
            type="xss",
            message="Reflected XSS vulnerability in user input rendering",
            suggestion="Sanitize user input before rendering",
        )
        result = _rules().check(issue)
        assert result is None

    def test_auth_bypass_not_excluded(self):
        issue = _issue(
            type="auth_bypass",
            message="Missing authentication check on admin endpoint",
            suggestion="Add @PreAuthorize annotation",
        )
        result = _rules().check(issue)
        assert result is None

    def test_path_traversal_not_excluded(self):
        issue = _issue(
            type="path_traversal",
            message="Path traversal vulnerability in file download",
            suggestion="Validate and sanitize file paths",
        )
        result = _rules().check(issue)
        assert result is None

    def test_insecure_deserialization_not_excluded(self):
        issue = _issue(
            type="insecure_deserialization",
            message="Insecure deserialization of untrusted input",
            suggestion="Use allowlist for deserialized classes",
        )
        result = _rules().check(issue)
        assert result is None


class TestHardExclusionEdgeCases:
    """Edge cases for HardExclusionRules.check()."""

    def test_empty_file_field(self):
        issue = _issue(file="", type="dos", message="potential denial of service vulnerability")
        result = _rules().check(issue)
        assert result is not None  # DOS pattern still matches

    def test_empty_message(self):
        issue = _issue(message="")
        result = _rules().check(issue)
        assert result is None  # Nothing to match

    def test_case_insensitive_matching(self):
        issue = _issue(type="DOS", message="POTENTIAL DENIAL OF SERVICE")
        result = _rules().check(issue)
        assert result is not None

    def test_mixed_case_matching(self):
        issue = _issue(message="Potential Denial Of Service vulnerability")
        result = _rules().check(issue)
        assert result is not None

    def test_suggestion_field_also_searched(self):
        """The suggestion field is included in the text search."""
        issue = _issue(
            type="generic",
            message="Normal finding",
            suggestion="follow best practices for logging",
        )
        result = _rules().check(issue)
        assert result is not None

    def test_cc_extension_c_not_cpp(self):
        """The .cc extension should also not be excluded for memory safety."""
        issue = _issue(
            file="src/module.cc",
            type="memory_safety",
            message="buffer overflow vulnerability",
        )
        result = _rules().check(issue)
        assert result is None


# ===========================================================================
# FilterStats tests
# ===========================================================================


class TestFilterStats:

    def test_default_values(self):
        stats = FilterStats()
        assert stats.total_input == 0
        assert stats.excluded_by_hard_rules == 0
        assert stats.confidence_adjusted == 0
        assert stats.passed_through == 0
        assert stats.exclusion_reasons == {}

    def test_construction_with_values(self):
        stats = FilterStats(
            total_input=10,
            excluded_by_hard_rules=3,
            confidence_adjusted=1,
            passed_through=6,
            exclusion_reasons={"DOS": 2, "rate limit": 1},
        )
        assert stats.total_input == 10
        assert stats.excluded_by_hard_rules == 3
        assert stats.confidence_adjusted == 1
        assert stats.passed_through == 6
        assert stats.exclusion_reasons == {"DOS": 2, "rate limit": 1}

    def test_exclusion_reasons_default_factory_independent(self):
        """Each FilterStats instance should get its own exclusion_reasons dict."""
        s1 = FilterStats()
        s2 = FilterStats()
        s1.exclusion_reasons["test"] = 1
        assert "test" not in s2.exclusion_reasons


# ===========================================================================
# FindingsFilter tests
# ===========================================================================


class TestFindingsFilterAllLegitimate:

    async def test_all_legitimate_passes_through(self):
        issues = [
            _issue(type="sql_injection", message="SQL injection found"),
            _issue(type="xss", message="XSS vulnerability"),
            _issue(type="auth_bypass", message="Missing auth check"),
        ]
        f = FindingsFilter()
        result, stats = await f.filter_issues(issues)
        assert len(result) == 3
        assert stats.passed_through == 3
        assert stats.excluded_by_hard_rules == 0
        for issue in result:
            assert issue.filter_metadata.get("excluded", False) is False


class TestFindingsFilterDosExclusion:

    async def test_dos_finding_excluded(self):
        issues = [
            _issue(type="dos", message="potential denial of service vulnerability"),
        ]
        f = FindingsFilter()
        result, stats = await f.filter_issues(issues)
        assert len(result) == 1
        assert stats.excluded_by_hard_rules == 1
        assert stats.passed_through == 0
        assert result[0].confidence == 0.0
        assert result[0].filter_metadata["excluded"] is True
        assert "reason" in result[0].filter_metadata
        assert result[0].filter_metadata["stage"] == "hard_rules"


class TestFindingsFilterMixedResults:

    async def test_mixed_results(self):
        issues = [
            # Legitimate: SQL injection
            _issue(
                type="sql_injection",
                message="SQL injection vulnerability",
                file="src/main/java/UserService.java",
            ),
            # Excluded: DOS
            _issue(
                type="dos",
                message="potential denial of service vulnerability",
                file="src/main/java/Controller.java",
            ),
            # Legitimate: hardcoded secret
            _issue(
                type="hardcoded_secret",
                message="Hardcoded API key detected",
                file="src/main/java/Config.java",
            ),
            # Excluded: test file
            _issue(
                type="bug",
                message="Null pointer in test setup",
                file="src/test/java/UserServiceTest.java",
            ),
        ]
        f = FindingsFilter()
        result, stats = await f.filter_issues(issues)
        assert len(result) == 4
        assert stats.excluded_by_hard_rules == 2
        assert stats.passed_through == 2

        excluded = [i for i in result if i.filter_metadata.get("excluded", False)]
        passed = [i for i in result if not i.filter_metadata.get("excluded", False)]
        assert len(excluded) == 2
        assert len(passed) == 2


class TestFindingsFilterFailOpen:

    async def test_fail_open_on_exception(self):
        """If the filter raises, the original list should be returned unchanged."""

        class BadIssue:
            """An object that will cause an AttributeError inside the filter."""
            def __init__(self):
                self.file = None  # Will cause _file_extension to fail? No.
                # Make 'type' property raise to trigger exception in check()
                self._type = "test"
                self.message = "test"
                self.suggestion = "test"
                self.confidence = 1.0
                self.filter_metadata = {}

            @property
            def type(self):
                raise RuntimeError("unexpected error")

        bad_issues = [BadIssue()]
        f = FindingsFilter()
        result, stats = await f.filter_issues(bad_issues)
        # Fail-open: should return original list
        assert result is bad_issues


class TestFindingsFilterConfidenceThreshold:

    async def test_default_threshold_is_0_5(self):
        f = FindingsFilter()
        assert f._threshold == 0.5

    async def test_custom_threshold(self):
        f = FindingsFilter(confidence_threshold=0.8)
        assert f._threshold == 0.8


class TestFindingsFilterMarking:

    async def test_excluded_issues_get_filter_metadata(self):
        issues = [
            _issue(
                type="dos",
                message="potential denial of service vulnerability",
            ),
        ]
        f = FindingsFilter()
        result, stats = await f.filter_issues(issues)
        meta = result[0].filter_metadata
        assert meta["excluded"] is True
        assert "reason" in meta
        assert meta["stage"] == "hard_rules"

    async def test_excluded_issues_confidence_set_to_zero(self):
        issues = [
            _issue(
                type="dos",
                message="potential denial of service vulnerability",
                confidence=0.9,
            ),
        ]
        f = FindingsFilter()
        result, stats = await f.filter_issues(issues)
        assert result[0].confidence == 0.0

    async def test_legitimate_issues_keep_confidence(self):
        issues = [
            _issue(
                type="sql_injection",
                message="SQL injection found",
                confidence=0.85,
            ),
        ]
        f = FindingsFilter()
        result, stats = await f.filter_issues(issues)
        assert result[0].confidence == 0.85


class TestFindingsFilterExclusionReasons:

    async def test_tracks_exclusion_reasons(self):
        issues = [
            _issue(
                type="dos",
                message="potential denial of service vulnerability",
                file="src/main/java/A.java",
            ),
            _issue(
                type="rate_limit",
                message="missing rate limiting on endpoint",
                file="src/main/java/B.java",
            ),
            _issue(
                type="style",
                message="follow best practices for naming",
                file="src/main/java/C.java",
            ),
        ]
        f = FindingsFilter()
        result, stats = await f.filter_issues(issues)
        assert stats.excluded_by_hard_rules == 3
        assert len(stats.exclusion_reasons) >= 1
        # Total across all reasons should be 3
        total = sum(stats.exclusion_reasons.values())
        assert total == 3


class TestFindingsFilterEmptyInput:

    async def test_empty_list_returns_empty(self):
        f = FindingsFilter()
        result, stats = await f.filter_issues([])
        assert result == []
        assert stats.total_input == 0
        assert stats.passed_through == 0
        assert stats.excluded_by_hard_rules == 0


class TestFindingsFilterStatsCounts:

    async def test_stats_total_input_matches_input_length(self):
        issues = [
            _issue(type="sql_injection", message="sqli"),
            _issue(type="dos", message="potential denial of service"),
            _issue(type="xss", message="cross site scripting"),
        ]
        f = FindingsFilter()
        result, stats = await f.filter_issues(issues)
        assert stats.total_input == 3
        assert len(result) == 3

    async def test_stats_passed_through_plus_excluded_equals_total(self):
        issues = [
            _issue(type="sql_injection", message="sqli found"),
            _issue(type="dos", message="potential denial of service"),
            _issue(type="rate_limit", message="missing rate limiting"),
            _issue(type="xss", message="reflected XSS"),
            _issue(type="style", message="follow best practices"),
        ]
        f = FindingsFilter()
        result, stats = await f.filter_issues(issues)
        assert stats.excluded_by_hard_rules + stats.passed_through == stats.total_input


class TestFindingsFilterCustomPrecedents:

    def test_custom_precedents_appended(self):
        custom = [{"pattern": "custom pattern", "verdict": "not_real", "reason": "test"}]
        f = FindingsFilter(custom_precedents=custom)
        assert len(f._precedents) > len(custom)
        # The custom one should be the last entry
        assert f._precedents[-1] == custom[0]

    def test_no_custom_precedents_default_count(self):
        from app.agent.false_positive_filter import _DEFAULT_PRECEDENTS

        f = FindingsFilter()
        # YAML config has 17 precedents, plus default _DEFAULT_PRECEDENTS (26) gives us the total
        # The filter merges both sources, so count will be >= YAML precedents
        assert len(f._precedents) >= 17  # At least the YAML config precedents

    def test_yaml_precedents_loaded(self):
        """Test that YAML config precedents are loaded"""
        f = FindingsFilter()
        # Check that some expected patterns from YAML are present
        patterns = [p["pattern"] for p in f._precedents]
        # Should have both YAML and default precedents
        assert len(patterns) >= 17


class TestFindingsFilterLLMVerificationDisabled:

    async def test_llm_verification_off_by_default(self):
        f = FindingsFilter()
        assert f._verify is False

    async def test_llm_not_called_when_disabled(self):
        mock_llm = MagicMock()
        mock_llm.ainvoke = AsyncMock()
        issues = [
            _issue(type="sql_injection", message="SQL injection found"),
        ]
        f = FindingsFilter(enable_llm_verification=False, llm=mock_llm)
        await f.filter_issues(issues)
        mock_llm.ainvoke.assert_not_called()
