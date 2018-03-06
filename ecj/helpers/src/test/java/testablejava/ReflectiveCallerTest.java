package testablejava;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

import static java.util.stream.Collectors.joining;
import static org.junit.Assert.*;

public class ReflectiveCallerTest {

    @Test
    public void findMethodDeep() throws Exception {
        assertEquals("isEmpty",ReflectiveCaller.findMethodDeep(String.class,"isEmpty", new Class<?>[]{}).get().getName());
    }
    @Test
    public void findMethodDeep_Missing() throws Exception {
        assertFalse(ReflectiveCaller.findMethodDeep(String.class,"missingMethod", new Class<?>[]{}).isPresent());
    }
    @Test
    public void findMethodDeepOrThrow() throws Exception {
        ReflectiveCaller.findMethodDeepOrThrow(String.class,"isEmpty", new Class<?>[]{});
    }
    @Test
    public void findMethodDeepOrThrow_OnThrow() throws Exception {
        try {
            ReflectiveCaller.findMethodDeepOrThrow(String.class,"missingMethod", new Class<?>[]{});
            fail();
        } catch(RuntimeException ex) {

        }
    }

    @Test
    public void classHierarchy() throws Exception {

        List<Class<?>> acc = new ArrayList<>();
        ReflectiveCaller.classHierarchy(ArrayIndexOutOfBoundsException.class, acc);
        assertEquals("ArrayIndexOutOfBoundsException,IndexOutOfBoundsException,RuntimeException,Exception,Throwable,Object",
                acc.stream().
                        map(cl->cl.getSimpleName()).collect(joining(",")));

    }

    @Test
    public void findMethod() throws Exception {
        assertNotNull(ReflectiveCaller.findMethod(String.class,"isEmpty", new Class<?>[]{}));
    }
    @Test
    public void findMethod_InParent() throws Exception {
        assertNotNull(ReflectiveCaller.findMethod(String.class,"notify", new Class<?>[]{}));
    }
    @Test
    public void findMethod_FailOnProtected() throws Exception {
        assertNull(ReflectiveCaller.findMethod(String.class,"finalize", new Class<?>[]{}));
    }
    @Test
    public void apply() throws Exception {
        ReflectiveCaller caller = new ReflectiveCaller(String.class, "isEmpty");
        assertEquals(false, caller.apply("abc"));
    }
    @Test
    public void applyWithArg() throws Exception {
        ReflectiveCaller caller = new ReflectiveCaller(String.class, "substring", int.class);
        assertEquals("bc", caller.apply("abc", 1));
    }
    @Test
    public void applyProtectedWithArg() throws Exception {
        ReflectiveCaller caller = new ReflectiveCaller(String.class, "finalize");
        caller.apply("abc");
    }
    @Test
    public void applyWithArgs() throws Exception {
        ReflectiveCaller caller = new ReflectiveCaller(String.class, "split", String.class, int.class);
        assertEquals(
                "a,b,c".split(",", 2),
                (String[])caller.apply("a,b,c", ",", 2));
    }
    @Test
    public void applyStaticWithArgs() throws Exception {
        ReflectiveCaller caller = new ReflectiveCaller(String.class, "valueOf", int.class);
        assertEquals(
                "1",
                caller.apply(null, 1));
    }
    @Test
    public void applyWithVarargs() throws Exception {
        ReflectiveCaller caller = new ReflectiveCaller(
                String.class,
                "format",
                 new Class<?>[]{String.class,  Object[].class}
                 );

        assertEquals(
                String.format("%s%d","a",1),
                caller.apply(null, "%s%d", new Object[]{"a", 1}));
    }
}