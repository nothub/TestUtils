package lol.hub.testutils;

import java.io.IOException;
import java.nio.file.Files;
import java.util.List;

final class ServerLog {

    private ServerLog() {
    }

    static List<String> errors(TestServer server) throws IOException {
        return Files.readAllLines(server.logFile()).stream()
                .filter(line -> line.contains(" ERROR]") || line.contains("Ambiguous plugin name"))
                .toList();
    }
}
