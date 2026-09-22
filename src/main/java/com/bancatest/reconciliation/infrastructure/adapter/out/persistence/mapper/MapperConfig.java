package com.bancatest.reconciliation.infrastructure.adapter.out.persistence.mapper;

import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Shared MapStruct configuration for every mapper in the service.
 *
 * <p>{@code unmappedTargetPolicy = ERROR} is the reason this exists. Without it, adding a
 * field to a contract and forgetting to map it compiles cleanly and produces a silent null
 * at runtime. With it, the build fails and names the field.
 *
 * <p>{@code JAKARTA_CDI} rather than {@code CDI}: the latter targets the old {@code javax}
 * namespace and would generate beans Quarkus cannot see.
 */
@org.mapstruct.MapperConfig(
        componentModel = MappingConstants.ComponentModel.JAKARTA_CDI,
        unmappedTargetPolicy = ReportingPolicy.ERROR,
        unmappedSourcePolicy = ReportingPolicy.IGNORE)
public interface MapperConfig {}
