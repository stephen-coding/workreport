package com.workreport.agent.tools;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

// Decodes text files without assuming UTF-8: a BOM wins (UTF-8 / UTF-16 LE / BE),
// then strict UTF-8, then GBK (the Windows default for PowerShell 5 redirection and
// Maven output), and finally a lossy UTF-8 pass. Writing reuses the detected
// encoding, so an edit never silently rewrites a GBK or UTF-16 file as UTF-8.
// 文本文件解码：先看 BOM（UTF-8 / UTF-16），再按严格 UTF-8 试，失败退 GBK
//（PowerShell 5 重定向、Maven 输出的默认编码），最后有损 UTF-8。
// 写回沿用探测到的编码，避免编辑把 GBK / UTF-16 文件悄悄改成 UTF-8。
public final class FileText {

    private static final Charset GBK = Charset.isSupported("GBK") ? Charset.forName("GBK") : null;

    private FileText() {
    }

    public static final class Decoded {
        private final String text;
        private final Charset charset;
        private final boolean bom;

        private Decoded(String text, Charset charset, boolean bom) {
            this.text = text;
            this.charset = charset;
            this.bom = bom;
        }

        public String text() {
            return text;
        }

        // Human readable encoding name, e.g. "UTF-16LE" or "UTF-8 (BOM)".
        // 便于在工具结果里告诉模型真实编码。
        public String label() {
            return charset.name() + (bom ? " (BOM)" : "");
        }

        public boolean isPlainUtf8() {
            return StandardCharsets.UTF_8.equals(charset) && !bom;
        }
    }

    public static Decoded read(Path path) throws IOException {
        byte[] bytes = Files.readAllBytes(path);
        int len = bytes.length;
        if (startsWith(bytes, 0xEF, 0xBB, 0xBF)) {
            return new Decoded(new String(bytes, 3, len - 3, StandardCharsets.UTF_8),
                    StandardCharsets.UTF_8, true);
        }
        if (startsWith(bytes, 0xFF, 0xFE)) {
            return new Decoded(new String(bytes, 2, len - 2, StandardCharsets.UTF_16LE),
                    StandardCharsets.UTF_16LE, true);
        }
        if (startsWith(bytes, 0xFE, 0xFF)) {
            return new Decoded(new String(bytes, 2, len - 2, StandardCharsets.UTF_16BE),
                    StandardCharsets.UTF_16BE, true);
        }
        String utf8 = decodeStrict(bytes, StandardCharsets.UTF_8);
        if (utf8 != null) {
            return new Decoded(utf8, StandardCharsets.UTF_8, false);
        }
        String gbk = GBK == null ? null : decodeStrict(bytes, GBK);
        if (gbk != null) {
            return new Decoded(gbk, GBK, false);
        }
        // Neither UTF-8 nor GBK: keep the readable parts instead of failing.
        // 两种编码都不合法时保留可读部分，而不是让整次读取失败。
        return new Decoded(new String(bytes, StandardCharsets.UTF_8), StandardCharsets.UTF_8, false);
    }

    // Writes with the encoding of the file that was read; new files are UTF-8.
    // 按原编码写回；新文件用 UTF-8。
    public static void write(Path path, String text, Decoded original) throws IOException {
        Charset charset = original == null ? StandardCharsets.UTF_8 : original.charset;
        boolean bom = original != null && original.bom;
        byte[] body = text.getBytes(charset);
        if (!bom) {
            Files.write(path, body);
            return;
        }
        byte[] head = StandardCharsets.UTF_16BE.equals(charset)
                ? new byte[]{(byte) 0xFE, (byte) 0xFF}
                : StandardCharsets.UTF_16LE.equals(charset)
                ? new byte[]{(byte) 0xFF, (byte) 0xFE}
                : new byte[]{(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] out = new byte[head.length + body.length];
        System.arraycopy(head, 0, out, 0, head.length);
        System.arraycopy(body, 0, out, head.length, body.length);
        Files.write(path, out);
    }

    // Splits like Files.readAllLines: no terminators, trailing newline adds no line.
    // 与 Files.readAllLines 一致：不含换行符，结尾换行不会多出一行。
    public static List<String> splitLines(String text) {
        List<String> lines = new ArrayList<>();
        int start = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c != '\n' && c != '\r') {
                continue;
            }
            lines.add(text.substring(start, i));
            if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                i++;
            }
            start = i + 1;
        }
        if (start < text.length()) {
            lines.add(text.substring(start));
        }
        return lines;
    }

    private static boolean startsWith(byte[] bytes, int... prefix) {
        if (bytes.length < prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if ((bytes[i] & 0xFF) != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static String decodeStrict(byte[] bytes, Charset charset) {
        CharsetDecoder decoder = charset.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            return decoder.decode(ByteBuffer.wrap(bytes)).toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }
}
