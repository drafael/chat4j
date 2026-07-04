(function() {
    var MERMAID_MAX_CHARS = 20000;
    var SMILES_MAX_CHARS = 4000;
    var MOL_MAX_CHARS = 50000;
    var SDF_MAX_CHARS = 200000;
    var SDF_MAX_RECORDS = 12;
    var MERMAID_CATEGORY_COLOR_COUNT = 12;
    var renderCounter = 0;
    var mermaidInitialized = false;

    function closestCodeBlockShell(table) {
        var parent = table ? table.parentNode : null;
        return parent && parent.classList && parent.classList.contains('code-block-shell') ? parent : null;
    }
    function sourceFromBlock(table) {
        var pre = table ? (table.querySelector('tr.code-body pre') || table.querySelector('pre')) : null;
        return pre ? String(pre.textContent || '').trim() : '';
    }
    function sourceNode(table) {
        return closestCodeBlockShell(table) || table;
    }
    function compactErrorMessage(error, fallback) {
        var message = String(error && (error.str || error.message || error) || fallback || 'Diagram render failed');
        return message.replace(/ +/g, ' ').trim().slice(0, 220);
    }
    function friendlyDiagramError(message) {
        var detail = String(message || 'Diagram render failed');
        if (/parse error|expecting|syntax error/i.test(detail)) {
            return 'Mermaid syntax error — source shown below';
        }
        if (/timed out/i.test(detail)) {
            return 'Mermaid render timed out — source shown below';
        }
        if (/unavailable/i.test(detail)) {
            return detail;
        }
        if (/^MOL/i.test(detail)) {
            return 'MOL must be complete V2000 source — source shown below';
        }
        if (/^SDF/i.test(detail)) {
            return 'SDF must be complete V2000 source — source shown below';
        }
        return 'Diagram render failed — source shown below';
    }
    function markError(table, message) {
        var detail = message || 'Diagram render failed';
        var label = friendlyDiagramError(detail);
        var node = sourceNode(table);
        if (!node) {
            return;
        }
        node.setAttribute('data-chat4j-diagram-rendered', 'error');
        node.setAttribute('data-chat4j-diagram-error', label);
        node.setAttribute('title', detail);
        if (node === table && table && !table.querySelector('tr.chat4j-diagram-error')) {
            var row = table.insertRow(0);
            row.className = 'chat4j-diagram-error';
            var cell = row.insertCell(0);
            cell.colSpan = 2;
            var badge = document.createElement('span');
            badge.className = 'chat4j-diagram-error-badge';
            badge.textContent = label;
            badge.title = detail;
            cell.appendChild(badge);
        }
    }
    function replaceSource(table, replacement) {
        var node = sourceNode(table);
        if (node && node.parentNode) {
            node.parentNode.replaceChild(replacement, node);
        }
    }
    function cssColor(property, fallback) {
        var value = '';
        try {
            value = window.getComputedStyle(document.body).getPropertyValue(property);
        } catch (error) {
            value = '';
        }
        return value && value.trim() ? value.trim() : fallback;
    }
    function bodyColor(fallback) {
        try {
            return window.getComputedStyle(document.body).color || fallback;
        } catch (error) {
            return fallback;
        }
    }
    function bodyBackground(fallback) {
        try {
            return window.getComputedStyle(document.body).backgroundColor || fallback;
        } catch (error) {
            return fallback;
        }
    }
    function colorParts(color) {
        var value = String(color || '').trim();
        var rgb = value.match(/rgba?\((\d+),\s*(\d+),\s*(\d+)/i);
        if (rgb) {
            return [Number(rgb[1]), Number(rgb[2]), Number(rgb[3])];
        }
        var hex = value.match(/^#([0-9a-f]{3}|[0-9a-f]{6})$/i);
        if (!hex) {
            return null;
        }
        var digits = hex[1].length === 3
            ? hex[1].replace(/./g, function(ch) { return ch + ch; })
            : hex[1];
        return [
            parseInt(digits.slice(0, 2), 16),
            parseInt(digits.slice(2, 4), 16),
            parseInt(digits.slice(4, 6), 16)
        ];
    }
    function relativeLuminance(color) {
        var parts = colorParts(color);
        if (!parts) {
            return null;
        }
        function channel(value) {
            var normalized = value / 255;
            return normalized <= 0.03928
                ? normalized / 12.92
                : Math.pow((normalized + 0.055) / 1.055, 2.4);
        }
        return channel(parts[0]) * 0.2126 + channel(parts[1]) * 0.7152 + channel(parts[2]) * 0.0722;
    }
    function contrastRatio(first, second) {
        var firstLuminance = relativeLuminance(first);
        var secondLuminance = relativeLuminance(second);
        if (firstLuminance === null || secondLuminance === null) {
            return 0;
        }
        var lighter = Math.max(firstLuminance, secondLuminance);
        var darker = Math.min(firstLuminance, secondLuminance);
        return (lighter + 0.05) / (darker + 0.05);
    }
    function readableColor(background, preferred, alternate) {
        return contrastRatio(background, preferred) >= contrastRatio(background, alternate) ? preferred : alternate;
    }
    function isDarkColor(color) {
        var parts = colorParts(color);
        if (!parts) {
            return false;
        }
        return ((parts[0] * 299 + parts[1] * 587 + parts[2] * 114) / 1000) < 128;
    }
    function diagramContainer(className) {
        var div = document.createElement('div');
        div.className = 'chat4j-diagram ' + className;
        div.setAttribute('data-chat4j-diagram-rendered', 'true');
        return div;
    }
    function computedStyleValue(element, property, fallback) {
        try {
            var style = element ? window.getComputedStyle(element) : null;
            var value = style ? style.getPropertyValue(property) : '';
            return value && value.trim() ? value.trim() : fallback;
        } catch (error) {
            return fallback;
        }
    }
    function mermaidPayload(container) {
        var svg = container ? container.querySelector('svg') : null;
        if (!svg || typeof XMLSerializer === 'undefined') {
            return null;
        }
        return JSON.stringify({
            type: 'mermaid',
            title: container.getAttribute('data-chat4j-diagram-title') || 'Mermaid Diagram',
            source: container.getAttribute('data-chat4j-diagram-source') || '',
            svg: new XMLSerializer().serializeToString(svg),
            background: computedStyleValue(container, 'background-color', bodyBackground('transparent')),
            color: computedStyleValue(container, 'color', bodyColor('currentColor')),
            borderColor: computedStyleValue(container, 'border-color', computedStyleValue(container, 'color', bodyColor('currentColor')))
        });
    }
    function openMermaidDiagram(container) {
        var payload = mermaidPayload(container);
        if (payload && window.chat4jDispatchTranscriptAction) {
            window.chat4jDispatchTranscriptAction('open-diagram-html', -1, payload);
        }
    }
    window.chat4jOpenMermaidDiagram = openMermaidDiagram;
    function installMermaidOpenButton(container, source) {
        container.setAttribute('data-chat4j-diagram-type', 'mermaid');
        container.setAttribute('data-chat4j-diagram-title', 'Mermaid Diagram');
        container.setAttribute('data-chat4j-diagram-source', source || '');
        var button = document.createElement('button');
        button.className = 'diagram-open-button';
        button.type = 'button';
        button.title = 'Open diagram';
        button.textContent = '↗';
        button.onclick = function(event) {
            event.preventDefault();
            event.stopPropagation();
            openMermaidDiagram(container);
        };
        container.appendChild(button);
    }
    function mermaidColors() {
        var text = cssColor('--chat4j-mermaid-text', bodyColor('currentColor'));
        var background = bodyBackground('transparent');
        var diagramBackground = cssColor('--chat4j-mermaid-canvas-bg', cssColor('--chat4j-code-bg', background));
        var primarySurface = cssColor('--chat4j-mermaid-primary-bg', cssColor('--chat4j-menu-bg', diagramBackground));
        var secondarySurface = cssColor('--chat4j-mermaid-secondary-bg', cssColor('--chat4j-inline-code-bg', primarySurface));
        var tertiarySurface = cssColor('--chat4j-mermaid-tertiary-bg', cssColor('--chat4j-code-header-bg', secondarySurface));
        var border = cssColor('--chat4j-mermaid-border', cssColor('--chat4j-scrollbar-thumb', cssColor('--chat4j-border', text)));
        var line = cssColor('--chat4j-mermaid-line', cssColor('--chat4j-muted-text', text));
        var edgeLabelBackground = cssColor('--chat4j-mermaid-edge-label-bg', diagramBackground);
        var sequenceLine = readableColor(diagramBackground, line, text);
        var sequenceSurface = readableColor(diagramBackground, secondarySurface, tertiarySurface);
        var sequenceLabelSurface = readableColor(diagramBackground, primarySurface, secondarySurface);
        var branchLabelText = readableColor(line, text, diagramBackground);
        return {
            text: text,
            background: background,
            diagramBackground: diagramBackground,
            primarySurface: primarySurface,
            secondarySurface: secondarySurface,
            tertiarySurface: tertiarySurface,
            border: border,
            line: line,
            edgeLabelBackground: edgeLabelBackground,
            sequenceLine: sequenceLine,
            sequenceSurface: sequenceSurface,
            sequenceLabelSurface: sequenceLabelSurface,
            branchLabelText: branchLabelText
        };
    }
    function mermaidCategoricalThemeVariables(colors) {
        var surfaces = [
            colors.primarySurface,
            colors.secondarySurface,
            colors.tertiarySurface
        ];
        var variables = {};
        for (var index = 0; index < MERMAID_CATEGORY_COLOR_COUNT; index++) {
            var surface = surfaces[index % surfaces.length];
            variables['cScale' + index] = surface;
            variables['cScalePeer' + index] = colors.border;
            variables['cScaleInv' + index] = colors.line;
            variables['cScaleLabel' + index] = colors.text;
            variables['lineColor' + index] = colors.line;
            if (index < 8) {
                variables['git' + index] = colors.line;
                variables['gitInv' + index] = colors.diagramBackground;
                variables['gitBranchLabel' + index] = colors.branchLabelText;
            }
        }
        return variables;
    }
    function mermaidQuadrantThemeVariables(colors) {
        return {
            quadrant1Fill: colors.secondarySurface,
            quadrant2Fill: colors.tertiarySurface,
            quadrant3Fill: colors.diagramBackground,
            quadrant4Fill: colors.primarySurface,
            quadrant1TextFill: colors.text,
            quadrant2TextFill: colors.text,
            quadrant3TextFill: colors.text,
            quadrant4TextFill: colors.text,
            quadrantPointFill: colors.line,
            quadrantPointTextFill: colors.text,
            quadrantXAxisTextFill: colors.text,
            quadrantYAxisTextFill: colors.text,
            quadrantInternalBorderStrokeFill: colors.border,
            quadrantExternalBorderStrokeFill: colors.border,
            quadrantTitleFill: colors.text
        };
    }
    function mermaidTheme() {
        var colors = mermaidColors();
        var text = colors.text;
        var background = colors.background;
        var diagramBackground = colors.diagramBackground;
        var primarySurface = colors.primarySurface;
        var secondarySurface = colors.secondarySurface;
        var tertiarySurface = colors.tertiarySurface;
        var border = colors.border;
        var line = colors.line;
        var sequenceLine = colors.sequenceLine;
        var sequenceSurface = colors.sequenceSurface;
        var sequenceLabelSurface = colors.sequenceLabelSurface;
        var edgeLabelBackground = colors.edgeLabelBackground;
        var branchLabelText = colors.branchLabelText;
        var themeVariables = Object.assign({
                background: background,
                mainBkg: primarySurface,
                primaryColor: primarySurface,
                primaryTextColor: text,
                primaryBorderColor: border,
                secondaryColor: secondarySurface,
                secondaryTextColor: text,
                secondaryBorderColor: border,
                tertiaryColor: tertiarySurface,
                tertiaryTextColor: text,
                tertiaryBorderColor: border,
                noteBkgColor: secondarySurface,
                noteTextColor: text,
                noteBorderColor: border,
                lineColor: line,
                textColor: text,
                nodeTextColor: text,
                actorBkg: sequenceSurface,
                actorTextColor: text,
                actorBorder: sequenceLine,
                actorLineColor: sequenceLine,
                actor0: sequenceLine,
                actor1: sequenceLine,
                actor2: sequenceLine,
                actor3: sequenceLine,
                actor4: sequenceLine,
                actor5: sequenceLine,
                signalColor: sequenceLine,
                signalTextColor: text,
                labelBoxBkgColor: sequenceLabelSurface,
                labelBoxBorderColor: sequenceLine,
                labelTextColor: text,
                loopTextColor: text,
                activationBkgColor: tertiarySurface,
                activationBorderColor: sequenceLine,
                clusterBkg: diagramBackground,
                clusterBorder: border,
                edgeLabelBackground: edgeLabelBackground,
                nodeBkg: primarySurface,
                nodeBorder: border,
                defaultLinkColor: line,
                titleColor: text,
                darkMode: isDarkColor(diagramBackground),
                stateBkg: primarySurface,
                stateLabelColor: text,
                transitionColor: line,
                transitionLabelColor: text,
                labelBackgroundColor: edgeLabelBackground,
                compositeBackground: diagramBackground,
                altBackground: tertiarySurface,
                compositeTitleBackground: secondarySurface,
                compositeBorder: border,
                innerEndBackground: line,
                specialStateColor: line,
                classText: text,
                fillType0: primarySurface,
                fillType1: secondarySurface,
                fillType2: tertiarySurface,
                fillType3: primarySurface,
                fillType4: secondarySurface,
                fillType5: tertiarySurface,
                fillType6: primarySurface,
                fillType7: secondarySurface,
                attributeBackgroundColorOdd: secondarySurface,
                attributeBackgroundColorEven: tertiarySurface,
                rowOdd: secondarySurface,
                rowEven: tertiarySurface,
                sectionBkgColor: secondarySurface,
                altSectionBkgColor: tertiarySurface,
                sectionBkgColor2: secondarySurface,
                excludeBkgColor: tertiarySurface,
                gridColor: border,
                taskBorderColor: border,
                taskBkgColor: primarySurface,
                activeTaskBorderColor: line,
                activeTaskBkgColor: secondarySurface,
                doneTaskBorderColor: border,
                doneTaskBkgColor: tertiarySurface,
                critBorderColor: line,
                critBkgColor: primarySurface,
                todayLineColor: line,
                vertLineColor: border,
                taskTextColor: text,
                taskTextOutsideColor: text,
                taskTextLightColor: text,
                taskTextDarkColor: text,
                taskTextClickableColor: text,
                personBorder: border,
                personBkg: secondarySurface,
                branchLabelColor: branchLabelText,
                commitLabelColor: text,
                commitLabelBackground: edgeLabelBackground,
                tagLabelColor: text,
                tagLabelBackground: secondarySurface,
                tagLabelBorder: border
            }, mermaidCategoricalThemeVariables(colors), mermaidQuadrantThemeVariables(colors));
        return {
            theme: 'base',
            securityLevel: 'strict',
            startOnLoad: false,
            themeVariables: themeVariables
        };
    }
    function appendMermaidThemeStyle(svg) {
        if (!svg || !svg.appendChild) {
            return;
        }
        var colors = mermaidColors();
        var text = "var(--chat4j-mermaid-text, " + colors.text + ")";
        var canvas = "var(--chat4j-mermaid-canvas-bg, " + colors.diagramBackground + ")";
        var edgeLabel = "var(--chat4j-mermaid-edge-label-bg, " + colors.edgeLabelBackground + ")";
        var line = "var(--chat4j-mermaid-line, " + colors.line + ")";
        var sequenceLine = colors.sequenceLine;
        var sequenceSurface = colors.sequenceSurface;
        var sequenceLabelSurface = colors.sequenceLabelSurface;
        var rootFill = "var(--chat4j-mermaid-primary-bg, " + colors.primarySurface + ")";
        var branchLabelText = colors.branchLabelText;
        var style = document.createElementNS('http://www.w3.org/2000/svg', 'style');
        style.setAttribute('data-chat4j-mermaid-theme', 'true');
        style.textContent = "" +
            "svg { background: transparent !important; color: " + text + " !important; }" +
            "svg .edgeLabel, svg .edgeLabel p { color: " + text + " !important; background: " + edgeLabel + " !important; }" +
            "svg .edgeLabel text, svg .edgeLabel tspan { fill: " + text + " !important; color: " + text + " !important; }" +
            "svg .mindmap-node-label, svg .mindmap-node-label div, svg .mindmap-node-label span, svg .mindmap-node .nodeLabel, svg .mindmap-node .nodeLabel span, svg .mindmap-node .text-inner-tspan, svg .mindmap-node .text-outer-tspan { color: " + text + " !important; fill: " + text + " !important; opacity: 1 !important; fill-opacity: 1 !important; }" +
            "svg .mindmap-node.section-root rect, svg .mindmap-node.section-root path, svg .mindmap-node.section-root circle, svg .mindmap-node.section-root polygon { fill: " + rootFill + " !important; }" +
            "svg .branchLabel text, svg .branchLabel tspan { fill: " + branchLabelText + " !important; color: " + branchLabelText + " !important; }" +
            "svg .commit-merge, svg .commit-reverse, svg .commit-highlight-inner { stroke: " + line + " !important; fill: " + line + " !important; }" +
            "svg path.flowchart-link, svg .edgePath path, svg .edge-pattern-solid, svg .edge-pattern-dashed, svg .edge-pattern-dotted, svg .transition, svg .relationshipLine, svg .mindmap-edge, svg [class*='section-edge-'], svg marker path { stroke: " + line + " !important; }" +
            "svg marker path, svg .arrowheadPath, svg .marker { fill: " + line + " !important; stroke: " + line + " !important; }" +
            "svg rect.actor, svg .actor-box { fill: " + sequenceSurface + " !important; stroke: " + sequenceLine + " !important; }" +
            "svg text.actor, svg text.actor tspan, svg .messageText, svg .messageText tspan, svg .labelText, svg .labelText tspan, svg .loopText, svg .loopText tspan, svg .noteText, svg .noteText tspan { fill: " + text + " !important; color: " + text + " !important; stroke: none !important; }" +
            "svg .actor-line, svg .messageLine0, svg .messageLine1, svg .loopLine, svg .actor-man line, svg .actor-man circle, svg #arrowhead path, svg #crosshead path { stroke: " + sequenceLine + " !important; }" +
            "svg #arrowhead path, svg #crosshead path { fill: " + sequenceLine + " !important; }" +
            "svg .labelBox, svg .note { fill: " + sequenceLabelSurface + " !important; stroke: " + sequenceLine + " !important; }" +
            "svg .activation0, svg .activation1, svg .activation2 { fill: " + sequenceLabelSurface + " !important; stroke: " + sequenceLine + " !important; }";
        svg.appendChild(style);
    }
    function setImportantColor(element, color) {
        if (!element || !element.style) {
            return;
        }
        element.style.setProperty('color', color, 'important');
        element.style.setProperty('fill', color, 'important');
        element.style.setProperty('fill-opacity', '1', 'important');
        element.style.setProperty('opacity', '1', 'important');
    }
    function svgPaint(element, property) {
        var value = element ? element.getAttribute(property) : '';
        if (!value || !value.trim()) {
            value = computedStyleValue(element, property, '');
        }
        value = String(value || '').trim();
        return value && value !== 'none' && value !== 'transparent' && value.indexOf('url(') !== 0 && colorParts(value) ? value : '';
    }
    function mermaidNodeFillColor(node) {
        if (!node || !node.querySelectorAll) {
            return '';
        }
        var shapes = node.querySelectorAll('rect, polygon, path, circle, ellipse');
        for (var index = 0; index < shapes.length; index++) {
            var fill = svgPaint(shapes[index], 'fill');
            if (fill) {
                return fill;
            }
        }
        return svgPaint(node, 'fill');
    }
    function applyReadableNodeLabelTheme(svg) {
        if (!svg || !svg.querySelectorAll) {
            return;
        }
        var colors = mermaidColors();
        Array.prototype.forEach.call(svg.querySelectorAll('g.node'), function(node) {
            if (node.closest && node.closest('.edgeLabel')) {
                return;
            }
            var fill = mermaidNodeFillColor(node);
            if (!fill) {
                return;
            }
            var labelColor = readableColor(fill, colors.text, colors.diagramBackground);
            Array.prototype.forEach.call(node.querySelectorAll('text, tspan, .nodeLabel, .nodeLabel *, foreignObject, foreignObject *'), function(label) {
                setImportantColor(label, labelColor);
            });
        });
    }
    function applyMindmapLabelTheme(svg) {
        if (!svg || !svg.querySelectorAll) {
            return;
        }
        var colors = mermaidColors();
        Array.prototype.forEach.call(svg.querySelectorAll('.mindmap-node-label, .mindmap-node-label *, .mindmap-node .nodeLabel, .mindmap-node .nodeLabel *, .mindmap-node .text-inner-tspan, .mindmap-node .text-outer-tspan'), function(label) {
            setImportantColor(label, colors.text);
        });
    }
    function ensureMermaid() {
        if (typeof window.mermaid === 'undefined') {
            return false;
        }
        if (!mermaidInitialized) {
            window.mermaid.initialize(mermaidTheme());
            mermaidInitialized = true;
        }
        return true;
    }
    function mermaidErrorSvg(svg) {
        var text = String(svg || '');
        return text.indexOf('Syntax error in text') >= 0
            || text.indexOf('mermaid version') >= 0
            || text.indexOf('Syntax error') >= 0;
    }
    function sanitizeMermaidLabel(label) {
        return String(label || '')
            .replace(/\$\\text\{([^}]+)}\s*_\{?([^}\s$]+)}?\$/g, '$1$2')
            .replace(/\$\\text\{([^}]+)}\s*_([^\s$]+)\$/g, '$1$2')
            .replace(/\$\\text\{([^}]+)}\$/g, '$1')
            .replace(/\$([^$]+)\$/g, '$1')
            .replace(/\\text\{([^}]+)}/g, '$1')
            .replace(/\\rightarrow/g, '→')
            .replace(/\|/g, '/')
            .replace(/&/g, 'and')
            .replace(/"/g, '&quot;')
            .trim();
    }
    function normalizeMermaidLabelLineBreaks(label) {
        return String(label || '').replace(/\\r\\n|\\n|\\r/g, '<br/>');
    }
    function replaceMermaidDelimitedLabelLineBreaks(source, pattern) {
        return source.replace(pattern, function() {
            return arguments[1] + normalizeMermaidLabelLineBreaks(arguments[2]) + arguments[3];
        });
    }
    function normalizeMermaidEscapedLineBreaks(source) {
        var normalized = String(source || '');
        normalized = replaceMermaidDelimitedLabelLineBreaks(normalized, /(\b[A-Za-z][A-Za-z0-9_]*\[)([^\]\r\n]*)(\])/g);
        normalized = replaceMermaidDelimitedLabelLineBreaks(normalized, /(\b[A-Za-z][A-Za-z0-9_]*\{)([^}\r\n]*)(\})/g);
        normalized = replaceMermaidDelimitedLabelLineBreaks(normalized, /(\b[A-Za-z][A-Za-z0-9_]*\()([^)\r\n]*)(\))/g);
        normalized = replaceMermaidDelimitedLabelLineBreaks(normalized, /(--\s+)((?:(?!-->).)*)(\s+-->)/g);
        normalized = replaceMermaidDelimitedLabelLineBreaks(normalized, /(\|)([^|\r\n]*)(\|)/g);
        return normalized.replace(/\\r\\n|\\n|\\r/g, '\n');
    }
    function repairMermaidSource(source) {
        var lines = String(source || '').split(/\r?\n/);
        return lines.map(function(line) {
            var repaired = line.replace(/;\s*$/, '');
            repaired = repaired.replace(/^(\s*subgraph\s+)([^"\[].*[\s()&].*)$/i, function(match, prefix, title) {
                return prefix + '"' + sanitizeMermaidLabel(title) + '"';
            });
            repaired = repaired.replace(/--\s+(.+?)\s+-->/g, function(match, label) {
                return '-->|' + sanitizeMermaidLabel(label) + '|';
            });
            repaired = repaired.replace(/(\b[A-Za-z][A-Za-z0-9_]*)(\[)([^\]]+)(\])/g, function(match, id, open, label, close) {
                return id + '["' + sanitizeMermaidLabel(label) + '"]';
            });
            repaired = repaired.replace(/(\b[A-Za-z][A-Za-z0-9_]*)(\{)([^}]+)(\})/g, function(match, id, open, label, close) {
                return id + '{"' + sanitizeMermaidLabel(label) + '"}';
            });
            repaired = repaired.replace(/(\b[A-Za-z][A-Za-z0-9_]*)(\()([^)]*)(\))/g, function(match, id, open, label, close) {
                return id + '("' + sanitizeMermaidLabel(label) + '")';
            });
            return repaired;
        }).join('\n');
    }
    function renderMermaidBlock(table, index) {
        if (!ensureMermaid()) {
            markError(table, 'Mermaid renderer unavailable');
            return;
        }
        if (table.getAttribute('data-chat4j-diagram-rendered')) {
            return;
        }
        var source = sourceFromBlock(table);
        if (!source) {
            return;
        }
        if (source.length > MERMAID_MAX_CHARS) {
            markError(table, 'Mermaid diagram is too large');
            return;
        }
        var renderableSource = normalizeMermaidEscapedLineBreaks(source);
        if (renderableSource.length > MERMAID_MAX_CHARS) {
            markError(table, 'Mermaid diagram is too large');
            return;
        }
        table.setAttribute('data-chat4j-diagram-rendered', 'pending');
        var id = 'chat4j-mermaid-' + Date.now() + '-' + index + '-' + (++renderCounter);
        var completed = false;
        var timeoutId = window.setTimeout(function() {
            if (!completed && table.parentNode) {
                completed = true;
                markError(table, 'Mermaid render timed out');
            }
        }, 3000);
        function fail(message) {
            if (completed) {
                return;
            }
            completed = true;
            window.clearTimeout(timeoutId);
            markError(table, message || 'Mermaid render failed');
        }
        function renderSource(candidate, suffix) {
            return Promise.resolve(window.mermaid.parse(candidate)).then(function() {
                return Promise.resolve(window.mermaid.render(id + suffix, candidate));
            });
        }
        try {
            renderSource(renderableSource, '-original').catch(function(originalError) {
                var repaired = repairMermaidSource(renderableSource);
                if (repaired === renderableSource) {
                    throw originalError;
                }
                return renderSource(repaired, '-repaired');
            }).then(function(result) {
                if (completed) {
                    return;
                }
                completed = true;
                window.clearTimeout(timeoutId);
                var svg = result && result.svg ? result.svg : '';
                if (!svg || mermaidErrorSvg(svg)) {
                    markError(table, 'Mermaid render failed');
                    return;
                }
                var target = diagramContainer('chat4j-mermaid-display');
                target.innerHTML = svg;
                var renderedSvg = target.querySelector('svg');
                if (!renderedSvg) {
                    markError(table, 'Mermaid render failed');
                    return;
                }
                appendMermaidThemeStyle(renderedSvg);
                installMermaidOpenButton(target, renderableSource);
                replaceSource(table, target);
                applyReadableNodeLabelTheme(renderedSvg);
                applyMindmapLabelTheme(renderedSvg);
            }).catch(function(error) {
                fail(compactErrorMessage(error, 'Mermaid render failed'));
            });
        } catch (error) {
            fail(compactErrorMessage(error, 'Mermaid render failed'));
        }
    }
    function chemistryTheme() {
        var text = bodyColor('#222222');
        var background = bodyBackground('#ffffff');
        var dark = isDarkColor(background);
        return {
            C: text,
            O: dark ? '#ff6b6b' : '#e53935',
            N: dark ? '#60a5fa' : '#2563eb',
            F: dark ? '#34d399' : '#059669',
            CL: dark ? '#34d399' : '#059669',
            BR: dark ? '#fb923c' : '#c2410c',
            I: dark ? '#c084fc' : '#7e22ce',
            P: dark ? '#fb923c' : '#c2410c',
            S: dark ? '#facc15' : '#ca8a04',
            B: dark ? '#fb923c' : '#c2410c',
            SI: dark ? '#fb923c' : '#c2410c',
            H: dark ? '#a3a3a3' : '#737373',
            BACKGROUND: background
        };
    }
    function svgElement(name) {
        return document.createElementNS('http://www.w3.org/2000/svg', name);
    }
    function parseMolV2000(source) {
        var lines = String(source || '').replace(/\r\n?/g, '\n').split('\n');
        var countsIndex = -1;
        for (var index = 0; index < Math.min(lines.length, 12); index++) {
            if (/^\s*\d+\s+\d+\b.*(?:V2000)?\s*$/.test(lines[index])) {
                countsIndex = index;
                break;
            }
        }
        if (countsIndex < 0) {
            throw new Error('MOL counts line not found');
        }
        var countParts = lines[countsIndex].trim().split(/\s+/);
        var atomCount = Number(countParts[0]);
        var bondCount = Number(countParts[1]);
        if (!Number.isFinite(atomCount) || !Number.isFinite(bondCount) || atomCount <= 0) {
            throw new Error('MOL counts are invalid');
        }
        var atoms = [];
        var bonds = [];
        for (var atomIndex = 0; atomIndex < atomCount; atomIndex++) {
            var atomLine = lines[countsIndex + 1 + atomIndex] || '';
            var atomParts = atomLine.trim().split(/\s+/);
            if (atomParts.length < 4) {
                throw new Error('MOL atom line is invalid');
            }
            atoms.push({
                x: Number(atomParts[0]),
                y: Number(atomParts[1]),
                symbol: atomParts[3]
            });
        }
        for (var bondIndex = 0; bondIndex < bondCount; bondIndex++) {
            var bondLine = lines[countsIndex + 1 + atomCount + bondIndex] || '';
            var bondParts = bondLine.trim().split(/\s+/);
            if (bondParts.length < 3) {
                throw new Error('MOL bond line is invalid');
            }
            var from = Number(bondParts[0]) - 1;
            var to = Number(bondParts[1]) - 1;
            var order = Number(bondParts[2]);
            if (from < 0 || to < 0 || from >= atoms.length || to >= atoms.length) {
                throw new Error('MOL bond references an unknown atom');
            }
            bonds.push({ from: from, to: to, order: order });
        }
        return { title: String(lines[0] || 'Molecule').trim(), atoms: atoms, bonds: bonds };
    }
    function sdfRecords(source) {
        return String(source || '').replace(/\r\n?/g, '\n').split(/^\$\$\$\$/m)
            .map(function(record) { return record.trim(); })
            .filter(function(record) { return record.length > 0; });
    }
    function atomColor(symbol) {
        var theme = chemistryTheme();
        return theme[String(symbol || '').toUpperCase()] || theme.C;
    }
    function line(svg, x1, y1, x2, y2, color, width, dash) {
        var element = svgElement('line');
        element.setAttribute('x1', x1.toFixed(2));
        element.setAttribute('y1', y1.toFixed(2));
        element.setAttribute('x2', x2.toFixed(2));
        element.setAttribute('y2', y2.toFixed(2));
        element.setAttribute('stroke', color);
        element.setAttribute('stroke-width', width || '2');
        element.setAttribute('stroke-linecap', 'round');
        if (dash) {
            element.setAttribute('stroke-dasharray', dash);
        }
        svg.appendChild(element);
    }
    function renderBond(svg, a, b, order, color) {
        var dx = b.px - a.px;
        var dy = b.py - a.py;
        var length = Math.max(1, Math.sqrt(dx * dx + dy * dy));
        var nx = -dy / length;
        var ny = dx / length;
        if (order === 2) {
            line(svg, a.px + nx * 4, a.py + ny * 4, b.px + nx * 4, b.py + ny * 4, color, 2);
            line(svg, a.px - nx * 4, a.py - ny * 4, b.px - nx * 4, b.py - ny * 4, color, 2);
            return;
        }
        if (order === 3) {
            line(svg, a.px, a.py, b.px, b.py, color, 2);
            line(svg, a.px + nx * 6, a.py + ny * 6, b.px + nx * 6, b.py + ny * 6, color, 2);
            line(svg, a.px - nx * 6, a.py - ny * 6, b.px - nx * 6, b.py - ny * 6, color, 2);
            return;
        }
        line(svg, a.px, a.py, b.px, b.py, color, 2, order === 4 ? '4 4' : null);
    }
    function molSvg(molecule) {
        var atoms = molecule.atoms;
        if (!atoms || atoms.length === 0) {
            throw new Error('MOL has no atoms');
        }
        var minX = Math.min.apply(null, atoms.map(function(atom) { return atom.x; }));
        var maxX = Math.max.apply(null, atoms.map(function(atom) { return atom.x; }));
        var minY = Math.min.apply(null, atoms.map(function(atom) { return atom.y; }));
        var maxY = Math.max.apply(null, atoms.map(function(atom) { return atom.y; }));
        var padding = 26;
        var rawWidth = Math.max(1, maxX - minX);
        var rawHeight = Math.max(1, maxY - minY);
        var width = Math.max(260, Math.min(760, Math.round(rawWidth * 90 + padding * 2)));
        var height = Math.max(180, Math.min(520, Math.round(rawHeight * 90 + padding * 2)));
        var scale = Math.min((width - padding * 2) / rawWidth, (height - padding * 2) / rawHeight);
        atoms.forEach(function(atom) {
            atom.px = padding + (atom.x - minX) * scale;
            atom.py = padding + (maxY - atom.y) * scale;
        });
        var svg = svgElement('svg');
        svg.setAttribute('viewBox', '0 0 ' + width + ' ' + height);
        svg.setAttribute('width', String(width));
        svg.setAttribute('height', String(height));
        svg.setAttribute('role', 'img');
        svg.setAttribute('aria-label', molecule.title || 'Molecule');
        var textColor = bodyColor('#222222');
        var bg = bodyBackground('#ffffff');
        molecule.bonds.forEach(function(bond) {
            renderBond(svg, atoms[bond.from], atoms[bond.to], bond.order, textColor);
        });
        atoms.forEach(function(atom) {
            var group = svgElement('g');
            var labelBg = svgElement('rect');
            labelBg.setAttribute('x', (atom.px - 10).toFixed(2));
            labelBg.setAttribute('y', (atom.py - 10).toFixed(2));
            labelBg.setAttribute('width', '20');
            labelBg.setAttribute('height', '20');
            labelBg.setAttribute('rx', '5');
            labelBg.setAttribute('fill', bg);
            labelBg.setAttribute('opacity', '0.88');
            var label = svgElement('text');
            label.setAttribute('x', atom.px.toFixed(2));
            label.setAttribute('y', (atom.py + 5).toFixed(2));
            label.setAttribute('text-anchor', 'middle');
            label.setAttribute('font-family', 'Inter, -apple-system, BlinkMacSystemFont, sans-serif');
            label.setAttribute('font-size', '15');
            label.setAttribute('font-weight', '700');
            label.setAttribute('fill', atomColor(atom.symbol));
            label.textContent = atom.symbol || 'C';
            group.appendChild(labelBg);
            group.appendChild(label);
            svg.appendChild(group);
        });
        return svg;
    }
    function renderMolLikeBlock(table, index, kind) {
        if (table.getAttribute('data-chat4j-diagram-rendered')) {
            return;
        }
        var source = sourceFromBlock(table);
        var maxChars = kind === 'sdf' ? SDF_MAX_CHARS : MOL_MAX_CHARS;
        if (!source) {
            return;
        }
        if (source.length > maxChars) {
            markError(table, kind.toUpperCase() + ' source is too large');
            return;
        }
        try {
            table.setAttribute('data-chat4j-diagram-rendered', 'pending');
            var target = diagramContainer('chat4j-chem-display chat4j-' + kind + '-display');
            var records = kind === 'sdf' ? sdfRecords(source) : [source];
            if (records.length === 0) {
                throw new Error(kind.toUpperCase() + ' contains no molecule records');
            }
            var visibleRecords = records.slice(0, SDF_MAX_RECORDS);
            visibleRecords.forEach(function(record) {
                var molecule = parseMolV2000(record);
                target.appendChild(molSvg(molecule));
            });
            if (kind === 'sdf' && records.length > visibleRecords.length) {
                var summary = document.createElement('div');
                summary.className = 'chat4j-chem-record-summary';
                summary.textContent = 'Showing first ' + visibleRecords.length + ' of ' + records.length + ' SDF records.';
                target.appendChild(summary);
            }
            replaceSource(table, target);
        } catch (error) {
            markError(table, kind.toUpperCase() + ' render failed');
        }
    }
    function renderSmilesBlock(table, index) {
        if (typeof window.SmilesDrawer === 'undefined') {
            markError(table, 'SMILES renderer unavailable');
            return;
        }
        if (table.getAttribute('data-chat4j-diagram-rendered')) {
            return;
        }
        var source = sourceFromBlock(table);
        if (!source) {
            return;
        }
        if (source.length > SMILES_MAX_CHARS) {
            markError(table, 'SMILES string is too large');
            return;
        }
        table.setAttribute('data-chat4j-diagram-rendered', 'pending');
        try {
            window.SmilesDrawer.parse(source, function(tree) {
                var target = diagramContainer('chat4j-chem-display');
                var svg = document.createElementNS('http://www.w3.org/2000/svg', 'svg');
                svg.setAttribute('id', 'chat4j-smiles-' + Date.now() + '-' + index + '-' + (++renderCounter));
                target.appendChild(svg);
                var options = {
                    width: 500,
                    height: 360,
                    scale: 1.35,
                    padding: 12,
                    compactDrawing: true,
                    themes: { chat4j: chemistryTheme() }
                };
                var drawer = new window.SmilesDrawer.SvgDrawer(options);
                var originalNode = sourceNode(table);
                replaceSource(table, target);
                try {
                    drawer.draw(tree, svg, 'chat4j', false);
                } catch (error) {
                    if (target.parentNode && originalNode) {
                        target.parentNode.replaceChild(originalNode, target);
                    }
                    markError(table, 'SMILES render failed');
                }
            }, function() {
                markError(table, 'SMILES render failed');
            });
        } catch (error) {
            markError(table, 'SMILES render failed');
        }
    }
    window.chat4jRenderMermaid = function(root) {
        var targetRoot = root || document;
        Array.prototype.forEach.call(targetRoot.querySelectorAll('table.md-mermaid-block:not([data-chat4j-diagram-rendered])'), renderMermaidBlock);
    };
    window.chat4jRenderChemistry = function(root) {
        var targetRoot = root || document;
        Array.prototype.forEach.call(targetRoot.querySelectorAll('table.md-smiles-block:not([data-chat4j-diagram-rendered])'), renderSmilesBlock);
        Array.prototype.forEach.call(targetRoot.querySelectorAll('table.md-mol-block:not([data-chat4j-diagram-rendered])'), function(table, index) {
            renderMolLikeBlock(table, index, 'mol');
        });
        Array.prototype.forEach.call(targetRoot.querySelectorAll('table.md-sdf-block:not([data-chat4j-diagram-rendered])'), function(table, index) {
            renderMolLikeBlock(table, index, 'sdf');
        });
    };
    window.chat4jRenderDiagrams = function(root) {
        window.chat4jRenderMermaid(root || document);
        window.chat4jRenderChemistry(root || document);
    };
    window.chat4jRenderEnhancements = function(root) {
        if (window.chat4jRenderMath) {
            window.chat4jRenderMath(root || document);
        }
        window.chat4jRenderDiagrams(root || document);
    };
})();
