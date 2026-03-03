# Graduation Project Backend

## 📋 Table of Contents
1. [Project Overview](#project-overview)
2. [Technology Stack](#technology-stack)
3. [System Architecture](#system-architecture)
4. [Project Structure](#project-structure)
5. [Application Flow](#application-flow)
6. [Getting Started](#getting-started)
7. [Layer Details](#layer-details)

---

## 🎯 Project Overview

This is the backend of the **Graduation Project**, built with **Spring Boot 3.5.7** and **Java 21**. The project implements **Clean Architecture** (Ports & Adapters Pattern) - a clean architecture that provides:
- Separation of business logic from technical details
- Easy to test and maintain
- Flexibility to change technology (database, framework) without affecting core logic

---

## 🛠 Technology Stack

| Technology | Version | Purpose |
|-----------|-----------|----------|
| Java | 21 | Programming Language |
| Spring Boot | 3.5.7 | Main Framework |
| Spring Security | 3.x | Authentication & Authorization |
| Spring Data JPA | 3.x | ORM & Database |
| PostgreSQL | 42.5.6 | Database |
| Liquibase | Latest | Database Migration |
| JWT (jjwt) | 0.11.5 | Token-based Authentication |
| Lombok | Latest | Reduce Boilerplate Code |
| Gradle | Latest | Build Tool |
| Redis | Latest | Caching & Token Storage |

---

## 🏗 System Architecture

### 🔷 Clean Architecture (Ports & Adapters)

This project implements **Clean Architecture** with 4 main layers:

```
┌─────────────────────────────────────────────────────────────────┐
│                         ADAPTER LAYER                            │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │         Web Controllers (REST API)                          │ │
│  │    - AuthController, UserController                         │ │
│  │    - DTOs Request/Response                                  │ │
│  └────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
                              ↓ ↑
┌─────────────────────────────────────────────────────────────────┐
│                      APPLICATION LAYER                           │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │              Use Cases (Business Logic)                     │ │
│  │         - LoginUsecase                                      │ │
│  │                                                             │ │
│  │              Ports (Interfaces)                             │ │
│  │         - UserRepository (interface)                        │ │
│  │         - JwtService (interface)                            │ │
│  │         - PasswordEncoder (interface)                       │ │
│  └────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
                              ↓ ↑
┌─────────────────────────────────────────────────────────────────┐
│                        DOMAIN LAYER                              │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │         Domain Models (Core Business Objects)               │ │
│  │         - User, PaginatedResult, PaginationParams           │ │
│  │         - Business Rules & Exceptions                       │ │
│  └────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
                              ↓ ↑
┌─────────────────────────────────────────────────────────────────┐
│                     INFRASTRUCTURE LAYER                         │
│  ┌────────────────────────────────────────────────────────────┐ │
│  │    Implementations (Adapters to External Systems)           │ │
│  │    - UserRepositoryImpl                                     │ │
│  │    - JwtServiceImpl                                         │ │
│  │    - PasswordEncoderImpl                                    │ │
│  │    - UserJpaRepository (Spring Data)                        │ │
│  │    - UserEntity (JPA Entity)                                │ │
│  │    - SecurityConfiguration, JwtAuthenticationFilter         │ │
│  └────────────────────────────────────────────────────────────┘ │
└─────────────────────────────────────────────────────────────────┘
                              ↓ ↑
                         ┌──────────┐
                         │ Database │
                         │PostgreSQL│
                         └──────────┘
```

### 📐 Dependency Rule

```
Infrastructure → Application → Domain
     ↓              ↓            ↓
  Adapter  →   Use Cases  →  Models

❌ Domain MUST NOT depend on any layer
❌ Application depends only on Domain
✅ Infrastructure & Adapter depend on Application & Domain
```

---

## 📂 Project Structure

```
src/main/java/graduation_project_be/
│
├── 📱 adapter/                          # ADAPTER LAYER
│   └── web/
│       └── api/
│           ├── controller/              # REST Controllers
│           │   ├── AuthController.java       # Login, Refresh, Logout endpoints
│           │   ├── ClassController.java      # Class CRUD + nested students
│           │   ├── ExamController.java       # Exam create + detail
│           │   └── UserController.java       # User management
│           ├── dtos/                    # Data Transfer Objects
│           │   ├── request/             # Request DTOs
│           │   └── response/            # Response DTOs (see note below)
│           └── exceptions/              # Web layer exceptions
│
├── 💼 application/                      # APPLICATION LAYER
│   ├── exceptions/                      # Application exceptions
│   │   ├── ApplicationException.java
│   │   ├── UnauthorizedException.java
│   │   ├── BadRequestException.java
│   │   ├── ConflictException.java
│   │   ├── ResourceNotFoundException.java
│   │   └── OperationFailedException.java
│   ├── port/                            # Ports (Interfaces)
│   │   ├── repositories/
│   │   │   └── UserRepository.java          # Repository interface
│   │   └── services/
│   │       ├── JwtService.java              # JWT interface
│   │       ├── PasswordEncoder.java         # Password encoder interface
│   │       ├── CurrentUserService.java
│   │       └── ObjectMapperService.java
│   └── usecases/                        # Use Cases (Business Logic)
│       ├── LoginUsecase.java                # Login logic
│       ├── RefreshUsecase.java              # Token refresh logic
│       ├── LogoutUsecase.java               # Logout logic
│       ├── request/                     # Use case requests
│       └── response/                    # Use case responses
│
├── 🎯 domain/                           # DOMAIN LAYER
│   ├── models/                          # Domain Models
│   │   ├── User.java                        # User domain model
│   │   ├── PaginatedResult.java
│   │   ├── PaginationParams.java
│   │   └── enums/                       # Domain enums
│   └── exceptions/                      # Domain exceptions
│       ├── InvalidBusinessRuleException.java
│       └── InvalidProgressException.java
│
└── 🔧 infrastructure/                   # INFRASTRUCTURE LAYER
    ├── configurations/                  # Spring configurations
    │   ├── ApplicationConfiguration.java
    │   ├── SecurityConfiguration.java       # Security & JWT setup
    │   └── UsecasesConfiguration.java       # Use cases beans
    ├── errors/                          # Global error handling
    │   ├── GlobalExceptionHandler.java      # Centralized exception handler
    │   ├── ErrorResponse.java
    │   ├── code.java
    │   └── FieldErrorDetail.java
    ├── persistence/                     # Database implementations
    │   ├── entities/
    │   │   └── UserEntity.java              # JPA Entity
    │   └── repositories/
    │       ├── UserRepositoryImpl.java      # Repository implementation
    │       └── jpa/
    │           └── UserJpaRepository.java   # Spring Data JPA
    ├── security/                        # Security implementations
    │   └── JwtAuthenticationFilter.java     # JWT filter
    └── services/                        # Service implementations
        ├── JwtServiceImpl.java              # JWT implementation
        ├── PasswordEncoderImpl.java         # Password encoder impl
        ├── CurrentUserServiceImpl.java
        └── JacksonObjectMapperService.java
```

> Note on response DTOs
>
> The project uses a shared response wrapper to keep API responses consistent. The current shape is:
>
> {
>   "data": { ... },         // the payload
>   "meta": {                // metadata object
>     "timestamp": "...",  // ISO timestamp
>     "pagination": { ... }  // optional, present for paginated responses
>   },
>   "code": "OK",          // top-level string code (e.g. "OK", "ERROR")
>   "message": "..."       // top-level human-readable message (English)
> }
>
> - Use `ResponseDto` for normal responses. It contains `data`, `meta`, `code` and `message`.
> - Use `PaginationResponseDto` for paginated results: `data` is a list, `meta.pagination` contains pagination info, `code` and `message` remain top-level strings.

---

## 🔄 Application Flow

### 1️⃣ Application Startup Flow

```
┌─────────────────────────────────────────────────────────────┐
│  1. Spring Boot Application Starts                          │
│     (GraduationProjectBeApplication.main())                 │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  2. Load Configuration Files                                │
│     - application.yaml                                      │
│     - application-local.yaml / application-development.yaml │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  3. Initialize Spring Beans                                 │
│     - SecurityConfiguration                                 │
│     - UsecasesConfiguration                                 │
│     - ApplicationConfiguration                              │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  4. Liquibase Database Migration                            │
│     - Read: db/changelog/grad-changelog-master.yaml         │
│     - Execute: grad-changelog-init.yaml                     │
│     - Create tables: users, etc.                            │
│     - Insert seed data                                      │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  5. Initialize Security Filter Chain                        │
│     - JwtAuthenticationFilter                               │
│     - CORS Configuration                                    │
│     - Permit: /api/auth/**                                  │
│     - Authenticate: all other endpoints                     │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  6. Application Ready to Accept Requests                    │
│     🚀 Server running on port 8080                          │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  7. Application Ready to Accept Requests                    │
└────────────────────────┬────────────────────────────────────┘
                         ↓
                    [Client]
```

---

### 2️⃣ Login Flow (Authentication)

```
  [Client]
     │
     │ POST /api/auth/login
     │ Body: { email, password }
     ↓
┌─────────────────────────────────────────────────────────────┐
│  AuthController.login()                                     │
│  📍 adapter/web/api/controller/AuthController.java          │
│                                                             │
│  1. Validate request (Spring Validation)                   │
│  2. Convert LoginRequestDto → LoginRequest                 │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  LoginUsecase.execute()                                     │
│  📍 application/usecases/LoginUsecase.java                  │
│                                                             │
│  3. Call userRepository.findByEmail(email)                 │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  UserRepositoryImpl.findByEmail()                           │
│  📍 infrastructure/persistence/repositories/                │
│      UserRepositoryImpl.java                                │
│                                                             │
│  4. Call userJpaRepository.findByEmail()                   │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  UserJpaRepository (Spring Data JPA)                        │
│  📍 infrastructure/persistence/repositories/jpa/            │
│      UserJpaRepository.java                                 │
│                                                             │
│  5. Execute SQL: SELECT * FROM users WHERE email = ?       │
└────────────────────────┬────────────────────────────────────┘
                         ↓
                   [PostgreSQL]
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  6. Return UserEntity → Convert to User (domain model)      │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  LoginUsecase (continued)                                   │
│                                                             │
│  7. If user not found → throw UnauthorizedException        │
│  8. Verify password: passwordEncoder.matches()             │
│     - Compare plaintext with BCrypt hash                   │
│  9. If password wrong → throw UnauthorizedException        │
│  10. Generate JWT: jwtService.generateToken(user)          │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  JwtServiceImpl.generateToken()                             │
│  📍 infrastructure/services/JwtServiceImpl.java             │
│                                                             │
│  11. Create JWT with:                                      │
│      - email (subject)                                     │
│      - role (claim)                                        │
│      - expiration time                                     │
│      - Sign with secret key                                │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  12. Return LoginResponse with JWT token                    │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  AuthController (continued)                                 │
│                                                             │
│  13. Convert LoginResponse → LoginResponseDto              │
│  14. Wrap in ResponseDto                                   │
│  15. Return HTTP 200 OK                                    │
└────────────────────────┬────────────────────────────────────┘
                         ↓
                    [Client]
- Receives top-level fields: `data`, `meta` (containing timestamp and optional pagination), `code` (String), `message` (String)
```

---

### 3️⃣ Refresh Token Flow (Token Rotation)

```
  [Client]
     │
     │ POST /api/auth/refresh
     │ Body: { refreshToken }
     ↓
┌─────────────────────────────────────────────────────────────┐
│  AuthController.refresh()                                   │
│  📍 adapter/web/api/controller/AuthController.java          │
│                                                             │
│  1. Validate request (Spring Validation)                   │
│  2. Convert RefreshTokenRequestDto → RefreshTokenRequest   │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  RefreshUsecase.execute()                                   │
│  📍 application/usecases/RefreshUsecase.java                │
│                                                             │
│  3. Validate refresh token: jwtService.validateToken()     │
│  4. Extract userId, tokenId from token                      │
│  5. Lookup token in Redis: refreshTokenRepository.find()   │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  6. If token not found:                                     │
│     - Revoke all tokens: refreshTokenRepository.deleteAll   │
│     - Throw UnauthorizedException (reuse detected)          │
│                                                             │
│  7. If hash mismatch:                                       │
│     - Revoke all tokens: refreshTokenRepository.deleteAll   │
│     - Throw UnauthorizedException (reuse detected)          │
│                                                             │
│  8. Verify hash: refreshTokenHasher.verify()               │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  9. Generate new tokens:                                    │
│     - New access token (short-lived)                        │
│     - New refresh token (long-lived)                        │
│  10. Save new refresh token to Redis                        │
│  11. Return new access token + refresh token               │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  AuthController (continued)                                 │
│                                                             │
│  12. Convert RefreshTokenResponse → RefreshTokenResponseDto │
│  13. Wrap in ResponseDto                                   │
│  14. Return HTTP 200 OK                                    │
└────────────────────────┬────────────────────────────────────┘
                         ↓
                    [Client]
- Receives new access token in `data`, meta with timestamp, code "OK", message "Token refreshed"
```

---

### 4️⃣ Logout Flow (Token Revocation)

```
  [Client]
     │
     │ POST /api/auth/logout
     │ Body: { refreshToken }
     ↓
┌─────────────────────────────────────────────────────────────┐
│  AuthController.logout()                                    │
│  📍 adapter/web/api/controller/AuthController.java          │
│                                                             │
│  1. Validate request (Spring Validation)                   │
│  2. Convert LogoutRequestDto → LogoutRequest               │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  LogoutUsecase.execute()                                    │
│  📍 application/usecases/LogoutUsecase.java                │
│                                                             │
│  3. If token is blank → return (logout succeeds)            │
│  4. Try to validate token: jwtService.validateToken()      │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  5a. If token is valid:                                     │
│      - Extract userId, tokenId                             │
│      - Delete specific token: refreshTokenRepository.delete │
│                                                             │
│  5b. If token is expired/invalid (catch Exception):        │
│      - Try to extract userId (JWT claims still accessible) │
│      - Delete all user tokens: deleteAllByUserId()          │
│      - Ensures complete logout even with expired token      │
│                                                             │
│  6. Return successfully (no exception thrown)               │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  AuthController (continued)                                 │
│                                                             │
│  7. Return HTTP 200 OK                                     │
│  8. Response: { "message": "Logout successful" }            │
└────────────────────────┬────────────────────────────────────┘
                         ↓
                    [Client]
- Logout always succeeds with HTTP 200, regardless of token state
```

---

### 5️⃣ Authenticated Request Flow (with JWT)

```
  [Client]
     │
     │ GET /api/users/...
     │ Header: Authorization: Bearer <JWT>
     ↓
┌─────────────────────────────────────────────────────────────┐
│  JwtAuthenticationFilter.doFilterInternal()                 │
│  📍 infrastructure/security/JwtAuthenticationFilter.java    │
│                                                             │
│  1. Extract JWT from Authorization header                  │
│  2. Validate token: jwtService.validateToken(jwt)          │
│  3. Extract email: jwtService.extractEmail(jwt)            │
│  4. Extract role: jwtService.extractRole(jwt)              │
│  5. Create Authentication object                           │
│  6. Set SecurityContext                                    │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  SecurityFilterChain                                        │
│                                                             │
│  7. Check authorization rules                              │
│     - @PreAuthorize annotations                            │
│     - Role-based access control                            │
└────────────────────────┬────────────────────────────────────┘
                         ↓
┌─────────────────────────────────────────────────────────────┐
│  UserController / Other Controllers                         │
│                                                             │
│  8. Process request                                        │
│  9. Call appropriate use case                              │
│  10. Return response                                       │
└────────────────────────┬────────────────────────────────────┘
                         ↓
                    [Client]
```

---

## 📚 Layer Details

### 1. 📱 Adapter Layer

**Responsibility:** Receive requests from clients and return responses

**Components:**
- **Controllers:** Handle HTTP requests (resource-based, with `@PreAuthorize` for authorization)
  - `AuthController`: Endpoint `/api/auth/**` (login, refresh, logout)
  - `ClassController`: Endpoint `/api/classes/**` (CRUD classes, nested students)
  - `ExamController`: Endpoint `/api/exams/**` (create exam, get exam detail)
  - `UserController`: Endpoint `/api/users/**`
- **DTOs:** Data Transfer Objects for JSON serialization/deserialization
- **Exceptions:** Web-specific exceptions

**Rules:**
- ✅ Validate input
- ✅ Convert DTOs ↔ Domain models
- ✅ Call use cases
- ❌ NO business logic
- ❌ NO direct database access

---

### 2. 💼 Application Layer

**Responsibility:** Contains business logic and orchestration

**Components:**
- **Use Cases:** Business flows
  - `LoginUsecase`: Handle login logic
  - `RefreshUsecase`: Handle token refresh and rotation
  - `LogoutUsecase`: Handle logout and token revocation
- **Ports (Interfaces):** Define contracts
  - `UserRepository`: Interface for user queries
  - `JwtService`: Interface for JWT handling
  - `PasswordEncoder`: Interface for password encoding
- **Exceptions:** Application-level exceptions

**Rules:**
- ✅ Contains business logic
- ✅ Orchestrate multiple repositories/services
- ✅ Throw domain/application exceptions
- ✅ Only depends on Domain and Ports (interfaces)
- ❌ NO knowledge of HTTP, Database, Framework

---

### 3. 🎯 Domain Layer

**Responsibility:** Core business objects and rules

**Components:**
- **Models:** Domain objects (POJOs)
  - `User`: Domain model (not JPA Entity)
  - `PaginatedResult`, `PaginationParams`
- **Enums:** Domain enumerations
- **Exceptions:** Business rule violations

**Rules:**
- ✅ Pure Java objects (POJOs)
- ✅ Business rules and validations
- ✅ No framework annotations (@Entity, @Table, etc.)
- ❌ NO dependencies on any layer
- ❌ NO knowledge of database, framework, infrastructure

---

### 4. 🔧 Infrastructure Layer

**Responsibility:** Implement Ports and integrate with external systems

**Components:**

**a) Persistence:**
- `UserEntity`: JPA Entity (with @Entity, @Table)
- `UserJpaRepository`: Spring Data JPA interface
- `UserRepositoryImpl`: Implementation of UserRepository port

**b) Security:**
- `JwtAuthenticationFilter`: Filter to validate JWT
- `SecurityConfiguration`: Spring Security config

**c) Services:**
- `JwtServiceImpl`: Implementation of JwtService port
- `PasswordEncoderImpl`: Implementation of PasswordEncoder port

**d) Configurations:**
- `UsecasesConfiguration`: Wiring use cases with dependencies
- `ApplicationConfiguration`: General app configs

**e) Error Handling:**
- `GlobalExceptionHandler`: Centralized exception handling

**f) Redis:**
- `RedisConfiguration`: Configuration for Redis connection
- `RedisRefreshTokenRepository`: Implementation for refresh token storage

**Rules:**
- ✅ Implement interfaces from Application layer
- ✅ Use framework specifics (JPA, Spring, etc.)
- ✅ Convert Entity ↔ Domain model
- ✅ Handle technical concerns (logging, transactions, etc.)

---

## 🔐 Authentication - Access + Refresh (HttpOnly cookie) with Redis Rotation

This project now implements a secure Access + Refresh Token strategy (similar to Auth0/Okta/Google Identity):

- Access token: short-lived JWT returned in the JSON response body.
- Refresh token: long-lived JWT stored in an HttpOnly, SameSite cookie. Refresh tokens are stored hashed in Redis using the key pattern `rt:{userId}:{tokenId}`.
- Rotation: refresh tokens are rotated on `/auth/refresh`. On reuse detection the backend revokes all tokens for that user.

### Endpoints

1) **POST /api/auth/login**
   - Request body: `{ "email": string, "password": string }`
   - Response body (JSON):
     ```json
     {
       "data": { "accessToken": "...", "refreshToken": "...", "accessTokenExpiresAt": "...", "refreshTokenExpiresAt": "..." },
       "meta": { "timestamp": "..." },
       "code": "OK",
       "message": "Login successful"
     }
     ```
   - Creates new access and refresh tokens
   - Revokes any previous tokens for this user (single-device session)

