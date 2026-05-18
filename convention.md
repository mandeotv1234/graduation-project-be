# Coding Convention — Graduation Project BE

## 1. Overall Architecture (Clean Architecture)

```
adapter/           → Framework-dependent layer (Spring MVC)
application/       → Business logic layer (framework-agnostic)
domain/            → Domain model layer (pure Java)
infrastructure/    → Technical implementation layer (JPA, Redis, WebSocket)
```

### Request Flow

```
Controller → DTO (request) → Usecase → Port (interface) → Infrastructure (impl)
                                ↓
                          Domain Model
                                ↓
            Usecase Response → DTO (response) → Controller
```

## 2. Layer Details

### 2.1 Adapter Layer (`adapter.web.api`)

#### Controller
- **Routing only** — no business logic
- Use `@RestController`, `@RequestMapping`, `@RequiredArgsConstructor`
- Inject usecases via constructor (Lombok `@RequiredArgsConstructor`)
- Validate at controller with `@Valid`, `@Positive`, `@Validated`
- Map DTO ↔ Usecase Request/Response directly in controller methods
- Return `ResponseEntity<ResponseDto>` or `ResponseEntity<PaginationResponseDto<T>>`
- Authorization with `@PreAuthorize("hasRole('TEACHER')")`

```java
@RestController
@RequestMapping("/api/exams")
@RequiredArgsConstructor
@Validated
public class ExamController {

    private final CreateExamUsecase createExamUsecase;

    @PostMapping
    @PreAuthorize("hasRole('TEACHER')")
    public ResponseEntity<ResponseDto> createExam(
            @RequestBody @Valid CreateExamRequestDto requestDto) {
        CreateExamRequest request = requestDto.toRequest();
        CreateExamResponse response = createExamUsecase.execute(request);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ResponseDto.of(
                        CreateExamResponseDto.fromResponse(response),
                        "CREATED",
                        "Exam created successfully"));
    }
}
```

#### Request DTO (`adapter.web.api.dtos.request`)
- Is a **Java Record**
- Contains validation annotations (`@NotBlank`, `@NotNull`, etc.)
- Has a `toRequest()` method to convert to Usecase Request

```java
public record CreateSchemaTemplateRequestDto(
        @NotBlank(message = "Name is required") String name,
        @NotBlank(message = "DDL script is required") String ddlScript,
        String defaultDataScript) {
    public CreateSchemaTemplateRequest toRequest() {
        return new CreateSchemaTemplateRequest(name, ddlScript, defaultDataScript);
    }
}
```

#### Response DTO (`adapter.web.api.dtos.response`)
- Is a **Java Record**
- Has a static factory method `fromResponse(UsecaseResponse r)` to convert from Usecase Response

```java
public record ExamViolationResponseDto(
        Long id, Long examId, Long studentId,
        String violationType, String description,
        String ipAddress, String userAgent,
        LocalDateTime createdAt) {

    public static ExamViolationResponseDto fromResponse(ExamViolationResponse r) {
        return new ExamViolationResponseDto(
                r.id(), r.examId(), r.studentId(), r.violationType(),
                r.description(), r.ipAddress(), r.userAgent(), r.createdAt());
    }
}
```

#### Wrapper Responses
- `ResponseDto` — for single responses
- `PaginationResponseDto<T>` — for paginated responses

### 2.2 Application Layer (`application`)

#### Usecase (`application.usecases`)
- **Contains all business logic**
- **No Spring annotations** (`@Service`, `@Component`, ...) — beans are registered manually in `UsecasesConfiguration`
- Use `@RequiredArgsConstructor`, `@Slf4j` (Lombok)
- Inject dependencies via constructor (Port interfaces)
- Main method: `execute(Request) → Response`

