# AGENTS.md - graduation-project-be

## Project Snapshot

- Backend for a SQL exam/grading platform.
- Java 21, Spring Boot 3.5.7, Gradle, PostgreSQL, Redis, Liquibase, Spring Security JWT/OAuth2, WebSocket/STOMP, Lombok.
- Base package: `graduation_project_be`.
- Read `CLAUDE.md` and `convention.md` for fuller rules; this file is the concise Codex entrypoint.

## Commands

- Compile after Java changes: `GRADLE_USER_HOME=.gradle ./gradlew compileJava`
- Run tests when touching behavior with tests: `GRADLE_USER_HOME=.gradle ./gradlew test`
- Run app locally: `./gradlew bootRun`
- Build jar: `./gradlew build`

## Architecture

Use Clean Architecture boundaries:

- `adapter/web/api`: controllers, request DTOs, response DTOs, global web exceptions.
- `application`: usecases, usecase request/response records, port interfaces, business exceptions.
- `domain`: pure domain models/enums/exceptions. No Spring or JPA annotations.
- `infrastructure`: Spring configuration, security, JPA/Redis persistence, external services, WebSocket.
- `shared/utils`: shared utilities such as `TimeUtils`.

Request flow:

`Controller -> RequestDto.toRequest() -> Usecase.execute() -> Port interface -> Infrastructure impl -> Domain -> UsecaseResponse.fromModel() -> ResponseDto.fromResponse() -> Controller`

## Backend Rules

- Controllers route only. Do not put business logic in controllers.
- Request/response DTOs are Java `record`s.
- Request DTOs live in `adapter.web.api.dtos.request`, use Jakarta validation, and expose `toRequest()`.
- Response DTOs live in `adapter.web.api.dtos.response` and expose `fromResponse(...)`.
- Usecase request/response objects live in `application.usecases.request` and `application.usecases.response`.
- New usecases should be plain Java classes with Lombok such as `@RequiredArgsConstructor`/`@Slf4j`; do not add `@Service` or `@Component`.
- Register new usecases with `@Bean` in `infrastructure/configurations/UsecasesConfiguration.java`.
- Usecases depend on ports from `application.port.repositories` or `application.port.services`, not concrete infrastructure classes.
- Domain models are Lombok data holders: `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`.
- Use enums in `domain.models.enums` for fixed sets of values. Do not use raw strings for status/type fields.
- JPA entities live in `infrastructure.persistence.entities` and must provide `toModel()` and `static fromModel(...)`.
- Repository ports live in `application.port.repositories`; implementations live in `infrastructure.persistence.repositories`; JPA interfaces live in `infrastructure.persistence.repositories.jpa`.
- Avoid inline fully qualified class names. Import `java.util.List`, `java.time.LocalDateTime`, etc.
- Use `TimeUtils.now()` for current timestamps. Never call `LocalDateTime.now()` directly.
- Redis keys use `{feature}:{id}:{sub_id}` and every Redis write must have an explicit TTL.

## API, Auth, and Responses

- Teacher endpoints generally use `@PreAuthorize("hasRole('TEACHER')")`.
- `/api/admin/**` is guarded by admin security configuration.
- Wrap single responses in `ResponseDto`; wrap pagination in `PaginationResponseDto<T>`.
- Use `ResourceNotFoundException("EntityName", "fieldName", value)` for missing resources.
- Business exceptions belong in `application.exceptions`; web mapping belongs in `adapter.web.api.exceptions`.

## Liquibase

- Changelogs live in `src/main/resources/db/changelog/`.
- Include new changelog files in `grad-changelog-master.yaml`.
- File naming: `grad-changelog-GRAD-<ticket>.yaml`.
- ChangeSet id format: `DDMMYYhhmm` only.
- Valid authors: `manhuynh`, `phucpha`, `tdhoang`. Do not use `system` or `graduation-project`.
- Include a comment like `GRAD-XXX: Short description`.

## AI and SQL Grading Context

- The AI port is `application.port.services.AIService`.
- `infrastructure.services.CodexServiceImpl` is the primary AI service and calls the VM through `CodexVmClient`.
- Codex VM config keys are under `spring.application.codex.*`; local/development profiles use `CODEX_VM_API_URL` and `CODEX_VM_API_KEY`.
- Keep AI prompt templates in `src/main/resources/prompts/`.
- Codex calls can be slow; preserve the 600 second timeout behavior unless there is a clear reason to change it.
- Rubric/grading logic is sensitive. Prefer small, well-scoped edits and keep JSON shapes compatible with existing parsing code.
- The project uses Microsoft SQL Server for student exam schemas and grading execution; do not assume PostgreSQL syntax for exam SQL.

## Naming

- Controller: `{Feature}Controller`
- Request DTO: `{Action}RequestDto`
- Response DTO: `{Action}ResponseDto`
- Usecase: `{Action}Usecase`
- Usecase request: `{Action}Request`
- Usecase response: `{Action}Response`
- Domain model: `{Entity}`
- JPA entity: `{Entity}Entity`
- Port repository: `{Entity}Repository`
- Repository impl: `{Entity}RepositoryImpl`
- JPA interface: `{Entity}JpaRepository`
- Port service: `{Feature}Service`

## Editing Guidance

- Keep changes inside the existing layer and naming patterns.
- Prefer existing helpers and mappers over adding new abstractions.
- Do not refactor unrelated code while fixing a focused issue.
- When behavior changes, look for the paired DTO, usecase, port, repository impl, endpoint constant, and FE action/type.
- The worktree may contain user changes. Do not revert files unless explicitly asked.

## graphify

This project has a knowledge graph at graphify-out/ with god nodes, community structure, and cross-file relationships.

When the user types `/graphify`, invoke the `skill` tool with `skill: "graphify"` before doing anything else.

Rules:
- For codebase questions, first run `graphify query "<question>"` when graphify-out/graph.json exists. Use `graphify path "<A>" "<B>"` for relationships and `graphify explain "<concept>"` for focused concepts. These return a scoped subgraph, usually much smaller than GRAPH_REPORT.md or raw grep output.
- Dirty graphify-out/ files are expected after hooks or incremental updates; dirty graph files are not a reason to skip graphify. Only skip graphify if the task is about stale or incorrect graph output, or the user explicitly says not to use it.
- If graphify-out/wiki/index.md exists, use it for broad navigation instead of raw source browsing.
- Read graphify-out/GRAPH_REPORT.md only for broad architecture review or when query/path/explain do not surface enough context.
- After modifying code, run `graphify update .` to keep the graph current (AST-only, no API cost).
