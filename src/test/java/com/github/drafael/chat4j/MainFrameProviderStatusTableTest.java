package com.github.drafael.chat4j;

import com.github.drafael.chat4j.provider.registry.ProviderRegistry;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MainFrameProviderStatusTableTest {

    @Test
    @DisplayName("Dynamic provider widths preserve format placeholders for status rows")
    void buildProviderStatusTable_whenProviderWidthIsDynamic_formatsAllColumns() throws Exception {
        Method method = MainFrame.class.getDeclaredMethod("buildProviderStatusTable", List.class, Map.class);
        method.setAccessible(true);
        var status = new ProviderRegistry.ProviderStatus("Long Provider", true, true, false);

        String table = (String) method.invoke(null, List.of(status), Map.of("Long Provider", 3));

        assertThat(table)
                .contains("Long Provider", "yes", "no", "3")
                .doesNotContain("%-7s", "%-13s", "%-9s", "%6s");
    }
}
