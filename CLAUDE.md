# CLAUDE.md — graduation-project-be

## Tech Stack

- **Java 21** / **Spring Boot 3.5.7** / **Gradle**
- **Spring Security** (JWT + OAuth2 Google/Microsoft)
- **Spring Data JPA** + **PostgreSQL**
- **Redis** (exam sessions, token blacklist)
- **WebSocket** (STOMP — exam monitoring)
- **Liquibase** (YAML migrations)
- **Lombok** (`@Data`, `@Builder`, `@RequiredArgsConstructor`, `@Slf4j`)
- Base package: `graduation_project_be`

## Running the Project

```bash
./gradlew bootRun          # start dev server
./gradlew test             # run tests
./gradlew build            # build JAR
```

## Architecture — Clean Architecture (4 Layers)

```
adapter/       → Spring MVC controllers, request/response DTOs
application/   → Business logic: usecases, port interfaces, exceptions
domain/        → Pure Java models and enums (no frameworks)
infrastructure/→ JPA entities, repository impls, security, Redis, WebSocket
```

### Request Flow

```
Controller → RequestDto.toRequest() → Usecase.execute() → Port Interface → Impl
                                            ↓
                                      Domain Model
                                            ↓
                        UsecaseResponse.fromModel() → ResponseDto.fromResponse() → Controller
```

## Key Patterns

### Usecases

- **No `@Service`** on usecase classes — they are plain Java with Lombok only.
- All usecases are registered as `@Bean` methods in `UsecasesConfiguration`.
- When you create a new usecase, add a `@Bean` for it in `UsecasesConfiguration`.

```java
@Slf4j
@RequiredArgsConstructor
public class MyUsecase {
    private final SomeRepository someRepository;

    public MyResponse execute(MyRequest request) { ... }
}
```

### Java Records for DTOs and Usecase Objects

- **Request DTOs** (`adapter.web.api.dtos.request`): records with Jakarta Validation, `toRequest()` method.
- **Response DTOs** (`adapter.web.api.dtos.response`): records with static `fromResponse(UsecaseResponse)`.
- **Usecase Request** (`application.usecases.request`): plain records, no annotations.
- **Usecase Response** (`application.usecases.response`): records with static `fromModel(DomainModel)`.

### Controllers

- Routing only — no business logic.
- Use `@RestController`, `@RequestMapping`, `@RequiredArgsConstructor`, `@Validated`.
- Authorization via `@PreAuthorize("hasRole('TEACHER')")`.
- Return `ResponseEntity<ResponseDto>` or `ResponseEntity<PaginationResponseDto<T>>`.

### Domain Models

- Plain Java with `@Data @Builder @NoArgsConstructor @AllArgsConstructor` (Lombok).
- No JPA annotations.
- Enums in `domain.models.enums` for any fixed-value fields.

### JPA Entities

- Located in `infrastructure.persistence.entities`.
- Always implement `toModel()` (Entity → Domain) and `static fromModel(Model)` (Domain → Entity).

### Repository Pattern

- Port interface in `application.port.repositories` — no Spring annotations.
- Implementation in `infrastructure.persistence.repositories` using `@Repository`.
- JPA interface in `infrastructure.persistence.repositories.jpa` extending `JpaRepository`.

### Pagination

- Usecase returns `PaginationResponse<T>` (generic record with `PaginationMeta`).
- Controller wraps in `PaginationResponseDto<T>`.

### Error Handling

- Use `ResourceNotFoundException("EntityName", "fieldName", value)` — 3-argument constructor.
- Business exceptions in `application.exceptions`.
- Domain exceptions in `domain.exceptions`.
- Web exception handler in `adapter.web.api.exceptions`.

### Security

- `/api/admin/**` → `hasRole("ADMIN")` — already configured in `SecurityConfiguration`.
- `/api/teacher/**` → use `@PreAuthorize("hasRole('TEACHER')")` at the method level.
- Default admin: `admin@fit.hcmus.edu.vn` / `Xxxxxxxxxxxx`

### Redis

- Use `RedisTemplate<String, String>`.
- Key format: `{feature}:{id}:{sub_id}` (e.g., `exam_session:1:2`).
- Set TTL explicitly for every key.

## Liquibase Migrations

- Files: `src/main/resources/db/changelog/`
- Master file: `grad-changelog-master.yaml` — add includes here for new files.
- File naming: `grad-changelog-GRAD-<ticket>.yaml`

### Changeset ID Format: `DDMMYYhhmm`

```
DD = day (01-31), MM = month (01-12), YY = year (25, 26...)
hh = hour (00-23), mm = minute (00-59)
```

Example: `1605261430` = created on 16/05/26 at 14:30.

### Author: always real developer name

- `manhuynh`, `phucpha`, `tdhoang` — never `system` or `graduation-project`.

### Example Changeset

```yaml
databaseChangeLog:
  - changeSet:
      id: 1605261430
      author: manhuynh
      comment: "GRAD-XXX: short description"
      changes:
        - addColumn:
            tableName: users
            columns:
              - column:
                  name: some_field
                  type: VARCHAR(50)
```

## Important Files

| File | Purpose |
|------|---------|
| `infrastructure/configurations/UsecasesConfiguration.java` | Register ALL usecase beans here |
| `infrastructure/configurations/SecurityConfiguration.java` | Security rules and JWT filter |
| `infrastructure/configurations/RedisConfiguration.java` | Redis + Redis-based services |
| `infrastructure/configurations/WebSocketConfiguration.java` | STOMP WebSocket setup |
| `adapter/web/api/exceptions/GlobalExceptionHandler.java` | Global error handler |
| `src/main/resources/db/changelog/grad-changelog-master.yaml` | Liquibase master file |
| `src/main/resources/application.properties` | App configuration |

## Naming Conventions

| Layer | Pattern | Example |
|-------|---------|---------|
| Controller | `{Feature}Controller` | `ExamController` |
| Request DTO | `{Action}RequestDto` | `CreateExamRequestDto` |
| Response DTO | `{Action}ResponseDto` | `CreateExamResponseDto` |
| Usecase | `{Action}Usecase` | `CreateExamUsecase` |
| Usecase Request | `{Action}Request` | `CreateExamRequest` |
| Usecase Response | `{Action}Response` | `CreateExamResponse` |
| Domain Model | `{Entity}` | `Exam`, `ExamViolation` |
| JPA Entity | `{Entity}Entity` | `ExamEntity` |
| Port Repository | `{Entity}Repository` | `ExamRepository` |
| Repo Impl | `{Entity}RepositoryImpl` | `ExamRepositoryImpl` |
| JPA Interface | `{Entity}JpaRepository` | `ExamJpaRepository` |
| Port Service | `{Feature}Service` | `ViolationNotificationService` |

## What NOT to Do

- **Never** add `@Service` or `@Component` to a usecase class — register in `UsecasesConfiguration`.
- **Never** put business logic in a controller — controllers route only.
- **Never** use fully qualified class names inline (e.g., `java.util.List<T>`) — always import.
- **Never** use `String` for fixed-value fields — define a Java Enum in `domain.models.enums`.
- **Never** use `graduation-project` or `system` as Liquibase changeset author.
- **Never** use non-`DDMMYYhhmm` format for Liquibase changeset IDs (e.g., `GRAD-68-add-status` is wrong).
- **Never** add JPA annotations to domain models — those belong only on entities in `infrastructure.persistence.entities`.
