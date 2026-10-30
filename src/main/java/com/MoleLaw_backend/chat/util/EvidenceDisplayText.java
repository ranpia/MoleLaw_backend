package com.MoleLaw_backend.chat.util;

import java.io.IOException;
import java.io.StringReader;
import java.util.Set;
import java.util.regex.Pattern;
import javax.swing.text.MutableAttributeSet;
import javax.swing.text.html.HTML;
import javax.swing.text.html.HTMLEditorKit;
import javax.swing.text.html.parser.ParserDelegator;
import org.springframework.web.util.HtmlUtils;

/** Display-only HTML conversion. Stored evidence and model inputs keep the original source. */
public final class EvidenceDisplayText {
    private static final Pattern TAG = Pattern.compile("(?is)<\\s*/?\\s*[a-z][^>]*>");
    private static final Set<String> BLOCKS = Set.of("p", "div", "li", "tr", "h1", "h2", "h3", "h4", "blockquote", "pre");
    private static final Set<String> HIDDEN = Set.of("script", "style", "iframe", "object");

    private EvidenceDisplayText() { }

    public static String plain(String source) {
        if (source == null || source.isEmpty()) return source;
        String text = source;
        // Providers may return escaped markup as well as ordinary HTML.
        for (int pass = 0; pass < 2; pass++) {
            text = HtmlUtils.htmlUnescape(text);
            if (TAG.matcher(text).find()) text = parse(text);
        }
        return text.replace('\u00a0', ' ').replace("\r\n", "\n").replace('\r', '\n')
                .replaceAll("[ \\t]+\\n", "\n").replaceAll("\\n[ \\t]+", "\n")
                .replaceAll("\\n{3,}", "\n\n").strip();
    }

    private static String parse(String html) {
        StringBuilder text = new StringBuilder();
        try {
            new ParserDelegator().parse(new StringReader(html), new HTMLEditorKit.ParserCallback() {
                private int hiddenDepth;
                private void line() {
                    if (!text.isEmpty() && text.charAt(text.length() - 1) != '\n') text.append('\n');
                }
                @Override public void handleStartTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                    if (HIDDEN.contains(tag.toString())) hiddenDepth++;
                    if (hiddenDepth == 0 && BLOCKS.contains(tag.toString())) line();
                    if (hiddenDepth == 0 && (tag == HTML.Tag.TD || tag == HTML.Tag.TH)
                            && !text.isEmpty() && text.charAt(text.length() - 1) != '\n') text.append(' ');
                }
                @Override public void handleEndTag(HTML.Tag tag, int position) {
                    if (HIDDEN.contains(tag.toString()) && hiddenDepth > 0) hiddenDepth--;
                    if (hiddenDepth == 0 && BLOCKS.contains(tag.toString())) line();
                }
                @Override public void handleSimpleTag(HTML.Tag tag, MutableAttributeSet attributes, int position) {
                    if (hiddenDepth == 0 && (tag == HTML.Tag.BR || tag == HTML.Tag.HR)) line();
                }
                @Override public void handleText(char[] data, int position) {
                    if (hiddenDepth == 0) text.append(data);
                }
            }, true);
        } catch (IOException impossible) {
            throw new IllegalStateException("Unable to read in-memory evidence", impossible);
        }
        return text.toString();
    }
}
