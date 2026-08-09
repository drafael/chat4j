package com.github.drafael.chat4j.settings;

import com.formdev.flatlaf.util.SystemInfo;
import com.github.drafael.chat4j.chat.export.pdf.PdfExportMode;
import com.github.drafael.chat4j.chat.export.pdf.PdfExportSettings;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.awt.Component;
import java.awt.Container;
import java.awt.event.FocusEvent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.assertj.core.api.Assertions.assertThat;

class PdfExportPanelTest {

    @TempDir
    Path tempDirectory;

    @Test
    @DisplayName("Executable fields discover Pandoc, both LaTeX engines, Mermaid CLI, and Chromium")
    void constructor_whenToolsAreOnPath_prepopulatesExecutableFields() throws Exception {
        Path pandoc = createExecutable("pandoc");
        Path lualatex = createExecutable("lualatex");
        Path xelatex = createExecutable("xelatex");
        Path mmdc = createExecutable("mmdc");
        Path chromium = createExecutable("chromium");
        Map<String, String> environment = Map.of(
                "PATH", tempDirectory.toString(),
                "PATHEXT", ".EXE",
                "PUPPETEER_EXECUTABLE_PATH", chromium.toString()
        );
        SettingsRepository repository = new SettingsRepository(tempDirectory.resolve("discovery.properties"));
        PdfExportPanel subject = callOnEdt(() -> new PdfExportPanel(repository, environment));
        try {
            JTextField pandocPath = callOnEdt(() -> componentNamed(
                    subject,
                    "pdfExportPandocPathField",
                    JTextField.class
            ));
            JComboBox<?> latexEngine = callOnEdt(() -> componentNamed(
                    subject,
                    "pdfExportLatexEngineComboBox",
                    JComboBox.class
            ));
            JTextField latexPath = callOnEdt(() -> componentNamed(
                    subject,
                    "pdfExportLatexPathField",
                    JTextField.class
            ));
            JTextField mermaidCliPath = callOnEdt(() -> componentNamed(
                    subject,
                    "pdfExportMermaidCliPathField",
                    JTextField.class
            ));
            JTextField chromiumPath = callOnEdt(() -> componentNamed(
                    subject,
                    "pdfExportChromiumPathField",
                    JTextField.class
            ));

            String displayedPandocPath = callOnEdt(pandocPath::getText);
            String displayedLatexPath = callOnEdt(latexPath::getText);
            String displayedMermaidPath = callOnEdt(mermaidCliPath::getText);
            String displayedChromiumPath = callOnEdt(chromiumPath::getText);
            assertThat(displayedPandocPath).isEqualTo(pandoc.toString());
            assertThat(displayedLatexPath).isEqualTo(lualatex.toString());
            assertThat(displayedMermaidPath).isEqualTo(mmdc.toString());
            assertThat(displayedChromiumPath).isEqualTo(chromium.toString());

            callOnEdt(() -> {
                latexEngine.setSelectedItem("xelatex");
                return null;
            });

            String selectedLatexPath = callOnEdt(latexPath::getText);
            assertThat(selectedLatexPath).isEqualTo(xelatex.toString());
            CompletableFuture<Boolean> saved = callOnEdt(subject::savePendingChangesAsync);
            assertThat(saved.get(5, TimeUnit.SECONDS)).isTrue();
            PdfExportSettings settings = new PdfExportSettings(repository);
            assertThat(settings.pandocPathOverride()).isEqualTo(pandoc.toString());
            assertThat(settings.latexPathOverride()).isEqualTo(xelatex.toString());
            assertThat(settings.mermaidCliPath()).isEqualTo(mmdc.toString());
            assertThat(settings.chromiumPathOverride()).isEqualTo(chromium.toString());
        } finally {
            callOnEdt(() -> {
                subject.disposePanel();
                return null;
            });
            callOnEdt(() -> null);
        }
    }

