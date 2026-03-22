# Coding Convention — Graduation Project BE

## 1. Kiến trúc tổng quan (Clean Architecture)

```
adapter/           → Framework-dependent layer (Spring MVC)
application/       → Business logic layer (framework-agnostic)
domain/            → Domain model layer (pure Java)
infrastructure/    → Technical implementation layer (JPA, Redis, WebSocket)
```

### Luồng xử lý (Request Flow)

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
- **Chỉ làm routing**, không chứa business logic
- Sử dụng `@RestController`, `@RequestMapping`, `@RequiredArgsConstructor`
- Inject các Usecase qua constructor (Lombok `@RequiredArgsConstructor`)
- Validation tại controller với `@Valid`, `@Positive`, `@Validated`
- Map DTO ↔ Usecase Request/Response ngay tại controller methods
- Trả về `ResponseEntity<ResponseDto>` hoặc `ResponseEntity<PaginationResponseDto<T>>`
- Phân quyền bằng `@PreAuthorize("hasRole('TEACHER')")`

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
- Là **Java Record**
- Chứa validation annotations (`@NotBlank`, `@NotNull`, v.v.)
- Có method `toRequest()` để convert sang Usecase Request

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
- Là **Java Record**
- Có static factory method `fromResponse(UsecaseResponse r)` để convert từ Usecase Response

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

#### Wrapper Response
- `ResponseDto` — cho response đơn lẻ
- `PaginationResponseDto<T>` — cho response phân trang

### 2.2 Application Layer (`application`)

#### Usecase (`application.usecases`)
- **Chứa toàn bộ business logic**
- **Không có Spring annotations** (`@Service`, `@Component`, ...) — bean được tạo thủ công trong `UsecasesConfiguration`
- Sử dụng `@RequiredArgsConstructor`, `@Slf4j` (Lombok)
- Inject dependencies qua constructor (các Port interfaces)
- Method chính: `execute(Request) → Response`

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

> **Lưu ý**: Một số usecase cũ vẫn có `@Service` (ví dụ: `GetClassesUsecase`). Convention mới là **KHÔNG** dùng `@Service`, mà đăng ký bean trong `UsecasesConfiguration`.

#### Usecase Request (`application.usecases.request`)
- Là **Java Record** — immutable
- Pure Java, không annotations ngoài Lombok

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
- Là **Java Record**
- Có static factory method `fromModel(DomainModel model, ...)` để convert từ Domain Model

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
- Sử dụng `PaginationResponse<T>` record generic
- `PaginationResponse.PaginationMeta` chứa `page`, `size`, `total`, `totalPages`

#### Port — Repository Interface (`application.port.repositories`)
- Interface thuần, không framework annotations
- Định nghĩa contract cho data access

```java
public interface ExamViolationRepository {
    ExamViolation save(ExamViolation violation);
    List<ExamViolation> findByExamId(Long examId);
    long countByExamIdAndStudentId(Long examId, Long studentId);
}
```

#### Port — Service Interface (`application.port.services`)
- Interface cho infrastructure services (JWT, Redis, WebSocket, etc.)
- Không framework-specific

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
- Plain Java class với Lombok `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`
- Không có JPA annotations
- Không có business logic phức tạp (chỉ data holder)

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
- Mọi trường dữ liệu có tập giá trị cố định (ví dụ: trạng thái, phân loại, type) **phải được định nghĩa bằng Java Enum**, tuyệt đối không dùng kiểu `String` thuần để tránh lỗi typo và dễ maintain.
- Các enum nên đặt ở thư mục `domain.models.enums`.
- Ví dụ: `GradingStatus` (PENDING, GRADING, COMPLETED, FAILED), `Role` (ADMIN, TEACHER, STUDENT), v.v.

### 2.4 Infrastructure Layer (`infrastructure`)

#### Entity (`infrastructure.persistence.entities`)
- JPA Entity, ánh xạ trực tiếp tới database table
- Sử dụng Lombok `@Data`, `@Builder`, `@NoArgsConstructor`, `@AllArgsConstructor`
- Có 2 method convert:
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
- Extend `JpaRepository<Entity, ID>`
- Chứa Spring Data query methods

#### Repository Implementation (`infrastructure.persistence.repositories`)
- Implements Port Repository Interface
- Sử dụng `@Repository`, `@RequiredArgsConstructor`
- Inject JPA Repository
- Convert Entity ↔ Domain Model

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
- Sử dụng `@RequiredArgsConstructor`, `@Slf4j`
- Có thể dùng `@Service` hoặc đăng ký trong Configuration

