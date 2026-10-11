package com.github.kookaburra.field;

import java.io.Serializable;
import java.lang.invoke.MethodHandleInfo;
import java.lang.invoke.SerializedLambda;
import java.lang.reflect.Method;

/**
 * Resolves a getter method reference such as {@code Person::getAge} to the property it reads.
 *
 * @Author: Fred Feng
 * @Date: 11/10/2026
 * @Version 1.0.0
 */
class GetterResolver {

    /** Owner class (from the method reference's receiver type) and property name. */
    record PropertyRef(Class<?> owner, String property) {
    }

    private static final ClassValue<PropertyRef[]> CACHE = new ClassValue<>() {
        @Override
        protected PropertyRef[] computeValue(Class<?> lambdaClass) {
            return new PropertyRef[1];
        }
    };

    private GetterResolver() {
    }

    static PropertyRef resolve(Serializable getter) {
        PropertyRef[] slot = CACHE.get(getter.getClass());
        PropertyRef ref = slot[0];
        if (ref == null) {
            ref = doResolve(getter);
            slot[0] = ref;   // benign race: resolution is deterministic
        }
        return ref;
    }

    private static PropertyRef doResolve(Serializable getter) {
        SerializedLambda lambda = serialized(getter);
        int kind = lambda.getImplMethodKind();
        boolean instanceMethod = kind == MethodHandleInfo.REF_invokeVirtual
                || kind == MethodHandleInfo.REF_invokeInterface;
        if (!instanceMethod || lambda.getCapturedArgCount() != 0
                || !lambda.getImplMethodSignature().startsWith("()")) {
            throw notAProperty(lambda.getImplMethodName());
        }
        Class<?> owner = receiverClass(lambda, getter.getClass().getClassLoader());
        String method = lambda.getImplMethodName();
        for (String candidate : candidates(method)) {
            if (Property.find(owner, candidate).isPresent()) {
                return new PropertyRef(owner, candidate);
            }
        }
        throw new IllegalArgumentException(owner.getName() + "::" + method
                + " does not read a persistent field (expected getX / isX / x() for field x)");
    }

    private static SerializedLambda serialized(Serializable getter) {
        try {
            Method m = getter.getClass().getDeclaredMethod("writeReplace");
            m.setAccessible(true);
            if (m.invoke(getter) instanceof SerializedLambda s) {
                return s;
            }
        } catch (ReflectiveOperationException | RuntimeException e) {
            throw notAProperty(getter.getClass().getName());
        }
        throw notAProperty(getter.getClass().getName());
    }

    private static Class<?> receiverClass(SerializedLambda lambda, ClassLoader loader) {
        String desc = lambda.getInstantiatedMethodType();          // e.g. (Lcom/x/Person;)I
        if (desc.length() < 2 || desc.charAt(1) != 'L') {
            throw notAProperty(lambda.getImplMethodName());
        }
        String name = desc.substring(2, desc.indexOf(';')).replace('/', '.');
        try {
            return Class.forName(name, false, loader);
        } catch (ClassNotFoundException e) {
            throw new IllegalStateException("Cannot load " + name, e);
        }
    }

    private static String[] candidates(String method) {
        if (method.length() > 3 && method.startsWith("get") && Character.isUpperCase(method.charAt(3))) {
            return new String[]{decapitalize(method.substring(3)), method};
        }
        if (method.length() > 2 && method.startsWith("is") && Character.isUpperCase(method.charAt(2))) {
            return new String[]{decapitalize(method.substring(2)), method};
        }
        return new String[]{method};
    }

    /** JavaBeans rule: {@code URL} stays {@code URL}, {@code Age} becomes {@code age}. */
    static String decapitalize(String s) {
        if (s.length() > 1 && Character.isUpperCase(s.charAt(0)) && Character.isUpperCase(s.charAt(1))) {
            return s;
        }
        return Character.toLowerCase(s.charAt(0)) + s.substring(1);
    }

    private static IllegalArgumentException notAProperty(String what) {
        return new IllegalArgumentException("Not a property getter reference: " + what
                + ". Use a method reference such as Person::getAge, not a lambda.");
    }
}