    @Test
    @DisplayName("Browse buttons select and persist executable overrides through the chooser")
    void browseButtons_whenExecutablesAreSelected_overrideDiscoveredPaths() throws Exception {
        Path pandoc = createExecutable("custom-pandoc");
        Path latex = createExecutable("custom-latex");
        Path mermaid = createExecutable("custom-mmdc");
        Path chromium = createExecutable("custom-chromium");
        SettingsRepository repository = new SettingsRepository(tempDirectory.resolve("browse.properties"));
        PdfExportPanel.ExecutableChooser chooser = (title, currentPath) -> Optional.of(switch (title) {
            case "Select Pandoc executable" -> pandoc;
            case "Select LaTeX executable" -> latex;
            case "Select Mermaid CLI executable" -> mermaid;
            case "Select Chromium executable" -> chromium;
            default -> throw new AssertionError("Unexpected chooser title: %s".formatted(title));
        });
        PdfExportPanel subject = callOnEdt(() -> new PdfExportPanel(repository, Map.of(), chooser));
        try {
            JButton pandocBrowse = callOnEdt(() -> componentNamed(
                    subject,
                    "pdfExportPandocBrowseButton",
                    JButton.class
            ));
            JButton latexBrowse = callOnEdt(() -> componentNamed(
                    subject,
                    "pdfExportLatexBrowseButton",
                    JButton.class
            ));
            JButton mermaidBrowse = callOnEdt(() -> componentNamed(
                    subject,
                    "pdfExportMermaidBrowseButton",
                    JButton.class
            ));
            JButton chromiumBrowse = callOnEdt(() -> componentNamed(
                    subject,
                    "pdfExportChromiumBrowseButton",
                    JButton.class
            ));

            callOnEdt(() -> {
                pandocBrowse.doClick();
                latexBrowse.doClick();
                mermaidBrowse.doClick();
                chromiumBrowse.doClick();
                return null;
            });

            CompletableFuture<Boolean> saved = callOnEdt(subject::savePendingChangesAsync);
            assertThat(saved.get(5, TimeUnit.SECONDS)).isTrue();
            PdfExportSettings settings = new PdfExportSettings(repository);
            assertThat(settings.pandocPathOverride()).isEqualTo(pandoc.toString());
            assertThat(settings.latexPathOverride()).isEqualTo(latex.toString());
            assertThat(settings.mermaidCliPath()).isEqualTo(mermaid.toString());
            assertThat(settings.chromiumPathOverride()).isEqualTo(chromium.toString());
        } finally {
            callOnEdt(() -> {
                subject.disposePanel();
                return null;
            });
            callOnEdt(() -> null);
        }
    }

    @Test
    @DisplayName("Selecting Publication persists the PDF export backend without blocking the EDT")
    void modeSelection_whenPublicationSelected_persistsSetting() throws Exception {
        SettingsRepository repository = new SettingsRepository(tempDirectory.resolve("settings.properties"));
        PdfExportPanel subject = callOnEdt(() -> new PdfExportPanel(repository));
        try {
            JComboBox<?> mode = callOnEdt(() -> componentNamed(subject, "pdfExportModeComboBox", JComboBox.class));

            callOnEdt(() -> {
                mode.setSelectedItem(PdfExportMode.PUBLICATION);
                return null;
            });
            CompletableFuture<Boolean> saved = callOnEdt(subject::savePendingChangesAsync);
            assertThat(saved.get(5, TimeUnit.SECONDS)).isTrue();

            assertThat(new PdfExportSettings(repository).mode()).isEqualTo(PdfExportMode.PUBLICATION);
        } finally {
            callOnEdt(() -> {
                subject.disposePanel();
                return null;
            });
            callOnEdt(() -> null);
        }
    }

    @Test
    @DisplayName("Closing settings persists executable paths even while a path field remains focused")
    void savePendingChangesAsync_whenPathFieldHasUncommittedText_persistsVisibleValue() throws Exception {
        SettingsRepository repository = new SettingsRepository(tempDirectory.resolve("focused-path.properties"));
        PdfExportPanel subject = callOnEdt(() -> new PdfExportPanel(repository));
        try {
            JTextField pandocPath = callOnEdt(() -> componentNamed(
                    subject,
                    "pdfExportPandocPathField",
                    JTextField.class
            ));
            JTextField mermaidCliPath = callOnEdt(() -> componentNamed(
                    subject,
                    "pdfExportMermaidCliPathField",
                    JTextField.class
            ));
            String mermaidTooltip = callOnEdt(mermaidCliPath::getToolTipText);
            assertThat(mermaidTooltip)
                    .contains("Mermaid CLI 11.x")
                    .contains("shell launchers are unsupported");
            callOnEdt(() -> {
                pandocPath.setText("/tools/pandoc-current");
                mermaidCliPath.setText("/tools/mmdc-current");
                return null;
            });

            CompletableFuture<Boolean> saved = callOnEdt(subject::savePendingChangesAsync);

            assertThat(saved.get(5, TimeUnit.SECONDS)).isTrue();
            PdfExportSettings settings = new PdfExportSettings(repository);
            assertThat(settings.pandocPathOverride()).isEqualTo("/tools/pandoc-current");
            assertThat(settings.mermaidCliPath()).isEqualTo("/tools/mmdc-current");
        } finally {
            callOnEdt(() -> {
                subject.disposePanel();
                return null;
            });
            callOnEdt(() -> null);
        }
    }

