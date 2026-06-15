# Graduation Project BE AI Context

This backend is a SQL exam system for university database practice. Teachers create exams, database specifications, questions, grading rubrics, and PDFs. Students answer SQL in isolated Microsoft SQL Server schemas. AI is used only to generate supporting SQL/rubric/schema text; backend code validates/parses/executes the result.

## AI Entry Points

All AI calls go through `graduation_project_be.application.port.services.AIService`.

### 1. `generateSqlAnswer(questionContent, questionType, schemaContext)`

Call sites:

- `CreateExamQuestionUsecase`: called when a teacher creates one question and `correctQuery` is blank.
- `CreateExamQuestionsUsecase`: called per batch item when `correctQuery` is blank.

Purpose:

- Generate `correctQuery` and optionally `verifyScript` for a SQL exam question.

Required output:

```json
{
  "correctQuery": "...",
  "verifyScript": "..."
}
```

Rules:

- SQL dialect is MSSQL / T-SQL only.
- Respect `questionType`: `CREATE_TABLE`, `INSERT_DATA`, `SELECT_QUERY`, `TRIGGER`, `FUNCTION`, `STORED_PROCEDURE`.
- Use exact table/column names from `questionContent` and `schemaContext`.
- For `INSERT_DATA`, include all rows/values from the question. Do not invent or omit data.
- For routines, use `{SCHEMA}` when schema-qualified execution is needed.

### 2. `generateGradingRubric(correctQuery, questionContent, totalPoints, questionType, priorQuestionContext, schemaContext)`

Call sites:

- `RubricTestingUsecase.generateGradingRubric`: API endpoint `/generate-rubric`.
- `CreateExamQuestionsUsecase`: after saving routine/trigger questions, generates rubric and test cases if rubric is missing.

Purpose:

- Generate grading rubric JSON from teacher/reference SQL.

Question categories:

- `CREATE_TABLE`: rubric must describe expected tables, columns, data types, PK/FK/UNIQUE/CHECK/DEFAULT constraints, and deduction penalties.
- `INSERT_DATA`: rubric must describe rows/cells and deduction rules.
- `SELECT_QUERY`: rubric must create high-quality test cases and grading rules; backend derives expected results by running the reference SQL where applicable.
- `FUNCTION` / `STORED_PROCEDURE`: category is `ROUTINE`; generate routine metadata and 3-6 executable test cases.
- `TRIGGER`: generate trigger metadata and side-effect test cases.

Rules:

- Return only valid JSON.
- Do not include markdown, comments, explanations, or trailing text.
- For routine/trigger rubrics, do not invent `expected_value`, `expected_result`, or `expected_output` when the backend derives expected values.
- Test case scripts must be executable T-SQL.
- Read DDL/schema context carefully: column names, column lengths, PK/FK, and nullable constraints matter.
- Keep Vietnamese descriptions natural and accented.

### 3. `generateSpecificationSchema(specificationDescription, currentSchemaJson)`

Call site:

- `GenerateSpecificationSchemaUsecase`: used by the teacher schema builder UI.

Purpose:

- Convert a teacher's business description plus an optional current schema into a full schema JSON snapshot.

Required output:

```json
[
  {
    "tableName": "string",
    "columns": [
      {
        "columnName": "string",
        "dataType": "string",
        "primaryKey": true,
        "foreignKey": false,
        "referencesTable": null,
        "referencesColumn": null,
        "nullable": false,
        "unique": true,
        "autoIncrement": true
      }
    ]
  }
]
```

Rules:

- Return a full snapshot, not a patch.
- If a column is not FK: `foreignKey=false`, `referencesTable=null`, `referencesColumn=null`.
- If a column is FK: referenced table/column must exist.
- PK columns should be `nullable=false`; numeric PKs often use `autoIncrement=true`.
- Use practical SQL Server types such as `INT`, `BIGINT`, `NVARCHAR(255)`, `DATETIME`, `DECIMAL(10,2)`.

### 4. `generateEntityDescription(entityName, displayName, attributes, schemaContext)`

Call sites:

- `GenerateSpecEntityDescriptionUsecase`: teacher asks AI to generate one entity description.
- `ExportExamPdfUsecase`: lazily generates missing entity descriptions before rendering exam PDF.

Purpose:

- Generate a Vietnamese "Tân từ" paragraph for one database entity.

Required output:

- Plain text only.
- Must start with `Tân từ:`.
- 2-4 natural Vietnamese sentences, max roughly 120 words.
- No markdown, no bullets.
- Do not mention "entity", "attribute", "foreign key", "primary key", or "database".
- Avoid generic phrases such as "lưu trữ thông tin chi tiết".

### 5. `extractQuestionsFromPdf(pdfBytes, schemaContext)`

Call site:

- `ExtractQuestionsFromPdfUsecase`: PDF bytes are converted to text with PDFBox before sending to AI.

Purpose:

- Extract `schemaScript` and question drafts from an uploaded SQL exam PDF.

Required output:

```json
{
  "schemaScript": "",
  "questions": [
    {
      "title": "short title",
      "content": "full question text",
      "questionType": "SELECT_QUERY",
      "points": 1.0,
      "difficultyLevel": 2,
      "orderIndex": 1
    }
  ]
}
```

Rules:

- Use only table/column names visible in the PDF text.
- If schema is not visible, use `schemaScript=""`.
- Do not hallucinate generic training examples such as `SinhVien`, `NhanVien`, `KhachHang`, `DonHang` unless they are present in the PDF.
- Output MSSQL/T-SQL schema only.
- Copy the full question text exactly enough for teacher review.

## Backend Parser Constraints

- `CodexVmClient.askForJson(...)` strips markdown fences, then backend parses with Jackson `ObjectMapper.readTree`.
- Invalid JSON means the backend call fails or returns null.
- When text is expected, return only the text payload.
- Keep output deterministic and compact.

## Payload Size Guidance

Dynamic payloads that still matter per request:

- Teacher question content.
- `correctQuery`.
- `schemaContext` / DDL.
- Current schema JSON.
- PDF extracted text.
- Prior question context.

Do not ask for missing project context when the task-specific payload is enough. Use this file for stable behavior, and use the prompt payload as source of truth.
