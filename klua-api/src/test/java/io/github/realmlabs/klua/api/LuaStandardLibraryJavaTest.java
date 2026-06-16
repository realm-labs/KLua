package io.github.realmlabs.klua.api;

import org.junit.jupiter.api.Test;

import java.util.EnumSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LuaStandardLibraryJavaTest {
    @Test
    void allReturnsEveryLibraryAsImmutableSet() {
        assertEquals(EnumSet.allOf(LuaStandardLibrary.class), LuaStandardLibrary.all());
        assertThrows(
                UnsupportedOperationException.class,
                () -> LuaStandardLibrary.all().remove(LuaStandardLibrary.DEBUG)
        );
    }
}
