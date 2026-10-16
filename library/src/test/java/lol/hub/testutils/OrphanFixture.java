package lol.hub.testutils;

import java.nio.file.Path;

/** A test JVM in miniature: owns one server, reports its pid, and waits to be killed. Started by {@link OrphanTest}. */
public final class OrphanFixture {

    public static void main(String[] args) throws Exception {
        // Dies with the test that started it, or Ctrl-C of the outer build would leave this JVM and its server behind.
        ProcessHandle.current().parent().ifPresent(parent -> parent.onExit().thenRun(() -> System.exit(0)));
        TestServer server = TestServer.start(PortSource.LOOPBACK, Path.of(args[0]));
        System.out.println("SERVER_PID=" + server.pid());
        System.out.flush();
        Thread.sleep(Long.MAX_VALUE);
    }
}