2) **POST /api/auth/refresh**
   - Request body: `{ "refreshToken": string }`
   - Response body (JSON):
     ```json
     {
       "data": { "accessToken": "...", "refreshToken": "...", "accessTokenExpiresAt": "...", "refreshTokenExpiresAt": "..." },
       "meta": { "timestamp": "..." },
       "code": "OK",
       "message": "Token refreshed"
     }
     ```
   - Validates refresh token and generates new access token
   - Detects token reuse and revokes all tokens for that user if detected
   - Rotates refresh token

3) **POST /api/auth/logout**
   - Request body: `{ "refreshToken": string }`
   - Response body (JSON):
     ```json
     {
       "message": "Logout successful"
     }
     ```
   - Deletes refresh token from Redis
   - Handles expiration gracefully (logout succeeds even if token is expired)

### Single-Device Session Policy

This application enforces a **single active session per user**: when a successful login occurs, any existing refresh tokens for that user (from other devices/sessions) are revoked. In other words: one device allowed to be logged in at a time; a new login automatically signs out previous session(s).
- Redis is used to persist hashed refresh tokens with TTL (recommended: SHA-256 + pepper or BCrypt hashing). The project includes a `RedisConfiguration` and a `RedisRefreshTokenRepository` implementation.
- Key pattern used: `rt:{userId}:{tokenId}` → hashedValue

