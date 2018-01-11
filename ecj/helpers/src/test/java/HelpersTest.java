import org.junit.Test;
import testablejava.Helpers;
import testablejava.HelpersInstrumenter;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.IntStream;

import static java.util.stream.Collectors.toList;
import static java.util.stream.Collectors.toMap;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

public class HelpersTest {
    @Test(expected = IOException.class)
    public void uncheckedThrow() throws Exception {
        Helpers.uncheckedThrow(new IOException(""));
    }
    @Test
    public void emitFunctions() throws Exception {
        Map<String, Optional<Class<?>>> nameToClass =
                IntStream.range(0, HelpersInstrumenter.maxArgs).
                        mapToObj(iArity -> "helpers.Function" + iArity).
                        collect(toMap(
                                interfaceName -> interfaceName,
                                interfaceName -> {
                                    try {
                                        Class<?> cl = Class.forName(interfaceName);
                                        return Optional.of(cl);
                                    } catch (ClassNotFoundException e) {
                                        return Optional.empty();
                                    }
                                }));

        //interface should exist
        nameToClass.forEach( (name, classObjectOption) -> {
            assertTrue(name + " should exist", classObjectOption.isPresent());
        });

        nameToClass.forEach( (name, classObjectOption) -> {
            int iFunction = Integer.parseInt(name.substring(name.indexOf("Function")+"Function".length()));
            //interface should contain method "apply"
            Class<?> classObject = classObjectOption.get();
            //class parameters
            List<String> classArgNames = IntStream.range(0,iFunction).mapToObj(i->"T"+(i+1)).collect(toList());
            classArgNames.add("R");
            assertEquals(
                    "type args for " + name,
                    classArgNames,
                    Arrays.stream(classObject.getTypeParameters()).map(type->type.getName()).collect(toList()));


            Optional<Method> applyOption = getMethod(classObject, "apply");
            assertTrue(name + " should have apply()", applyOption.isPresent());
            applyOption.ifPresent(applyMethod -> {
                //which returns R
                assertEquals("R",applyMethod.getGenericReturnType().getTypeName());
                //and takes arguments

                assertEquals(
                        "arg types for method in " + name,
                        IntStream.range(0,iFunction).mapToObj(i->"T"+(i+1)).collect(toList()),
                        Arrays.stream(applyMethod.getGenericParameterTypes()).map(type -> type.getTypeName()).collect(toList()));
            });

        });
    }
    @Test
    public void emitConsumers() throws Exception {
        Map<String, Optional<Class<?>>> nameToClass =
                IntStream.range(0, HelpersInstrumenter.maxArgs).
                        mapToObj(iArity -> "helpers.Consumer" + iArity).
                        collect(toMap(
                                interfaceName -> interfaceName,
                                interfaceName -> {
                                    try {
                                        Class<?> cl = Class.forName(interfaceName);
                                        return Optional.of(cl);
                                    } catch (ClassNotFoundException e) {
                                        return Optional.empty();
                                    }
                                }));

        //interface should exist
        nameToClass.forEach( (name, classObjectOption) -> {
            assertTrue(name + " should exist", classObjectOption.isPresent());
        });

        nameToClass.forEach( (name, classObjectOption) -> {

            Class<?> classObject = classObjectOption.get();

            int iFunction = Integer.parseInt(name.substring(name.indexOf("Consumer")+"Consumer".length()));

            List<String> classArgNames = IntStream.range(0,iFunction).mapToObj(i->"T"+(i+1)).collect(toList());

            assertEquals(
                    "type args for " + name,
                    classArgNames,
                    Arrays.stream(classObject.getTypeParameters()).map(type->type.getName()).collect(toList()));

            //interface should contain method "accept"
            Optional<Method> acceptOption = getMethod(classObject, "accept");
            assertTrue(name + " should have accept()", acceptOption.isPresent());
            acceptOption.ifPresent(acceptMethod -> {
                //which returns R
                assertEquals("void",acceptMethod.getGenericReturnType().getTypeName());
                //and takes arguments

                assertEquals(
                        "type args for " + name,
                        IntStream.range(0,iFunction).mapToObj(i->"T"+(i+1)).collect(toList()),
                        Arrays.stream(acceptMethod.getGenericParameterTypes()).map(type -> type.getTypeName()).collect(toList()));
            });

        });
    }
    Optional<Method> getMethod(Class<?> classObject, String methodName) {
        return Arrays.stream(classObject.getDeclaredMethods()).
                filter(method -> method.getName().equals(methodName)).
                findFirst();
    }

}
