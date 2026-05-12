"""Two-stage false positive filter for code review findings.

Stage 1: HardExclusionRules – deterministic regex-based filtering (fast, cheap).
Stage 2: Optional LLM verification – per-finding AI analysis (expensive, opt-in).

Rules are loaded from config/false_positive_rules.yaml with fallback to hardcoded defaults.
"""

from __future__ import annotations

import json
import logging
import re
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

from app.models.schemas import IssuePayload

logger = logging.getLogger(__name__)

# ---------------------------------------------------------------------------
# Default rules (fallback when YAML config not available)
# ---------------------------------------------------------------------------

_DEFAULT_DOS_PATTERNS: list[re.Pattern] = [
    re.compile(r"potential\s+denial\s+of\s+service", re.IGNORECASE),
    re.compile(r"dos\s+attack", re.IGNORECASE),
    re.compile(r"resource\s+exhaustion", re.IGNORECASE),
    re.compile(r"exhaust|overwhelm|overload\s+(?:resource|memory|cpu)", re.IGNORECASE),
    re.compile(r"unbounded\s+(?:growth|loop|recursion)", re.IGNORECASE),
]

_DEFAULT_RATE_LIMIT_PATTERNS: list[re.Pattern] = [
    re.compile(r"consider\s+adding\s+rate\s+limit", re.IGNORECASE),
    re.compile(r"implement\s+rate\s+limit", re.IGNORECASE),
    re.compile(r"missing\s+rate\s+limit", re.IGNORECASE),
    re.compile(r"no\s+rate\s+limit", re.IGNORECASE),
]

_DEFAULT_GENERIC_PERF_PATTERNS: list[re.Pattern] = [
    re.compile(r"consider\s+(?:adding|using|implementing)\s+(?:a\s+)?caching", re.IGNORECASE),
    re.compile(r"add\s+(?:a\s+)?caching\s+layer", re.IGNORECASE),
    re.compile(r"consider\s+(?:using|adding)\s+connection\s+pool", re.IGNORECASE),
    re.compile(r"use\s+(?:a\s+)?connection\s+pool", re.IGNORECASE),
]

_DEFAULT_MEMORY_SAFETY_PATTERNS: list[re.Pattern] = [
    re.compile(r"buffer\s+overflow", re.IGNORECASE),
    re.compile(r"use[\s-]?after[\s-]?free", re.IGNORECASE),
    re.compile(r"double[\s-]?free", re.IGNORECASE),
    re.compile(r"stack\s+overflow", re.IGNORECASE),
    re.compile(r"heap\s+overflow", re.IGNORECASE),
    re.compile(r"out[\s-]?of[\s-]?bounds\s+(?:read|write|access)", re.IGNORECASE),
    re.compile(r"integer\s+overflow", re.IGNORECASE),
    re.compile(r"memory\s+(?:corruption|safety)", re.IGNORECASE),
]

_DEFAULT_OPEN_REDIRECT_PATTERNS: list[re.Pattern] = [
    re.compile(r"\b(open redirect|unvalidated redirect)\b", re.IGNORECASE),
]

_DEFAULT_SSRF_PATTERNS: list[re.Pattern] = [
    re.compile(r"\b(ssrf|server\s+.?side\s+.?request\s+.?forgery)\b", re.IGNORECASE),
]

_DEFAULT_REGEX_INJECTION_PATTERNS: list[re.Pattern] = [
    re.compile(r"\b(regex|regular expression)\s+injection\b", re.IGNORECASE),
    re.compile(r"\b(regex|regular expression)\s+denial of service\b", re.IGNORECASE),
]

_DEFAULT_GENERIC_SUGGESTION_PATTERNS: list[re.Pattern] = [
    re.compile(r"follow\s+best\s+practices?", re.IGNORECASE),
    re.compile(r"improve\s+code\s+quality", re.IGNORECASE),
    re.compile(r"consider\s+refactoring", re.IGNORECASE),
    re.compile(r"follow\s+(?:coding|code)\s+standards?", re.IGNORECASE),
    re.compile(r"this\s+(?:code|method|function)\s+could\s+be\s+improved", re.IGNORECASE),
    re.compile(r"add\s+(?:more\s+)?(?:comments|documentation)", re.IGNORECASE),
]

_DEFAULT_DOC_EXTENSIONS: tuple[str, ...] = (
    ".md", ".txt", ".rst", ".adoc", ".tex", ".org",
)

