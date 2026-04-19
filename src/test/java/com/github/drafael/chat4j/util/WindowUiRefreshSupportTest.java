package com.github.drafael.chat4j.util;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;

class WindowUiRefreshSupportTest {

    @Test
    @DisplayName("Refresh all windows executes without throwing")
    void refreshAllWindows_whenCalled_doesNotThrow() {
        assertThatCode(WindowUiRefreshSupport::refreshAllWindows).doesNotThrowAnyException();
    }
}