### Response wrapper (adapter layer)
The API uses a single response wrapper placed in the adapter layer (package `adapter.web.api.dtos.response`). Shape:

{
  "data": { ... },
  "meta": {
    "timestamp": "...",
    "pagination": { ... } // present only for paginated responses
  },
  "code": "OK",   // string code - top level
  "message": "Human readable message"
}

Notes:
- `ResponseDto` (used by non-paginated responses) and `PaginationResponseDto` (used only for paginated controller responses) live in the adapter layer.
- `code` and `message` are top-level strings (not numeric) as requested.
- Refresh tokens are deliberately not returned inside `data` — they are set as HttpOnly cookies.

### Security notes
- In production set `.secure(true)` on cookies and configure cookie domain/path appropriately.
- Use environment variables to control cookie attributes and Redis connection.

---

## 💾 Database Migration (Liquibase)

```
src/main/resources/db/changelog/
├── grad-changelog-master.yaml     # Main changelog (include other files)
└── grad-changelog-init.yaml       # Initial schema + seed data

Migration Flow:
1. Application starts
2. Liquibase checks liquibase.DATABASECHANGELOG table
3. Executes changesets that haven't been applied
4. Creates tables, inserts seed data
5. Marks changesets as executed
```

**Example Changeset:**
```yaml
- changeSet:
    id: 0811252046
    author: manhuynh
    changes:
      - insert:
          tableName: users
          columns:
            - column:
                name: email
                value: 22120201@student.hcmus.edu.vn
            - column:
                name: password
                value: $2b$12$...  # BCrypt hashed password
```