_DEFAULT_TEST_FILE_EXTENSIONS: tuple[str, ...] = (
    "Test.java", "Tests.java", "Spec.java", "IT.java",
    "_test.py", "test_", "Test.kt", "Tests.kt",
    ".test.ts", ".test.tsx", ".spec.ts", ".spec.tsx",
)

_DEFAULT_C_CPP_EXTENSIONS: frozenset[str] = frozenset({".c", ".cc", ".cpp", ".h", ".hpp"})

_DEFAULT_CLIENT_SIDE_EXTENSIONS: frozenset[str] = frozenset({
    ".js", ".ts", ".tsx", ".jsx"
})

# Default precedents (language/framework specific false positive patterns)
_DEFAULT_PRECEDENTS: list[dict[str, str]] = [
    {"pattern": "JPA @Query with :named parameters", "verdict": "not_sql_injection",
     "reason": "JPA @Query with :param uses parameterized queries internally"},
    {"pattern": "Spring @Value with ${...} placeholder", "verdict": "not_hardcoded_secret",
     "reason": "Spring property placeholder references external config"},
    {"pattern": "PreparedStatement with parameter binding", "verdict": "not_sql_injection",
     "reason": "PreparedStatement uses parameterized queries"},
    {"pattern": "MyBatis #{param} syntax", "verdict": "not_sql_injection",
     "reason": "MyBatis #{} uses parameterized binding; only ${} is unsafe"},
    {"pattern": "Spring Security @PreAuthorize / @Secured annotation", "verdict": "not_missing_auth",
     "reason": "Method-level security annotations provide authorization"},
    {"pattern": "Log statement with structured logging", "verdict": "not_log_injection",
     "reason": "Structured logging frameworks handle sanitization"},
    {"pattern": "UUID usage as identifier", "verdict": "not_predictable_id",
     "reason": "UUIDs are unguessable"},
    {"pattern": "Environment variable or config property reference", "verdict": "not_hardcoded",
     "reason": "Environment variables and config properties are trusted input"},
    {"pattern": "try-with-resources in Java", "verdict": "not_resource_leak",
     "reason": "try-with-resources guarantees resource cleanup"},
    {"pattern": "Spring @Transactional on service method", "verdict": "not_missing_transaction",
     "reason": "Annotation-based transaction management is active"},
    # Claude 官方新增的 17 条 precedents
    {"pattern": "Logging high value secrets in plaintext is a vulnerability", "verdict": "not_log_secret",
     "reason": "Only report secrets logged in plaintext"},
    {"pattern": "UUIDs can be assumed to be unguessable", "verdict": "not_uuid_guess",
     "reason": "If a vulnerability requires guessing a UUID, it is not a valid vulnerability"},
    {"pattern": "Audit logs are not a critical security feature", "verdict": "not_missing_audit",
     "reason": "Audit logs are not critical security feature"},
    {"pattern": "Environment variables and CLI flags are trusted values", "verdict": "not_env_injection",
     "reason": "Attackers cannot modify environment variables in a secure environment"},
    {"pattern": "React is generally secure against XSS", "verdict": "not_react_xss",
     "reason": "React escapes content by default unless using dangerouslySetInnerHTML"},
    {"pattern": "Most GitHub Action workflow vulnerabilities are not exploitable", "verdict": "not_action_vuln",
     "reason": "GitHub Action workflow vulnerabilities require concrete attack path"},
    {"pattern": "Client-side permission checking is not required", "verdict": "not_client_auth",
     "reason": "Client-side code is not trusted; server is responsible for validation"},
    {"pattern": "Most IPython notebook vulnerabilities are not exploitable", "verdict": "not_ipynb_vuln",
     "reason": "Notebook vulnerabilities require concrete attack path"},
    {"pattern": "Logging non-PII data is not a vulnerability", "verdict": "not_log_pii",
     "reason": "Only report logging of secrets, passwords, or PII"},
    {"pattern": "Command injection in shell scripts requires untrusted input", "verdict": "not_shell_injection",
     "reason": "Shell scripts generally don't run with untrusted user input"},
    {"pattern": "Client-side SSRF is not exploitable", "verdict": "not_client_ssrf",
     "reason": "Client-side JS cannot bypass firewalls"},
    {"pattern": "Path traversal via ../ is not a problem for HTTP requests", "verdict": "not_http_path_traversal",
     "reason": "Path traversal via ../ only matters when reading files"},
    {"pattern": "Injecting into log queries is generally not an issue", "verdict": "not_log_query_injection",
     "reason": "Only report if injection will definitely expose sensitive data"},
    {"pattern": "Lacking hardening measures is not a vulnerability", "verdict": "not_missing_hardening",
     "reason": "Code is not expected to implement all security best practices"},
    {"pattern": "Race conditions are theoretical unless extremely problematic", "verdict": "not_race_condition",
     "reason": "Only report race conditions if extremely problematic"},
    {"pattern": "Outdated third-party libraries are managed separately", "verdict": "not_outdated_dep",
     "reason": "Outdated libraries are managed separately from code review"},
]


