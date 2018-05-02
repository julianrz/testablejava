/*******************************************************************************
 * Copyright (c) 2017-2018 Julian Rozentur
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *******************************************************************************/

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

    static public int maxArgs = 20;//255; //change this to 20 or so for practical reasons when running in dev environment

    public static void main(String[] args) throws Exception {
        String targetDir = args[0];

        System.out.println("instrumenting Helpers.uncheckedThrow into " + targetDir);
        rewriteUncheckedThrow(targetDir);

        new File(targetDir, "helpers").mkdir();

        if (maxArgs == 255)
            System.out.println("\nWarning: will emit functions up to 255 arguments which will take awhile, modify the HelpersInstrumenter.maxArgs value to ~20 in dev environment" + targetDir);

        ForkJoinPool forkJoinPool = new ForkJoinPool();
        try {
            forkJoinPool.submit(() -> {
                        try {
                            long t0 = System.currentTimeMillis();
                            System.out.println("emitting Consumers into " + targetDir);

                            emitConsumers(targetDir, maxArgs);
                            long t1 = System.currentTimeMillis();
                            System.out.printf("->%2.2f sec\n", (t1 - t0) / 1000.0);

                            System.out.println("emitting Functions into " + targetDir);

                            emitFunctions(targetDir, maxArgs);
                            long t2 = System.currentTimeMillis();
                            System.out.printf("->%2.2f sec\n", (t2 - t1) / 1000.0);

                        } catch (Exception ex) {
                            ex.printStackTrace(); //should not happen
                        }
                    }
            ).get();
        } finally {
            forkJoinPool.shutdown();
        }

    }

    static void emitFunctions(String targetDir, int maxArgs) throws Exception {

        int nExtraTypeVariablesRange = 255;

        for (int i = 0; i < nExtraTypeVariablesRange; i++) {

            int nExtraTypeVariables = i;

            Optional<Exception> oneFailure = IntStream.range(0, maxArgs).parallel().
                    mapToObj(iFunction -> {
                        try {
                            emitFunction(iFunction, nExtraTypeVariables, targetDir);
                        } catch (Exception e) {
                            return Optional.of(e);
                        } catch (Throwable throwable) {
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
    }

    static void emitConsumers(String targetDir, int maxArgs) throws Exception {
        int nExtraTypeVariablesRange = 255;

        for (int i = 0; i < nExtraTypeVariablesRange; i++) {

            int nExtraTypeVariables = i;

            Optional<Exception> oneFailure = IntStream.range(0, maxArgs).parallel().
                    mapToObj(iFunction -> {
                        try {
                            emitConsumer(iFunction, nExtraTypeVariables, targetDir);
                        } catch (Exception e) {
                            return Optional.of(e);
                        } catch (Throwable throwable) {
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
    }

    static void emitFunction(int iFunction, int nExtraTypeVars, String targetDir) throws IOException {

        if (nExtraTypeVars > iFunction || iFunction + nExtraTypeVars>255)
            return; //pragmatically since extra type vars needed to cast existing vars, never need more than args

        String name = "helpers.Function" + iFunction + (nExtraTypeVars>0? "_" + nExtraTypeVars : "");

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
                        toArray(new TypeDescription.Generic[0]);

        DynamicType.Builder.MethodDefinition.ExceptionDefinition<?> methodDefinition = soFar
                .defineMethod("apply",
                        TypeDescription.Generic.Builder.typeVariable("R").build(),
                        Visibility.PUBLIC) //irrelevant for interfaces
                .withParameters(parameters);

        DynamicType.Builder.MethodDefinition.ReceiverTypeDefinition<?> readyToMake;

        if (nExtraTypeVars > 0) {
            DynamicType.Builder.MethodDefinition.TypeVariableDefinition.Annotatable<?> methodDefinitionWithTypeVars =
                    methodDefinition.typeVariable("E1");

            for (int i = 1; i < nExtraTypeVars; i++)
                methodDefinitionWithTypeVars = methodDefinitionWithTypeVars.typeVariable("E" + (i + 1));

            readyToMake = methodDefinitionWithTypeVars
                    .withoutCode();
        } else {
            readyToMake = methodDefinition
                    .withoutCode();
        }
        readyToMake
                .make()
                .saveIn(new File(targetDir));
    }
    static void emitConsumer(int iFunction, int nExtraTypeVars, String targetDir) throws IOException {

        if (nExtraTypeVars > iFunction || iFunction + nExtraTypeVars>255)
            return; //pragmatically since extra type vars needed to cast existing vars, never need more than args

        String name = "helpers.Consumer" + iFunction + (nExtraTypeVars>0? "_" + nExtraTypeVars : "");
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

            DynamicType.Builder.MethodDefinition.ExceptionDefinition<?> methodDefinition = soFar
                    .defineMethod("accept",
                            TypeDescription.VOID,
                            Visibility.PUBLIC) //irrelevant for interfaces
                    .withParameters(parameters);

            DynamicType.Builder.MethodDefinition.ReceiverTypeDefinition<?> readyToMake;

            if (nExtraTypeVars > 0) {
                DynamicType.Builder.MethodDefinition.TypeVariableDefinition.Annotatable<?> methodDefinitionWithTypeVars =
                        methodDefinition.typeVariable("E1");

                for (int i = 1; i < nExtraTypeVars; i++)
                    methodDefinitionWithTypeVars = methodDefinitionWithTypeVars.typeVariable("E" + (i + 1));

                readyToMake = methodDefinitionWithTypeVars
                        .withoutCode();
            } else {
                readyToMake = methodDefinition
                        .withoutCode();
            }

            readyToMake
                    .make()
                    .saveIn(new File(targetDir));
        }
    }
}
