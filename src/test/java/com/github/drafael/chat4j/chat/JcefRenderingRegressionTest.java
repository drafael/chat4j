package com.github.drafael.chat4j.chat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class JcefRenderingRegressionTest {

    @Test
    @DisplayName("Chat page uses markdown-it token parsing to avoid math replacement inside code tokens")
    void chatPageScript_whenRendered_containsTokenBasedMathExtractionFlow() throws Exception {
        var html = readGeneratedChatHtml();

        assertThat(html)
                .contains("var parsedTokens = md.parse(source, {});")
                .contains("if (child.type === 'text' && child.content)")
                .doesNotContain("extractMathTokens(wrapStandaloneLatex(text))");
    }

    @Test
    @DisplayName("JCEF bridge JSON escaping handles Unicode line separators")
    void jsonString_whenInputContainsUnicodeSeparators_escapesUnsafeJavaScriptCharacters() throws Exception {
        Method jsonString = JcefChatView.class.getDeclaredMethod("jsonString", String.class);
        jsonString.setAccessible(true);

        var value = "first\u2028second\u2029third\b\f\n";
        var escaped = (String) jsonString.invoke(null, value);

        assertThat(escaped)
                .startsWith("\"")
                .endsWith("\"")
                .contains("\\u2028")
                .contains("\\u2029")
                .contains("\\b")
                .contains("\\f")
                .contains("\\n");
    }

    private static String readGeneratedChatHtml() throws Exception {
        var url = JcefChatPage.url();
        Path path = Path.of(URI.create(url));
        return Files.readString(path);
    }
}