# ---------------------------------------------------------------------------
# Rule Loader
# ---------------------------------------------------------------------------

class _RuleLoader:
    """Loads false positive rules from YAML config file."""

    def __init__(self, config_path: str | None = None):
        if config_path:
            self._path = Path(config_path)
        else:
            # Default to config/false_positive_rules.yaml relative to this file
            self._path = Path(__file__).parent.parent.parent / "config" / "false_positive_rules.yaml"

        self._config: dict | None = None
        self._load()

    def _load(self) -> None:
        """Load rules from YAML file."""
        try:
            if self._path.exists():
                with open(self._path, encoding="utf-8") as f:
                    self._config = yaml.safe_load(f)
                logger.info("Loaded false positive rules from %s", self._path)
            else:
                logger.warning("Rule config not found at %s, using defaults", self._path)
                self._config = None
        except Exception as e:
            logger.warning("Failed to load rule config: %s, using defaults", e)
            self._config = None

    def get_exclusion_rules(self) -> list[dict]:
        """Get exclusion rules from config."""
        if self._config and "exclusion_rules" in self._config:
            return self._config["exclusion_rules"]
        return []

    def get_precedents(self) -> list[dict[str, str]]:
        """Get precedent rules from config."""
        if self._config and "precedents" in self._config:
            # Convert YAML format to our internal format
            return [
                {"pattern": p.get("pattern", ""),
                 "verdict": p.get("verdict", ""),
                 "reason": p.get("reason", "")}
                for p in self._config["precedents"]
            ]
        return _DEFAULT_PRECEDENTS


# ---------------------------------------------------------------------------
# Hard Exclusion Rules
# ---------------------------------------------------------------------------

def _file_extension(file_path: str) -> str:
    idx = file_path.rfind(".")
    return file_path[idx:].lower() if idx >= 0 else ""


def _get_file_name_lower(file_path: str) -> str:
    """Get lowercase filename for pattern matching."""
    import os
    return os.path.basename(file_path).lower()


