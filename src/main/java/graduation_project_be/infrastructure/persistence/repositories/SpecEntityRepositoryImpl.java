package graduation_project_be.infrastructure.persistence.repositories;

import graduation_project_be.application.port.repositories.SpecEntityRepository;
import graduation_project_be.domain.models.SpecEntity;
import graduation_project_be.infrastructure.persistence.repositories.jpa.SpecEntityJpaRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Repository
@RequiredArgsConstructor
public class SpecEntityRepositoryImpl implements SpecEntityRepository {

    private final SpecEntityJpaRepository jpaRepository;

    @Override
    public Optional<SpecEntity> findById(Long id) {
        return jpaRepository.findByIdWithAttributes(id)
                .map(e -> e.toModel());
    }

    @Override
    @Transactional
    public void updateDescription(Long entityId, String description) {
        jpaRepository.updateDescription(entityId, description);
    }
}
