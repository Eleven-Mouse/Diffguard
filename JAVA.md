# Java Coding Standards — DiffGuard Gateway

## Architecture

Follow hexagonal (ports & adapters) architecture strictly:
- `adapter/` — inbound adapters (webhook, tool server REST endpoints)
- `domain/` — pure business logic, zero external dependencies (JDK only)
- `infrastructure/` — outbound adapters (Git, config loading, external services)

Dependency flow: `adapter → domain ← infrastructure`. Domain never imports from adapter or infrastructure.

## Naming Conventions

- Classes: PascalCase (`WebhookServer`, `CodeGraphBuilder`)
- Methods: camelCase (`computeImpactSet`, `findShortestPath`)
- Constants: UPPER_SNAKE_CASE (`MAX_CACHE_SIZE`)
- Packages: lowercase, dot-separated (`com.diffguard.domain.ast`)

## Testing

- Unit tests mirror source structure under `src/test/java/`
- Use JUnit 5 + Mockito 5
- Test class naming: `<Class>Test.java` (`WebhookControllerTest.java`)
- Integration tests suffix: `<Class>IntegrationTest.java`

## Build

```bash
cd services/gateway
mvn -B verify
```

## Code Style

- 4-space indentation, no tabs
- Maximum line length: 120 characters
- Prefer immutability (final fields, records where appropriate)
- Use `Optional` instead of null for return types
