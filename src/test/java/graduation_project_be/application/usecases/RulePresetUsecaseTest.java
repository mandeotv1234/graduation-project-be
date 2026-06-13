package graduation_project_be.application.usecases;

import graduation_project_be.application.dto.RulePresetDto;
import graduation_project_be.domain.models.QuestionType;
import graduation_project_be.domain.models.RulePreset;
import graduation_project_be.domain.repositories.RulePresetRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit test for RulePresetUseCase verifying the 'kind' discriminator logic:
 * - Creating a preset with kind="WHITEBOX" persists correctly.
 * - Creating a preset without kind defaults to "BLACKBOX".
 * - Listing by kind requests the repository with the correct kind parameter.
 */
@ExtendWith(MockitoExtension.class)
class RulePresetUsecaseTest {

    @Mock
    private RulePresetRepository rulePresetRepository;

    private RulePresetUseCase rulePresetUseCase;

    private static final Long TEACHER_ID = 1L;
    private static final Long PRESET_ID = 100L;

    @BeforeEach
    void setUp() {
        rulePresetUseCase = new RulePresetUseCase(rulePresetRepository);
    }

    // --- Test 1: Creating a preset with kind="WHITEBOX" persists correctly ---

    @Test
    void createPreset_withWhiteboxKind_persists() {
        // Arrange
        RulePresetDto.CreateRequest request = RulePresetDto.CreateRequest.builder()
                .name("Whitebox Rules")
                .questionType(QuestionType.SELECT_QUERY)
                .rulesJson("{\"rules\": []}")
                .kind("WHITEBOX")
                .build();

        LocalDateTime now = LocalDateTime.now();
        RulePreset savedPreset = RulePreset.builder()
                .id(PRESET_ID)
                .teacherId(TEACHER_ID)
                .name("Whitebox Rules")
                .questionType(QuestionType.SELECT_QUERY)
                .rulesJson("{\"rules\": []}")
                .kind("WHITEBOX")
                .createdAt(now)
                .updatedAt(now)
                .build();

        when(rulePresetRepository.save(any(RulePreset.class))).thenReturn(savedPreset);

        // Act
        RulePresetDto.Response response = rulePresetUseCase.createPreset(TEACHER_ID, request);

        // Assert
        assertEquals("WHITEBOX", response.getKind());
        assertEquals(PRESET_ID, response.getId());
        assertEquals(TEACHER_ID, response.getTeacherId());

        // Verify repository was called with correct kind
        ArgumentCaptor<RulePreset> captor = ArgumentCaptor.forClass(RulePreset.class);
        verify(rulePresetRepository).save(captor.capture());
        assertEquals("WHITEBOX", captor.getValue().getKind());
    }

    // --- Test 2: Creating a preset without kind defaults to "BLACKBOX" ---

    @Test
    void createPreset_withoutKind_defaultsToBlackbox() {
        // Arrange
        RulePresetDto.CreateRequest request = RulePresetDto.CreateRequest.builder()
                .name("Blackbox Rules")
                .questionType(QuestionType.SELECT_QUERY)
                .rulesJson("{\"rules\": []}")
                .kind(null)  // No kind specified
                .build();

        LocalDateTime now = LocalDateTime.now();
        RulePreset savedPreset = RulePreset.builder()
                .id(PRESET_ID)
                .teacherId(TEACHER_ID)
                .name("Blackbox Rules")
                .questionType(QuestionType.SELECT_QUERY)
                .rulesJson("{\"rules\": []}")
                .kind("BLACKBOX")
                .createdAt(now)
                .updatedAt(now)
                .build();

        when(rulePresetRepository.save(any(RulePreset.class))).thenReturn(savedPreset);

        // Act
        RulePresetDto.Response response = rulePresetUseCase.createPreset(TEACHER_ID, request);

        // Assert
        assertEquals("BLACKBOX", response.getKind());

        // Verify repository was called with BLACKBOX as the default
        ArgumentCaptor<RulePreset> captor = ArgumentCaptor.forClass(RulePreset.class);
        verify(rulePresetRepository).save(captor.capture());
        assertEquals("BLACKBOX", captor.getValue().getKind());
    }

    // --- Test 3: Creating a preset with blank kind defaults to "BLACKBOX" ---

