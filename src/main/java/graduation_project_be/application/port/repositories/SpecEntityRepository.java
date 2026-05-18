package graduation_project_be.application.port.repositories;

import graduation_project_be.domain.models.SpecEntity;

import java.util.Optional;

/** Port for spec_entity CRUD and targeted updates. */
public interface SpecEntityRepository {

    Optional<SpecEntity> findById(Long id);

    /** Update the description field for a single entity row. */
    void updateDescription(Long entityId, String description);
}
