package testablejava;

import java.lang.reflect.Method;
import java.util.*;

import static java.util.stream.Collectors.joining;

public class ReflectiveCaller {

    Method m;

    public ReflectiveCaller(Class<?> clazz, String methodName, Class<?>... argTypes) {

        m = findMethodDeepOrThrow(clazz, methodName, argTypes);

        m.setAccessible(true);
        //TODO confirm under JDK9, may need MethodHandles.privateLookupIn​,
        // see http://in.relation.to/2017/04/11/accessing-private-state-of-java-9-modules/
    }

    static Method findMethodDeepOrThrow(Class<?> clazz, String methodName, Class<?>[] argTypes) {
        return findMethodDeep(clazz, methodName, argTypes).
                orElseThrow(()->new RuntimeException(
                        String.format(
                                "cannot find method %s(%s) in class %s",
                                methodName,
                                Arrays.stream(argTypes).
                                        map(Object::toString).
                                        collect(joining(",")),
                                clazz)));
    }

    static void classHierarchy(Class<?> clazz, List<Class<?>> acc){
        acc.add(clazz);
        Class<?> superclass = clazz.getSuperclass();
        if (superclass!=null)
            classHierarchy(superclass, acc);
    }

    static Optional<Method> findMethodDeep(Class<?> clazz, String methodName, Class<?>[] argTypes) {
        List<Class<?>> classes = new ArrayList<>();
        classHierarchy(clazz, classes);
        return classes.stream().
                map(cl -> findMethod(cl, methodName, argTypes)).
                filter(Objects::nonNull).
                findFirst();

    }

    static Method findMethod(Class<?> clazz, String methodName, Class<?>[] argTypes) {
        try {
            return clazz.getDeclaredMethod(methodName, argTypes);
        } catch (NoSuchMethodException e) {

        }

        try { //note: does not find non-public methods in superclass
            return clazz.getMethod(methodName, argTypes);
        } catch (NoSuchMethodException e1) {

        }
        return null;
    }

    public Object apply(Object instance, Object... args) {
        try {
            return m.invoke(instance, args);
        } catch (IllegalAccessException e) {
            e.printStackTrace();
        } catch (Throwable throwable) {
            throwable.printStackTrace();
            throw new RuntimeException(throwable);
        }
        return null;
    }
}