    @Test
    @DisplayName("Leaving the Mermaid executable field persists its trimmed value asynchronously")
    void focusLost_whenMermaidPathChanges_persistsTrimmedValue() throws Exception {
        var repository = new TrackingSettingsRepository(tempDirectory.resolve("mermaid-focus.properties"));
        PdfExportPanel subject = callOnEdt(() -> new PdfExportPanel(repository));
        try {
            JTextField mermaidCliPath = callOnEdt(() -> componentNamed(
                    subject,
                    "pdfExportMermaidCliPathField",
                    JTextField.class
            ));
            callOnEdt(() -> {
                mermaidCliPath.setText(" /tools/mmdc ");
                FocusEvent event = new FocusEvent(mermaidCliPath, FocusEvent.FOCUS_LOST);
                Arrays.stream(mermaidCliPath.getFocusListeners()).forEach(listener -> listener.focusLost(event));
                return null;
            });

            assertThat(repository.mermaidWrite.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(new PdfExportSettings(repository).mermaidCliPath()).isEqualTo("/tools/mmdc");
        } finally {
            callOnEdt(() -> {
                subject.disposePanel();
                return null;
            });
            callOnEdt(() -> null);
        }
    }

    @Test
    @DisplayName("Retrying settings close persists the selected mode after a transient write failure")
    void savePendingChangesAsync_whenModeWriteInitiallyFails_retriesVisibleSelection() throws Exception {
        var repository = new FailOnceSettingsRepository(tempDirectory.resolve("retry-mode.properties"));
        PdfExportPanel subject = callOnEdt(() -> new PdfExportPanel(repository));
        try {
            JComboBox<?> mode = callOnEdt(() -> componentNamed(
                    subject,
                    "pdfExportModeComboBox",
                    JComboBox.class
            ));
            callOnEdt(() -> {
                mode.setSelectedItem(PdfExportMode.PUBLICATION);
                return null;
            });

            CompletableFuture<Boolean> firstSave = callOnEdt(subject::savePendingChangesAsync);
            assertThat(firstSave.get(5, TimeUnit.SECONDS)).isFalse();

            CompletableFuture<Boolean> retry = callOnEdt(subject::savePendingChangesAsync);
            assertThat(retry.get(5, TimeUnit.SECONDS)).isTrue();
            assertThat(new PdfExportSettings(repository).mode()).isEqualTo(PdfExportMode.PUBLICATION);
        } finally {
            callOnEdt(() -> {
                subject.disposePanel();
                return null;
            });
            callOnEdt(() -> null);
        }
    }

    private Path createExecutable(String name) throws Exception {
        String executableName = SystemInfo.isWindows ? "%s.exe".formatted(name) : name;
        Path executable = Files.writeString(tempDirectory.resolve(executableName), "test");
        if (!SystemInfo.isWindows) {
            assertThat(executable.toFile().setExecutable(true)).isTrue();
        }
        return executable.toAbsolutePath().normalize();
    }

    private <T extends Component> T componentNamed(Container root, String name, Class<T> type) {
        return Arrays.stream(root.getComponents())
                .map(component -> {
                    if (type.isInstance(component) && name.equals(component.getName())) {
                        return type.cast(component);
                    }
                    return component instanceof Container container ? componentNamedOrNull(container, name, type) : null;
                })
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElseThrow();
    }

    private <T extends Component> T componentNamedOrNull(Container root, String name, Class<T> type) {
        return Arrays.stream(root.getComponents())
                .map(component -> {
                    if (type.isInstance(component) && name.equals(component.getName())) {
                        return type.cast(component);
                    }
                    return component instanceof Container container ? componentNamedOrNull(container, name, type) : null;
                })
                .filter(java.util.Objects::nonNull)
                .findFirst()
                .orElse(null);
    }

    private static final class TrackingSettingsRepository extends SettingsRepository {

        private final CountDownLatch mermaidWrite = new CountDownLatch(1);

        private TrackingSettingsRepository(Path settingsFile) {
            super(settingsFile);
        }

        @Override
        public void put(String key, String value) {
            super.put(key, value);
            if (PdfExportSettings.MERMAID_CLI_PATH_KEY.equals(key)) {
                mermaidWrite.countDown();
            }
        }
    }

    private static final class FailOnceSettingsRepository extends SettingsRepository {

        private final AtomicBoolean failModeWrite = new AtomicBoolean(true);

        private FailOnceSettingsRepository(Path settingsFile) {
            super(settingsFile);
        }

        @Override
        public void put(String key, String value) {
            if (PdfExportSettings.MODE_KEY.equals(key) && failModeWrite.compareAndSet(true, false)) {
                throw new IllegalStateException("simulated mode write failure");
            }
            super.put(key, value);
        }
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
