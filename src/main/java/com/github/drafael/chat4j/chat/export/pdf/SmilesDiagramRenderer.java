package com.github.drafael.chat4j.chat.export.pdf;

import com.github.weisj.jsvg.SVGDocument;
import com.github.weisj.jsvg.parser.LoaderContext;
import com.github.weisj.jsvg.parser.SVGLoader;
import com.github.weisj.jsvg.parser.resources.ResourcePolicy;
import com.github.weisj.jsvg.view.ViewBox;
import java.awt.Color;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import javax.imageio.ImageIO;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.graalvm.polyglot.Context;
import org.graalvm.polyglot.Value;

@Slf4j
final class SmilesDiagramRenderer {

    static final int MAX_SOURCE_LENGTH = 4_000;
    static final int IMAGE_WIDTH = 1_800;
    static final int IMAGE_HEIGHT = 1_296;
    private static final int LOGICAL_WIDTH = 500;
    private static final int LOGICAL_HEIGHT = 360;
    private static final double OUTPUT_SCALE = 0.8;
    private static final int MAX_DIAGNOSTIC_LENGTH = 220;
    private static final SmilesDiagramRenderer INSTANCE = new SmilesDiagramRenderer();
    private static final String DOM_ADAPTER = resourceText("/web/export/pdf/smiles-svg-dom.js");
    private static final String SMILES_DRAWER = resourceText("/web/smilesdrawer/smiles-drawer.min.js");
    private static final String RENDER_FUNCTION = """
            (function(smiles) {
                var tree = null;
                var parseFailed = false;
                SmilesDrawer.parse(String(smiles || ''), function(parsed) {
                    tree = parsed;
                }, function() {
                    parseFailed = true;
                });
                if (parseFailed || tree === null) {
                    return '';
                }
                try {
                    var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
                    var options = {
                        width: 500,
                        height: 360,
                        scale: 1.35,
                        padding: 12,
                        compactDrawing: false,
                        themes: {
                            chat4j: {
                                C: '#202124', O: '#e53935', N: '#2563eb', F: '#059669',
                                CL: '#059669', BR: '#c2410c', I: '#7e22ce', P: '#c2410c',
                                S: '#ca8a04', B: '#c2410c', SI: '#c2410c', H: '#737373',
                                BACKGROUND: '#ffffff'
                            }
                        }
                    };
                    new SmilesDrawer.SvgDrawer(options).draw(tree, svg, 'chat4j', null);
                    svg.setAttribute('xmlns', 'http://www.w3.org/2000/svg');
                    svg.setAttribute('width', '500');
                    svg.setAttribute('height', '360');
                    svg.style.width = '';
                    svg.style.height = '';
                    return svg.outerHTML;
                } catch (error) {
                    return '';
                }
            })
            """;

    private Context context;
    private Value renderFunction;
    private boolean unavailable;

    private SmilesDiagramRenderer() {
    }

    static SmilesDiagramRenderer instance() {
        return INSTANCE;
    }

    synchronized Result render(String source) {
        String normalized = StringUtils.trimToEmpty(source);
        if (normalized.isBlank()) {
            return Result.failure(Failure.BLANK);
        }
        if (normalized.length() > MAX_SOURCE_LENGTH) {
            return Result.failure(Failure.TOO_LARGE);
        }
        if (unavailable) {
            return Result.failure(Failure.UNAVAILABLE);
        }

        try {
            String svg = renderer().execute(normalized).asString();
            if (StringUtils.isBlank(svg)) {
                return Result.failure(Failure.INVALID);
            }
            return Result.success(renderPng(svg), displaySize(normalized));
        } catch (Throwable t) {
            log.warn("Static SMILES rendering is unavailable: {}", boundedMessage(t));
            unavailable = true;
            closeContext();
            return Result.failure(Failure.UNAVAILABLE);
        }
    }

    private DisplaySize displaySize(String source) {
        int atomCount = estimatedAtomCount(source);
        if (atomCount <= 3) {
            return DisplaySize.SMALL;
        }
        return atomCount <= 8 ? DisplaySize.MEDIUM : DisplaySize.LARGE;
    }