```java
@Slf4j
@RequiredArgsConstructor
public class ReportViolationUsecase {

    private final ExamViolationRepository examViolationRepository;
    private final CurrentUserService currentUserService;

    public ReportViolationResponse execute(ReportViolationRequest request) {
        // business logic here — no Spring dependency
    }
}
```

> **Note**: Some older usecases still have `@Service` (e.g., `GetClassesUsecase`). The new convention is to **NOT** use `@Service` and instead register the bean in `UsecasesConfiguration`.

#### Usecase Request (`application.usecases.request`)
- Is a **Java Record** — immutable
- Pure Java, no annotations other than Lombok

```java
public record ReportViolationRequest(
        Long examId,
        String violationType,
        String description,
        String ipAddress,
        String userAgent) {
}
```

#### Usecase Response (`application.usecases.response`)
- Is a **Java Record**
- Has a static factory method `fromModel(DomainModel model, ...)` to convert from Domain Model

```java
public record ReportViolationResponse(
        Long violationId, Long examId, Long studentId,
        String violationType, String description,
        long violationCount, boolean autoSubmitted,
        String message, LocalDateTime createdAt) {

    public static ReportViolationResponse fromModel(ExamViolation model, long violationCount,
                                                     boolean autoSubmitted) {
        // ...
    }
}
```

#### Pagination Response
- Use the generic `PaginationResponse<T>` record
- `PaginationResponse.PaginationMeta` contains `page`, `size`, `total`, `totalPages`

#### Port — Repository Interface (`application.port.repositories`)
- Pure interface, no framework annotations
- Defines the contract for data access

```java
public interface ExamViolationRepository {
    ExamViolation save(ExamViolation violation);
    List<ExamViolation> findByExamId(Long examId);
    long countByExamIdAndStudentId(Long examId, Long studentId);
}
```

#### Port — Service Interface (`application.port.services`)
- Interface for infrastructure services (JWT, Redis, WebSocket, etc.)
- Not framework-specific

```java
public interface ViolationNotificationService {
    void notifyTeacher(Long examId, Long teacherId, Long studentId,
                       String studentName, String violationType,
                       String description, long violationCount,
                       boolean autoSubmitted);
}
```

### 2.3 Domain Layer (`domain`)

#### Domain Model (`domain.models`)
- Plain Java class with Lombok `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`
- No JPA annotations
- No complex business logic (data holder only)

```java
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ExamViolation {
    private Long id;
    private Long examId;
    private Long studentId;
    private String violationType;
    private String description;
    private String ipAddress;
    private String userAgent;
    private LocalDateTime createdAt;
}
```

#### Enums (`domain.models.enums`)
- Every field with a fixed set of values (e.g., status, type) **must be defined as a Java Enum** — never use plain `String` to avoid typos and improve maintainability.
- Enums should be placed in `domain.models.enums`.
- Examples: `GradingStatus` (PENDING, GRADING, COMPLETED, FAILED), `Role` (ADMIN, TEACHER, STUDENT), etc.

### 2.4 Infrastructure Layer (`infrastructure`)

#### Entity (`infrastructure.persistence.entities`)
- JPA Entity, mapped directly to a database table
- Use Lombok `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`
- Must have 2 conversion methods:
  - `toModel()` → Entity → Domain Model
  - `static fromModel(Model m)` → Domain Model → Entity

```java
@Table(name = "exam_violations")
@Entity
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExamViolationEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;
    // ... fields

    public ExamViolation toModel() { /* ... */ }
    public static ExamViolationEntity fromModel(ExamViolation model) { /* ... */ }
}
```

#### JPA Repository (`infrastructure.persistence.repositories.jpa`)
- Extends `JpaRepository<Entity, ID>`
- Contains Spring Data query methods

#### Repository Implementation (`infrastructure.persistence.repositories`)
- Implements Port Repository Interface
- Use `@Repository`, `@RequiredArgsConstructor`
- Inject JPA Repository
- Converts Entity ↔ Domain Model

