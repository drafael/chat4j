(function () {
    function closest(node, selector) {
        if (!node) {
            return null;
        }
        if (node.closest) {
            return node.closest(selector);
        }
        while (node && node.tagName) {
            if (matches(node, selector)) {
                return node;
            }
            node = node.parentNode;
        }
        return null;
    }
    function matches(node, selector) {
        var matcher = node.matches || node.msMatchesSelector || node.webkitMatchesSelector;
        return matcher ? matcher.call(node, selector) : false;
    }
    function selectedText() {
        var selection = window.getSelection ? window.getSelection() : null;
        return selection ? String(selection.toString()) : '';
    }
    function dispatchTranscriptAction(action, messageIndex, text) {
        var payloadText = text || '';
        if (window.chat4jTranscriptAction && (messageIndex >= 0 || payloadText.length > 0)) {
            window.chat4jTranscriptAction(action, String(messageIndex), payloadText);
        }
    }
    window.chat4jDispatchTranscriptAction = dispatchTranscriptAction;
    function animateCopyButton(button) {
        if (!button) {
            return;
        }
        button.classList.remove('copy-flash');
        void button.offsetWidth;
        button.classList.add('copy-flash');
        setTimeout(function() {
            button.classList.remove('copy-flash');
        }, 460);
    }
    function hideTranscriptMenu() {
        var menu = document.getElementById('chat4j-transcript-menu');
        if (menu) {
            menu.style.display = 'none';
        }
    }
    function hideSourcePreview() {
        var preview = document.getElementById('chat4j-source-preview');
        if (!preview) {
            return;
        }
        preview.classList.remove('visible');
        preview.setAttribute('aria-hidden', 'true');
    }
    function sourceInitial(domain) {
        var value = String(domain || '').trim();
        return value.length > 0 ? value.charAt(0).toUpperCase() : '↗';
    }
    function showSourcePreview(anchor, event) {
        var preview = document.getElementById('chat4j-source-preview');
        if (!preview || !anchor) {
            return;
        }
        preview.querySelector('.source-preview-favicon').textContent = sourceInitial(anchor.getAttribute('data-source-domain'));
        preview.querySelector('.source-preview-domain').textContent = anchor.getAttribute('data-source-domain') || '';
        preview.querySelector('.source-preview-title').textContent = anchor.getAttribute('data-source-title') || anchor.textContent || '';
        preview.querySelector('.source-preview-snippet').textContent = anchor.getAttribute('data-source-snippet') || '';
        preview.classList.add('visible');
        preview.setAttribute('aria-hidden', 'false');
        positionSourcePreview(event || { clientX: anchor.getBoundingClientRect().left, clientY: anchor.getBoundingClientRect().bottom });
    }
    function positionSourcePreview(event) {
        var preview = document.getElementById('chat4j-source-preview');
        if (!preview || !preview.classList.contains('visible')) {
            return;
        }
        var margin = 12;
        var rect = preview.getBoundingClientRect();
        var x = Math.min(Math.max(margin, event.clientX + 14), Math.max(margin, window.innerWidth - rect.width - margin));
        var y = event.clientY + 16;
        if (y + rect.height + margin > window.innerHeight) {
            y = Math.max(margin, event.clientY - rect.height - 14);
        }
        preview.style.left = x + 'px';
        preview.style.top = y + 'px';
    }
    function rowText(row) {
        if (!row) {
            return '';
        }
        var message = row.querySelector('.message');
        return message ? String(message.textContent || '') : '';
    }
    function messageActionText(button) {
        if (!button || button.getAttribute('data-action') !== 'read-aloud') {
            return '';
        }
        return rowText(closest(button, '.row[data-message-index]'));
    }
    function dispatchMessageActionButton(button, event) {
        if (!button) {
            return;
        }
        if (event) {
            event.preventDefault();
            event.stopPropagation();
        }
        var action = button.getAttribute('data-action');
        dispatchTranscriptAction(
                action,
                Number(button.getAttribute('data-message-index')),
                messageActionText(button)
        );
        if (action === 'copy') {
            animateCopyButton(button);
        }
    }
    function showTranscriptMenu(event, row) {
        var menu = document.getElementById('chat4j-transcript-menu');
        if (!menu || !row) {
            return;
        }
        var messageIndex = Number(row.getAttribute('data-message-index'));
        if (messageIndex < 0) {
            return;
        }
        var isAssistant = row.classList.contains('assistant');
        var readAloudButton = row.querySelector('button[data-action="read-aloud"]');
        var hasReadAloud = isAssistant && !!readAloudButton;
        var readAloudActive = hasReadAloud && readAloudButton.getAttribute('data-read-aloud-active') === 'true';
        var regenerateLabel = row.classList.contains('user') ? 'Regenerate Response' : 'Regenerate This Response';
        var selection = selectedText().trim();
        var diagram = closest(event.target, '.chat4j-mermaid-display');
        var selectedCopy = selection.length > 0
                ? '<button data-action="copy-selected"><span class="icon copy" aria-hidden="true"></span><span class="label">Copy Selected Text</span><span class="shortcut">⌘C</span></button><div class="transcript-menu-separator"></div>'
                : '';
        var diagramOpen = diagram
                ? '<button data-action="open-diagram"><span class="icon open-diagram" aria-hidden="true"></span><span class="label">Open Diagram</span><span class="shortcut"></span></button><div class="transcript-menu-separator"></div>'
                : '';
        var readAloud = hasReadAloud
                ? '<div class="transcript-menu-separator"></div><button data-action="read-aloud"><span class="icon ' + (readAloudActive ? 'player-stop' : 'read-aloud') + '" aria-hidden="true"></span><span class="label">' + (readAloudActive ? 'Stop' : 'Read aloud') + '</span><span class="shortcut"></span></button>'
                : '';
        menu.setAttribute('data-selected-text', selection);
        menu.innerHTML = selectedCopy
                + diagramOpen
                + '<button data-action="copy"><span class="icon copy" aria-hidden="true"></span><span class="label">Copy Message</span><span class="shortcut"></span></button>'
                + readAloud
                + '<div class="transcript-menu-separator"></div>'
                + '<button data-action="regenerate"><span class="icon regenerate" aria-hidden="true"></span><span class="label">' + regenerateLabel + '</span><span class="shortcut"></span></button>';
        menu._chat4jDiagram = diagram || null;
        Array.prototype.forEach.call(menu.querySelectorAll('button[data-action]'), function(button) {
            button.onclick = function(clickEvent) {
                clickEvent.preventDefault();
                clickEvent.stopPropagation();
                var action = button.getAttribute('data-action');
                if (action === 'open-diagram') {
                    if (window.chat4jOpenMermaidDiagram) {
                        window.chat4jOpenMermaidDiagram(menu._chat4jDiagram);
                    }
                    hideTranscriptMenu();
                    return;
                }
                var text = action === 'copy-selected'
                        ? menu.getAttribute('data-selected-text')
                        : (action === 'read-aloud' ? rowText(row) : '');
                dispatchTranscriptAction(action, messageIndex, text);
                hideTranscriptMenu();
            };
        });
        menu.style.left = Math.min(event.clientX, Math.max(0, window.innerWidth - 240)) + 'px';
        menu.style.top = Math.min(event.clientY, Math.max(0, window.innerHeight - 120)) + 'px';
        menu.style.display = 'block';
    }
    function installCodeCopyButtons() {
        Array.prototype.forEach.call(document.querySelectorAll('table.md-code-block'), function(table) {
            if (table.parentNode && table.parentNode.classList && table.parentNode.classList.contains('code-block-shell')) {
                return;
            }
            var shell = document.createElement('div');
            shell.className = 'code-block-shell';
            table.parentNode.insertBefore(shell, table);
            shell.appendChild(table);
            var button = document.createElement('button');
            button.className = 'code-copy-button';
            button.title = 'Copy code';
            button.innerHTML = '<span class="icon copy" aria-hidden="true"></span>';
            button.onclick = function(event) {
                var code = table.querySelector('tr.code-body pre') || table.querySelector('pre') || table;
                dispatchTranscriptAction('copy-text', -1, String(code.textContent || ''));
                animateCopyButton(button);
                event.preventDefault();
                event.stopPropagation();
            };
            shell.appendChild(button);
        });
    }
    function scrollRoot() {
        return document.scrollingElement || document.documentElement || document.body;
    }
    function updateFadeOverlays() {
        var root = scrollRoot();
        var topFade = document.getElementById('chat4j-top-fade');
        var bottomFade = document.getElementById('chat4j-bottom-fade');
        if (!root || !topFade || !bottomFade) {
            return;
        }
        var scrollHeight = Math.max(root.scrollHeight, document.body ? document.body.scrollHeight : 0);
        var clientHeight = Math.max(root.clientHeight || 0, window.innerHeight || 0);
        var scrollTop = root.scrollTop || 0;
        var maxScroll = Math.max(0, scrollHeight - clientHeight);
        topFade.classList.toggle('visible', scrollTop > 3);
        bottomFade.classList.toggle('visible', maxScroll - scrollTop > 3);
    }
    window.chat4jUpdateFadeOverlays = updateFadeOverlays;
    function updateJumpButtonVisibility() {
        var root = scrollRoot();
        var jump = document.getElementById('chat4j-jump-bottom');
        if (!root || !jump) {
            return;
        }
        var scrollHeight = Math.max(root.scrollHeight, document.body ? document.body.scrollHeight : 0);
        var clientHeight = Math.max(root.clientHeight || 0, window.innerHeight || 0);
        var maxScroll = Math.max(0, scrollHeight - clientHeight);
        var scrollTop = root.scrollTop || 0;
        var streaming = jump.getAttribute('data-streaming') === 'true';
        var atBottom = maxScroll - scrollTop <= 3;
        jump.classList.toggle('streaming', streaming && !atBottom);
        jump.style.display = atBottom ? 'none' : 'flex';
    }
    window.chat4jUpdateJumpButton = updateJumpButtonVisibility;
    function updateCustomScrollbar() {
        var root = scrollRoot();
        var track = document.getElementById('chat4j-scrollbar');
        var thumb = document.getElementById('chat4j-scrollbar-thumb');
        updateFadeOverlays();
        updateJumpButtonVisibility();
        if (!root || !track || !thumb) {
            return;
        }
        var scrollHeight = Math.max(root.scrollHeight, document.body ? document.body.scrollHeight : 0);
        var clientHeight = Math.max(root.clientHeight || 0, window.innerHeight || 0);
        if (scrollHeight <= clientHeight + 1) {
            track.classList.add('hidden');
            return;
        }
        track.classList.remove('hidden');
        var trackHeight = track.clientHeight;
        var thumbHeight = Math.max(32, Math.round(trackHeight * clientHeight / scrollHeight));
        var maxTop = Math.max(0, trackHeight - thumbHeight);
        var maxScroll = Math.max(1, scrollHeight - clientHeight);
        var top = Math.round(maxTop * (root.scrollTop || 0) / maxScroll);
        thumb.style.height = thumbHeight + 'px';
        thumb.style.transform = 'translateY(' + top + 'px)';
    }
    function installCustomScrollbar() {
        var track = document.getElementById('chat4j-scrollbar');
        var thumb = document.getElementById('chat4j-scrollbar-thumb');
        if (!track || !thumb || track.getAttribute('data-installed') === 'true') {
            updateCustomScrollbar();
            return;
        }
        track.setAttribute('data-installed', 'true');
        var dragging = false;
        var dragStartY = 0;
        var dragStartScrollTop = 0;
        thumb.addEventListener('mousedown', function(event) {
            dragging = true;
            dragStartY = event.clientY;
            dragStartScrollTop = scrollRoot().scrollTop || 0;
            track.classList.add('dragging');
            event.preventDefault();
            event.stopPropagation();
        }, true);
        track.addEventListener('mousedown', function(event) {
            if (event.target === thumb) {
                return;
            }
            var root = scrollRoot();
            var rect = track.getBoundingClientRect();
            var thumbTop = thumb.getBoundingClientRect().top - rect.top;
            var direction = event.clientY < rect.top + thumbTop ? -1 : 1;
            root.scrollTop += direction * Math.max(80, root.clientHeight * 0.85);
            updateCustomScrollbar();
            event.preventDefault();
            event.stopPropagation();
        }, true);
        document.addEventListener('mousemove', function(event) {
            if (!dragging) {
                return;
            }
            var root = scrollRoot();
            var trackHeight = Math.max(1, track.clientHeight - thumb.clientHeight);
            var scrollRange = Math.max(1, root.scrollHeight - root.clientHeight);
            root.scrollTop = dragStartScrollTop + ((event.clientY - dragStartY) / trackHeight) * scrollRange;
            updateCustomScrollbar();
            event.preventDefault();
        }, true);
        document.addEventListener('mouseup', function() {
            dragging = false;
            track.classList.remove('dragging');
        }, true);
        window.addEventListener('scroll', updateCustomScrollbar, true);
        window.addEventListener('resize', updateCustomScrollbar);
        setTimeout(updateCustomScrollbar, 0);
    }
    window.chat4jInstallTranscriptActions = function() {
        hideTranscriptMenu();
        if (window.chat4jRenderEnhancements) {
            window.chat4jRenderEnhancements(document.querySelector('.transcript') || document.body);
        }
        installCodeCopyButtons();
        installCustomScrollbar();
    };
    window.addEventListener('load', function() {
        if (window.chat4jRenderEnhancements) {
            window.chat4jRenderEnhancements(document.querySelector('.transcript') || document.body);
        }
        installCodeCopyButtons();
        installCustomScrollbar();
        setTimeout(function() {
            installCodeCopyButtons();
            updateCustomScrollbar();
        }, 50);
    });
    document.addEventListener('mouseover', function(event) {
        var sourceLink = closest(event.target, 'a[data-source-url]');
        if (sourceLink) {
            showSourcePreview(sourceLink, event);
        }
    }, true);
    document.addEventListener('mousemove', function(event) {
        positionSourcePreview(event);
    }, true);
    document.addEventListener('mouseout', function(event) {
        var sourceLink = closest(event.target, 'a[data-source-url]');
        if (sourceLink) {
            hideSourcePreview();
        }
    }, true);
    document.addEventListener('focusin', function(event) {
        var sourceLink = closest(event.target, 'a[data-source-url]');
        if (sourceLink) {
            showSourcePreview(sourceLink, event);
        }
    }, true);
    document.addEventListener('focusout', function(event) {
        if (closest(event.target, 'a[data-source-url]')) {
            hideSourcePreview();
        }
    }, true);
    document.addEventListener('click', function (event) {
        hideSourcePreview();
        var attachmentButton = closest(event.target, '[data-action="open-attachment"][data-attachment-path]');
        if (attachmentButton) {
            event.preventDefault();
            event.stopPropagation();
            dispatchTranscriptAction('open-attachment', -1, attachmentButton.getAttribute('data-attachment-path') || '');
            return;
        }
        var activityCopyButton = closest(event.target, 'button[data-action="copy-activity"]');
        if (activityCopyButton) {
            var activityBox = closest(activityCopyButton, '.activity-box');
            var activityContent = activityBox ? activityBox.querySelector('.activity-content') : null;
            var activitySummary = activityBox ? activityBox.querySelector('summary') : null;
            var text = activityContent && String(activityContent.textContent || '').trim().length > 0
                    ? activityContent.textContent
                    : (activitySummary ? activitySummary.textContent : '');
            dispatchTranscriptAction('copy-text', -1, String(text || ''));
            animateCopyButton(activityCopyButton);
            event.preventDefault();
            event.stopPropagation();
            return;
        }
        var actionButton = closest(event.target, 'button[data-action][data-message-index]');
        if (actionButton) {
            dispatchMessageActionButton(actionButton, event);
            return;
        }
        hideTranscriptMenu();
        var anchor = closest(event.target, 'a[href]');
        if (!anchor) {
            return;
        }
        event.preventDefault();
        event.stopPropagation();
        if (window.chat4jOpenExternalLink) {
            window.chat4jOpenExternalLink(anchor.href);
        }
    }, true);
    document.addEventListener('contextmenu', function (event) {
        var row = closest(event.target, '.row[data-message-index]');
        if (!row) {
            hideTranscriptMenu();
            return;
        }
        event.preventDefault();
        event.stopPropagation();
        showTranscriptMenu(event, row);
    }, true);
})();
