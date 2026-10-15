package lol.hub.testutils;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolutionException;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Injects the run's single {@link TestServer}, which starts on first use and stops when the run ends,
 * and a fresh {@link Area} per test invocation, which removes its bots when the invocation ends.
 */
public final class TestServerExtension implements ParameterResolver {

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