---

## 🚀 Getting Started

### Prerequisites
```bash
- Java 21
- PostgreSQL
- Gradle (or use ./gradlew)
```

### Step 1: Clone repository
```bash
git clone https://github.com/mandeotv1234/graduation-project-be.git
cd graduation-project-be
```

### Step 2: Configure Database
Create database in PostgreSQL:
```sql
CREATE DATABASE graduation_project;
```

Update file `src/main/resources/application-local.yaml`:
```yaml
spring:
  datasource:
    url: jdbc:postgresql://localhost:5432/graduation_project
    username: your_username
    password: your_password
```

### Step 3: Build project
```bash
./gradlew build
```

### Step 4: Run application
```bash
./gradlew bootRun
```

Or run from IDE (Run `GraduationProjectBeApplication.main()`)

### Step 5: Test API

**Login Example:**
```bash
curl -X POST http://localhost:8080/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "22120201@student.hcmus.edu.vn",
    "password": "your_password"
  }'
```

**Response:**
```json
{
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "accessTokenExpiresAt": "2025-12-04T12:34:56",
    "refreshTokenExpiresAt": "2025-12-18T12:34:56"
  },
  "meta": {
    "timestamp": "2025-11-20T10:30:00"
  },
  "code": "OK",
  "message": "Login successful"
}
```

