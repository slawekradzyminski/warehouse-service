package com.awesome.warehouse.entity;

import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToOne;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.type.filter.AnnotationTypeFilter;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The GraalVM native image runs Hibernate without a runtime bytecode provider,
 * so it cannot create lazy to-one proxies. JVM tests would never notice a lazy
 * association; this test fails instead of a 500 in production.
 */
class NativeEntityMappingTest {

    @Test
    void toOneAssociationsAreNeverLazy() throws ClassNotFoundException {
        ClassPathScanningCandidateComponentProvider scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(Entity.class));
        List<String> lazy = new ArrayList<>();
        int entities = 0;
        for (var candidate : scanner.findCandidateComponents("com.awesome.warehouse.entity")) {
            entities++;
            for (Field field : Class.forName(candidate.getBeanClassName()).getDeclaredFields()) {
                ManyToOne manyToOne = field.getAnnotation(ManyToOne.class);
                OneToOne oneToOne = field.getAnnotation(OneToOne.class);
                if ((manyToOne != null && manyToOne.fetch() == FetchType.LAZY)
                        || (oneToOne != null && oneToOne.fetch() == FetchType.LAZY)) {
                    lazy.add(candidate.getBeanClassName() + "." + field.getName());
                }
            }
        }

        assertThat(entities).isGreaterThanOrEqualTo(6);
        assertThat(lazy).as("lazy to-one associations break the native image").isEmpty();
    }
}
