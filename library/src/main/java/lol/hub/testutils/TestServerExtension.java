package lol.hub.testutils;

import org.junit.jupiter.api.extension.AfterAllCallback;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.List;

/**
 * Injects the run's single {@link TestServer}, which starts on first use and stops when the run ends,
 * and a fresh {@link Area} per test invocation, which removes its bots when the invocation ends.
 * An error of the plugin under test in the server log fails the test that was running, or the class if it
 * came after the last test.
 */
public final class TestServerExtension implements ParameterResolver, AfterEachCallback, AfterAllCallback {

    private static final ExtensionContext.Namespace NAMESPACE = ExtensionContext.Namespace.create(TestServerExtension.class);

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        Class<?> type = parameter.getParameter().getType();
        return type == TestServer.class || type == Area.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        TestServer server = server(context);
        if (parameter.getParameter().getType() == TestServer.class) return server;
        // A class-level area would be shared by every test of the class and defeat the isolation.
        if (context.getTestMethod().isEmpty()) {
            throw new ParameterResolutionException("an Area belongs to one test, ask for it in a test or @BeforeEach method");
        }
        // @BeforeEach and the test method share this invocation's context, so they share its area.
        return context.getStore(NAMESPACE).computeIfAbsent(Area.class, key -> server.area(), Area.class);
    }

    @Override
    public void afterEach(ExtensionContext context) {
        // Closed here, not by the store later, so errors from the bots leaving are blamed on this test.
        Area area = context.getStore(NAMESPACE).remove(Area.class, Area.class);
        try {
            if (area != null) area.close();
        } finally {
            failOnPluginErrors(context);
        }
    }

    @Override
    public void afterAll(ExtensionContext context) {
        failOnPluginErrors(context);
    }

    private static void failOnPluginErrors(ExtensionContext context) {
        // A class that never asked for the server did not start one.
        TestServer server = context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL).get(TestServer.class, TestServer.class);
        if (server == null) return;
        List<String> errors = server.pluginErrors();
        if (!errors.isEmpty()) {
            throw new AssertionError("the plugin under test logged errors (full log: " + TestServer.LOG_COPY + " after the run):\n" + String.join("\n---\n", errors));
        }
    }

    private static TestServer server(ExtensionContext context) {
        // The root store closes its AutoCloseable values after the last test of the run.
        return context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL).computeIfAbsent(TestServer.class, key -> {
            try {
                return TestServer.start();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        }, TestServer.class);
    }
}