    @Test
    void createPreset_withBlankKind_defaultsToBlackbox() {
        // Arrange
        RulePresetDto.CreateRequest request = RulePresetDto.CreateRequest.builder()
                .name("Blackbox Rules")
                .questionType(QuestionType.SELECT_QUERY)
                .rulesJson("{\"rules\": []}")
                .kind("   ")  // Blank string
                .build();

        LocalDateTime now = LocalDateTime.now();
        RulePreset savedPreset = RulePreset.builder()
                .id(PRESET_ID)
                .teacherId(TEACHER_ID)
                .name("Blackbox Rules")
                .questionType(QuestionType.SELECT_QUERY)
                .rulesJson("{\"rules\": []}")
                .kind("BLACKBOX")
                .createdAt(now)
                .updatedAt(now)
                .build();

        when(rulePresetRepository.save(any(RulePreset.class))).thenReturn(savedPreset);

        // Act
        RulePresetDto.Response response = rulePresetUseCase.createPreset(TEACHER_ID, request);

        // Assert
        assertEquals("BLACKBOX", response.getKind());

        ArgumentCaptor<RulePreset> captor = ArgumentCaptor.forClass(RulePreset.class);
        verify(rulePresetRepository).save(captor.capture());
        assertEquals("BLACKBOX", captor.getValue().getKind());
    }

    // --- Test 4: Listing by kind requests repository with correct kind parameter ---

    @Test
    void getPresetsByTeacherIdAndQuestionTypeAndKind_requestsRepositoryWithKind() {
        // Arrange
        RulePreset preset = RulePreset.builder()
                .id(PRESET_ID)
                .teacherId(TEACHER_ID)
                .name("Whitebox Rules")
                .questionType(QuestionType.SELECT_QUERY)
                .rulesJson("{\"rules\": []}")
                .kind("WHITEBOX")
                .build();

        when(rulePresetRepository.findByTeacherIdAndQuestionTypeAndKind(
                eq(TEACHER_ID), eq(QuestionType.SELECT_QUERY), eq("WHITEBOX")))
                .thenReturn(List.of(preset));

        // Act
        List<RulePresetDto.Response> responses = rulePresetUseCase.getPresetsByTeacherIdAndQuestionTypeAndKind(
                TEACHER_ID, QuestionType.SELECT_QUERY, "WHITEBOX");

        // Assert
        assertEquals(1, responses.size());
        assertEquals("WHITEBOX", responses.get(0).getKind());

        // Verify repository was called with the correct kind
        verify(rulePresetRepository).findByTeacherIdAndQuestionTypeAndKind(
                TEACHER_ID, QuestionType.SELECT_QUERY, "WHITEBOX");
    }

    // --- Test 5: Listing by teacher and question type (without kind filter) ---

    @Test
    void getPresetsByTeacherIdAndQuestionType_returnsMixedKinds() {
        // Arrange
        RulePreset whiteboxPreset = RulePreset.builder()
                .id(101L)
                .teacherId(TEACHER_ID)
                .name("Whitebox Rules")
                .questionType(QuestionType.SELECT_QUERY)
                .kind("WHITEBOX")
                .build();

        RulePreset blackboxPreset = RulePreset.builder()
                .id(102L)
                .teacherId(TEACHER_ID)
                .name("Blackbox Rules")
                .questionType(QuestionType.SELECT_QUERY)
                .kind("BLACKBOX")
                .build();

        when(rulePresetRepository.findByTeacherIdAndQuestionType(
                eq(TEACHER_ID), eq(QuestionType.SELECT_QUERY)))
                .thenReturn(List.of(whiteboxPreset, blackboxPreset));

        // Act
        List<RulePresetDto.Response> responses = rulePresetUseCase.getPresetsByTeacherIdAndQuestionType(
                TEACHER_ID, QuestionType.SELECT_QUERY);

        // Assert
        assertEquals(2, responses.size());
        assertTrue(responses.stream().anyMatch(r -> "WHITEBOX".equals(r.getKind())));
        assertTrue(responses.stream().anyMatch(r -> "BLACKBOX".equals(r.getKind())));

        verify(rulePresetRepository).findByTeacherIdAndQuestionType(TEACHER_ID, QuestionType.SELECT_QUERY);
    }
}
