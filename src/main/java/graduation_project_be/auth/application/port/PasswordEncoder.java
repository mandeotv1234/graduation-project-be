package graduation_project_be.auth.application.port;

public interface PasswordEncoder {
    String encode(String data);
    boolean matches(String rawPassword, String encodedPassword);
}
