package dev.stonestats.plugin.config;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Inserts missing options into a YAML file as text, copied with their comments
 * from the bundled default file. Everything the admin wrote stays byte for
 * byte as it was - re-saving through YamlConfiguration would requote values,
 * reindent lists and drop blank lines all over the file.
 * <p>
 * Handles the block-style YAML that config files use. Anything it can't place
 * makes it return {@code null}, and {@link ConfigUpdater} verifies every
 * result before using it.
 */
final class YamlTextPatcher {

    private enum Kind { BLANK, COMMENT, KEY, OTHER }

    private record Line(String text, int indent, Kind kind, String key) {
    }

    // "key:", "'key':" or "\"key\":" followed by a space or the line end. List
    // items ("- x") and plain text never match.
    private static final Pattern KEY = Pattern.compile("^ *(?:'([^']*)'|\"([^\"]*)\"|([^\\s#'\"\\-][^:#]*?)) *:(?: |$)");

    private YamlTextPatcher() {
    }

    /**
     * @param missingPaths top-most missing paths, in default-file order
     * @return the patched text, or {@code null} if a path couldn't be placed
     */
    static String insertMissing(String userText, String defaultText, YamlConfiguration defaults, List<String> missingPaths) {
        String newline = userText.contains("\r\n") ? "\r\n" : "\n";
        List<Line> user = parse(userText);
        List<Line> defaultLines = parse(defaultText);

        for (String path : missingPaths) {
            int[] source = locate(defaultLines, path);
            if (source == null) {
                return null;
            }
            int parentDot = path.lastIndexOf('.');
            String parentPath = parentDot < 0 ? null : path.substring(0, parentDot);
            String key = path.substring(parentDot + 1);

            int parentStart = 0;
            int parentEnd = user.size();
            int parentIndent = -1;
            if (parentPath != null) {
                int[] parent = locate(user, parentPath);
                if (parent == null) {
                    return null;
                }
                parentStart = parent[0] + 1;
                parentEnd = parent[1];
                parentIndent = user.get(parent[0]).indent();
            }

            int childIndent = childIndent(user, parentStart, parentEnd, parentIndent);
            if (childIndent < 0) {
                if (parentPath != null) {
                    // A section without any lines below it (e.g. "slots: {}") can't be
                    // extended as text.
                    return null;
                }
                childIndent = 0;
            }

            Integer insertAt = insertionPoint(user, defaults, parentPath, key, parentStart, parentEnd);
            if (insertAt == null) {
                return null;
            }

            int blockStart = leadingCommentsStart(defaultLines, source[0]);
            int shift = childIndent - defaultLines.get(source[0]).indent();
            List<Line> block = new ArrayList<>();
            for (int i = blockStart; i < source[1]; i++) {
                block.add(parseLine(reindent(defaultLines.get(i).text(), shift)));
            }
            if (parentPath == null) {
                if (insertAt > 0 && user.get(insertAt - 1).kind() != Kind.BLANK) {
                    block.addFirst(parseLine(""));
                }
                if (insertAt < user.size() && user.get(insertAt).kind() != Kind.BLANK) {
                    block.add(parseLine(""));
                }
            }
            user.addAll(insertAt, block);
        }

        StringBuilder out = new StringBuilder(userText.length() + 256);
        for (int i = 0; i < user.size(); i++) {
            if (i > 0) {
                out.append(newline);
            }
            out.append(user.get(i).text());
        }
        if (userText.endsWith("\n") || userText.isEmpty()) {
            out.append(newline);
        }
        return out.toString();
    }

    /** Right after the closest earlier sibling (default order), else before the closest later one, else at the end. */
    private static Integer insertionPoint(List<Line> user, YamlConfiguration defaults, String parentPath, String key,
                                          int parentStart, int parentEnd) {
        ConfigurationSection defaultParent = parentPath == null ? defaults : defaults.getConfigurationSection(parentPath);
        if (defaultParent == null) {
            return null;
        }
        List<String> siblings = new ArrayList<>(defaultParent.getKeys(false));
        int position = siblings.indexOf(key);
        String prefix = parentPath == null ? "" : parentPath + ".";

        for (int i = position - 1; i >= 0; i--) {
            int[] sibling = locate(user, prefix + siblings.get(i));
            if (sibling != null) {
                return sibling[1];
            }
        }
        for (int i = position + 1; i < siblings.size(); i++) {
            int[] sibling = locate(user, prefix + siblings.get(i));
            if (sibling != null) {
                return leadingCommentsStart(user, sibling[0]);
            }
        }
        return parentEnd > parentStart || parentPath == null ? parentEnd : null;
    }

