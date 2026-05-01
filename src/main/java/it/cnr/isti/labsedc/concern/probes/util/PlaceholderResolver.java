package it.cnr.isti.labsedc.concern.probes.util;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Resolves ${...} placeholders in template strings from YAML config. */
public class PlaceholderResolver {

    private static final Pattern PH = Pattern.compile("\\$\\{([^}]+)\\}");

    private final Map<String, Object> payload;

    public PlaceholderResolver(Map<String, Object> payload) {
        this.payload = payload != null ? payload : Map.of();
    }

    public String resolve(String template) {
        if (template == null) {
			return null;
		}
        Matcher m = PH.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String val = resolveExpr(m.group(1).trim());
            m.appendReplacement(sb, Matcher.quoteReplacement(val != null ? val : m.group(0)));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private String resolveExpr(String expr) {
        return switch (expr) {
            case "uuid" -> java.util.UUID.randomUUID().toString();
            case "now"  -> String.valueOf(System.currentTimeMillis());
            default -> {
                if (expr.startsWith("env.")){yield System.getenv(expr.substring(4));}
                if (expr.startsWith("sys.")){yield System.getProperty(expr.substring(4));}
                if (expr.startsWith("payload.")){yield lookupPath(payload, expr.substring(8));}
                if (expr.equals("payload")){yield payload.toString();}
                yield null;
            }
        };
    }

    @SuppressWarnings("unchecked")
    private String lookupPath(Map<String, Object> root, String path) {
        Object cur = root;
        for (String p : path.split("\\.")) {
            if (cur instanceof Map<?,?> mp) {
				cur = ((Map<String,Object>) mp).get(p);
			} else {
				return null;
			}
            if (cur == null) {
				return null;
			}
        }
        return String.valueOf(cur);
    }
}
