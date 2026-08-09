package com.github.drafael.chat4j.settings;

import com.formdev.flatlaf.util.SystemFileChooser;
import com.github.drafael.chat4j.chat.export.pdf.PdfExportMode;
import com.github.drafael.chat4j.chat.export.pdf.PdfExportSettings;
import com.github.drafael.chat4j.persistence.settings.SettingsRepository;
import java.awt.BorderLayout;
import java.awt.GridBagConstraints;
import java.awt.Insets;
import java.awt.event.FocusAdapter;
import java.awt.event.FocusEvent;
import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;
import javax.swing.JButton;
import javax.swing.JComboBox;
import javax.swing.JFileChooser;
import javax.swing.JPanel;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import lombok.NonNull;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.exception.ExceptionUtils;

import static com.github.drafael.chat4j.chat.export.pdf.ChromiumExecutableResolver.discover;
import static com.github.drafael.chat4j.provider.support.ProcessCommandSupport.findDirectExecutable;
import static com.github.drafael.chat4j.provider.support.ProcessCommandSupport.isDirectExecutable;

public class PdfExportPanel extends AbstractSettingsPanel implements AsyncPendingSettingsSaveParticipant {

    private final PdfExportSettings exportSettings;
    private final Map<String, String> environment;
    private final ExecutableChooser executableChooser;
    private final SettingsWriteQueue writeQueue = new SettingsWriteQueue("pdf-export-settings-save-");
    private final JComboBox<PdfExportMode> mode;
    private final JTextField pandocPath;
    private final JComboBox<String> latexEngine;
    private final JTextField latexPath;
    private final JTextField mermaidCliPath;
    private final JTextField chromiumPath;
    private CompletableFuture<Void> latestSave = CompletableFuture.completedFuture(null);
    private String previousLatexEngine;
    private String lastSaveError = "";
    private boolean disposed;

    public PdfExportPanel(@NonNull SettingsRepository settingsRepository) {
        this(settingsRepository, System.getenv(), null);
    }

    PdfExportPanel(@NonNull SettingsRepository settingsRepository, @NonNull Map<String, String> environment) {
        this(settingsRepository, environment, null);
    }

