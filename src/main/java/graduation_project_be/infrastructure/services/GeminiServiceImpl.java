package graduation_project_be.infrastructure.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import graduation_project_be.application.port.services.GeminiService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

@Slf4j
@Service
public class GeminiServiceImpl implements GeminiService {

    private static final String GEMINI_URL =
            "https://generativelanguage.googleapis.com/v1beta/models/gemini-2.5-flash-lite:generateContent?key=";

    private final String apiKey;
    private volatile HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public GeminiServiceImpl(@Value("${spring.application.gemini.api-key}") String apiKey) {
        this.apiKey = apiKey;
        this.httpClient = null;
        this.objectMapper = new ObjectMapper();
    }

    @Override
    public GeneratedQuestion generateSqlAnswer(String questionContent, String questionType, String schemaContext) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("Gemini API key is missing. Skipping AI generation.");
            return new GeneratedQuestion("-- AI generation unavailable: missing Gemini API key", null);
        }

        HttpClient client = getOrCreateHttpClient();
        if (client == null) {
            return new GeneratedQuestion("-- AI generation unavailable: HTTP client initialization failed", null);
        }

        String prompt = buildPrompt(questionContent, questionType, schemaContext);
        String requestBody = buildRequestBody(prompt);

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(GEMINI_URL + apiKey))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(requestBody))
                    .timeout(Duration.ofSeconds(30))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                log.error("Gemini API error {}: {}", response.statusCode(), response.body());
                return new GeneratedQuestion("-- AI generation failed", null);
            }

            return parseResponse(response.body());

        } catch (Exception e) {
            log.error("Failed to call Gemini API: {}", e.getMessage(), e);
            return new GeneratedQuestion("-- AI generation failed: " + e.getMessage(), null);
        }
    }

    private HttpClient getOrCreateHttpClient() {
        if (httpClient != null) {
            return httpClient;
        }

        synchronized (this) {
            if (httpClient != null) {
                return httpClient;
            }
            try {
                httpClient = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(15))
                        .build();
                return httpClient;
            } catch (Exception e) {
                log.error("Failed to initialize HTTP client for Gemini: {}", e.getMessage(), e);
                return null;
            }
        }
    }

    private String buildPrompt(String questionContent, String questionType, String schemaContext) {
        return String.format("""
                Bạn là chuyên gia SQL cho hệ thống thi thực hành cơ sở dữ liệu tại trường đại học.
                Mỗi sinh viên làm bài trên một schema MSSQL (SQL Server) riêng biệt, được tạo sẵn nhưng rỗng.
                Sinh viên phải tự tạo bảng, thêm dữ liệu và viết truy vấn theo yêu cầu.

                === MÔ TẢ SCHEMA CỦA BÀI THI ===
                %s

                === VÍ DỤ THỰC TẾ (từ bài thi Quản lý thuê xe) ===

                Ví dụ 1 — Loại CREATE_TABLE:
                  Câu hỏi: "Tạo bảng HopDong gồm: SoHD (INT, khóa chính), MaXe (INT), MaKH (INT), NgayThue (DATE), NgayTra (DATE)"
                  correctQuery: "SELECT COLUMN_NAME, DATA_TYPE FROM INFORMATION_SCHEMA.COLUMNS WHERE TABLE_NAME='HopDong' ORDER BY ORDINAL_POSITION"
                  verifyScript: "SELECT 'SoHD' AS COLUMN_NAME, 'int' AS DATA_TYPE UNION ALL SELECT 'MaXe', 'int' UNION ALL SELECT 'MaKH', 'int' UNION ALL SELECT 'NgayThue', 'date' UNION ALL SELECT 'NgayTra', 'date'"

                Ví dụ 2 — Loại INSERT_DATA:
                  Câu hỏi: "Thêm đúng 3 dòng vào bảng KhachHang: (1, N'Nguyen Van A', '0901234567'), (2, N'Tran Thi B', '0912345678'), (3, N'Le Van C', '0923456789')"
                  correctQuery: "SELECT MaKH, HoTen, DienThoai FROM KhachHang ORDER BY MaKH"
                  verifyScript: "SELECT 1 AS MaKH, N'Nguyen Van A' AS HoTen, N'0901234567' AS DienThoai UNION ALL SELECT 2, N'Tran Thi B', N'0912345678' UNION ALL SELECT 3, N'Le Van C', N'0923456789'"

                Ví dụ 3 — Loại SELECT_QUERY:
                  Câu hỏi: "Viết truy vấn JOIN HopDong với KhachHang và Xe, hiển thị SoHD, HoTen, BienSo, NgayThue, NgayTra"
                  correctQuery: "SELECT hd.SoHD, kh.HoTen, x.BienSo, hd.NgayThue, hd.NgayTra FROM HopDong hd JOIN KhachHang kh ON hd.MaKH = kh.MaKH JOIN Xe x ON hd.MaXe = x.MaXe ORDER BY hd.SoHD"
                  verifyScript: "SELECT 1 AS SoHD, N'Nguyen Van A' AS HoTen, N'30A-12345' AS BienSo, CAST('2025-01-01' AS DATE) AS NgayThue, CAST('2025-01-05' AS DATE) AS NgayTra UNION ALL SELECT 2, N'Tran Thi B', N'51B-67890', CAST('2025-02-10' AS DATE), CAST('2025-02-15' AS DATE)"

                Ví dụ 4 — Loại TRIGGER:
                  Câu hỏi: "Tạo Trigger chặn insert vào HopDong khi NgayTra < NgayThue"
                  correctQuery: "SELECT 'PASS' AS result"
                  verifyScript: "BEGIN TRY INSERT INTO HopDong (SoHD, MaXe, MaKH, NgayThue, NgayTra) VALUES (999, 1, 1, '2025-12-31', '2025-01-01'); DELETE FROM HopDong WHERE SoHD = 999; SELECT 'FAIL' AS result; END TRY BEGIN CATCH SELECT 'PASS' AS result; END CATCH"

                Ví dụ 5 — Loại FUNCTION:
                  Câu hỏi: "Tạo FUNCTION fn_TinhSoNgayThue(@SoHD INT) tính số ngày thuê"
                  correctQuery: "SELECT 4 AS result"
                  verifyScript: "SELECT {SCHEMA}.fn_TinhSoNgayThue(1) AS result"

                Ví dụ 6 — Loại STORED_PROCEDURE:
                  Câu hỏi: "Tạo SP sp_ThemHopDong thêm một dòng vào HopDong"
                  correctQuery: "SELECT 998 AS SoHD, 1 AS MaXe, 1 AS MaKH"
                  verifyScript: "EXEC sp_ThemHopDong 998, 1, 1, '2025-06-01', '2025-06-05'; SELECT SoHD, MaXe, MaKH FROM HopDong WHERE SoHD = 998; DELETE FROM HopDong WHERE SoHD = 998"

                === QUAN TRỌNG ===
                - Database: Microsoft SQL Server (MSSQL / T-SQL). KHÔNG dùng cú pháp MySQL/PostgreSQL.
                
                - **correctQuery**: KHÔNG phải câu tạo bảng (CREATE TABLE)! Là câu SQL để xác minh kết quả sinh viên:
                  * CREATE_TABLE: Dùng INFORMATION_SCHEMA để SELECT cấu trúc bảng
                  * INSERT_DATA: SELECT dữ liệu đã thêm
                  * SELECT_QUERY: Chính là câu SELECT cần viết
                  * TRIGGER/FUNCTION/STORED_PROCEDURE: SELECT kết quả mong muốn (ví dụ: "SELECT 'PASS'")
                
                - **verifyScript**: Dùng để so sánh (hardcoded expected data):
                  * CREATE_TABLE: Kết quả cột+kiểu dữ liệu mong muốn
                  * INSERT_DATA: Dữ liệu chính xác cần thêm
                  * SELECT_QUERY: Kết quả mong muốn từ câu SELECT
                  * TRIGGER: Test hành vi (TRY-CATCH) → "PASS" hoặc "FAIL"
                  * FUNCTION: Gọi hàm với tham số cụ thể → kết quả số
                  * STORED_PROCEDURE: EXEC + SELECT kết quả sau đó
                
                - Với FUNCTION: dùng placeholder {SCHEMA} nếu cần gọi hàm user-defined
                - Sử dụng NVARCHAR thay VARCHAR cho tiếng Việt
                - Nếu câu hỏi không rõ, vẫn phải tạo correctQuery + verifyScript hợp lý nhất

                === YÊU CẦU HIỆN TẠI ===
                Loại câu hỏi: %s
                Nội dung câu hỏi: %s

                Trả lời ĐÚNG ĐỊNH DẠNG JSON sau (không markdown, không giải thích thêm):
                {
                  "correctQuery": "<SQL để xác minh: SELECT cấu trúc/dữ liệu/kết quả hoặc hardcoded expected result>",
                  "verifyScript": "<SQL kiểm tra: dữ liệu/kết quả mong muốn cụ thể>"
                }
                """,
                schemaContext != null && !schemaContext.isBlank() ? schemaContext : "Không có thông tin schema",
                questionType,
                questionContent);
    }

    private String buildRequestBody(String prompt) {
        try {
            String escaped = objectMapper.writeValueAsString(prompt);
            // escaped includes surrounding quotes, remove them
            escaped = escaped.substring(1, escaped.length() - 1);
            return String.format("""
                    {
                      "contents": [{"parts": [{"text": "%s"}]}],
                      "generationConfig": {
                        "temperature": 0.1,
                        "maxOutputTokens": 1024
                      }
                    }
                    """, escaped);
        } catch (Exception e) {
            throw new RuntimeException("Failed to build Gemini request body", e);
        }
    }

    private GeneratedQuestion parseResponse(String body) {
        try {
            JsonNode root = objectMapper.readTree(body);
            String text = root
                    .path("candidates").get(0)
                    .path("content")
                    .path("parts").get(0)
                    .path("text").asText();

            // Strip markdown code blocks if present
            text = text.replaceAll("```json\\s*", "").replaceAll("```\\s*", "").trim();

            JsonNode result = objectMapper.readTree(text);
            String correctQuery = result.path("correctQuery").asText("-- no query generated");
            String verifyScript = result.path("verifyScript").asText(null);

            return new GeneratedQuestion(correctQuery, verifyScript.isBlank() ? null : verifyScript);

        } catch (Exception e) {
            log.error("Failed to parse Gemini response: {}", e.getMessage());
            return new GeneratedQuestion("-- failed to parse AI response", null);
        }
    }
}
