import org.junit.Test;
import testablejava.Helpers;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.Arrays;
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
                IntStream.range(0, 255).
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
            //interface should contain method "apply"
            Optional<Method> applyOption = getMethod(classObjectOption.get(), "apply");
            assertTrue(name + " should have apply()", applyOption.isPresent());
            applyOption.ifPresent(applyMethod -> {
                //which returns R
                assertEquals("R",applyMethod.getGenericReturnType().getTypeName());
                //and takes arguments
                int iFunction = Integer.parseInt(name.substring(name.indexOf("Function")+"Function".length()));
                assertEquals(
                        "type args for " + name,
                        IntStream.range(0,iFunction).mapToObj(i->"T"+(i+1)).collect(toList()),
                        Arrays.stream(applyMethod.getGenericParameterTypes()).map(type -> type.getTypeName()).collect(toList()));
            });

        });
    }
    @Test
    public void emitConsumers() throws Exception {
        Map<String, Optional<Class<?>>> nameToClass =
                IntStream.range(1, 255).
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
            //interface should contain method "accept"
            Optional<Method> acceptOption = getMethod(classObjectOption.get(), "accept");
            assertTrue(name + " should have accept()", acceptOption.isPresent());
            acceptOption.ifPresent(acceptMethod -> {
                //which returns R
                assertEquals("void",acceptMethod.getGenericReturnType().getTypeName());
                //and takes arguments
                int iFunction = Integer.parseInt(name.substring(name.indexOf("Consumer")+"Consumer".length()));
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