    private int estimatedAtomCount(String source) {
        int atomCount = 0;
        boolean bracketedAtom = false;
        for (int index = 0; index < source.length(); index++) {
            char current = source.charAt(index);
            if (current == '[') {
                atomCount++;
                bracketedAtom = true;
                continue;
            }
            if (bracketedAtom) {
                bracketedAtom = current != ']';
                continue;
            }
            if (Character.isUpperCase(current)) {
                atomCount++;
                if (index + 1 < source.length()
                        && ((current == 'C' && source.charAt(index + 1) == 'l')
                        || (current == 'B' && source.charAt(index + 1) == 'r'))
                ) {
                    index++;
                }
                continue;
            }
            if ("bcnops".indexOf(current) >= 0) {
                atomCount++;
            }
        }
        return atomCount;
    }

    private Value renderer() {
        if (renderFunction != null) {
            return renderFunction;
        }

        if (DOM_ADAPTER.isBlank() || SMILES_DRAWER.isBlank()) {
            throw new IllegalStateException("Required SMILES rendering resources are unavailable");
        }

        context = Context.newBuilder("js")
                .allowAllAccess(false)
                .option("engine.WarnInterpreterOnly", "false")
                .build();
        context.eval("js", DOM_ADAPTER);
        context.eval("js", SMILES_DRAWER);
        renderFunction = context.eval("js", RENDER_FUNCTION);
        return renderFunction;
    }

    private byte[] renderPng(String svgSource) throws IOException {
        LoaderContext loaderContext = LoaderContext.builder()
                .externalResourcePolicy(ResourcePolicy.DENY_ALL)
                .build();
        SVGDocument svg;
        try (var input = new ByteArrayInputStream(svgSource.getBytes(StandardCharsets.UTF_8))) {
            svg = new SVGLoader().load(input, null, loaderContext);
        }
        if (svg == null) {
            throw new IOException("Generated SVG could not be parsed");
        }

        var image = new BufferedImage(IMAGE_WIDTH, IMAGE_HEIGHT, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.WHITE);
            graphics.fillRect(0, 0, IMAGE_WIDTH, IMAGE_HEIGHT);
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            double scale = Math.min(
                    (double) IMAGE_WIDTH / LOGICAL_WIDTH,
                    (double) IMAGE_HEIGHT / LOGICAL_HEIGHT
            ) * OUTPUT_SCALE;
            graphics.translate(
                    (IMAGE_WIDTH - LOGICAL_WIDTH * scale) / 2,
                    (IMAGE_HEIGHT - LOGICAL_HEIGHT * scale) / 2
            );
            graphics.scale(scale, scale);
            svg.render(null, graphics, new ViewBox(0, 0, LOGICAL_WIDTH, LOGICAL_HEIGHT));
        } finally {
            graphics.dispose();
        }

        try (var output = new ByteArrayOutputStream()) {
            if (!ImageIO.write(image, "png", output)) {
                throw new IOException("PNG encoder is unavailable");
            }
            return output.toByteArray();
        }
    }

    private void closeContext() {
        if (context != null) {
            try {
                context.close(true);
            } catch (Exception ignored) {
                // Source fallback remains available when cleanup fails.
            }
        }
        context = null;
        renderFunction = null;
    }

    private String boundedMessage(Throwable failure) {
        String message = StringUtils.defaultIfBlank(failure.getMessage(), failure.getClass().getSimpleName());
        return StringUtils.abbreviate(message.replaceAll("\\s+", " "), MAX_DIAGNOSTIC_LENGTH);
    }

    private static String resourceText(String path) {
        try (InputStream input = SmilesDiagramRenderer.class.getResourceAsStream(path)) {
            return input == null ? "" : new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    enum Failure {
        BLANK,
        TOO_LARGE,
        INVALID,
        UNAVAILABLE
    }

    enum DisplaySize {
        SMALL,
        MEDIUM,
        LARGE
    }

    record Result(byte[] png, Failure failure, DisplaySize displaySize) {

        static Result success(byte[] png, DisplaySize displaySize) {
            return new Result(png, null, displaySize);
        }

        static Result failure(Failure failure) {
            return new Result(null, failure, null);
        }

        boolean successful() {
            return png != null;
        }
    }
}
