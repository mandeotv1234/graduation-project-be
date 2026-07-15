package graduation_project_be.infrastructure.services;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.stream.Stream;

class RubricPromptTemplateFormatTest {

    @ParameterizedTest
    @MethodSource("rubricPromptCases")
    void rubricPromptTemplates_formatWithoutInvalidPercentLiteral(PromptCase promptCase) {
        String formatted = assertDoesNotThrow(
                () -> String.format(Locale.ROOT, loadPrompt(promptCase.fileName()), promptCase.args()));

        if ("select_query_rubric_prompt.txt".equals(promptCase.fileName())) {
            assertTrue(formatted.contains("30% điểm"));
        }
    }

    private static Stream<PromptCase> rubricPromptCases() {
        return Stream.of(
                new PromptCase(
                        "create_table_rubric_prompt.txt",
                        "Tạo các bảng theo mô tả",
                        "CREATE TABLE SinhVien (MaSV INT PRIMARY KEY);",
                        10.0,
                        10.0),
                new PromptCase(
                        "create_table_rules_prompt.txt",
                        "Tạo các bảng theo mô tả",
                        "CREATE TABLE SinhVien (MaSV INT PRIMARY KEY);",
                        10.0,
                        10.0),
                new PromptCase(
                        "insert_data_rubric_prompt.txt",
                        "Thêm dữ liệu mẫu",
                        "INSERT INTO SinhVien VALUES (1);",
                        10.0,
                        10.0,
                        10.0),
                new PromptCase(
                        "select_query_rubric_prompt.txt",
                        "Liệt kê sinh viên",
                        "SELECT MaSV FROM SinhVien;",
                        "Không có ngữ cảnh bổ sung",
                        10.0,
                        10.0),
                new PromptCase(
                        "function_rubric_prompt.txt",
                        "Viết hàm tính điểm",
                        "CREATE FUNCTION dbo.fnScore() RETURNS INT AS BEGIN RETURN 1 END",
                        "FUNCTION",
                        10.0,
                        10.0,
                        "CREATE TABLE BangDiem (Diem INT);"),
                new PromptCase(
                        "stored_procedure_rubric_prompt.txt",
                        "Viết procedure cập nhật điểm",
                        "CREATE PROCEDURE dbo.spUpdate AS SELECT 1",
                        "STORED_PROCEDURE",
                        "CREATE TABLE BangDiem (Diem INT);",
                        10.0,
                        10.0),
                new PromptCase(
                        "trigger_rubric_prompt.txt",
                        "Viết trigger kiểm tra điểm âm",
                        "CREATE TRIGGER trg ON BangDiem FOR INSERT AS SELECT 1",
                        10.0,
                        "CREATE TABLE BangDiem (Diem INT);"),
                new PromptCase(
                        "routine_rubric_prompt.txt",
                        "Viết routine tính điểm",
                        "CREATE FUNCTION dbo.fnScore() RETURNS INT AS BEGIN RETURN 1 END",
                        "FUNCTION",
                        "CREATE TABLE BangDiem (Diem INT);",
                        10.0,
                        10.0));
    }

    private static String loadPrompt(String fileName) throws IOException {
        String resourceName = "prompts/" + fileName;
        try (InputStream inputStream = RubricPromptTemplateFormatTest.class
                .getClassLoader()
                .getResourceAsStream(resourceName)) {
            if (inputStream == null) {
                throw new IOException("Prompt resource not found: " + resourceName);
            }
            return new String(inputStream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    private record PromptCase(String fileName, Object... args) {
    }
}