```java
@Slf4j
@RequiredArgsConstructor
public class WebSocketViolationNotificationService implements ViolationNotificationService {
    private final SimpMessagingTemplate messagingTemplate;
    // ...
}
```

#### Configuration (`infrastructure.configurations`)
- **`UsecasesConfiguration`** — đăng ký tất cả Usecase beans thủ công bằng `@Bean`
- **`RedisConfiguration`** — cấu hình Redis + đăng ký Redis-based services
- **`SecurityConfiguration`** — Spring Security
- **`WebSocketConfiguration`** — STOMP WebSocket

## 3. Quy tắc chung

| Quy tắc | Mô tả |
|----|-----|
| **DTO là Record** | Tất cả Request/Response DTOs đều là Java `record` |
| **Controller chỉ route** | Không có business logic trong controller |
| **Usecase không framework** | Usecase KHÔNG dùng Spring annotations, inject qua constructor |
| **Giao tiếp qua Interface** | Usecase → Port Interface → Infrastructure Implementation |
| **Entity ↔ Model convert** | Entity có `toModel()` và `fromModel()` |
| **Response wrap** | Mọi response đều wrap trong `ResponseDto` hoặc `PaginationResponseDto` |
| **Validation ở Controller** | Sử dụng Jakarta Validation annotations trên DTO |
| **Lombok everywhere** | Sử dụng `@RequiredArgsConstructor`, `@Data`, `@Builder`, `@Slf4j` |
| **Usecase bean registration** | Usecase beans đăng ký trong `UsecasesConfiguration` |

## 4. Naming Convention

| Layer | Pattern | Ví dụ |
|-------|---------|-------|
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

- Sử dụng `RedisTemplate<String, String>` 
- Key naming: `{feature}:{id}:{sub_id}` (ví dụ: `exam_session:1:2`)
- Serialize/deserialize thủ công (String-based)
- Set TTL phù hợp cho mỗi loại data

## 7. Database Migration — Liquibase Changelog Convention

- Sử dụng Liquibase với YAML changelog
- File nằm trong `src/main/resources/db/changelog/`
- **Các changeSet PHẢI tuân thủ quy tắc ID và Author sau:**

### 7.1 ChangeSet ID Format

**ID format: `DDMMYYhhmm`** (Date + Time)

```
DD = Ngày (01-31)
MM = Tháng (01-12)
YY = Năm (25, 26, ...)
hh = Giờ (00-23)
mm = Phút (00-59)
```

**Ví dụ:**
- `2212251601` = 22/12/25 16:01 (lúc 16 giờ 01 phút)
- `1803260002` = 18/03/26 00:02 (lúc 00 giờ 02 phút)
- `2103260003` = 21/03/26 00:03 (lúc 00 giờ 03 phút)

**❌ SAI**: `GRAD-68-add-status`, `GRAD-63-update-default`, ...  
**✅ ĐÚNG**: `2212251601`, `1803260002`, `2103260003`, ...

### 7.2 Author Convention

**Author PHẢI là tên thực của người tạo changelog**, không phải "graduation-project" hay "system".

**Ví dụ tên hợp lệ:**
- `manhuynh` (Mạn Huy Ân)
- `phucpha` (Phúc Phạm)
- `tdhoang` (Trần Duy Hoàng)

**❌ SAI**: `graduation-project`, `system`, `Graduation Project`, ...  
**✅ ĐÚNG**: `manhuynh`, `phucpha`, `tdhoang`, ...

### 7.3 Changelog File Naming

File changelog nên đặt tên theo JIRA ticket hoặc feature:
- `grad-changelog-GRAD-25.yaml` (cho ticket GRAD-25)
- `grad-changelog-GRAD-68.yaml` (cho ticket GRAD-68)

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

**Giải thích:**
- ID `2212251601` = Được tạo lúc 22/12/25 16:01 (4:01 PM)
- Author `manhuynh` = Tên thực của developer ([không dùng "graduation-project" hay "system")
- Comment ghi rõ JIRA ticket ID (GRAD-25) và mô tả ngắn gọn

## 8. General Coding Standards

- **Tránh dùng Fully Qualified Class Names (FQCN) trực tiếp trong code**: Các package chuẩn của Java/Spring (`java.util.List`, `java.time.LocalDateTime`, v.v.) BẮT BUỘC phải được đưa lên phần `import` ở đầu file, nghiêm cấm viết thẳng package (`java.util.List<T>`) vào khai báo thuộc tính, tham số hoặc kiểu trả về của method.
