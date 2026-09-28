package com.kgtech.inventoryapi.inventory.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.kgtech.inventoryapi.inventory.DetailsPrecondition;

/** DESIGN-V2 §8 "Edit": If-Match per RFC 9110 §13.1.1, strong comparison; malformed → empty (400). */
class IfMatchTest {

    static Stream<Arguments> parsed() {
        return Stream.of(
                Arguments.of(List.of(), Optional.of(new DetailsPrecondition.Any())),
                Arguments.of(List.of("*"), Optional.of(new DetailsPrecondition.Any())),
                Arguments.of(List.of("\"3\""), Optional.of(new DetailsPrecondition.Versions(List.of(3L)))),
                Arguments.of(List.of("\"3\", \"7\""), Optional.of(new DetailsPrecondition.Versions(List.of(3L, 7L)))),
                Arguments.of(List.of("\"3\"", "\"7\""), Optional.of(new DetailsPrecondition.Versions(List.of(3L, 7L)))),
                Arguments.of(List.of("W/\"3\""), Optional.of(new DetailsPrecondition.Versions(List.of()))),
                Arguments.of(List.of("\"abc\""), Optional.of(new DetailsPrecondition.Versions(List.of()))),
                Arguments.of(List.of("\"3\", W/\"4\", \"x\""), Optional.of(new DetailsPrecondition.Versions(List.of(3L)))),
                Arguments.of(List.of("\"-1\""), Optional.of(new DetailsPrecondition.Versions(List.of()))),
                Arguments.of(List.of("\"01\""), Optional.of(new DetailsPrecondition.Versions(List.of()))),
                Arguments.of(List.of("\"0\""), Optional.of(new DetailsPrecondition.Versions(List.of(0L)))),
                Arguments.of(List.of("\"99999999999999999999\""), Optional.of(new DetailsPrecondition.Versions(List.of()))),
                Arguments.of(List.of("3"), Optional.empty()),
                Arguments.of(List.of("\"3"), Optional.empty()),
                Arguments.of(List.of(""), Optional.empty()),
                Arguments.of(List.of("\"3\" \"4\""), Optional.empty()),
                Arguments.of(List.of("\"3\", *"), Optional.empty()),
                Arguments.of(List.of("\"a\"b\""), Optional.empty()));
    }

    @ParameterizedTest(name = "{0} → {1}")
    @MethodSource("parsed")
    void parses(List<String> headerValues, Optional<DetailsPrecondition> expected) {
        assertThat(IfMatch.parse(headerValues)).isEqualTo(expected);
    }
}