class HardExclusionRules:
    """Deterministic regex-based false positive pre-filter.

    Supports loading rules from YAML config with fallback to defaults.
    """

    def __init__(self, rule_loader: _RuleLoader | None = None):
        self._loader = rule_loader or _RuleLoader()
        self._rules = self._loader.get_exclusion_rules()
        self._precedents = self._loader.get_precedents()

        # Compile patterns from config
        self._compiled_rules = self._compile_rules()

    def _compile_rules(self) -> dict:
        """Compile exclusion rules into efficient structures."""
        compiled = {
            "text_patterns": [],  # list of (re.Pattern, description)
            "doc_extensions": set(_DEFAULT_DOC_EXTENSIONS),
            "test_patterns": [],   # compiled test file patterns
            "client_side_ssrf": True,  # whether to apply client-side SSRF rule
            "memory_safety_exclude_cpp": True,  # exclude memory safety for non-C/C++
            "cpp_extensions": _DEFAULT_C_CPP_EXTENSIONS,
        }

        # Add patterns from YAML rules
        for rule in self._rules:
            rule_id = rule.get("id", "")

            if "patterns" in rule:
                for pattern in rule["patterns"]:
                    try:
                        compiled["text_patterns"].append(
                            (re.compile(pattern, re.IGNORECASE), rule.get("description", ""))
                        )
                    except re.error:
                        logger.warning("Invalid regex pattern in rule %s: %s", rule_id, pattern)

            # File extensions
            if "applies_to_files" in rule:
                for file_spec in rule["applies_to_files"]:
                    # Handle 'extensions' field (list of extensions like [".md", ".txt"])
                    if "extensions" in file_spec:
                        for ext in file_spec["extensions"]:
                            if ext.startswith("."):
                                compiled["doc_extensions"].add(ext.lower())
                            else:
                                # It's a filename pattern, compile it
                                try:
                                    pattern = re.compile(file_spec["pattern"], re.IGNORECASE)
                                    compiled["test_patterns"].append((pattern, rule.get("description", "")))
                                except re.error:
                                    pass
                    # Handle 'patterns' field (list of filename patterns)
                    if "patterns" in file_spec:
                        for pattern_str in file_spec["patterns"]:
                            try:
                                pattern = re.compile(pattern_str, re.IGNORECASE)
                                compiled["test_patterns"].append((pattern, rule.get("description", "")))
                            except re.error as e:
                                logger.warning("Invalid test pattern '%s': %s", pattern_str, e)

            # Memory safety C/C++ exclusion
            if "applies_to_extensions" in rule:
                if "exclude" in rule["applies_to_extensions"]:
                    if "memory-safety" in rule_id:
                        compiled["memory_safety_exclude_cpp"] = True

        # Add default patterns if not already present
        for pattern, desc in [
            (_DEFAULT_DOS_PATTERNS, "Generic DOS/resource exhaustion finding"),
            (_DEFAULT_RATE_LIMIT_PATTERNS, "Generic rate limiting suggestion"),
            (_DEFAULT_GENERIC_PERF_PATTERNS, "Generic performance suggestion without specifics"),
            (_DEFAULT_MEMORY_SAFETY_PATTERNS, "Memory safety finding in non-C/C++ code"),
            (_DEFAULT_OPEN_REDIRECT_PATTERNS, "Open redirect vulnerability (low impact)"),
            (_DEFAULT_SSRF_PATTERNS, "SSRF in client-side code"),
            (_DEFAULT_REGEX_INJECTION_PATTERNS, "Regex injection finding"),
            (_DEFAULT_GENERIC_SUGGESTION_PATTERNS, "Overly generic suggestion without actionable specifics"),
        ]:
            compiled["text_patterns"].extend([(p, desc) for p in pattern])

        # Default test file patterns
        for suffix in _DEFAULT_TEST_FILE_EXTENSIONS:
            try:
                pattern = re.compile(re.escape(suffix) + r"$", re.IGNORECASE)
                compiled["test_patterns"].append((pattern, "Finding in test file"))
            except re.error:
                pass

        return compiled

    def check(self, issue: IssuePayload) -> str | None:
        """Check if an issue should be excluded as a false positive."""
        file_path = issue.file or ""
        ext = _file_extension(file_path)
        file_name = _get_file_name_lower(file_path)
        text = f"{issue.type} {issue.message} {issue.suggestion}".lower()

        # Check doc files
        if ext in self._compiled_rules["doc_extensions"]:
            return "Finding in documentation file"

        # Check test files
        for pattern, desc in self._compiled_rules["test_patterns"]:
            if pattern.search(file_name) or pattern.search(file_path):
                return desc or "Finding in test file"

        # Check text patterns
        for pattern, desc in self._compiled_rules["text_patterns"]:
            if pattern.search(text):
                # Special handling for memory safety: only exclude for non-C/C++
                if "memory safety" in desc.lower():
                    if ext not in self._compiled_rules["cpp_extensions"]:
                        return desc
                    continue  # Don't exclude C/C++ memory safety issues
                return desc

        # Check client-side SSRF
        if ext in _DEFAULT_CLIENT_SIDE_EXTENSIONS:
            if any(p.search(text) for p in _DEFAULT_SSRF_PATTERNS):
                return "SSRF in client-side code (not exploitable)"

        return None

    def get_precedents(self) -> list[dict[str, str]]:
        """Get list of known false positive precedents."""
        return self._precedents


# ---------------------------------------------------------------------------
# LLM Verification prompt templates
# ---------------------------------------------------------------------------

_LLM_VERIFICATION_SYSTEM = (
    "You are a senior code reviewer evaluating whether a specific finding "
    "from an automated code review is a genuine issue or a false positive.\n"
    "Respond ONLY with valid JSON: {\"is_real\": bool, \"confidence\": float(0-1), "
    "\"reasoning\": string}\n"
    "No markdown, no code blocks, no extra text."
)

_LLM_VERIFICATION_USER = """\
## Precedents (known false positive patterns):
{precedents}

## Finding to evaluate:
- Severity: {severity}
- File: {file}
- Line: {line}
- Type: {type}
- Message: {message}
- Suggestion: {suggestion}

## Code diff context:
{diff_context}

Is this finding a genuine issue that should be reported to the developer?
"""


