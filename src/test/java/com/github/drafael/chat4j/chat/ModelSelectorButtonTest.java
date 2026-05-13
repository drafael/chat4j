package com.github.drafael.chat4j.chat;

import com.github.drafael.chat4j.util.Fonts;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.awt.FontMetrics;
import java.lang.reflect.Method;

import static org.assertj.core.api.Assertions.assertThat;

class ModelSelectorButtonTest {

    @Test
    @DisplayName("Provider icon size remains compact and independent from provider text height")
    void providerIconSize_whenCalled_returnsCompactScaledSize() throws Exception {
        Method method = ModelSelectorButton.class.getDeclaredMethod("providerIconSize", FontMetrics.class);
        method.setAccessible(true);

        int size = (int) method.invoke(null, new Object[]{null});

        assertThat(size).isEqualTo(Fonts.scale(14));
    }
}
