package com.github.drafael.chat4j.chat.model;

import com.github.drafael.chat4j.provider.api.ProviderModelInfo;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.GraphicsConfiguration;
import java.awt.Insets;
import java.awt.Rectangle;
import java.awt.Toolkit;
import java.awt.Window;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.util.Arrays;
import java.util.Locale;
import javax.swing.BorderFactory;
import javax.swing.BoxLayout;
import javax.swing.Icon;
import javax.swing.JLabel;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JWindow;
import javax.swing.UIManager;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.StringUtils;
import org.apache.commons.lang3.Strings;

final class ModelInfoPopup extends JWindow {

    ModelInfoPopup(Window owner) {
        super(owner);
        setType(Type.POPUP);
        setFocusableWindowState(false);
        setAutoRequestFocus(false);
    }

    void showInfo(ProviderModelInfo info, Icon providerIcon, Rectangle selectorBounds, Rectangle rowBounds) {
        JPanel content = content(info, providerIcon);
        var scroll = new JScrollPane(content);
        scroll.setBorder(BorderFactory.createEmptyBorder());
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        GraphicsConfiguration configuration = getOwner().getGraphicsConfiguration();
        Rectangle screen = configuration.getBounds();
        Insets insets = Toolkit.getDefaultToolkit().getScreenInsets(configuration);
        Rectangle usable = new Rectangle(screen.x + insets.left, screen.y + insets.top,
                screen.width - insets.left - insets.right, screen.height - insets.top - insets.bottom);
        fitToScreen(scroll, usable.getSize());
        setContentPane(scroll);
        pack();
        setBounds(position(selectorBounds, rowBounds, getSize(), usable));
        setVisible(true);
    }

