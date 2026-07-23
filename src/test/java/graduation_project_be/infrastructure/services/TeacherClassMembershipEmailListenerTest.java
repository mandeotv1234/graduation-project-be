package graduation_project_be.infrastructure.services;

import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Properties;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TeacherClassMembershipEmailListenerTest {

    @Mock
    private JavaMailSender mailSender;

    @Test
    void usesSystemAccountAsFromAndLoggedInTeacherAsReplyTo() throws Exception {
        MimeMessage message = new MimeMessage(Session.getInstance(new Properties()));
        when(mailSender.createMimeMessage()).thenReturn(message);

        TeacherClassMembershipEmailListener listener =
                new TeacherClassMembershipEmailListener(mailSender, "https://csdl.phanphuc.id.vn");
        ReflectionTestUtils.setField(listener, "mailEnabled", true);
        ReflectionTestUtils.setField(listener, "mailFrom", "system@gmail.com");
        ReflectionTestUtils.setField(
                listener,
                "mailFromName",
                "Ứng dụng Hỗ trợ Giảng dạy và Đánh giá Học phần Cơ sở Dữ liệu");

        listener.sendMembershipEmail(new TeacherClassMembershipEvent(
                TeacherClassMembershipEvent.Action.ADDED,
                10L,
                "CSDL-K24",
                "2026-1",
                "recipient@fit.hcmus.edu.vn",
                "Recipient",
                "actor@fit.hcmus.edu.vn",
                "Actor"));

        verify(mailSender).send(message);
        assertThat(((InternetAddress) message.getFrom()[0]).getAddress()).isEqualTo("system@gmail.com");
        assertThat(((InternetAddress) message.getReplyTo()[0]).getAddress())
                .isEqualTo("actor@fit.hcmus.edu.vn");
        assertThat(((InternetAddress) message.getAllRecipients()[0]).getAddress())
                .isEqualTo("recipient@fit.hcmus.edu.vn");
        assertThat(message.getSubject()).contains("CSDL-K24");
        assertThat(message.getContent().toString())
                .contains("Ứng dụng Hỗ trợ Giảng dạy")
                .contains("https://csdl.phanphuc.id.vn/teacher/classes/10")
                .contains("Mở lớp học");
    }
}
