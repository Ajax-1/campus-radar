package io.github.ajax1.campusradar.mail;

import jakarta.mail.BodyPart;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;

import java.io.IOException;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 从 MIME 邮件里取正文：优先 text/plain，没有就把 text/html 去标签
 */
final class MailText {

    private static final Pattern ENTITY = Pattern.compile("&#(\\d+);");

    private MailText() {
    }

    static String of(Part part) throws MessagingException, IOException {
        String plain = find(part, "text/plain");
        if (plain != null && !plain.isBlank()) {
            return plain;
        }
        String html = find(part, "text/html");
        return html == null ? "" : stripHtml(html);
    }

    private static String find(Part part, String mime) throws MessagingException, IOException {
        if (part.isMimeType(mime) && !Part.ATTACHMENT.equalsIgnoreCase(part.getDisposition())) {
            Object content = part.getContent();
            return content instanceof String s ? s : null;
        }
        if (part.isMimeType("multipart/*")) {
            Multipart mp = (Multipart) part.getContent();
            for (int i = 0; i < mp.getCount(); i++) {
                BodyPart child = mp.getBodyPart(i);
                String found = find(child, mime);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    static String stripHtml(String html) {
        String s = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", " ")
                .replaceAll("(?i)<br\\s*/?>", "\n")
                .replaceAll("(?i)</(p|div|tr|li|h\\d)>", "\n")
                .replaceAll("(?s)<[^>]+>", " ")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">")
                .replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        Matcher m = ENTITY.matcher(s);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            m.appendReplacement(sb, Matcher.quoteReplacement(String.valueOf((char) Integer.parseInt(m.group(1)))));
        }
        m.appendTail(sb);
        return sb.toString().replaceAll("[ \\t]+", " ").replaceAll("\\n\\s*\\n+", "\n");
    }
}