    /** @return {keyLine, endOfBlockExclusive} or null if the path isn't in the text */
    private static int[] locate(List<Line> lines, String path) {
        int start = 0;
        int end = lines.size();
        int parentIndent = -1;
        int keyLine = -1;
        for (String segment : path.split("\\.")) {
            keyLine = findChild(lines, start, end, parentIndent, segment);
            if (keyLine < 0) {
                return null;
            }
            parentIndent = lines.get(keyLine).indent();
            end = blockEnd(lines, keyLine, end);
            start = keyLine + 1;
        }
        return new int[]{keyLine, end};
    }

    private static int findChild(List<Line> lines, int start, int end, int parentIndent, String name) {
        int indent = childIndent(lines, start, end, parentIndent);
        for (int i = start; i < end; i++) {
            Line line = lines.get(i);
            if (line.kind() == Kind.KEY && line.indent() == indent && name.equals(line.key())) {
                return i;
            }
        }
        return -1;
    }

    private static int childIndent(List<Line> lines, int start, int end, int parentIndent) {
        for (int i = start; i < end; i++) {
            Line line = lines.get(i);
            if ((line.kind() == Kind.KEY || line.kind() == Kind.OTHER) && line.indent() > parentIndent) {
                return line.indent();
            }
        }
        return -1;
    }

    /** Index after the last content line belonging to the key; trailing comments belong to what follows. */
    private static int blockEnd(List<Line> lines, int keyLine, int limit) {
        int keyIndent = lines.get(keyLine).indent();
        int lastContent = keyLine;
        for (int i = keyLine + 1; i < limit; i++) {
            Line line = lines.get(i);
            if (line.kind() == Kind.BLANK || line.kind() == Kind.COMMENT) {
                continue;
            }
            if (line.indent() <= keyIndent) {
                break;
            }
            lastContent = i;
        }
        return lastContent + 1;
    }

    /** Comment lines directly above a key (no blank line in between) describe it and move with it. */
    private static int leadingCommentsStart(List<Line> lines, int keyLine) {
        int start = keyLine;
        while (start > 0 && lines.get(start - 1).kind() == Kind.COMMENT) {
            start--;
        }
        return start;
    }

    private static String reindent(String text, int shift) {
        if (text.isBlank()) {
            return "";
        }
        if (shift >= 0) {
            return " ".repeat(shift) + text;
        }
        int remove = 0;
        while (remove < -shift && remove < text.length() && text.charAt(remove) == ' ') {
            remove++;
        }
        return text.substring(remove);
    }

    private static List<Line> parse(String text) {
        String[] raw = text.split("\\R", -1);
        int count = raw.length;
        if (count > 0 && raw[count - 1].isEmpty()) {
            count--;
        }
        List<Line> lines = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            lines.add(parseLine(raw[i]));
        }
        return lines;
    }

    private static Line parseLine(String text) {
        String trimmed = text.strip();
        if (trimmed.isEmpty()) {
            return new Line(text, 0, Kind.BLANK, null);
        }
        int indent = 0;
        while (indent < text.length() && text.charAt(indent) == ' ') {
            indent++;
        }
        if (trimmed.startsWith("#")) {
            return new Line(text, indent, Kind.COMMENT, null);
        }
        Matcher matcher = KEY.matcher(text);
        if (matcher.find()) {
            String key = matcher.group(1) != null ? matcher.group(1)
                    : matcher.group(2) != null ? matcher.group(2) : matcher.group(3);
            return new Line(text, indent, Kind.KEY, key);
        }
        return new Line(text, indent, Kind.OTHER, null);
    }
}
