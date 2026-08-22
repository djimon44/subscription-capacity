package com.arcticblu.subscriptioncapacity.web.dto;

import org.springframework.data.domain.Page;

import java.util.List;
import java.util.function.Function;

/**
 * Stable pagination envelope.
 *
 * <p>Spring's {@link Page} serializes its full internal structure, including the
 * {@code Pageable} and {@code Sort} objects. That shape is an implementation detail
 * and has changed between Spring versions, so the API exposes this instead.
 */
public record PagedResponse<T>(
        List<T> content,
        int page,
        int size,
        long totalElements,
        int totalPages) {

    public PagedResponse {
        content = List.copyOf(content);
    }

    public static <S, T> PagedResponse<T> from(Page<S> source, Function<S, T> mapper) {
        return new PagedResponse<>(
                source.getContent().stream().map(mapper).toList(),
                source.getNumber(),
                source.getSize(),
                source.getTotalElements(),
                source.getTotalPages());
    }
}