**Refresh Token Example:**
```bash
curl -X POST http://localhost:8080/api/auth/refresh \
  -H "Content-Type: application/json" \
  -d '{
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
  }'
```

**Response:**
```json
{
  "data": {
    "accessToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9...",
    "accessTokenExpiresAt": "2025-12-04T12:35:00",
    "refreshTokenExpiresAt": "2025-12-18T12:35:00"
  },
  "meta": {
    "timestamp": "2025-11-20T10:30:30"
  },
  "code": "OK",
  "message": "Token refreshed"
}
```

**Logout Example:**
```bash
curl -X POST http://localhost:8080/api/auth/logout \
  -H "Content-Type: application/json" \
  -d '{
    "refreshToken": "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
  }'
```

**Response:**
```json
{
  "message": "Logout successful"
}
```

**Authenticated Request Example:**
```bash
curl -X GET http://localhost:8080/api/users/me \
  -H "Authorization: Bearer eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9..."
```

---

## 🧪 Testing Strategy

```
Unit Tests:
  - Use Cases (business logic)
  - Domain Models

Integration Tests:
  - Repository implementations
  - API endpoints (Controller tests)

Test Structure:
src/test/java/graduation_project_be/
  - GraduationProjectBeApplicationTests.java
  - usecases/
  - repositories/
  - controllers/
```

