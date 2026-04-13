package com.github.drafael.chat4j.chat;

import java.lang.reflect.Field;

final class TestReflection {

    private TestReflection() {
    }

    static Object readField(Object target, String fieldName) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        return field.get(target);
    }

    static void setField(Object target, String fieldName, Object value) throws Exception {
        Field field = findField(target.getClass(), fieldName);
        field.setAccessible(true);
        field.set(target, value);
    }

    private static Field findField(Class<?> type, String fieldName) throws NoSuchFieldException {
        Class<?> currentType = type;

        while (currentType != null) {
            try {
                return currentType.getDeclaredField(fieldName);
            } catch (NoSuchFieldException e) {
                currentType = currentType.getSuperclass();
            }
        }

        throw new NoSuchFieldException(fieldName);
    }
}
