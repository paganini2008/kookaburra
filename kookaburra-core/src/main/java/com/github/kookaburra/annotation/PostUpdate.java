package com.github.kookaburra.annotation;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Lifecycle callback (JPA style): a no-argument method of an entity (or one of its superclasses) called after an update has committed (the new version is set).
 * See {@link com.github.kookaburra.LifecycleEvent} for when callbacks run and which operations skip them.
 *
 * @Author: Fred Feng
 * @Date: 16/10/2026
 * @Version 1.0.0
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface PostUpdate {
}