    PdfExportPanel(
            @NonNull SettingsRepository settingsRepository,
            @NonNull Map<String, String> environment,
            ExecutableChooser executableChooser
    ) {
        super(settingsRepository);
        this.exportSettings = new PdfExportSettings(settingsRepository);
        this.environment = Map.copyOf(environment);
        this.executableChooser = executableChooser == null ? this::showExecutableChooser : executableChooser;

        JPanel form = createFormPanel("PDF Export");
        GridBagConstraints constraints = createFormConstraints();
        int row = 0;

        mode = withPreferredWidth(new JComboBox<>(PdfExportMode.values()), 300);
        mode.setName("pdfExportModeComboBox");
        mode.setSelectedItem(exportSettings.mode());
        addRow(form, constraints, row++, "Export engine", mode);

        pandocPath = withPreferredWidth(new JTextField(initialExecutablePath(
                exportSettings.pandocPathOverride(),
                "pandoc"
        )), 360);
        pandocPath.setName("pdfExportPandocPathField");
        pandocPath.setToolTipText("Detected from PATH when unset. Edit to use a different executable.");
        addRow(form, constraints, row++, "Pandoc executable", executablePathRow(
                pandocPath,
                "pdfExportPandocBrowseButton",
                "Select Pandoc executable",
                exportSettings::persistPandocPath
        ));

        latexEngine = withPreferredWidth(new JComboBox<>(new String[]{"lualatex", "xelatex"}), 220);
        latexEngine.setName("pdfExportLatexEngineComboBox");
        latexEngine.setSelectedItem(exportSettings.latexEngine());
        addRow(form, constraints, row++, "LaTeX engine", latexEngine);

        latexPath = withPreferredWidth(new JTextField(initialExecutablePath(
                exportSettings.latexPathOverride(),
                exportSettings.latexEngine()
        )), 360);
        latexPath.setName("pdfExportLatexPathField");
        latexPath.setToolTipText("Detected from PATH for the selected engine when unset. Edit to override it.");
        addRow(form, constraints, row++, "LaTeX executable", executablePathRow(
                latexPath,
                "pdfExportLatexBrowseButton",
                "Select LaTeX executable",
                exportSettings::persistLatexPath
        ));

        mermaidCliPath = withPreferredWidth(new JTextField(initialExecutablePath(
                exportSettings.mermaidCliPath(),
                "mmdc"
        )), 360);
        mermaidCliPath.setName("pdfExportMermaidCliPathField");
        mermaidCliPath.setToolTipText(
                "Optional Mermaid CLI 11.x executable, detected from PATH when unset; shell launchers are unsupported."
        );
        addRow(form, constraints, row++, "Mermaid CLI executable", executablePathRow(
                mermaidCliPath,
                "pdfExportMermaidBrowseButton",
                "Select Mermaid CLI executable",
                exportSettings::persistMermaidCliPath
        ));

        chromiumPath = withPreferredWidth(new JTextField(StringUtils.defaultIfBlank(
                exportSettings.chromiumPathOverride(),
                discover(this.environment).orElse("")
        )), 360);
        chromiumPath.setName("pdfExportChromiumPathField");
        chromiumPath.setToolTipText(
                "Chrome, Chromium, or Edge executable used by Mermaid CLI, detected automatically when unset."
        );
        addRow(form, constraints, row++, "Chromium executable", executablePathRow(
                chromiumPath,
                "pdfExportChromiumBrowseButton",
                "Select Chromium executable",
                exportSettings::persistChromiumPath
        ));

        row = addSectionHint(
                form,
                constraints,
                row,
                "<html>Auto uses a ready active Chromium transcript when available and the offline built-in renderer otherwise.<br>Publication requires installed Pandoc and LaTeX tools. Mermaid CLI 11.x is optional.</html>"
        );
        addVerticalSpacer(form, constraints, row);

        mode.addActionListener(e -> {
            if (mode.getSelectedItem() instanceof PdfExportMode selectedMode) {
                enqueueSave(() -> exportSettings.persistMode(selectedMode));
            }
        });
        previousLatexEngine = exportSettings.latexEngine();
        latexEngine.addActionListener(e -> {
            if (latexEngine.getSelectedItem() instanceof String selectedEngine) {
                String previousEngine = previousLatexEngine;
                previousLatexEngine = selectedEngine;
                String currentPath = StringUtils.trimToEmpty(latexPath.getText());
                String previousDiscoveredPath = discoveredExecutablePath(previousEngine);
                if (currentPath.isEmpty()
                        || previousEngine.equals(currentPath)
                        || previousDiscoveredPath.equals(currentPath)
                ) {
                    String selectedPath = discoveredExecutablePath(selectedEngine);
                    latexPath.setText(selectedPath);
                    enqueueSave(() -> exportSettings.persistLatexPath(selectedPath));
                }
                enqueueSave(() -> exportSettings.persistLatexEngine(selectedEngine));
            }
        });
        persistOnFocusLost(pandocPath, exportSettings::persistPandocPath);
        persistOnFocusLost(latexPath, exportSettings::persistLatexPath);
        persistOnFocusLost(mermaidCliPath, exportSettings::persistMermaidCliPath);
        persistOnFocusLost(chromiumPath, exportSettings::persistChromiumPath);
    }

    private JPanel executablePathRow(
            JTextField field,
            String buttonName,
            String dialogTitle,
            Consumer<String> writer
    ) {
        var row = new JPanel(new BorderLayout(6, 0));
        row.setOpaque(false);
        row.add(field, BorderLayout.CENTER);
        JButton browseButton = withPreferredWidth(new JButton("…"), 36);
        browseButton.setName(buttonName);
        browseButton.setMargin(new Insets(2, 8, 2, 8));
        browseButton.setToolTipText(dialogTitle);
        browseButton.getAccessibleContext().setAccessibleName(dialogTitle);
        browseButton.addActionListener(e -> browseExecutable(field, dialogTitle, writer));
        row.add(browseButton, BorderLayout.EAST);
        return row;
    }

    private void browseExecutable(JTextField field, String dialogTitle, Consumer<String> writer) {
        executableChooser.choose(dialogTitle, StringUtils.trimToEmpty(field.getText()))
                .map(path -> path.toAbsolutePath().normalize())
                .ifPresent(path -> {
                    if (!Files.isRegularFile(path) || !Files.isExecutable(path) || !isDirectExecutable(path)) {
                        setStatusError("Select a directly executable file; .cmd and .bat launchers are unsupported.");
                        return;
                    }
                    String selectedPath = path.toString();
                    field.setText(selectedPath);
                    enqueueSave(() -> writer.accept(selectedPath));
                });
    }

