package lol.hub.testutils;

import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.ParameterContext;
import org.junit.jupiter.api.extension.ParameterResolver;

import java.io.IOException;
import java.io.UncheckedIOException;

/** Injects the run's single {@link TestServer}. It starts on first use and stops when the run ends. */
public final class TestServerExtension implements ParameterResolver {

    @Override
    public boolean supportsParameter(ParameterContext parameter, ExtensionContext context) {
        return parameter.getParameter().getType() == TestServer.class;
    }

    @Override
    public Object resolveParameter(ParameterContext parameter, ExtensionContext context) {
        // The root store closes its AutoCloseable values after the last test of the run.
        return context.getRoot().getStore(ExtensionContext.Namespace.GLOBAL).computeIfAbsent(TestServer.class, key -> {
            try {
                return TestServer.start();
            } catch (IOException e) {
                throw new UncheckedIOException(e);
            }
        });
    }
}
