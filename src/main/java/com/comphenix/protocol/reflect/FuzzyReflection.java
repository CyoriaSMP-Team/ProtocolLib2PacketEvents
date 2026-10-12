/*
 * ProtocolLib2PacketEvents (P2P) - a drop-in ProtocolLib compatibility layer
 * powered by PacketEvents.
 */
package com.comphenix.protocol.reflect;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.LinkedHashSet;
import com.comphenix.protocol.reflect.fuzzy.AbstractFuzzyMatcher;
import java.util.regex.Pattern;

/** Minimal reflection search facade used by older ProtocolLib-based plugins. */
public class FuzzyReflection {
    private final Class<?> source;
    private final boolean forceAccess;

    public FuzzyReflection(Class<?> source, boolean forceAccess) {
        this.source = source;
        this.forceAccess = forceAccess;
    }

    public static FuzzyReflection fromClass(Class<?> source) {
        return fromClass(source, false);
    }

    public static FuzzyReflection fromClass(Class<?> source, boolean forceAccess) {
        return new FuzzyReflection(source, forceAccess);
    }

    public static FuzzyReflection fromObject(Object reference) {
        return fromClass(reference.getClass(), false);
    }

    public static FuzzyReflection fromObject(Object reference, boolean forceAccess) {
        return fromClass(reference.getClass(), forceAccess);
    }