    private Optional<Path> showExecutableChooser(String dialogTitle, String currentPath) {
        SystemFileChooser chooser = new SystemFileChooser();
        chooser.setDialogTitle(dialogTitle);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        chooser.setMultiSelectionEnabled(false);
        chooser.setAcceptAllFileFilterUsed(true);
        chooser.setFileHidingEnabled(false);
        if (StringUtils.isNotBlank(currentPath)) {
            try {
                File currentFile = Path.of(currentPath).toFile();
                File currentDirectory = currentFile.isDirectory() ? currentFile : currentFile.getParentFile();
                if (currentDirectory != null && currentDirectory.isDirectory()) {
                    chooser.setCurrentDirectory(currentDirectory);
                }
                if (currentFile.isFile()) {
                    chooser.setSelectedFile(currentFile);
                }
            } catch (Exception ignored) {
            }
        }
        if (chooser.showOpenDialog(this) != SystemFileChooser.APPROVE_OPTION || chooser.getSelectedFile() == null) {
            return Optional.empty();
        }
        return Optional.of(chooser.getSelectedFile().toPath());
    }

    private String initialExecutablePath(String configuredPath, String executable) {
        return StringUtils.defaultIfBlank(configuredPath, discoveredExecutablePath(executable));
    }

    private String discoveredExecutablePath(String executable) {
        return findDirectExecutable(executable, environment).orElse("");
    }

    private void persistOnFocusLost(JTextField field, Consumer<String> writer) {
        field.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                String value = StringUtils.trimToEmpty(field.getText());
                enqueueSave(() -> writer.accept(value));
            }
        });
    }

    private void enqueueSave(Runnable write) {
        if (disposed) {
            return;
        }
        CompletableFuture<Void> save = writeQueue.submit(write);
        latestSave = CompletableFuture.allOf(latestSave, save);
        save.whenComplete((ignored, error) -> SwingUtilities.invokeLater(() -> {
            if (disposed) {
                return;
            }
            if (error == null) {
                setStatusInfo(STATUS_SAVED);
                return;
            }
            lastSaveError = StringUtils.defaultIfBlank(ExceptionUtils.getRootCauseMessage(error), "Failed to save PDF export settings");
            setStatusError(lastSaveError);
        }));
    }

    @Override
    public CompletableFuture<Boolean> savePendingChangesAsync() {
        if (!SwingUtilities.isEventDispatchThread()) {
            return CompletableFuture.failedFuture(new IllegalStateException("PDF export settings must be read on the EDT"));
        }
        PdfExportMode currentMode = mode.getSelectedItem() instanceof PdfExportMode selectedMode
                ? selectedMode
                : PdfExportMode.AUTO;
        String currentLatexEngine = latexEngine.getSelectedItem() instanceof String selectedEngine
                ? selectedEngine
                : PdfExportSettings.DEFAULT_LATEX_ENGINE;
        String currentPandocPath = StringUtils.trimToEmpty(pandocPath.getText());
        String currentLatexPath = StringUtils.trimToEmpty(latexPath.getText());
        String currentMermaidCliPath = StringUtils.trimToEmpty(mermaidCliPath.getText());
        String currentChromiumPath = StringUtils.trimToEmpty(chromiumPath.getText());
        enqueueSave(() -> exportSettings.persistMode(currentMode));
        enqueueSave(() -> exportSettings.persistLatexEngine(currentLatexEngine));
        enqueueSave(() -> exportSettings.persistPandocPath(currentPandocPath));
        enqueueSave(() -> exportSettings.persistLatexPath(currentLatexPath));
        enqueueSave(() -> exportSettings.persistMermaidCliPath(currentMermaidCliPath));
        enqueueSave(() -> exportSettings.persistChromiumPath(currentChromiumPath));
        CompletableFuture<Void> pending = latestSave;
        latestSave = CompletableFuture.completedFuture(null);
        return pending.handle((ignored, error) -> {
            lastSaveError = error == null
                    ? ""
                    : StringUtils.defaultIfBlank(
                            ExceptionUtils.getRootCauseMessage(error),
                            "Failed to save PDF export settings"
                    );
            return error == null;
        });
    }

    @Override
    public String lastSaveError() {
        return lastSaveError;
    }

    @Override
    public String settingsSectionName() {
        return "PDF Export";
    }

    @FunctionalInterface
    interface ExecutableChooser {
        Optional<Path> choose(String dialogTitle, String currentPath);
    }

    public void disposePanel() {
        disposed = true;
        writeQueue.close();
        disposeSettingsPanel();
    }
}
