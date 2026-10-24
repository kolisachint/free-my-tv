package org.json;

class Parser {
    private final String s;
    private int i;

    Parser(String s) { this.s = s; }

    Object value() throws JSONException {
        ws();
        if (i >= s.length()) throw new JSONException("end");
        char c = s.charAt(i);
        if (c == '{') {
            JSONObject o = new JSONObject();
            i++;
            ws();
            if (s.charAt(i) == '}') { i++; return o; }
            while (true) {
                ws();
                String k = (String) value();
                ws();
                expect(':');
                o.map.put(k, value());
                ws();
                if (s.charAt(i) == ',') { i++; continue; }
                expect('}');
                return o;
            }
        }
        if (c == '[') {
            JSONArray a = new JSONArray();
            i++;
            ws();
            if (s.charAt(i) == ']') { i++; return a; }
            while (true) {
                a.list.add(value());
                ws();
                if (s.charAt(i) == ',') { i++; continue; }
                expect(']');
                return a;
            }
        }
        if (c == '"') {
            StringBuilder sb = new StringBuilder();
            i++;
            while (s.charAt(i) != '"') {
                char d = s.charAt(i++);
                if (d == '\\') {
                    char e = s.charAt(i++);
                    if (e == 'u') { sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); i += 4; }
                    else sb.append(e == 'n' ? '\n' : e == 't' ? '\t' : e);
                } else sb.append(d);
            }
            i++;
            return sb.toString();
        }
        if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
        if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
        if (s.startsWith("null", i)) { i += 4; return JSONObject.NULL; }
        int j = i;
        while (j < s.length() && "+-0123456789.eE".indexOf(s.charAt(j)) >= 0) j++;
        String n = s.substring(i, j);
        i = j;
        try {
            return n.contains(".") || n.contains("e") || n.contains("E") ? (Object) Double.valueOf(n) : (Object) Long.valueOf(n);
        } catch (NumberFormatException e) {
            throw new JSONException("bad number " + n);
        }
    }

    private void ws() { while (i < s.length() && Character.isWhitespace(s.charAt(i))) i++; }

    private void expect(char c) throws JSONException {
        if (i >= s.length() || s.charAt(i) != c) throw new JSONException("expected " + c + " at " + i);
        i++;
    }
}