    static JPanel content(ProviderModelInfo info, Icon providerIcon) {
        var content = new JPanel();
        content.setLayout(new BoxLayout(content, BoxLayout.Y_AXIS));
        Color border = ObjectUtils.firstNonNull(UIManager.getColor("Component.borderColor"), Color.GRAY);
        content.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(border), BorderFactory.createEmptyBorder(12, 14, 12, 14)));
        JTextArea title = wrappedText(StringUtils.defaultIfBlank(info.displayName(), info.modelId()));
        title.setFont(title.getFont().deriveFont(Font.BOLD));
        var heading = new JPanel(new BorderLayout(6, 0));
        heading.setOpaque(false);
        heading.setAlignmentX(LEFT_ALIGNMENT);
        var providerLabel = new JLabel(providerIcon);
        providerLabel.setVerticalAlignment(JLabel.TOP);
        if (providerIcon != null) {
            int lineHeight = title.getFontMetrics(title.getFont()).getHeight();
            int top = title.getInsets().top + Math.max(0, (lineHeight - providerIcon.getIconHeight()) / 2);
            providerLabel.setBorder(BorderFactory.createEmptyBorder(top, 0, 0, 0));
        }
        heading.add(providerLabel, BorderLayout.WEST);
        heading.add(title, BorderLayout.CENTER);
        content.add(heading);
        if (info.displayName() != null && !Strings.CS.equals(info.displayName(), info.modelId())) {
            content.add(wrappedText(info.modelId()));
        }
        if (info.description() != null) {
            JTextArea description = wrappedText(info.description());
            description.setBorder(BorderFactory.createEmptyBorder(8, 0, 8, 0));
            content.add(description);
        }
        addPrice(content, "Input", info.inputUsdPerMillion());
        addPrice(content, "Output", info.outputUsdPerMillion());
        addTokens(content, "Context window", info.contextWindowTokens());
        addTokens(content, "Max input", info.maxInputTokens());
        addTokens(content, "Max output", info.maxOutputTokens());
        measureWrappedText(title);
        heading.setMaximumSize(new Dimension(Integer.MAX_VALUE, heading.getPreferredSize().height));
        Arrays.stream(content.getComponents()).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                .forEach(ModelInfoPopup::measureWrappedText);
        content.getAccessibleContext().setAccessibleName("Model information for %s".formatted(info.modelId()));
        return content;
    }

    private static JTextArea wrappedText(String text) {
        var area = new JTextArea(text, 0, 32);
        area.setEditable(false);
        area.setFocusable(false);
        area.setOpaque(false);
        area.setLineWrap(true);
        area.setWrapStyleWord(true);
        area.setFont(UIManager.getFont("Label.font"));
        area.setForeground(UIManager.getColor("Label.foreground"));
        area.setAlignmentX(LEFT_ALIGNMENT);
        return area;
    }

    private static void fitToScreen(JScrollPane scroll, Dimension available) {
        JPanel content = (JPanel) scroll.getViewport().getView();
        int width = Math.min(scroll.getPreferredSize().width, available.width);
        measureContent(content, width);
        if (content.getPreferredSize().height > available.height) {
            measureContent(content, width - scroll.getVerticalScrollBar().getPreferredSize().width);
        }
        scroll.setPreferredSize(new Dimension(width, Math.min(content.getPreferredSize().height, available.height)));
    }

    private static void measureContent(JPanel content, int width) {
        Insets insets = content.getInsets();
        int textWidth = Math.max(1, width - insets.left - insets.right);
        JPanel heading = (JPanel) content.getComponent(0);
        BorderLayout layout = (BorderLayout) heading.getLayout();
        JLabel provider = (JLabel) layout.getLayoutComponent(BorderLayout.WEST);
        JTextArea title = (JTextArea) layout.getLayoutComponent(BorderLayout.CENTER);
        measureWrappedText(title, Math.max(1, textWidth - provider.getPreferredSize().width - layout.getHgap()));
        heading.setPreferredSize(null);
        heading.invalidate();
        Dimension headingSize = new Dimension(textWidth, heading.getPreferredSize().height);
        heading.setPreferredSize(headingSize);
        heading.setMaximumSize(new Dimension(Integer.MAX_VALUE, headingSize.height));
        Arrays.stream(content.getComponents()).filter(JTextArea.class::isInstance).map(JTextArea.class::cast)
                .forEach(area -> measureWrappedText(area, textWidth));
        content.invalidate();
        content.setPreferredSize(new Dimension(width, content.getLayout().preferredLayoutSize(content).height));
    }

    private static void measureWrappedText(JTextArea area) {
        measureWrappedText(area, area.getPreferredSize().width);
    }

    private static void measureWrappedText(JTextArea area, int width) {
        area.setPreferredSize(null);
        area.setSize(width, Short.MAX_VALUE);
        Dimension measured = area.getPreferredSize();
        area.setPreferredSize(new Dimension(width, measured.height));
        area.setMinimumSize(new Dimension(0, measured.height));
        area.setMaximumSize(new Dimension(Integer.MAX_VALUE, measured.height));
    }

    private static void addPrice(JPanel content, String label, BigDecimal amount) {
        if (amount != null) {
            NumberFormat format = NumberFormat.getCurrencyInstance(Locale.US);
            format.setMaximumFractionDigits(6);
            JTextArea line = wrappedText("%s: %s / M tokens".formatted(label, format.format(amount)));
            line.setBorder(BorderFactory.createEmptyBorder());
            content.add(line);
        }
    }

    private static void addTokens(JPanel content, String label, Long tokens) {
        if (tokens != null) {
            JTextArea line = wrappedText("%s: %s tokens".formatted(label, NumberFormat.getIntegerInstance().format(tokens)));
            line.setBorder(BorderFactory.createEmptyBorder());
            content.add(line);
        }
    }

    static Rectangle position(Rectangle selector, Rectangle row, Dimension preferred, Rectangle screen) {
        int width = Math.min(preferred.width, screen.width);
        int height = Math.min(preferred.height, screen.height);
        int right = selector.x + selector.width + 8;
        int x = right + width <= screen.x + screen.width ? right : selector.x - width - 8;
        x = Math.max(screen.x, Math.min(x, screen.x + screen.width - width));
        int y = Math.max(screen.y, Math.min(row.y, screen.y + screen.height - height));
        return new Rectangle(x, y, width, height);
    }
}
