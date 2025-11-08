package graduation_project_be.application.port.services;

import com.fasterxml.jackson.core.type.TypeReference;

public interface ObjectMapperService<D> {

    /**
     * Converts the given source object to the destination type.
     *
     * @param source the source object to convert
     * @return the converted object of type D
     */
    D convert(Object source, TypeReference<D> typeReference);
    String toJsonString(Object object);
}
