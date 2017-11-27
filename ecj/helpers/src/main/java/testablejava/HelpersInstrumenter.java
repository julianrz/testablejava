package testablejava;

import net.bytebuddy.ByteBuddy;
import net.bytebuddy.description.annotation.AnnotationDescription;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.description.type.TypeDescription;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.implementation.MethodDelegation;
import net.bytebuddy.jar.asm.Opcodes;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Optional;
import java.util.concurrent.ForkJoinPool;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.toList;
import static net.bytebuddy.matcher.ElementMatchers.named;

public class HelpersInstrumenter {

    public static void rewriteUncheckedThrow(String targetDir) throws IOException, IllegalAccessException, NoSuchMethodException, InvocationTargetException {
        new ByteBuddy()
                .redefine(Helpers.class)
                .modifiers(Opcodes.ACC_PUBLIC)
                .method(named("uncheckedThrow"))
                .intercept(MethodDelegation.to(HelpersTemplate.class))
                .make()
                .saveIn(new File(targetDir));

        checkInstrumentation();
    }

    static void checkInstrumentation() throws NoSuchMethodException, IllegalAccessException, InvocationTargetException {
        try {
            Method method =
                    new ByteBuddy()
                            .redefine(Helpers.class)
                            .name(Helpers.class.getName() + "2")
                            .make()
                            .load(HelpersInstrumenter.class.getClassLoader())
                            .getLoaded()
                            .getDeclaredMethod("uncheckedThrow", Exception.class);

            method.setAccessible(true);
            method.invoke(null, new IOException());

        } catch (Throwable th) {
            if (th instanceof RuntimeException) {
                throw th;
            }
        }
    }

    public static void main(String[] args) throws Exception {
        String targetDir = args[0];
        System.out.println("instrumenting Helpers.uncheckedThrow into " + targetDir);
        rewriteUncheckedThrow(targetDir);

        new File(targetDir, "helpers").mkdir();
        ForkJoinPool forkJoinPool = new ForkJoinPool();
        try {
            forkJoinPool.submit(() -> {
                try {
                    System.out.println("emitting Consumers into " + targetDir);
                    emitConsumers(targetDir);
                    System.out.println("emitting Functions into " + targetDir);
                    emitFunctions(targetDir);

                } catch(Exception ex){
                    ex.printStackTrace(); //should not happen
                }
                    }
            ).get();
        } finally {
            forkJoinPool.shutdown();
        }

    }

    static void emitFunctions(String targetDir) throws Exception {


        Optional<Exception> oneFailure = IntStream.range(0, 255).parallel().
                mapToObj(iFunction -> {
                    try {
                        emitFunction(iFunction, targetDir);
                    } catch (Exception e) {
                        return Optional.of(e);
                    } catch (Throwable throwable){
                        throwable.printStackTrace();
                    }
                    return Optional.<Exception>empty();
                }).
                filter(Optional::isPresent).
                map(Optional::get).
                findFirst();
        if (oneFailure.isPresent())
            throw oneFailure.get();
    }
    static void emitConsumers(String targetDir) throws Exception {
        Optional<Exception> oneFailure = IntStream.range(0, 255).parallel().
                mapToObj(iFunction -> {
                    try {
                        emitConsumer(iFunction, targetDir);
                    } catch (Exception e) {
                        return Optional.of(e);
                    } catch (Throwable throwable){
                        throwable.printStackTrace();
                    }
                    return Optional.<Exception>empty();
                }).
                filter(Optional::isPresent).
                map(Optional::get).
                findFirst();
        if (oneFailure.isPresent())
            throw oneFailure.get();
    }

    static void emitFunction(int iFunction, String targetDir) throws IOException {

        String name = "helpers.Function" + iFunction;
        if (new File(targetDir +"/" + name.replace(".","/") + ".class").exists())
            return;


        DynamicType.Builder<?> builder = new ByteBuddy()
                .makeInterface()
                .name(name)
                .annotateType(AnnotationDescription.Builder.ofType(FunctionalInterface.class).build());

        DynamicType.Builder.TypeVariableDefinition<?> soFar;
        if (iFunction == 0) {
            soFar = builder
                    .typeVariable("R");
        } else {
            soFar = builder
                    .typeVariable("T1");

            for (int iArg = 1; iArg < iFunction; iArg++) {
                soFar = soFar.typeVariable("T" + (1 + iArg));
            }

            soFar = soFar.typeVariable("R");
        }

        TypeDescription.Generic[] parameters =
                IntStream.range(0, iFunction).
                        mapToObj(iArg -> TypeDescription.Generic.Builder.typeVariable("T" + (1 + iArg)).build()).
                        collect(toList()).
                        toArray(new TypeDescription.Generic[iFunction]);

        soFar
                .defineMethod("apply",
                        TypeDescription.Generic.Builder.typeVariable("R").build(),
                        Visibility.PUBLIC) //irrelevant for interfaces
                .withParameters(parameters)
                .withoutCode()

                .make()
                .saveIn(new File(targetDir));
    }
    static void emitConsumer(int iFunction, String targetDir) throws IOException {

        String name = "helpers.Consumer" + iFunction;
        if (new File(targetDir +"/" + name.replace(".","/") + ".class").exists())
            return;


        DynamicType.Builder<?> builder = new ByteBuddy()
                .makeInterface()
                .name(name)
                .annotateType(AnnotationDescription.Builder.ofType(FunctionalInterface.class).build());

        if (iFunction == 0) {
            builder
                    .defineMethod("accept",
                            TypeDescription.VOID,
                            Visibility.PUBLIC) //irrelevant for interfaces
                    .withoutCode()
                    .make()
                    .saveIn(new File(targetDir));
        } else {

            DynamicType.Builder.TypeVariableDefinition<?> soFar = builder.typeVariable("T1");

            for (int iArg = 1; iArg < iFunction; iArg++) {
                soFar = soFar.typeVariable("T" + (1 + iArg));
            }

            TypeDescription.Generic[] parameters =
                    IntStream.range(0, iFunction).
                            mapToObj(iArg -> TypeDescription.Generic.Builder.typeVariable("T" + (1 + iArg)).build()).
                            collect(toList()).
                            toArray(new TypeDescription.Generic[iFunction]);

            soFar
                    .defineMethod("accept",
                            TypeDescription.VOID,
                            Visibility.PUBLIC) //irrelevant for interfaces
                    .withParameters(parameters)
                    .withoutCode()
                    .make()
                    .saveIn(new File(targetDir));
        }
    }
}
