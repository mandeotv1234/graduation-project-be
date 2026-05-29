package graduation_project_be.application.usecases;

import graduation_project_be.application.exceptions.UnauthorizedException;
import graduation_project_be.application.port.repositories.ClassRepository;
import graduation_project_be.application.port.repositories.ClassStudentBanRepository;
import graduation_project_be.application.port.repositories.UserRepository;
import graduation_project_be.application.port.services.CurrentUserService;
import graduation_project_be.application.usecases.response.BannedStudentResponse;
import graduation_project_be.application.usecases.response.PaginationResponse;
import graduation_project_be.domain.models.ClassStudentBan;
import graduation_project_be.domain.models.User;
import lombok.RequiredArgsConstructor;

import java.util.List;

@RequiredArgsConstructor
public class GetClassBansUsecase {

    private final ClassRepository classRepository;
    private final ClassStudentBanRepository classStudentBanRepository;
    private final UserRepository userRepository;
    private final CurrentUserService currentUserService;

    public PaginationResponse<BannedStudentResponse> execute(Long classId, int page, int size) {
        Long currentUserId = currentUserService.getCurrentUserId();

        if (!classRepository.existsTeacherAccess(classId, currentUserId)) {
            throw new UnauthorizedException("User does not have access to this class");
        }

        List<ClassStudentBan> allBans = classStudentBanRepository.findActiveByClassId(classId);
        long total = allBans.size();

        int safeSize = Math.max(1, size);
        int zeroBasedPage = page < 1 ? 0 : page - 1;
        int fromIndex = zeroBasedPage * safeSize;
        int toIndex = Math.min(fromIndex + safeSize, (int) total);

        List<ClassStudentBan> pageBans = (fromIndex >= total)
                ? List.of()
                : allBans.subList(fromIndex, toIndex);

        List<BannedStudentResponse> responses = pageBans.stream()
                .map(ban -> {
                    User student = userRepository.findById(ban.getStudentId()).orElse(null);
                    String email = student != null ? student.getEmail() : "";
                    String fullName = student != null ? student.getFullName() : "";

                    User bannedBy = userRepository.findById(ban.getBannedBy()).orElse(null);
                    String bannedByName = bannedBy != null ? bannedBy.getFullName() : "";

                    return BannedStudentResponse.fromModel(ban, email, fullName, bannedByName);
                })
                .toList();

        return PaginationResponse.valueOf(
                responses,
                PaginationResponse.PaginationMeta.valueOf(zeroBasedPage, safeSize, total));
    }
}