---

## 📝 Best Practices

### 1. Dependency Direction
```
❌ DON'T: Domain depends on Infrastructure
✅ DO: Infrastructure depends on Domain
```

### 2. Use Interfaces (Ports)
```
❌ DON'T: Use case directly uses JwtServiceImpl
✅ DO: Use case depends on JwtService interface
```

### 3. Convert Between Layers
```
HTTP → DTO → Use Case Request → Domain Model → Entity → Database
Database → Entity → Domain Model → Use Case Response → DTO → HTTP
```

### 4. Exception Handling
```
Domain → throw InvalidBusinessRuleException
Application → throw UnauthorizedException
Infrastructure → catch and convert to appropriate exception
Adapter → GlobalExceptionHandler converts to HTTP response
```

---

## 🔄 Workflow: Adding New Features

### Example: Add "Register User" Feature

**Step 1: Domain Layer**
```java
// No changes needed if User model is sufficient
```

**Step 2: Application Layer**
```java
// Create port if needed
public interface UserRepository {
    User save(User user);
    Optional<User> findByEmail(String email);
}

// Create use case
public class RegisterUsecase {
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    
    public RegisterResponse execute(RegisterRequest request) {
        // Business logic here
    }
}
```

**Step 3: Infrastructure Layer**
```java
// Implement repository
@Repository
public class UserRepositoryImpl implements UserRepository {
    public User save(User user) {
        // Implementation
    }
}

// Configure bean
@Configuration
public class UsecasesConfiguration {
    @Bean
    RegisterUsecase registerUsecase(...) {
        return new RegisterUsecase(...);
    }
}
```

**Step 4: Adapter Layer**
```java
// Create DTOs
public record RegisterRequestDto(String email, String password) {}

// Create controller method
@PostMapping("/register")
public ResponseEntity<ResponseDto> register(@RequestBody RegisterRequestDto dto) {
    // Call use case and return response
}
```

**Step 5: Update Security**
```java
// SecurityConfiguration: permit /api/auth/register
.requestMatchers("/api/auth/**").permitAll()
```

**Step 6: Update README or docs**
```text
Add new endpoint and example request/response
```

---

## 📖 References

- [Spring Boot Documentation](https://spring.io/projects/spring-boot)
- [Clean Architecture by Robert C. Martin](https://blog.cleancoder.com/uncle-bob/2012/08/13/the-clean-architecture.html)

---

## 👥 Contributors

- **Author:** manhuynh
- **Project:** Graduation Project Backend

---

## 📄 License

[Specify your license here]

---

**Last Updated:** December 22, 2025
