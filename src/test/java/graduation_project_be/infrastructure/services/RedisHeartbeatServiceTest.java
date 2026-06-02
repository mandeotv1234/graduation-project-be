package graduation_project_be.infrastructure.services;

import graduation_project_be.application.port.services.HeartbeatService.HeartbeatKey;
import graduation_project_be.application.port.services.HeartbeatService.HeartbeatState;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RedisHeartbeatServiceTest {

    @Mock private RedisTemplate<String, String> redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private RedisHeartbeatService service;

    @BeforeEach
    void setUp() {
        service = new RedisHeartbeatService(redisTemplate);
    }

    @Test
    void save_writesPipeDelimitedValueWithTtl() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);

        service.save(1L, 2L, new HeartbeatState(100L, 3, 2, true));

        verify(valueOps).set(eq("heartbeat:1:2"), eq("100|3|2|1"), eq(Duration.ofHours(4)));
    }

    @Test
    void get_parsesPipeDelimitedValue() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("heartbeat:1:2")).thenReturn("100|3|2|1");

        Optional<HeartbeatState> state = service.get(1L, 2L);

        assertThat(state).isPresent();
        assertThat(state.get().lastSeenEpochMs()).isEqualTo(100L);
        assertThat(state.get().lastSeq()).isEqualTo(3);
        assertThat(state.get().tamperStreak()).isEqualTo(2);
        assertThat(state.get().flagged()).isTrue();
    }

    @Test
    void get_malformedValue_returnsEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("heartbeat:1:2")).thenReturn("garbage");

        assertThat(service.get(1L, 2L)).isEmpty();
    }

    @Test
    void get_missingKey_returnsEmpty() {
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("heartbeat:1:2")).thenReturn(null);

        assertThat(service.get(1L, 2L)).isEmpty();
    }

    @Test
    void scanActive_extractsExamAndStudentIds() {
        when(redisTemplate.keys("heartbeat:*"))
                .thenReturn(Set.of("heartbeat:1:2", "heartbeat:3:4"));

        List<HeartbeatKey> keys = service.scanActive();

        assertThat(keys).containsExactlyInAnyOrder(
                new HeartbeatKey(1L, 2L), new HeartbeatKey(3L, 4L));
    }

    @Test
    void clear_deletesKey() {
        service.clear(1L, 2L);

        verify(redisTemplate).delete("heartbeat:1:2");
    }
}
