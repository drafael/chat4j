package com.github.drafael.chat4j.provider.support;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.github.drafael.chat4j.chat.model.ModelSelectorPopup;
import com.github.drafael.chat4j.chat.search.ChatSearchPopup;
import com.github.drafael.chat4j.sidebar.SidebarPanel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import javax.swing.SwingUtilities;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class TogetherProviderIconMapTest {

    @Test
    @DisplayName("Model selector popup independently resolves the Together provider mark")
    void providerIconMap_whenOwnedByModelSelectorPopup_resolvesTogetherResource() throws Exception {
        assertTogetherIconMapping(ModelSelectorPopup.class);
    }

    @Test
    @DisplayName("Chat search popup independently resolves the Together provider mark")
    void providerIconMap_whenOwnedByChatSearchPopup_resolvesTogetherResource() throws Exception {
        assertTogetherIconMapping(ChatSearchPopup.class);
    }

    @Test
    @DisplayName("Sidebar independently resolves the Together provider mark")
    void providerIconMap_whenOwnedBySidebar_resolvesTogetherResource() throws Exception {
        assertTogetherIconMapping(SidebarPanel.class);
    }

    @SuppressWarnings("unchecked")
    private void assertTogetherIconMapping(Class<?> owner) throws Exception {
        callOnEdt(() -> {
            Field field = owner.getDeclaredField("PROVIDER_ICON_PATHS");
            field.setAccessible(true);
            Map<String, String> paths = (Map<String, String>) field.get(null);
            String path = paths.get("Together");
            assertThat(path).isEqualTo("/icons/providers/together.svg");
            URL resource = owner.getResource(path);
            assertThat(resource).isNotNull();
            FlatSVGIcon icon = new FlatSVGIcon(resource).derive(16, 16);
            assertThat(icon.hasFound()).isTrue();
            var image = new BufferedImage(16, 16, BufferedImage.TYPE_INT_ARGB);
            Graphics2D graphics = image.createGraphics();
            try {
                icon.paintIcon(null, graphics, 0, 0);
            } finally {
                graphics.dispose();
            }
            boolean painted = false;
            for (int x = 0; x < image.getWidth() && !painted; x++) {
                for (int y = 0; y < image.getHeight(); y++) {
                    if ((image.getRGB(x, y) >>> 24) != 0) {
                        painted = true;
                        break;
                    }
                }
            }
            assertThat(painted).isTrue();
            return null;
        });
    }

    private <T> T callOnEdt(Callable<T> action) throws Exception {
        if (SwingUtilities.isEventDispatchThread()) {
            return action.call();
        }
        var result = new AtomicReference<T>();
        var error = new AtomicReference<Throwable>();
        SwingUtilities.invokeAndWait(() -> {
            try {
                result.set(action.call());
            } catch (Throwable t) {
                error.set(t);
            }
        });
        if (error.get() instanceof Exception e) {
            throw e;
        }
        if (error.get() instanceof Error e) {
            throw e;
        }
        if (error.get() != null) {
            throw new AssertionError(error.get());
        }
        return result.get();
    }
}
