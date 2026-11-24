package myau.guide;

import myau.Myau;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The guide's articles, read from {@code assets/minecraft/bettermyau/guide/zh_cn.txt} (UTF-8) so
 * the wording can change without touching code. The markup is described at the top of that file:
 * one block per line, a prefix saying what kind of block it is, paragraphs joined across lines.
 */
public final class GuideContent {
    private static final String FILE = "/assets/minecraft/bettermyau/guide/zh_cn.txt";

    public enum Kind { HEADING, PARAGRAPH, BULLET, STEP, TIP, WARN, ROW, COMMAND, BUTTON }

    public static final class Block {
        public final Kind kind;
        /** Main text (inline markup allowed); for COMMAND the command itself, for BUTTON the label. */
        public final String text;
        /** ROW: the right column; COMMAND: the description; BUTTON: the action id. Empty otherwise. */
        public final String extra;
        /** STEP: its number in the list. */
        public final int number;

        Block(Kind kind, String text, String extra, int number) {
            this.kind = kind;
            this.text = text;
            this.extra = extra == null ? "" : extra;
            this.number = number;
        }
    }

    public static final class Topic {
        public final String id, title, group, summary;
        public final List<Block> blocks = new ArrayList<>();
        /** Lower-case plain text of the whole article, for search. */
        String haystack = "";

        Topic(String id, String title, String group, String summary) {
            this.id = id;
            this.title = title;
            this.group = group;
            this.summary = summary;
        }

        /** How many times {@code query} (lower case) appears in the article. */
        public int matches(String query) {
            if (query.isEmpty()) return 0;
            int n = 0, at = 0;
            while ((at = this.haystack.indexOf(query, at)) >= 0) {
                n++;
                at += query.length();
            }
            return n;
        }

        /** Index of the first block containing {@code query} (lower case), or -1. */
        public int firstBlockWith(String query) {
            for (int i = 0; i < this.blocks.size(); i++) {
                Block b = this.blocks.get(i);
                if (plain(b.text + " " + b.extra).toLowerCase(Locale.ROOT).contains(query)) return i;
            }
            return -1;
        }
    }

    private static List<Topic> topics;

    private GuideContent() {
    }

    /** All topics in file order (loaded once; an unreadable file gives one topic saying so). */
    public static synchronized List<Topic> topics() {
        if (topics == null) topics = load();
        return topics;
    }

    /** The topic whose id or title contains {@code query}, or null. */
    public static Topic find(String query) {
        String q = query.trim().toLowerCase(Locale.ROOT);
        if (q.isEmpty()) return null;
        for (Topic t : topics()) if (t.id.equalsIgnoreCase(q) || t.title.equalsIgnoreCase(q)) return t;
        for (Topic t : topics()) if (t.id.toLowerCase(Locale.ROOT).contains(q) || t.title.toLowerCase(Locale.ROOT).contains(q)) return t;
        for (Topic t : topics()) if (t.matches(q) > 0) return t;
        return null;
    }

    private static List<Topic> load() {
        List<String> lines = new ArrayList<>();
        try (InputStream in = GuideContent.class.getResourceAsStream(FILE)) {
            if (in == null) throw new IllegalStateException("missing " + FILE);
            BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null) lines.add(line);
        } catch (Exception e) {
            Myau.LOGGER.error("[Guide] cannot read the guide", e);
            Topic t = new Topic("error", "指南加载失败", "帮助", "指南文件没有找到或无法读取");
            t.blocks.add(new Block(Kind.WARN, "读取 " + FILE + " 失败：" + e, "", 0));
            return Collections.singletonList(t);
        }
        return parse(lines);
    }

    static List<Topic> parse(List<String> lines) {
        List<Topic> out = new ArrayList<>();
        Topic topic = null;
        StringBuilder paragraph = new StringBuilder();
        int step = 0;
        for (String raw : lines) {
            if (!raw.isEmpty() && raw.charAt(0) == '﻿') raw = raw.substring(1);
            String line = raw.trim();
            if (line.startsWith("//")) continue;
            if (line.startsWith("@topic ")) {
                flush(topic, paragraph);
                String[] parts = split(line.substring(7), 4);
                topic = new Topic(parts[0], parts[1], parts[2], parts[3]);
                out.add(topic);
                step = 0;
                continue;
            }
            if (topic == null) continue;
            if (line.isEmpty()) {
                flush(topic, paragraph);
                step = 0;
                continue;
            }
            Block block = null;
            if (line.startsWith("## ")) {
                block = new Block(Kind.HEADING, line.substring(3).trim(), "", 0);
            } else if (line.startsWith("- ")) {
                block = new Block(Kind.BULLET, line.substring(2).trim(), "", 0);
            } else if (line.matches("^\\d+\\.\\s.*")) {
                block = new Block(Kind.STEP, line.substring(line.indexOf('.') + 1).trim(), "", ++step);
            } else if (line.startsWith("> ")) {
                block = new Block(Kind.TIP, line.substring(2).trim(), "", 0);
            } else if (line.startsWith("! ")) {
                block = new Block(Kind.WARN, line.substring(2).trim(), "", 0);
            } else if (line.startsWith("| ")) {
                String[] parts = split(line.substring(2), 2);
                block = new Block(Kind.ROW, parts[0], parts[1], 0);
            } else if (line.startsWith("$ ")) {
                String[] parts = split(line.substring(2), 2);
                block = new Block(Kind.COMMAND, parts[0], parts[1], 0);
            } else if (line.startsWith("@button ")) {
                String[] parts = split(line.substring(8), 2);
                block = new Block(Kind.BUTTON, parts[0], parts[1], 0);
            }
            if (block != null) {
                flush(topic, paragraph);
                topic.blocks.add(block);
            } else {
                // Plain lines run together into one paragraph until a blank line or another block.
                if (paragraph.length() > 0 && needsSpace(paragraph.charAt(paragraph.length() - 1), line.charAt(0))) paragraph.append(' ');
                paragraph.append(line);
            }
        }
        flush(topic, paragraph);
        for (Topic t : out) {
            StringBuilder hay = new StringBuilder(t.title).append(' ').append(t.summary);
            for (Block b : t.blocks) hay.append(' ').append(plain(b.text)).append(' ').append(plain(b.extra));
            t.haystack = hay.toString().toLowerCase(Locale.ROOT);
        }
        return out;
    }

    private static void flush(Topic topic, StringBuilder paragraph) {
        if (topic != null && paragraph.length() > 0) topic.blocks.add(new Block(Kind.PARAGRAPH, paragraph.toString(), "", 0));
        paragraph.setLength(0);
    }

    /** Two Latin words joined across a line break keep a space; Chinese runs straight on. */
    private static boolean needsSpace(char before, char after) {
        return before < 0x2E80 && after < 0x2E80 && before != ' ';
    }

    /** Splits on {@code |} into exactly {@code n} trimmed parts (missing ones are empty). */
    private static String[] split(String s, int n) {
        String[] out = new String[n];
        String[] parts = s.split("\\|", n);
        for (int i = 0; i < n; i++) out[i] = i < parts.length ? parts[i].trim() : "";
        return out;
    }

    /** Text without the inline markup, for search. */
    static String plain(String text) {
        return text.replace("**", "").replace("`", "").replace("[", "").replace("]", "");
    }
}
