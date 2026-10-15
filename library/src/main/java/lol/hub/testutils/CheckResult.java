package lol.hub.testutils;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What vanilla's {@code execute if|unless} answers. A plain "Test passed" counts as 1 and a plain
 * "Test failed" as 0. An {@code unless} that fails can carry a count too.
 */
public record CheckResult(boolean passed, int count) {

    private static final Pattern REPLY = Pattern.compile("Test (passed|failed)(?:\\. Count: (\\d+))?");

    static CheckResult parse(String reply) {
        Matcher m = REPLY.matcher(reply.strip());
        if (!m.matches()) throw new IllegalArgumentException("not a check result: " + reply.strip());
        boolean passed = m.group(1).equals("passed");
        int plain = passed ? 1 : 0;
        return new CheckResult(passed, m.group(2) != null ? Integer.parseInt(m.group(2)) : plain);
    }
}