```java
@Repository
@RequiredArgsConstructor
public class ExamViolationRepositoryImpl implements ExamViolationRepository {
    private final ExamViolationJpaRepository jpaRepository;

    @Override
    public ExamViolation save(ExamViolation violation) {
        ExamViolationEntity entity = ExamViolationEntity.fromModel(violation);
        return jpaRepository.save(entity).toModel();
    }
}
```

#### Service Implementation (`infrastructure.services`)
- Implements Port Service Interface
- Use `@RequiredArgsConstructor`, `@Slf4j`
- Can use `@Service` or be registered in a Configuration class

```java
@Slf4j
@RequiredArgsConstructor
public class WebSocketViolationNotificationService implements ViolationNotificationService {
    private final SimpMessagingTemplate messagingTemplate;
    // ...
}
```

#### Configuration (`infrastructure.configurations`)
- **`UsecasesConfiguration`** — manually registers all Usecase beans with `@Bean`
- **`RedisConfiguration`** — configures Redis and registers Redis-based services
- **`SecurityConfiguration`** — Spring Security
- **`WebSocketConfiguration`** — STOMP WebSocket

## 3. General Rules

| Rule | Description |
|----|-----|
| **DTOs are Records** | All Request/Response DTOs are Java `record` |
| **Controllers route only** | No business logic in controllers |
| **Usecases are framework-free** | Usecases do NOT use Spring annotations; injected via constructor |
| **Communicate via Interfaces** | Usecase → Port Interface → Infrastructure Implementation |
| **Entity ↔ Model conversion** | Entity has `toModel()` and `fromModel()` |
| **Wrap responses** | All responses are wrapped in `ResponseDto` or `PaginationResponseDto` |
| **Validate at Controller** | Use Jakarta Validation annotations on DTOs |
| **Lombok everywhere** | Use `@RequiredArgsConstructor`, `@Data`, `@Builder`, `@Slf4j` |
| **Usecase bean registration** | Usecase beans are registered in `UsecasesConfiguration` |

## 4. Naming Convention

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
| Port Service | `{Feature}Service` | `ViolationNotificationService` |
| JPA Interface | `{Entity}JpaRepository` | `ExamJpaRepository` |
| Repo Impl | `{Entity}RepositoryImpl` | `ExamRepositoryImpl` |
| Service Impl | `{Specific}{Feature}Service` | `WebSocketViolationNotificationService` |

## 5. Package Structure

```
graduation_project_be/
├── adapter/
│   └── web/
│       └── api/
│           ├── controller/          → Controllers (routing only)
│           ├── dtos/
│           │   ├── request/         → Request DTOs (records + validation)
│           │   └── response/        → Response DTOs (records + fromResponse)
│           └── exceptions/          → Web exception handlers
├── application/
│   ├── codes/                       → Response code enums
│   ├── exceptions/                  → Business exceptions
│   ├── port/
│   │   ├── repositories/            → Repository interfaces
│   │   └── services/                → Service interfaces
│   └── usecases/
│       ├── request/                 → Usecase request records
│       └── response/                → Usecase response records
├── domain/
│   ├── exceptions/                  → Domain exceptions
│   └── models/
│       └── enums/                   → Domain enums
└── infrastructure/
    ├── configurations/              → Spring @Configuration classes
    ├── errors/                      → Error handling
    ├── persistence/
    │   ├── entities/                → JPA Entities
    │   └── repositories/
    │       ├── jpa/                 → Spring Data JPA interfaces
    │       └── redis/               → Redis repository implementations
    ├── security/                    → Security implementations
    └── services/                    → Service implementations (Redis, WebSocket, etc.)
```

## 6. Redis Pattern

- Use `RedisTemplate<String, String>`
- Key naming: `{feature}:{id}:{sub_id}` (e.g., `exam_session:1:2`)
- Serialize/deserialize manually (String-based)
- Set an appropriate TTL for each type of data

