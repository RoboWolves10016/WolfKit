/*
 * Copyright (c) 2026 Clarkston RoboWolves 10016
 * SPDX-License-Identifier: MIT
 */

package com.robowolves.wolfkit;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Reads {@link Logged} fields and methods and logs their values.
 *
 * <p>Each class is inspected by reflection once and the result is cached. Registered objects are
 * then read on every call to {@link #logAll()}.
 */
final class AnnotationLogger {
    /** Maximum depth of nested {@code @Logged} objects. Bounds traversal of cyclic references. */
    private static final int MAX_DEPTH = 6;

    private static final LogAdapter<Object> NO_ADAPTER = (key, value) -> {};
    private static final byte NUMBER = 0, BOOLEAN = 1, OBJECT = 2;

    private static final Map<Class<?>, Member[]> plans = new ConcurrentHashMap<>();
    private static final Map<Class<?>, LogAdapter<Object>> adapters = new ConcurrentHashMap<>();
    private static final Map<Class<?>, LogAdapter<Object>> adapterFor = new ConcurrentHashMap<>();
    private static final List<Bound> roots = new CopyOnWriteArrayList<>();

    private AnnotationLogger() {}

    @SuppressWarnings("unchecked")
    /**
     * Registers an adapter for a value type.
     *
     * @param type the type to handle, including its subtypes
     * @param adapter logs a value of that type under a key
     */
    static <T> void adapter(Class<T> type, LogAdapter<? super T> adapter) {
        adapters.put(type, (LogAdapter<Object>) adapter);
        adapterFor.clear();
    }

    /**
     * Adds an object to be logged by {@link #logAll()}. An object that is already registered, or
     * whose class has no {@code @Logged} members, is ignored.
     */
    static void register(Object target) {
        for (Bound root : roots) if (root.target == target) return;
        if (plan(target.getClass()).length > 0)
            roots.add(new Bound(target, name(target.getClass())));
    }

    /** Removes every registered object. */
    static void clear() {
        roots.clear();
    }

    /** Logs the {@code @Logged} members of every registered object. */
    static void logAll() {
        // Indexed loop: a for-each would allocate an iterator on every call.
        for (int i = 0, count = roots.size(); i < count; i++) roots.get(i).log(0);
    }

    /** Returns the simple name of a class, or the binary name if the class is anonymous. */
    private static String name(Class<?> type) {
        String simple = type.getSimpleName();
        return simple.isEmpty()
                ? type.getName().substring(type.getName().lastIndexOf('.') + 1)
                : simple;
    }

    /**
     * Returns the {@code @Logged} members of a class and its superclasses, inspecting the class on
     * first use.
     */
    private static Member[] plan(Class<?> type) {
        Member[] plan = plans.get(type);
        if (plan != null) return plan;
        List<Member> members = new ArrayList<>();
        for (Class<?> c = type; c != null && c != Object.class; c = c.getSuperclass()) {
            for (Field field : c.getDeclaredFields()) {
                Logged logged = field.getAnnotation(Logged.class);
                if (logged == null) continue;
                field.setAccessible(true);
                members.add(new Member(field, null, field.getName(), logged.key()));
            }
            for (Method method : c.getDeclaredMethods()) {
                Logged logged = method.getAnnotation(Logged.class);
                if (logged == null
                        || method.getParameterTypes().length != 0
                        || method.getReturnType() == void.class) continue;
                method.setAccessible(true);
                String name = method.getName();
                if (name.startsWith("get") && name.length() > 3) name = name.substring(3);
                members.add(new Member(null, method, name, logged.key()));
            }
        }
        plan = members.toArray(new Member[0]);
        plans.put(type, plan);
        return plan;
    }

    /**
     * Returns the adapter registered for a type or one of its supertypes, or {@link #NO_ADAPTER}.
     */
    private static LogAdapter<Object> adapter(Class<?> type) {
        LogAdapter<Object> found = adapterFor.get(type);
        if (found == null) {
            found = NO_ADAPTER;
            for (Map.Entry<Class<?>, LogAdapter<Object>> entry : adapters.entrySet()) {
                if (entry.getKey().isAssignableFrom(type)) found = entry.getValue();
            }
            adapterFor.put(type, found);
        }
        return found;
    }

    /**
     * A {@code @Logged} field or method. Exactly one of {@link #field} and {@link #method} is set.
     */
    private static final class Member {
        final Field field;
        final Method method;
        final String name;
        final String key;
        final boolean isStatic;

        /**
         * How the value is read. Primitive fields are read unboxed; method results are always
         * boxed.
         */
        final byte read;

        Member(Field field, Method method, String name, String key) {
            this.field = field;
            this.method = method;
            this.name = name;
            this.key = key;
            this.isStatic =
                    Modifier.isStatic(field != null ? field.getModifiers() : method.getModifiers());
            Class<?> type = field != null ? field.getType() : Object.class;
            this.read = type == boolean.class ? BOOLEAN : type.isPrimitive() ? NUMBER : OBJECT;
        }
    }

    /** A registered object with the resolved key and slot of each of its members. */
    private static final class Bound {
        final Object target;
        final Member[] members;
        final String[] keys;
        final int[] slots;
        final Bound[] children;

        Bound(Object target, String prefix) {
            this.target = target;
            this.members = plan(target.getClass());
            this.keys = new String[members.length];
            this.slots = new int[members.length];
            this.children = new Bound[members.length];
            for (int i = 0; i < members.length; i++) {
                keys[i] =
                        members[i].key.isEmpty() ? prefix + "/" + members[i].name : members[i].key;
                slots[i] = Keys.slot(keys[i]);
            }
        }

        void log(int depth) {
            for (int i = 0; i < members.length; i++) {
                Member member = members[i];
                Object owner = member.isStatic ? null : target;
                try {
                    if (member.read == NUMBER) WolfKit.log(slots[i], member.field.getDouble(owner));
                    else if (member.read == BOOLEAN)
                        WolfKit.log(slots[i], member.field.getBoolean(owner));
                    else
                        logObject(
                                i,
                                member.field != null
                                        ? member.field.get(owner)
                                        : member.method.invoke(owner),
                                depth);
                } catch (ReflectiveOperationException | RuntimeException unreadable) {
                    // An unreadable member is skipped; the remaining members are still logged.
                }
            }
        }

        private void logObject(int i, Object value, int depth) {
            if (value == null) return;
            if (value instanceof Number) WolfKit.log(slots[i], ((Number) value).doubleValue());
            else if (value instanceof Boolean) WolfKit.log(slots[i], (boolean) (Boolean) value);
            else if (value instanceof double[]) WolfKit.log(slots[i], (double[]) value);
            else if (value instanceof String) WolfKit.log(slots[i], (String) value);
            else if (value instanceof Enum) WolfKit.log(slots[i], ((Enum<?>) value).name());
            else {
                LogAdapter<Object> adapter = adapter(value.getClass());
                if (adapter != NO_ADAPTER) adapter.log(keys[i], value);
                else if (plan(value.getClass()).length == 0)
                    WolfKit.log(slots[i], value.toString());
                else if (depth < MAX_DEPTH) {
                    // A nested object with @Logged members. Its binding is rebuilt only when the
                    // field is reassigned.
                    if (children[i] == null || children[i].target != value) {
                        children[i] =
                                new Bound(
                                        value,
                                        members[i].key.isEmpty()
                                                ? name(value.getClass())
                                                : members[i].key);
                    }
                    children[i].log(depth + 1);
                }
            }
        }
    }
}
