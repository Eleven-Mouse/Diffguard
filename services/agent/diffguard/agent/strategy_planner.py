"""Diff profiler and strategy planner - ported from Java."""

from __future__ import annotations

import enum
import re
from dataclasses import dataclass, field

from app.models.schemas import DiffEntry


class FileCategory(enum.Enum):
    CONTROLLER = "controller"
    SERVICE = "service"
    DAO = "dao"
    MODEL = "model"
    CONFIG = "config"
    TEST = "test"
    UTILITY = "utility"
    OTHER = "other"


class RiskLevel(enum.Enum):
    LOW = "low"
    MEDIUM = "medium"
    HIGH = "high"
    CRITICAL = "critical"


class AgentType(enum.Enum):
    SECURITY = "security"
    PERFORMANCE = "performance"
    ARCHITECTURE = "architecture"


@dataclass
class DiffProfile:
    total_files: int = 0
    total_lines: int = 0
    category_distribution: dict[FileCategory, int] = field(default_factory=dict)
    has_database_operations: bool = False
    has_security_sensitive_code: bool = False
    has_concurrency_code: bool = False
    has_external_api_calls: bool = False
    overall_risk: RiskLevel = RiskLevel.LOW


@dataclass
class ReviewStrategy:
    name: str = "default"
    description: str = ""
    agent_weights: dict[AgentType, float] = field(default_factory=dict)
    focus_areas: list[str] = field(default_factory=list)
    additional_rules: list[str] = field(default_factory=list)

    def get_enabled_agents(self) -> list[AgentType]:
        return [at for at, w in self.agent_weights.items() if w > 0]


# --- Pattern matching ---

_FILE_CATEGORY_PATTERNS: list[tuple[re.Pattern, FileCategory]] = [
    (re.compile(r"(Controller|Resource|Endpoint|Api)\.java$", re.I), FileCategory.CONTROLLER),
    (re.compile(r"(Service|ServiceImpl|Manager|Handler|Processor)\.java$", re.I), FileCategory.SERVICE),
    (re.compile(r"(Repository|Dao|Mapper|Repo)\.java$", re.I), FileCategory.DAO),
    (re.compile(r"(Entity|Model|Dto|Vo|Request|Response|Bean)\.java$", re.I), FileCategory.MODEL),
    (re.compile(r"(Config|Configuration|Properties|Settings)\.java$", re.I), FileCategory.CONFIG),
    (re.compile(r"(Test|Spec|IT)\.java$", re.I), FileCategory.TEST),
    (re.compile(r"(Util|Utils|Helper|Constants|Converter)\.java$", re.I), FileCategory.UTILITY),
]

_DB_PATTERNS = re.compile(
    r"(JdbcTemplate|PreparedStatement|ResultSet|@Query|@Entity|@Table|"
    r"INSERT|UPDATE|DELETE|SELECT\s|executeQuery|executeUpdate|createQuery|"
    r"save\(|findById|findAll|findBy)",
    re.I,
)

_SECURITY_PATTERNS = re.compile(
    r"(@PreAuthorize|@Secured|@RolesAllowed|Authentication|Principal|"
    r"Password|Secret|Token|JWT|OAuth|Cookie|Session|"
    r"encrypt|decrypt|hash|cipher|XSS|CSRF|injection|sanitize)",
    re.I,
)

_CONCURRENCY_PATTERNS = re.compile(
    r"(synchronized|volatile|AtomicInteger|AtomicLong|ConcurrentHashMap|"
    r"@Lock|ReentrantLock|Semaphore|CountDownLatch|CompletableFuture|"
    r"ThreadPool|ExecutorService|@Async|Thread\.|runnable)",
    re.I,
)

_API_PATTERNS = re.compile(
    r"(@RequestMapping|@GetMapping|@PostMapping|@PutMapping|@DeleteMapping|"
    r"RestTemplate|WebClient|HttpClient|OkHttp|Feign|fetch\(|axios|"
    r"ResponseBody|RequestBody|@PathVariable|@RequestParam)",
    re.I,
)


