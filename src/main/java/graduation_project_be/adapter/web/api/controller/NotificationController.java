package graduation_project_be.adapter.web.api.controller;

import graduation_project_be.adapter.web.api.dtos.response.*;
import graduation_project_be.application.usecases.DeleteNotificationUsecase;
import graduation_project_be.application.usecases.GetTeacherNotificationsUsecase;
import graduation_project_be.application.usecases.GetUnreadNotificationCountUsecase;
import graduation_project_be.application.usecases.MarkNotificationReadUsecase;
import graduation_project_be.application.usecases.request.DeleteNotificationRequest;
import graduation_project_be.application.usecases.request.GetTeacherNotificationsRequest;
import graduation_project_be.application.usecases.response.DeleteNotificationResponse;
import graduation_project_be.application.usecases.response.PaginationResponse;
import graduation_project_be.application.usecases.response.TeacherNotificationResponse;
import graduation_project_be.application.usecases.response.UnreadCountResponse;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/notifications")
@RequiredArgsConstructor
@Validated
public class NotificationController {

        private final GetTeacherNotificationsUsecase getTeacherNotificationsUsecase;
        private final MarkNotificationReadUsecase markNotificationReadUsecase;
        private final GetUnreadNotificationCountUsecase getUnreadNotificationCountUsecase;
        private final DeleteNotificationUsecase deleteNotificationUsecase;

        @GetMapping
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<PaginationResponseDto<TeacherNotificationResponseDto>> getNotifications(
                        @RequestParam(name = "page", defaultValue = "1") int page,
                        @RequestParam(name = "size", defaultValue = "10") int size) {

                GetTeacherNotificationsRequest request = new GetTeacherNotificationsRequest(page, size);
                PaginationResponse<TeacherNotificationResponse> response = getTeacherNotificationsUsecase
                                .execute(request);

                return ResponseEntity.ok(
                                PaginationResponseDto.fromResponse(response,
                                                TeacherNotificationResponseDto::fromResponse, "OK",
                                                "Notifications retrieved successfully"));
        }

        @GetMapping("/unread-count")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> getUnreadCount() {
                UnreadCountResponse response = getUnreadNotificationCountUsecase.execute();
                return ResponseEntity.ok(
                                ResponseDto.of(UnreadCountResponseDto.fromResponse(response), "OK",
                                                "Unread count retrieved successfully"));
        }

        @PatchMapping("/{notificationId}/read")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> markAsRead(
                        @PathVariable("notificationId") @Positive Long notificationId) {
                boolean success = markNotificationReadUsecase.execute(notificationId);
                if (!success) {
                        return ResponseEntity.notFound().build();
                }
                return ResponseEntity.ok(
                                ResponseDto.of(null, "OK", "Notification marked as read"));
        }

        @PatchMapping("/read-all")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> markAllAsRead() {
                int count = markNotificationReadUsecase.markAllAsRead();
                return ResponseEntity.ok(
                                ResponseDto.of(count, "OK",
                                                count + " notifications marked as read"));
        }

        @DeleteMapping("/{notificationId}")
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> deleteNotification(
                        @PathVariable("notificationId") @Positive Long notificationId) {
                DeleteNotificationResponse response = deleteNotificationUsecase.execute(
                                new DeleteNotificationRequest(notificationId, false));
                return ResponseEntity.ok(
                                ResponseDto.of(DeleteNotificationResponseDto.fromResponse(response),
                                                "OK", response.message()));
        }

        @DeleteMapping
        @PreAuthorize("hasRole('TEACHER')")
        public ResponseEntity<ResponseDto> deleteAllNotifications() {
                DeleteNotificationResponse response = deleteNotificationUsecase.execute(
                                new DeleteNotificationRequest(null, true));
                return ResponseEntity.ok(
                                ResponseDto.of(DeleteNotificationResponseDto.fromResponse(response),
                                                "OK", response.message()));
        }
}
