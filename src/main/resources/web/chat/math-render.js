(function() {
    function closestCodeBlockShell(table) {
        var parent = table ? table.parentNode : null;
        return parent && parent.classList && parent.classList.contains('code-block-shell') ? parent : null;
    }
    function parseDelimitedMath(source, fallbackDisplayMode) {
        var text = String(source || '').trim();
        if (!text) {
            return null;
        }
        if (text.length >= 4 && text.slice(0, 2) === '$$' && text.slice(-2) === '$$') {
            return { tex: text.slice(2, -2).trim(), displayMode: true };
        }
        if (text.length >= 4 && text.slice(0, 2) === '\\[' && text.slice(-2) === '\\]') {
            return { tex: text.slice(2, -2).trim(), displayMode: true };
        }
        if (text.length >= 4 && text.slice(0, 2) === '\\(' && text.slice(-2) === '\\)') {
            return { tex: text.slice(2, -2).trim(), displayMode: false };
        }
        if (text.length >= 2 && text.charAt(0) === '$' && text.charAt(text.length - 1) === '$') {
            return { tex: text.slice(1, -1).trim(), displayMode: false };
        }
        return { tex: text, displayMode: fallbackDisplayMode === true };
    }
    function renderOptions(displayMode) {
        return {
            displayMode: displayMode,
            throwOnError: false,
            trust: false,
            strict: false
        };
    }
    function renderIntoReplacement(sourceNode, parsed) {
        if (!sourceNode || !sourceNode.parentNode || !parsed || !parsed.tex || typeof window.katex === 'undefined') {
            return;
        }
        var target = document.createElement(parsed.displayMode ? 'div' : 'span');
        target.className = parsed.displayMode ? 'chat4j-math-display' : 'chat4j-math-inline';
        window.katex.render(parsed.tex, target, renderOptions(parsed.displayMode));
        target.setAttribute('data-chat4j-math-rendered', 'true');
        sourceNode.parentNode.replaceChild(target, sourceNode);
    }
    function renderInlineMath(root) {
        Array.prototype.forEach.call(root.querySelectorAll('code.md-latex-inline:not([data-chat4j-math-rendered])'), function(code) {
            try {
                renderIntoReplacement(code, parseDelimitedMath(code.textContent, false));
            } catch (error) {
                code.setAttribute('data-chat4j-math-rendered', 'error');
            }
        });
    }
    function renderDisplayMath(root) {
        Array.prototype.forEach.call(root.querySelectorAll('table.md-latex-block:not([data-chat4j-math-rendered])'), function(table) {
            var pre = table.querySelector('tr.code-body pre') || table.querySelector('pre');
            if (!pre) {
                return;
            }
            try {
                renderIntoReplacement(closestCodeBlockShell(table) || table, parseDelimitedMath(pre.textContent, true));
            } catch (error) {
                table.setAttribute('data-chat4j-math-rendered', 'error');
            }
        });
    }
    window.chat4jRenderMath = function(root) {
        if (typeof window.katex === 'undefined') {
            return;
        }
        var targetRoot = root || document;
        renderDisplayMath(targetRoot);
        renderInlineMath(targetRoot);
    };
})();