## 7. Database Migration — Liquibase Changelog Convention

- Uses Liquibase with YAML changelogs
- Files located in `src/main/resources/db/changelog/`
- **All changeSets MUST follow the ID and Author rules below:**

### 7.1 ChangeSet ID Format

**ID format: `DDMMYYhhmm`** (Date + Time)

```
DD = Day (01-31)
MM = Month (01-12)
YY = Year (25, 26, ...)
hh = Hour (00-23)
mm = Minute (00-59)
```

**Examples:**
- `2212251601` = 22/12/25 at 16:01
- `1803260002` = 18/03/26 at 00:02
- `2103260003` = 21/03/26 at 00:03

**❌ Wrong**: `GRAD-68-add-status`, `GRAD-63-update-default`, ...  
**✅ Correct**: `2212251601`, `1803260002`, `2103260003`, ...

### 7.2 Author Convention

**Author MUST be the real name of the developer** who created the changelog — not `graduation-project` or `system`.

**Valid author names:**
- `manhuynh` (Mạn Huy Ân)
- `phucpha` (Phúc Phạm)
- `tdhoang` (Trần Duy Hoàng)

**❌ Wrong**: `graduation-project`, `system`, `Graduation Project`, ...  
**✅ Correct**: `manhuynh`, `phucpha`, `tdhoang`, ...

### 7.3 Changelog File Naming

Changelog files should be named after the JIRA ticket or feature:
- `grad-changelog-GRAD-25.yaml` (for ticket GRAD-25)
- `grad-changelog-GRAD-68.yaml` (for ticket GRAD-68)

### 7.4 Example ChangeSet

```yaml
databaseChangeLog:
  - changeSet:
      id: 2212251601
      author: manhuynh
      comment: "GRAD-25: Add user roles and created_at timestamp"
      changes:
        - addColumn:
            tableName: users
            columns:
              - column:
                  name: role
                  type: VARCHAR(20)
                  defaultValue: "STUDENT"
```

**Explanation:**
- ID `2212251601` = Created on 22/12/25 at 16:01 (4:01 PM)
- Author `manhuynh` = Real developer name (do not use `graduation-project` or `system`)
- Comment includes the JIRA ticket ID (GRAD-25) and a brief description

## 8. TimeUtils — Timezone Convention

All timestamps in the system must use Vietnam timezone (Asia/Ho_Chi_Minh).

**Rule: NEVER use `LocalDateTime.now()` directly. Always use `TimeUtils.now()` instead.**

```java
// ❌ Wrong — uses server/JVM default timezone, inconsistent across environments
LocalDateTime now = LocalDateTime.now();

// ✅ Correct — always Vietnam timezone regardless of server config
import graduation_project_be.shared.utils.TimeUtils;
LocalDateTime now = TimeUtils.now();
```

`TimeUtils` is located at `graduation_project_be.shared.utils.TimeUtils` and can be imported from any layer (usecase, repository implementation, response builder, etc.).

Typical usage pattern — set timestamps in the **usecase** layer when building domain models:

```java
// In a usecase:
Class clazz = Class.builder()
        .classCode(request.classCode())
        .createdAt(TimeUtils.now())   // ✅ correct
        .build();
```

When time must be set inside a repository implementation (e.g., for a `@Modifying` UPDATE query), import and use `TimeUtils.now()` there as well:

```java
// In a repository impl:
import graduation_project_be.shared.utils.TimeUtils;

classJpaRepository.updateDeletedAt(classId, TimeUtils.now()); // ✅ correct
```

## 9. General Coding Standards

- **Avoid using Fully Qualified Class Names (FQCN) inline in code**: Standard Java/Spring packages (`java.util.List`, `java.time.LocalDateTime`, etc.) **must** be placed in the `import` section at the top of the file. Writing them inline (e.g., `java.util.List<T>` in a field declaration, parameter, or return type) is strictly forbidden.
