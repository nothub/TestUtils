package lol.hub.testutils;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Puts an injected {@link Area} in the dimension with this id, like {@code minecraft:the_nether}.
 * Without it the area is in the overworld.
 */
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.PARAMETER)
public @interface InDimension {
    String value();
}
