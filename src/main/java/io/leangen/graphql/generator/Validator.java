package io.leangen.graphql.generator;

import graphql.schema.GraphQLInputType;
import graphql.schema.GraphQLModifiedType;
import graphql.schema.GraphQLOutputType;
import graphql.schema.GraphQLType;
import io.leangen.graphql.execution.GlobalEnvironment;
import io.leangen.graphql.generator.mapping.TypeMapperRegistry;
import io.leangen.graphql.metadata.strategy.type.TypeInfoGenerator;
import io.leangen.graphql.util.ClassUtils;
import io.leangen.graphql.util.Directives;
import io.leangen.graphql.util.Urls;

import java.lang.reflect.AnnotatedType;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

class Validator {

    private final GlobalEnvironment environment;
    private final TypeMapperRegistry mappers;
    private final List<Set<AnnotatedType>> aliasGroups;
    private final Map<String, AnnotatedType> mappedTypes;

    Validator(GlobalEnvironment environment, TypeMapperRegistry mappers, Collection<GraphQLType> knownTypes, List<Set<AnnotatedType>> aliasGroups) {
        this.environment = environment;
        this.mappers = mappers;
        this.aliasGroups = aliasGroups;
        this.mappedTypes = knownTypes.stream()
                .filter(Directives::isMappedType)
                .collect(Collectors.toMap(GraphQLType::getName, type -> ClassUtils.normalize(Directives.getMappedType(type))));
    }

    ValidationResult checkUniqueness(GraphQLOutputType graphQLType, AnnotatedType javaType) {
        return checkUniqueness(graphQLType, () -> mappers.getMappableOutputType(javaType));
    }

    ValidationResult checkUniqueness(GraphQLInputType graphQLType, AnnotatedType javaType) {
        return checkUniqueness(graphQLType, () -> environment.getMappableInputType(javaType));
    }

    private ValidationResult checkUniqueness(GraphQLType graphQLType, Supplier<AnnotatedType> javaType) {
        if (graphQLType instanceof GraphQLModifiedType) {
            return ValidationResult.valid();
        }
        AnnotatedType resolvedType;
        try {
            resolvedType = resolveType(graphQLType, javaType);
        } catch (Exception e) {
            return ValidationResult.invalid(
                    String.format("Exception while checking the name uniqueness for %s: %s", graphQLType.getName(), e.getMessage()));
        }
        mappedTypes.putIfAbsent(graphQLType.getName(), resolvedType);
        AnnotatedType knownType = mappedTypes.get(graphQLType.getName());
        if (isMappingAllowed(resolvedType, knownType)) {
            return ValidationResult.valid();
        }
        return ValidationResult.invalid(String.format("Potential type name collision detected: %s bound to multiple types: %s and %s." +
                        " Assign unique names using the appropriate annotations or override the %s." +
                        " For details and solutions see %s." +
                        " If this warning is a false positive, please report it on %s.", graphQLType.getName(), knownType,
                resolvedType, TypeInfoGenerator.class.getSimpleName(), Urls.Errors.NON_UNIQUE_TYPE_NAME, Urls.ISSUES));
    }

    private AnnotatedType resolveType(GraphQLType graphQLType, Supplier<AnnotatedType> javaType) {
        AnnotatedType resolvedType;
        if (Directives.isMappedType(graphQLType)) {
            resolvedType = Directives.getMappedType(graphQLType);
        } else {
            resolvedType = javaType.get();
        }
        return ClassUtils.normalize(resolvedType);
    }

    private boolean isMappingAllowed(AnnotatedType resolvedType, AnnotatedType knownType) {
        return resolvedType.equals(knownType)
                || aliasGroups.stream().anyMatch(aliases -> aliases.contains(resolvedType) && aliases.contains(knownType));
    }

    static class ValidationResult {

        private final String message;

        private ValidationResult(String message) {
            this.message = message;
        }

        static ValidationResult valid() {
            return new ValidationResult(null);
        }

        static ValidationResult invalid(String message) {
            return new ValidationResult(message);
        }

        boolean isValid() {
            return message == null;
        }

        String getMessage() {
            return message;
        }
    }
}