def profile(entries: list[DiffEntry]) -> DiffProfile:
    """Analyze diff entries to produce a DiffProfile."""
    p = DiffProfile(total_files=len(entries))
    categories: dict[FileCategory, int] = {}

    for entry in entries:
        content = entry.content
        p.total_lines += content.count("\n")

        # Classify file
        cat = FileCategory.OTHER
        for pattern, category in _FILE_CATEGORY_PATTERNS:
            if pattern.search(entry.file_path):
                cat = category
                break
        categories[cat] = categories.get(cat, 0) + 1

        # Detect keywords
        if _DB_PATTERNS.search(content):
            p.has_database_operations = True
        if _SECURITY_PATTERNS.search(content):
            p.has_security_sensitive_code = True
        if _CONCURRENCY_PATTERNS.search(content):
            p.has_concurrency_code = True
        if _API_PATTERNS.search(content):
            p.has_external_api_calls = True

    p.category_distribution = categories

    # Risk assessment
    risk_score = 0
    if p.has_database_operations:
        risk_score += 2
    if p.has_security_sensitive_code:
        risk_score += 2
    if p.has_concurrency_code:
        risk_score += 1
    if p.has_external_api_calls:
        risk_score += 1
    if p.total_files > 10:
        risk_score += 1

    if risk_score >= 5:
        p.overall_risk = RiskLevel.CRITICAL
    elif risk_score >= 3:
        p.overall_risk = RiskLevel.HIGH
    elif risk_score >= 1:
        p.overall_risk = RiskLevel.MEDIUM
    else:
        p.overall_risk = RiskLevel.LOW

    return p


def _primary_category(profile: DiffProfile) -> FileCategory:
    if not profile.category_distribution:
        return FileCategory.OTHER
    return max(profile.category_distribution, key=profile.category_distribution.get)


class StrategyPlanner:
    """Selects a review strategy based on the diff profile."""

    def plan(self, entries: list[DiffEntry]) -> ReviewStrategy:
        p = profile(entries)
        primary = _primary_category(p)

        weights: dict[AgentType, float] = {
            AgentType.SECURITY: 1.0,
            AgentType.PERFORMANCE: 1.0,
            AgentType.ARCHITECTURE: 1.0,
        }
        focus: list[str] = []
        rules: list[str] = []

        if primary == FileCategory.CONTROLLER:
            weights[AgentType.SECURITY] = 1.5
            weights[AgentType.ARCHITECTURE] = 1.3
            focus.extend(["Input validation", "Authentication/Authorization", "API design"])

        elif primary == FileCategory.DAO:
            weights[AgentType.SECURITY] = 2.0
            weights[AgentType.PERFORMANCE] = 1.5
            weights[AgentType.ARCHITECTURE] = 0.5
            focus.extend(["SQL injection", "Query performance", "Transaction handling"])

        elif primary == FileCategory.SERVICE:
            weights[AgentType.PERFORMANCE] = 1.3
            focus.extend(["Business logic correctness", "Error handling"])

        elif primary == FileCategory.CONFIG:
            weights[AgentType.SECURITY] = 2.5
            weights[AgentType.PERFORMANCE] = 0.3
            focus.extend(["Configuration security", "Secret exposure"])

        elif primary == FileCategory.MODEL:
            weights[AgentType.ARCHITECTURE] = 1.5
            weights[AgentType.SECURITY] = 0.8
            focus.extend(["Data integrity", "Serialization safety"])

        elif primary == FileCategory.TEST:
            weights = {at: 0.3 for at in AgentType}
            weights[AgentType.ARCHITECTURE] = 0.5
            focus.extend(["Test coverage", "Test quality"])

        # Adjust for risk level
        if p.overall_risk in (RiskLevel.HIGH, RiskLevel.CRITICAL):
            if p.has_database_operations:
                weights[AgentType.SECURITY] = weights.get(AgentType.SECURITY, 1.0) + 0.5
                focus.append("Database operation safety")
            if p.has_concurrency_code:
                weights[AgentType.PERFORMANCE] = weights.get(AgentType.PERFORMANCE, 1.0) + 0.5
                focus.append("Thread safety and concurrency")

        return ReviewStrategy(
            name=f"{primary.value}_review",
            description=f"Primary category: {primary.value}, Risk: {p.overall_risk.value}",
            agent_weights=weights,
            focus_areas=focus,
            additional_rules=rules,
        )