    public static <T> T getFieldValue(Object instance, Class<T> fieldClass, boolean forceAccess) {
        if (instance == null) throw new IllegalArgumentException("instance cannot be null");
        for (Class<?> current = instance.getClass(); current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (fieldClass.isAssignableFrom(field.getType()) || field.getType().isAssignableFrom(fieldClass)) {
                    try {
                        if (forceAccess) field.setAccessible(true);
                        return fieldClass.cast(field.get(instance));
                    } catch (IllegalAccessException e) {
                        throw new IllegalStateException("Unable to read " + field, e);
                    }
                }
            }
        }
        throw new IllegalArgumentException("Unable to find a field of type " + fieldClass.getName()
                + " on " + instance.getClass().getName());
    }

    public Class<?> getSource() {
        return source;
    }

    public boolean isForceAccess() { return forceAccess; }

    public Object getSingleton() {
        for (Class<?> current = source; current != null; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (Modifier.isStatic(field.getModifiers()) && source.isAssignableFrom(field.getType())) {
                    try {
                        if (forceAccess) field.setAccessible(true);
                        return field.get(null);
                    } catch (IllegalAccessException | RuntimeException ignored) {
                        // Try the next candidate in the hierarchy.
                    }
                }
            }
        }
        throw new IllegalArgumentException("Unable to find singleton on " + source.getName());
    }

    @SafeVarargs
    public static <T> Set<T> combineArrays(T[]... arrays) {
        Set<T> result = new LinkedHashSet<>();
        if (arrays != null) for (T[] array : arrays) if (array != null) java.util.Collections.addAll(result, array);
        return result;
    }

    public Method getMethodByName(String nameRegex) {
        Pattern pattern = Pattern.compile(nameRegex);
        for (Method method : getMethods()) {
            if (pattern.matcher(method.getName()).matches()) return accessible(method);
        }
        throw new IllegalArgumentException("Unable to find method matching " + nameRegex);
    }

    public Method getMethodByParameters(String name, Class<?>... parameters) {
        for (Method method : getMethods()) {
            if (method.getName().equals(name) && java.util.Arrays.equals(method.getParameterTypes(), parameters)) {
                return accessible(method);
            }
        }
        // FastLoginPlus still asks for the obfuscated Minecraft Connection
        // method "a(SecretKey)". On remapped Paper/Leaf 1.21.11 the equivalent
        // is named setEncryptionKey(SecretKey). Resolve by exact signature only
        // when it is unique: never guess an arbitrary encryption/packet method.
        if ("a".equals(name) && parameters.length == 1
                && parameters[0] == javax.crypto.SecretKey.class) {
            Method found = null;
            for (Method method : getMethods()) {
                if (!java.util.Arrays.equals(method.getParameterTypes(), parameters)
                        || method.getReturnType() != void.class) continue;
                if (found != null && !found.equals(method)) {
                    throw new IllegalArgumentException("Ambiguous SecretKey encryption methods on "
                            + source.getName());
                }
                found = method;
            }
            if (found != null) return accessible(found);
        }
        throw new IllegalArgumentException("Unable to find method " + name + " on " + source.getName());
    }

    public List<Method> getMethodListByParameters(Class<?> returnType, Class<?>... parameters) {
        List<Method> out = new ArrayList<>();
        for (Method method : getMethods()) {
            if (method.getReturnType().equals(returnType)
                    && java.util.Arrays.equals(method.getParameterTypes(), parameters)) {
                out.add(accessible(method));
            }
        }
        return out;
    }

    public Method getMethod(AbstractFuzzyMatcher<MethodInfo> matcher) {
        for (Method method : getMethods()) {
            if (matcher.isMatch(MethodInfo.fromMethod(method), source)) return accessible(method);
        }
        throw new IllegalArgumentException("Unable to find fuzzy method on " + source.getName());
    }

    public Method getMethod(AbstractFuzzyMatcher<MethodInfo> matcher, String preferredName) {
        for (Method method : getMethods()) if (method.getName().equals(preferredName)) {
            if (matcher.isMatch(MethodInfo.fromMethod(method), source)) return accessible(method);
        }
        return getMethod(matcher);
    }

    public List<Method> getMethodList(AbstractFuzzyMatcher<MethodInfo> matcher) {
        List<Method> result = new ArrayList<>();
        for (Method method : getMethods()) if (matcher.isMatch(MethodInfo.fromMethod(method), source)) result.add(accessible(method));
        return result;
    }

    public Method getMethodByReturnTypeAndParameters(String name, Class<?> returnType, Class<?>... parameters) {
        for (Method method : getMethods()) if ((name == null || method.getName().equals(name)) && method.getReturnType().equals(returnType) && java.util.Arrays.equals(method.getParameterTypes(), parameters)) return accessible(method);
        throw new IllegalArgumentException("Unable to find method " + name);
    }

    public Field getField(AbstractFuzzyMatcher<Field> matcher) {
        for (Field field : getFields()) if (matcher.isMatch(field, source)) return accessible(field);
        throw new IllegalArgumentException("Unable to find fuzzy field on " + source.getName());
    }

    public Field getFieldByType(String name, Class<?> type) {
        for (Field field : getFields()) if ((name == null || field.getName().equals(name)) && field.getType().equals(type)) return accessible(field);
        throw new IllegalArgumentException("Unable to find field of type " + type);
    }

    public Field getParameterizedField(Class<?> fieldType, Class<?>... params) {
        return getFieldByType(null, fieldType);
    }

    public List<Field> getFieldList(AbstractFuzzyMatcher<Field> matcher) {
        List<Field> result = new ArrayList<>(); for (Field field : getFields()) if (matcher.isMatch(field, source)) result.add(accessible(field)); return result;
    }

    public Field getFieldByType(String typeRegex) {
        java.util.regex.Pattern pattern = Pattern.compile(typeRegex);
        for (Field field : getFields()) if (pattern.matcher(field.getType().getName()).matches()) return accessible(field);
        throw new IllegalArgumentException("Unable to find field type " + typeRegex);
    }

    public Constructor<?> getConstructor(AbstractFuzzyMatcher<MethodInfo> matcher) {
        for (Constructor<?> constructor : getConstructors()) if (matcher.isMatch(MethodInfo.fromConstructor(constructor), source)) return accessible(constructor);
        throw new IllegalArgumentException("Unable to find fuzzy constructor on " + source.getName());
    }

    public List<Constructor<?>> getConstructorList(AbstractFuzzyMatcher<MethodInfo> matcher) {
        List<Constructor<?>> result = new ArrayList<>(); for(Constructor<?> constructor:getConstructors())if(matcher.isMatch(MethodInfo.fromConstructor(constructor),source))result.add(accessible(constructor));return result;
    }

    public Field getFieldByName(String name) {
        for (Field field : getFields()) if (field.getName().equals(name)) return accessible(field);
        throw new IllegalArgumentException("Unable to find field " + name + " on " + source.getName());
    }

    public List<Field> getFieldListByType(Class<?> type) {
        List<Field> out = new ArrayList<>();
        for (Field field : getFields()) if (type.isAssignableFrom(field.getType())) out.add(accessible(field));
        return out;
    }

    public Set<Method> getMethods() {
        Set<Method> out = new LinkedHashSet<>();
        for (Class<?> current = source; current != null; current = current.getSuperclass()) {
            for (Method method : forceAccess ? current.getDeclaredMethods() : current.getMethods()) {
                if (!out.contains(method)) out.add(method);
            }
            if (!forceAccess) break;
        }
        return out;
    }

    public Set<Field> getFields() {
        Set<Field> out = new LinkedHashSet<>();
        for (Class<?> current = source; current != null; current = current.getSuperclass()) {
            for (Field field : forceAccess ? current.getDeclaredFields() : current.getFields()) {
                if (!Modifier.isStatic(field.getModifiers()) && !out.contains(field)) out.add(field);
            }
            if (!forceAccess) break;
        }
        return out;
    }

    public Set<Field> getDeclaredFields(Class<?> excludeClass) {
        Set<Field> result = new LinkedHashSet<>();
        for (Class<?> current = source; current != null && current != excludeClass; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) if (forceAccess || Modifier.isPublic(field.getModifiers())) result.add(accessible(field));
        }
        return result;
    }

    public Set<Constructor<?>> getConstructors() {
        Set<Constructor<?>> result = new LinkedHashSet<>();
        for (Constructor<?> constructor : source.getDeclaredConstructors()) if (forceAccess || Modifier.isPublic(constructor.getModifiers())) result.add(accessible(constructor));
        return result;
    }

    private <T extends java.lang.reflect.AccessibleObject> T accessible(T object) {
        if (forceAccess) object.setAccessible(true);
        return object;
    }
}