@dataclass
class FilterStats:
    total_input: int = 0
    excluded_by_hard_rules: int = 0
    confidence_adjusted: int = 0
    passed_through: int = 0
    exclusion_reasons: dict[str, int] = field(default_factory=dict)


class FindingsFilter:
    """Two-stage false positive filter: hard rules -> optional LLM verification."""

    def __init__(
        self,
        confidence_threshold: float = 0.5,
        enable_llm_verification: bool = False,
        llm: Any = None,
        diff_context: str = "",
        custom_precedents: list[dict[str, str]] | None = None,
        rule_config_path: str | None = None,
    ) -> None:
        self._threshold = confidence_threshold
        self._verify = enable_llm_verification
        self._llm = llm
        self._diff_context = diff_context

        # Initialize rules from config or defaults
        rule_loader = _RuleLoader(rule_config_path) if rule_config_path else _RuleLoader()
        self._hard_rules = HardExclusionRules(rule_loader)

        # Merge precedents
        self._precedents = list(self._hard_rules.get_precedents())
        if custom_precedents:
            self._precedents.extend(custom_precedents)

    async def filter_issues(
        self, issues: list[IssuePayload]
    ) -> tuple[list[IssuePayload], FilterStats]:
        stats = FilterStats(total_input=len(issues))
        try:
            return await self._do_filter(issues, stats)
        except Exception:
            logger.warning("False positive filter failed, keeping all issues", exc_info=True)
            stats.passed_through = len(issues)
            return issues, stats

    async def _do_filter(
        self, issues: list[IssuePayload], stats: FilterStats
    ) -> tuple[list[IssuePayload], FilterStats]:
        for issue in issues:
            reason = self._hard_rules.check(issue)
            if reason is not None:
                stats.excluded_by_hard_rules += 1
                stats.exclusion_reasons[reason] = stats.exclusion_reasons.get(reason, 0) + 1
                issue.confidence = 0.0
                issue.filter_metadata = {"excluded": True, "reason": reason, "stage": "hard_rules"}

        if self._verify and self._llm is not None:
            issues = await self._llm_verify_stage(issues, stats)

        stats.passed_through = sum(
            1 for i in issues if not i.filter_metadata.get("excluded", False)
        )
        return issues, stats

    async def _llm_verify_stage(
        self, issues: list[IssuePayload], stats: FilterStats
    ) -> list[IssuePayload]:
        from app.agent.llm_utils import invoke_with_retry
        from langchain_core.messages import HumanMessage, SystemMessage

        precedents_text = "\n".join(
            f"- {p['pattern']}: {p['reason']}" for p in self._precedents
        )

        for issue in issues:
            if issue.filter_metadata.get("excluded", False):
                continue

            try:
                prompt = _LLM_VERIFICATION_USER.format(
                    precedents=precedents_text,
                    severity=issue.severity,
                    file=issue.file,
                    line=issue.line or 0,
                    type=issue.type,
                    message=issue.message,
                    suggestion=issue.suggestion,
                    diff_context=self._diff_context[:2000],
                )

                response = await invoke_with_retry(
                    self._llm,
                    [SystemMessage(content=_LLM_VERIFICATION_SYSTEM),
                     HumanMessage(content=prompt)]
                )

                text = response.content if hasattr(response, "content") else str(response)
                parsed = json.loads(text.strip().removeprefix("```json").removesuffix("```").strip())

                is_real = parsed.get("is_real", True)
                llm_confidence = float(parsed.get("confidence", 0.8))

                if not is_real:
                    reason = parsed.get("reasoning", "LLM classified as false positive")
                    stats.exclusion_reasons["LLM: " + reason[:80]] = (
                        stats.exclusion_reasons.get("LLM: " + reason[:80], 0) + 1
                    )
                    issue.confidence = 0.0
                    issue.filter_metadata = {
                        "excluded": True,
                        "reason": reason,
                        "stage": "llm_verification",
                    }
                else:
                    issue.confidence = llm_confidence
                    if llm_confidence < self._threshold:
                        stats.confidence_adjusted += 1
                        issue.filter_metadata = {
                            "excluded": True,
                            "reason": f"Below confidence threshold ({llm_confidence:.2f} < {self._threshold})",
                            "stage": "confidence_threshold",
                        }

            except Exception:
                logger.warning("LLM verification failed for issue, keeping it: %s", issue.message[:80])

        return issues