package graduation_project_be.infrastructure.services;

import jakarta.mail.internet.MimeMessage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import org.springframework.web.util.HtmlUtils;

import java.nio.charset.StandardCharsets;

@Slf4j
@Component
public class TeacherClassMembershipEmailListener {

    private static final String APPLICATION_NAME =
            "Ứng dụng Hỗ trợ Giảng dạy và Đánh giá Học phần Cơ sở Dữ liệu";

    private final JavaMailSender mailSender;
    private final String frontendUrl;

    public TeacherClassMembershipEmailListener(
            JavaMailSender mailSender,
            @Qualifier("frontendUrl") String frontendUrl) {
        this.mailSender = mailSender;
        this.frontendUrl = frontendUrl;
    }

    @Value("${app.mail.enabled:false}")
    private boolean mailEnabled;

    @Value("${app.mail.from:}")
    private String mailFrom;

    @Value("${app.mail.from-name:" + APPLICATION_NAME + "}")
    private String mailFromName;

    @Async("mailExecutor")
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT, fallbackExecution = true)
    public void sendMembershipEmail(TeacherClassMembershipEvent event) {
        if (!mailEnabled) {
            log.debug("Teacher class email is disabled; skipping {} notification to {}",
                    event.action(), event.recipientEmail());
            return;
        }
        if (mailFrom == null || mailFrom.isBlank()) {
            log.error("Teacher class email cannot be sent because app.mail.from is empty");
            return;
        }

        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(
                    message, false, StandardCharsets.UTF_8.name());
            helper.setFrom(mailFrom, mailFromName);
            helper.setTo(event.recipientEmail());
            helper.setReplyTo(event.actorEmail(), displayName(event.actorName(), event.actorEmail()));
            helper.setSubject(subject(event));
            helper.setText(body(event), true);
            mailSender.send(message);
            log.info("Sent teacher class {} email to {}", event.action(), event.recipientEmail());
        } catch (MailException exception) {
            log.error("Failed to send teacher class {} email to {}: {}",
                    event.action(), event.recipientEmail(), exception.getMessage());
        } catch (Exception exception) {
            log.error("Could not build teacher class {} email to {}: {}",
                    event.action(), event.recipientEmail(), exception.getMessage());
        }
    }

    private String subject(TeacherClassMembershipEvent event) {
        return event.action() == TeacherClassMembershipEvent.Action.ADDED
                ? "Bạn được thêm vào lớp " + event.classCode()
                : "Bạn đã được gỡ khỏi lớp " + event.classCode();
    }

    private String body(TeacherClassMembershipEvent event) {
        String recipientName = escape(displayName(event.recipientName(), event.recipientEmail()));
        String actorName = escape(displayName(event.actorName(), event.actorEmail()));
        String actorEmail = escape(event.actorEmail());
        String classCode = escape(event.classCode());
        String semester = event.semester() == null || event.semester().isBlank()
                ? "Chưa cập nhật"
                : escape(event.semester());
        String classUrl = frontendUrl + "/teacher/classes/" + event.classId();
        String classesUrl = frontendUrl + "/teacher/classes";

        if (event.action() == TeacherClassMembershipEvent.Action.ADDED) {
            return emailLayout(
                    "Bạn đã được thêm vào một lớp học",
                    """
                    <p style="margin:0 0 18px;color:#334155;font-size:15px;line-height:1.7">
                      Xin chào <strong style="color:#0f172a">%s</strong>,
                    </p>
                    <p style="margin:0 0 22px;color:#334155;font-size:15px;line-height:1.7">
                      <strong style="color:#0f172a">%s</strong> (%s) đã thêm bạn vào lớp học dưới đây.
                    </p>
                    %s
                    %s
                    <p style="margin:22px 0 0;color:#64748b;font-size:13px;line-height:1.6">
                      Nếu chưa đăng nhập, hệ thống sẽ yêu cầu đăng nhập và tự động đưa bạn trở lại đúng lớp học này.
                      Bạn cũng có thể trả lời email để liên hệ trực tiếp với người mời.
                    </p>
                    """.formatted(
                            recipientName,
                            actorName,
                            actorEmail,
                            classInformation(classCode, semester),
                            actionButton(classUrl, "Mở lớp học")));
        }

        return emailLayout(
                "Quyền truy cập lớp học đã thay đổi",
                """
                <p style="margin:0 0 18px;color:#334155;font-size:15px;line-height:1.7">
                  Xin chào <strong style="color:#0f172a">%s</strong>,
                </p>
                <p style="margin:0 0 22px;color:#334155;font-size:15px;line-height:1.7">
                  <strong style="color:#0f172a">%s</strong> (%s) đã gỡ bạn khỏi lớp học dưới đây.
                </p>
                %s
                %s
                <p style="margin:22px 0 0;color:#64748b;font-size:13px;line-height:1.6">
                  Bạn có thể trả lời email này để liên hệ trực tiếp với người thực hiện.
                </p>
                """.formatted(
                        recipientName,
                        actorName,
                        actorEmail,
                        classInformation(classCode, semester),
                        actionButton(classesUrl, "Mở danh sách lớp")));
    }

    private String emailLayout(String heading, String content) {
        return """
                <!doctype html>
                <html lang="vi">
                <body style="margin:0;padding:0;background:#f1f5f9;font-family:Arial,'Helvetica Neue',sans-serif">
                  <table role="presentation" width="100%%" cellspacing="0" cellpadding="0"
                         style="width:100%%;background:#f1f5f9;padding:32px 12px">
                    <tr>
                      <td align="center">
                        <table role="presentation" width="600" cellspacing="0" cellpadding="0"
                               style="width:100%%;max-width:600px;background:#ffffff;border:1px solid #e2e8f0;border-radius:12px;overflow:hidden">
                          <tr>
                            <td style="background:#1d4ed8;padding:24px 30px">
                              <div style="color:#dbeafe;font-size:12px;font-weight:700;text-transform:uppercase;letter-spacing:.8px">
                                HCMUS · Cơ sở dữ liệu
                              </div>
                              <div style="margin-top:8px;color:#ffffff;font-size:18px;font-weight:700;line-height:1.45">
                                %s
                              </div>
                            </td>
                          </tr>
                          <tr>
                            <td style="padding:30px">
                              <h1 style="margin:0 0 22px;color:#0f172a;font-size:22px;line-height:1.4">%s</h1>
                              %s
                            </td>
                          </tr>
                          <tr>
                            <td style="border-top:1px solid #e2e8f0;padding:18px 30px;color:#94a3b8;font-size:12px;line-height:1.6">
                              Đây là email tự động từ %s.
                            </td>
                          </tr>
                        </table>
                      </td>
                    </tr>
                  </table>
                </body>
                </html>
                """.formatted(APPLICATION_NAME, heading, content, APPLICATION_NAME);
    }

    private String classInformation(String classCode, String semester) {
        return """
                <table role="presentation" width="100%%" cellspacing="0" cellpadding="0"
                       style="width:100%%;margin:0 0 24px;background:#f8fafc;border:1px solid #e2e8f0;border-radius:8px">
                  <tr>
                    <td style="padding:16px 18px">
                      <div style="color:#64748b;font-size:12px;font-weight:700;text-transform:uppercase">Lớp học</div>
                      <div style="margin-top:5px;color:#0f172a;font-size:17px;font-weight:700">%s</div>
                    </td>
                    <td style="padding:16px 18px;border-left:1px solid #e2e8f0">
                      <div style="color:#64748b;font-size:12px;font-weight:700;text-transform:uppercase">Học kỳ</div>
                      <div style="margin-top:5px;color:#0f172a;font-size:15px;font-weight:600">%s</div>
                    </td>
                  </tr>
                </table>
                """.formatted(classCode, semester);
    }

    private String actionButton(String url, String label) {
        return """
                <table role="presentation" cellspacing="0" cellpadding="0">
                  <tr>
                    <td style="background:#2563eb;border-radius:7px">
                      <a href="%s" style="display:inline-block;padding:12px 22px;color:#ffffff;text-decoration:none;font-size:14px;font-weight:700">
                        %s
                      </a>
                    </td>
                  </tr>
                </table>
                """.formatted(escape(url), label);
    }

    private String displayName(String name, String email) {
        return name == null || name.isBlank() ? email : name;
    }

    private String escape(String value) {
        return HtmlUtils.htmlEscape(value == null ? "" : value);
    }
}
