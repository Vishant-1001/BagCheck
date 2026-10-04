package util;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Tiny dependency-free JSON reader/writer.
 *
 * Why this exists: the project must run with no external libraries (no Maven/Gradle,
 * no internet, no Java collections doing the assessed DSA work). Data files are JSON,
 * so the engine needs a small parser. It is deliberately minimal and is NOT part of
 * the assessed data structures.
 */
public final class Json {

    private Json() {
    }

    // ------------------------------------------------------------------
    // Parsing
    // ------------------------------------------------------------------

    public static Object parse(String text) {
        Parser p = new Parser(text);
        p.skipWhitespace();
        Object value = p.readValue();
        p.skipWhitespace();
        return value;
    }

    public static Object parseResource(String path) {
        return parse(readFile(path));
    }

    public static String readFile(String path) {
        try {
            return new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get(path)),
                    java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw new RuntimeException("Could not read file: " + path, e);
        }
    }

    private static final class Parser {
        private final String s;
        private int i;

        Parser(String s) {
            this.s = s;
        }

        void skipWhitespace() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        Object readValue() {
            skipWhitespace();
            if (i >= s.length()) {
                throw error("unexpected end of input");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{':
                    return readObject();
                case '[':
                    return readArray();
                case '"':
                    return readString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return readNumber();
            }
        }

        private Map<String, Object> readObject() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            i++; // consume '{'
            skipWhitespace();
            if (i < s.length() && s.charAt(i) == '}') {
                i++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (i >= s.length()) {
                    throw error("unterminated object");
                }
                if (s.charAt(i) != '"') {
                    throw error("expected a quoted key");
                }
                String key = readString();
                skipWhitespace();
                if (i >= s.length() || s.charAt(i) != ':') {
                    throw error("expected ':' after key '" + key + "'");
                }
                i++;
                map.put(key, readValue());
                skipWhitespace();
                if (i >= s.length()) {
                    throw error("unterminated object");
                }
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                } else if (c == '}') {
                    i++;
                    return map;
                } else {
                    throw error("expected ',' or '}'");
                }
            }
        }

        private List<Object> readArray() {
            List<Object> list = new ArrayList<Object>();
            i++; // consume '['
            skipWhitespace();
            if (i < s.length() && s.charAt(i) == ']') {
                i++;
                return list;
            }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                if (i >= s.length()) {
                    throw error("unterminated array");
                }
                char c = s.charAt(i);
                if (c == ',') {
                    i++;
                } else if (c == ']') {
                    i++;
                    return list;
                } else {
                    throw error("expected ',' or ']'");
                }
            }
        }

        private String readString() {
            i++; // consume opening quote
            StringBuilder sb = new StringBuilder();
            while (i < s.length()) {
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c == '\\') {
                    if (i >= s.length()) {
                        throw error("bad escape");
                    }
                    char e = s.charAt(i++);
                    switch (e) {
                        case '"':  sb.append('"');  break;
                        case '\\': sb.append('\\'); break;
                        case '/':  sb.append('/');  break;
                        case 'b':  sb.append('\b'); break;
                        case 'f':  sb.append('\f'); break;
                        case 'n':  sb.append('\n'); break;
                        case 'r':  sb.append('\r'); break;
                        case 't':  sb.append('\t'); break;
                        case 'u':
                            if (i + 4 > s.length()) {
                                throw error("bad unicode escape");
                            }
                            sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                            i += 4;
                            break;
                        default: throw error("bad escape \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw error("unterminated string");
        }

        private Double readNumber() {
            int start = i;
            while (i < s.length()) {
                char c = s.charAt(i);
                if ((c >= '0' && c <= '9') || c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') {
                    i++;
                } else {
                    break;
                }
            }
            String raw = s.substring(start, i);
            try {
                return Double.valueOf(raw);
            } catch (NumberFormatException e) {
                throw error("bad number '" + raw + "'");
            }
        }

        private void expect(String literal) {
            if (!s.startsWith(literal, i)) {
                throw error("expected '" + literal + "'");
            }
            i += literal.length();
        }

        private RuntimeException error(String message) {
            return new RuntimeException("JSON parse error at character " + i + ": " + message);
        }
    }

    // ------------------------------------------------------------------
    // Writing
    // ------------------------------------------------------------------

    public static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        writeValue(value, sb);
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static void writeValue(Object value, StringBuilder sb) {
        if (value == null) {
            sb.append("null");
        } else if (value instanceof String) {
            writeString((String) value, sb);
        } else if (value instanceof Boolean) {
            sb.append(value.toString());
        } else if (value instanceof Double || value instanceof Float) {
            double d = ((Number) value).doubleValue();
            if (d == Math.rint(d) && !Double.isInfinite(d) && Math.abs(d) < 1e15) {
                sb.append((long) d);
            } else {
                sb.append(d);
            }
        } else if (value instanceof Number) {
            sb.append(value.toString());
        } else if (value instanceof Map) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<Object, Object> e : ((Map<Object, Object>) value).entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeString(String.valueOf(e.getKey()), sb);
                sb.append(':');
                writeValue(e.getValue(), sb);
            }
            sb.append('}');
        } else if (value instanceof Iterable) {
            sb.append('[');
            boolean first = true;
            for (Object item : (Iterable<Object>) value) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                writeValue(item, sb);
            }
            sb.append(']');
        } else {
            writeString(value.toString(), sb);
        }
    }

    private static void writeString(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"':  sb.append("\\\""); break;
                case '\\': sb.append("\\\\"); break;
                case '\n': sb.append("\\n");  break;
                case '\r': sb.append("\\r");  break;
                case '\t': sb.append("\\t");  break;
                default:
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
            }
        }
        sb.append('"');
    }

    // ------------------------------------------------------------------
    // Small typed accessors so service code stays readable
    // ------------------------------------------------------------------

    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        if (value == null) {
            return new LinkedHashMap<String, Object>();
        }
        if (!(value instanceof Map)) {
            throw new RuntimeException("Expected a JSON object but found: " + value.getClass().getSimpleName());
        }
        return (Map<String, Object>) value;
    }

    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object value) {
        if (value == null) {
            return new ArrayList<Object>();
        }
        if (!(value instanceof List)) {
            throw new RuntimeException("Expected a JSON array but found: " + value.getClass().getSimpleName());
        }
        return (List<Object>) value;
    }

    public static String string(Map<String, Object> object, String key, String fallback) {
        Object v = object.get(key);
        return v == null ? fallback : String.valueOf(v);
    }

    public static List<String> stringList(Object value) {
        List<String> out = new ArrayList<String>();
        for (Object item : asArray(value)) {
            out.add(String.valueOf(item));
        }
        return out;
    }

    public static List<String> stringList(Map<String, Object> object, String key) {
        return stringList(object.get(key));
    }

    public static int integer(Map<String, Object> object, String key, int fallback) {
        Object v = object.get(key);
        if (v == null) {
            return fallback;
        }
        return (int) Math.round(((Number) v).doubleValue());
    }
}
