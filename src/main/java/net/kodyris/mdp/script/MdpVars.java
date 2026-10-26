package net.kodyris.mdp.script;

import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * <name> and <name.property> placeholders.
 * A player variable <p> exposes: <p> (same as <p.name>), <p.name>, <p.uuid>.
 */
public final class MdpVars {
    private MdpVars() {}

    /** Matches <name> and <name.prop>. */
    public static final Pattern VAR = Pattern.compile("<(\\w+(?:\\.\\w+)?)>");

    /** Properties available on a player variable. */
    public static final Set<String> PLAYER_PROPS = Set.of("name", "uuid");

    /** Single pass, so a substituted value is never itself re-substituted. Unknown names stay as text. */
    public static String substitute(String line, Map<String, String> vars) {
        Matcher m = VAR.matcher(line);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = vars.get(m.group(1));
            m.appendReplacement(sb, Matcher.quoteReplacement(v != null ? v : m.group()));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    /** Adds param, param.name and param.uuid. */
    public static void putPlayer(Map<String, String> vars, String param, String name, String uuid) {
        vars.put(param, name);
        vars.put(param + ".name", name);
        vars.put(param + ".uuid", uuid);
    }

    /** "p.uuid" -> "p", "p" -> "p". */
    public static String baseName(String key) {
        int i = key.indexOf('.');
        return i < 0 ? key : key.substring(0, i);
    }
}
