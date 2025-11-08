package graduation_project_be.application.port.services;

public interface PasswordEncoder {
    String encode(String data);
    boolean matches(String rawPassword, String encodedPassword);
}
