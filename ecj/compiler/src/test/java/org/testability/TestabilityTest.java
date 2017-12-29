package org.testability;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.eclipse.jdt.internal.compiler.InstrumentationOptions;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.junit.Test;
import testablejava.CallContext;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.math.BigDecimal;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.*;

import static java.util.stream.Collectors.joining;
import static junit.framework.TestCase.fail;
import static org.junit.Assert.*;
import static org.unitils.reflectionassert.ReflectionAssert.assertReflectionEquals;


/**
 * Created by julianrozentur1 on 6/23/17.
 */
//TODO feature: all object allocations go through static callback inside its class. Typically we redirect via caller, but this case is different. Allows to find and instrument objects easier if multiple creators exist. Note: handle reflective create
//TODO feature: redirect full expressions such as System.out.println, otherwise System.out will still be computed and potentially ignored
public class TestabilityTest extends BaseTest {

    public static final ImmutableSet<InstrumentationOptions> INSERT_REDIRECTORS_ONLY = ImmutableSet.of(InstrumentationOptions.INSERT_REDIRECTORS);
    public static final ImmutableSet<InstrumentationOptions> INSERT_LISTENERS_ONLY = ImmutableSet.of(InstrumentationOptions.INSERT_LISTENERS);
    public static final ImmutableSet<InstrumentationOptions> INSERT_ALL = ImmutableSet.of(InstrumentationOptions.INSERT_REDIRECTORS, InstrumentationOptions.INSERT_LISTENERS);
    public static final ImmutableSet<InstrumentationOptions> INSERT_NONE = ImmutableSet.of();

    @Test
    public void testPackageCollideWithType() throws Exception {

        String[] task = {
                "X.java",
                "package a;\n\n" +
                "public class X {\n}",

                "A.java",
                "package X;\n" +
                "public class A {\n}"

        };
        String expectedOutput = task[1];

        Map<String, List<String>> stringListMap = compileAndDisassemble(task, INSERT_NONE);
        assertEquals(expectedOutput, stringListMap.get("a.X").stream().collect(joining("\n")));
    }
    @Test
    public void testReproduction() throws Exception {
        //we are forcing existing clinit by having a static field with initializer
        String[] task = {
                "timer/Timer.java",
                "package timer;\n" +
                "public class Timer {\n" +
                        "}"
        };

        compileAndDisassemble(task, INSERT_LISTENERS_ONLY);
    }
    @Test
    public void testTestabilityInjectFunctionField_ForNewOperatorCallbackWithExistingClinit() throws Exception {
        //we are forcing existing clinit by having a static field with initializer
        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	static int i=1;\n" +
                        "	X() {dontredirect:System.out.println();}" +
                        "}"
        };

        String expectedOutput =
                "import java.util.function.Consumer;\n\n" +
                "public class X {\n" +
                        "   static int i = 1;\n" +
                        "   public static Consumer<X> $$preCreate = (var0) -> {\n" +
                        "   };\n" +
                        "   public static Consumer<X> $$postCreate = (var0) -> {\n" +
                        "   };\n\n" +
                        "   X() {\n" +
                        "      $$preCreate.accept(this);\n" +
                        "      System.out.println();\n" +
                        "      $$postCreate.accept(this);\n" +
                        "   }\n" +
                        "}";
        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_LISTENERS_ONLY).get("X").stream().collect(joining("\n")));
    }
    @Test
    public void testTestabilityInjectFunctionField_ForNewOperatorCallbackWithMissingClinit() throws Exception {
        String[] task = {
                "X.java",
                "import java.util.function.Consumer;\n\n" +
                "public class X {\n" +
                        "	X() {dontredirect:System.out.println();}" +
                        "}\n"
        };

        String expectedOutput =
                "import java.util.function.Consumer;\n\n" +
                "public class X {\n" +
                        "   public static Consumer<X> $$preCreate = (var0) -> {\n" +
                        "   };\n" +
                        "   public static Consumer<X> $$postCreate = (var0) -> {\n" +
                        "   };\n\n" +
                        "   X() {\n" +
                        "      $$preCreate.accept(this);\n" +
                        "      System.out.println();\n" +
                        "      $$postCreate.accept(this);\n" +
                        "   }\n" +
                        "}";
        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_LISTENERS_ONLY).get("X").stream().collect(joining("\n")));
    }
    @Test
    public void testTestabilityInjectFunctionField_ForNewOperatorCallbackFromAnonymousInnerClass() throws Exception {

        String[] task = {
                "X.java",
                "import java.util.Comparator;\n" +
                "import java.util.function.Consumer;\n\n" +
                        "public class X {\n" +
                        "	X() {dontredirect:System.out.println();}\n"+
                        "	void fn() {new Comparator<String>(){\n" +
                        "            @Override\n" +
                        "            public int compare(String o1, String o2) {\n" +
                        "                return o1.compareTo(o2);\n" +
                        "            }\n" +
                        "        };}" +
                        "}\n"
        };

        String expectedOutput =
                "import X.1;\n" +
                "import java.util.Comparator;\n" +
                "import java.util.function.Consumer;\n\n" +
                        "public class X {\n" +
                        "   public static Consumer<X> $$preCreate = (var0) -> {\n" +
                        "   };\n" +
                        "   public static Consumer<X> $$postCreate = (var0) -> {\n" +
                        "   };\n" +
                        "   public static Consumer<Comparator<String>> $$Comparator_String_$preCreate = (var0) -> {\n" +
                        "   };\n" +
                        "   public static Consumer<Comparator<String>> $$Comparator_String_$postCreate = (var0) -> {\n" +
                        "   };\n\n" +
                        "   X() {\n" +
                        "      $$preCreate.accept(this);\n" +
                        "      System.out.println();\n" +
                        "      $$postCreate.accept(this);\n" +
                        "   }\n\n" +
                        "   void fn() {\n" +
                        "      new 1(this);\n" +
                        "   }\n" +
                        "}";
        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_LISTENERS_ONLY).get("X").stream().collect(joining("\n")));
    }
    @Test
    public void testTestabilityInjectFunctionField_ForNewOperatorCallbackFromNamedInnerClass() throws Exception {

        String[] task = {
                "X.java",
                "import java.util.Comparator;\n" +
                        "import java.util.function.Consumer;\n\n" +
                        "public class X {\n" +
                        "	public X() {dontredirect:System.out.println();}\n"+
                        "	void fn() {" +
                        "      class C<T> implements Comparator<T> {\n" +
                        "         @Override\n" +
                        "         public int compare(T o1, T o2) {\n" +
                        "            return -1;\n" +
                        "         }\n" +
                        "      };\n" +
                        "      new C<String>();" +
                        "   }\n" +
                        "}\n",
                "Y.java",
                "import java.util.List;\n" +
                "import java.util.ArrayList;\n" +
                        "public class Y {\n" +
                        "	static List<Object> instances = new ArrayList<>();\n" +
                        "	static List<Object> setAndTest() {\n" +
                        "     X.$$C_java$lang$String_$postCreate = inst -> instances.add(inst);\n" +
                        "     new X().fn();\n" +
                        "     return instances;\n" +
                        "   }\n" +
                        "}\n"
        };

        String expectedOutput =
                "import X.1C;\n" +
                        "import java.util.function.Consumer;\n\n" +
                        "public class X {\n" +
                        "   public static Consumer<X> $$preCreate = (var0) -> {\n" +
                        "   };\n" +
                        "   public static Consumer<X> $$postCreate = (var0) -> {\n" +
                        "   };\n" +
                        "   public static Consumer<1C<String>> $$C_java$lang$String_$preCreate = (var0) -> {\n" +
                        "   };\n" +
                        "   public static Consumer<1C<String>> $$C_java$lang$String_$postCreate = (var0) -> {\n" +
                        "   };\n\n" +
                        "   public X() {\n" +
                        "      $$preCreate.accept(this);\n" +
                        "      System.out.println();\n" +
                        "      $$postCreate.accept(this);\n" +
                        "   }\n\n" +
                        "   void fn() {\n" +
                        "      new 1C(this);\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_LISTENERS_ONLY);

        invokeCompiledMethod("X", "fn");
        List<Object> instances = (List<Object>) invokeCompiledMethod("Y", "setAndTest");
        assertEquals(1, instances.size());
        assertEquals("C", instances.get(0).getClass().getSimpleName());

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));
    }

    @Test
    public void testTestabilityInjectFunctionField_NotExpandingInsideRedirectedFields() throws Exception {

        String[] task = {
                "X.java",
                "import helpers.Function0;\n\n" +
                "public class X {\n" +
                        "   Function0<Void> $$java$io$PrintStream$println = () -> {\n" +
                        "      System.out.println();\n" +
                        "      return null;\n" +
                        "   };\n" +
                        "}"
        };
        String expectedOutput = task[1];

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));
    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallNoArgs() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(){System.out.println();}" +
                        "}\n"
        };
        String expectedOutput =
                "import helpers.Consumer1;\n" +
                "import java.io.PrintStream;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                "   public static Consumer1<CallContext<X, PrintStream>> $$PrintStream$println = (var0) -> {\n" +
                "      ((PrintStream)var0.calledClassInstance).println();\n" +
                "   };\n\n" +
                "   void fn() {\n" +
                "      $$PrintStream$println.accept(new CallContext(\"X\", \"java.io.PrintStream\", this, System.out));\n" +
                "   }\n" +
                "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

    }
    @Test
    public void testTestabilityInjectFunctionField_AccountForStaticInitializers() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "   static int ct=0;" +
                        "   static {ct++;}" +
                        "	int fn(){System.out.println();return X.ct;}" +
                        "}\n"
        };
        String expectedOutput =
                "import helpers.Consumer1;\n" +
                        "import java.io.PrintStream;\n" +
                        "import testablejava.CallContext;\n\n" +
                        "public class X {\n" +
                        "   static int ct = 0;\n" +
                        "   public static Consumer1<CallContext<X, PrintStream>> $$PrintStream$println;\n\n" +
                        "   static {\n" +
                        "      ++ct;\n" +
                        "      $$PrintStream$println = (var0) -> {\n" +
                        "         ((PrintStream)var0.calledClassInstance).println();\n" +
                        "      };\n" +
                        "   }\n\n" +
                        "   int fn() {\n" +
                        "      $$PrintStream$println.accept(new CallContext(\"X\", \"java.io.PrintStream\", this, System.out));\n" +
                        "      return ct;\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));
        assertEquals("static initialization block was executed",1, invokeCompiledMethod("X","fn"));

    }
    @Test
    public void testTestabilityInjectFunctionField_ForLocalCallNoArgs() throws Exception {
        //passes in instance for consistency. Also applicable for parent call

        //?? is $$X before $callee redundant here? $$ for consistency? $ always before name, $$$callee?
        String[] task = {
                "X.java",
                "public class X {\n" +
                "   String callee(){return \"1\";}\n" +
                "	String fn(){\n" +
                "      return callee();" +
                "   }\n" +
                "}\n"
        };
        String expectedOutput =
                "import helpers.Function1;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                "   public static Function1<CallContext<X, X>, String> $$X$callee = (var0) -> {\n" +
                "      return ((X)var0.calledClassInstance).callee();\n" +
                "   };\n\n" +
                "   String callee() {\n" +
                "      return \"1\";\n" +
                "   }\n\n" +
                "   String fn() {\n" +
                "      return (String)$$X$callee.apply(new CallContext(\"X\", \"X\", this, this));\n" +
                "   }\n" +
                "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));
        Object actual = invokeCompiledMethod("X", "fn");
        assertEquals("1", actual);

    }
    @Test
    public void testTestabilityInjectFunctionField_ForLocalCallNoArgs_FromStaticContext() throws Exception {
        //need static fields to be created/initialized

        String[] task = {
                "X.java",

                "public class X {\n" +
                        "   static Integer callee(){return 1;}\n" +
                        "	static Integer fn(){\n" +
                        "      return callee();" +
                        "   }\n" +
                        "}\n"
        };
        String expectedOutput =

                "import helpers.Function1;\n" +
                "import testablejava.CallContext;\n\n" +
                        "public class X {\n" +
                        "   public static Function1<CallContext<X, X>, Integer> $$X$callee = (var0) -> {\n" +
                        "      return callee();\n" +
                        "   };\n\n" +
                        "   static Integer callee() {\n" +
                        "      return Integer.valueOf(1);\n" +
                        "   }\n\n" +
                        "   static Integer fn() {\n" +
                        "      return (Integer)$$X$callee.apply(new CallContext(\"X\", \"X\", (Object)null, (Object)null));\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));
        Object actual = invokeCompiledMethod("X", "fn");
        assertEquals(1, actual);

    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallWithClassReceiver() throws Exception {


        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(){Integer.valueOf(2);}" +
                        "   static public void exec() throws Exception {dontredirect: new X().fn();}" +
                        "}\n"
        };
        String expectedOutput =
                        "import helpers.Function2;\n" +
                        "import testablejava.CallContext;\n\n" +
                        "public class X {\n" +
                        "   public static Function2<CallContext<X, Integer>, Integer, Integer> $$Integer$valueOf$$I = (var0, var1) -> {\n" +
                        "      return Integer.valueOf(var1.intValue());\n" +
                        "   };\n\n" +
                        "   void fn() {\n" +
                        "      $$Integer$valueOf$$I.apply(new CallContext(\"X\", \"java.lang.Integer\", this, (Object)null), Integer.valueOf(2));\n" +
                        "   }\n\n" +
                        "   public static void exec() throws Exception {\n" +
                        "      (new X()).fn();\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        main.invoke(null);

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallWithClassReceiver_StaticallyImported() throws Exception {

        //here receiver appears empty, should not resolve to 'this'

        String[] task = {
                "X.java",
                "import static java.lang.Integer.valueOf;" +
                "public class X {\n" +
                        "	void fn(){valueOf(2);}" +
                        "   static public void exec() throws Exception {dontredirect: new X().fn();}" +
                        "}\n"
        };
        String expectedOutput =
                "import helpers.Function2;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                        "   public static Function2<CallContext<X, Integer>, Integer, Integer> $$Integer$valueOf$$I = (var0, var1) -> {\n" +
                        "      return Integer.valueOf(var1.intValue());\n" +
                        "   };\n\n" +
                        "   void fn() {\n" +
                        "      $$Integer$valueOf$$I.apply(new CallContext(\"X\", \"java.lang.Integer\", this, (Object)null), Integer.valueOf(2));\n" +
                        "   }\n\n" +
                        "   public static void exec() throws Exception {\n" +
                        "      (new X()).fn();\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        main.invoke(null);

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallWithArgs() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(){Integer.getInteger(\"1\", Integer.valueOf(2));}" +
                        "}\n"
        };

        String expectedOutput =
                "import helpers.Function2;\n" +
                "import helpers.Function3;\n" +
                "import testablejava.CallContext;\n\n" +
                        "public class X {\n" +
                        "   public static Function3<CallContext<X, Integer>, String, Integer, Integer> $$Integer$getInteger$$String$Integer = (var0, var1, var2) -> {\n" +
                        "      return Integer.getInteger(var1, var2);\n" +
                        "   };\n" +
                        "   public static Function2<CallContext<X, Integer>, Integer, Integer> $$Integer$valueOf$$I = (var0, var1) -> {\n" +
                        "      return Integer.valueOf(var1.intValue());\n" +
                        "   };\n\n" +
                        "   void fn() {\n" +
                        "      $$Integer$getInteger$$String$Integer.apply("+
                        "new CallContext(\"X\", \"java.lang.Integer\", this, (Object)null), " +
                        "\"1\", " +
                        "(Integer)$$Integer$valueOf$$I.apply(" +
                        "new CallContext(\"X\", \"java.lang.Integer\", this, (Object)null), " +
                        "Integer.valueOf(2)));\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallsWithOverload() throws Exception {

        String[] task = {
                "X.java",
                "import java.util.*;\n" +
                "public class X {\n" +

                        "	void fn(){" +
                        "       StringBuffer buf0 = new StringBuffer(10);" +
                        "       StringBuffer buf = new StringBuffer();" +
                        "       buf.append(\"x\");" +
                        "       buf.append(1);" +
                        "   }" +
                       "}\n"
        };

        String expectedOutput =
                "import helpers.Function1;\n" +
                "import helpers.Function2;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                        "   public static Function2<CallContext<X, StringBuffer>, Integer, StringBuffer> $$StringBuffer$append$$I = (var0, var1) -> {\n" +
                        "      return ((StringBuffer)var0.calledClassInstance).append(var1);\n" +
                        "   };\n" +
                        "   public static Function2<CallContext<X, StringBuffer>, String, StringBuffer> $$StringBuffer$append$$String = (var0, var1) -> {\n" +
                        "      return ((StringBuffer)var0.calledClassInstance).append(var1);\n" +
                        "   };\n" +
                        "   public static Function2<CallContext<X, StringBuffer>, Integer, StringBuffer> $$StringBuffer$new$$I = (var0, var1) -> {\n" +
                        "      return new StringBuffer(var1.intValue());\n" +
                        "   };\n" +
                        "   public static Function1<CallContext<X, StringBuffer>, StringBuffer> $$StringBuffer$new = (var0) -> {\n" +
                        "      return new StringBuffer();\n" +
                        "   };\n" +
                        "\n" +
                        "   void fn() {\n" +
                        "      $$StringBuffer$new$$I.apply(new CallContext(\"X\", \"java.lang.StringBuffer\", this, (Object)null), Integer.valueOf(10));\n" +
                        "      StringBuffer var1 = (StringBuffer)$$StringBuffer$new.apply(new CallContext(\"X\", \"java.lang.StringBuffer\", this, (Object)null));\n" +
                        "      $$StringBuffer$append$$String.apply(new CallContext(\"X\", \"java.lang.StringBuffer\", this, var1), \"x\");\n" +
                        "      $$StringBuffer$append$$I.apply(new CallContext(\"X\", \"java.lang.StringBuffer\", this, var1), Integer.valueOf(1));\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

    }

    @Test
    public void testTestabilityInjectFunctionField_ForNewOperatorWithNullArg() throws Exception {
        String[] task = {
                "Base.java",
                "public class Base {}\n",

                "T.java",
                "public class T extends Base {}\n",

                "Y.java",
                "public class Y {\n" +
                "   Y(T t){}\n" +
                "}",

                "X.java",
                "public class X {\n" +
                "	Y fn(){" +
                "       T t = null;\n" +
                "       return new Y(t);\n" +
                "   }" +
                "}\n"
        };

        String expectedOutput = "import helpers.Function2;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                "   public static Function2<CallContext<X, Y>, T, Y> $$Y$new$$T = (var0, var1) -> {\n" +
                "      return new Y(var1);\n" +
                "   };\n" +
                "\n" +
                "   Y fn() {\n" +
                "      Object var1 = null;\n" +
                "      return (Y)$$Y$new$$T.apply(new CallContext(\"X\", \"Y\", this, (Object)null), var1);\n" +
                "   }\n" +
                "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

        Object actual = invokeCompiledMethod("X","fn");
        assertEquals("Y", actual.getClass().getName());

    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallWithNullArg() throws Exception {
        //Note: apparent type T and not its base should be used to name the field
        String[] task = {
                "Base.java",
                "public class Base {}\n",
                "T.java",
                "public class T extends Base {}\n",

                "Y.java",
                "public class Y {\n" +
                        "   String fn(Base b){return \"1\";}\n" +
                        "}",
                "X.java",
                        "public class X {\n" +
                        "	String fn(){\n" +
                        "       Y y;\n" +
                        "       dontredirect: y = new Y();\n" +
                        "       T t = null;\n" +
                        "       return y.fn(t);\n" +
                        "   }" +
                        "}\n"
        };
//NOTE: decompiler somehow reports Object t, its type is lost
        String expectedOutput =
                "import helpers.Function2;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                "   public static Function2<CallContext<X, Y>, T, String> $$Y$fn$$T = (var0, var1) -> {\n" +
                "      return ((Y)var0.calledClassInstance).fn(var1);\n" +
                "   };\n\n" +
                "   String fn() {\n" +
                "      Y var1 = new Y();\n" +
                "      Object var2 = null;\n" +
                "      return (String)$$Y$fn$$T.apply(new CallContext(\"X\", \"Y\", this, var1), var2);\n" +
                "   }\n" +
                "}";


        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));
        assertEquals("1", invokeCompiledMethod("X", "fn"));

    }

    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallsWithOverloadTakingCompilerType() throws Exception {

        String[] task = {
                "Base.java",
                "public class Base {\n" +
                        "	String fn(){ return null; }" +
                        "}\n",
                "Derived.java",
                "public class Derived extends Base {\n" +
                        "}\n",
                "X.java",
                        "public class X {\n" +
                        "	String fn(){" +
                        "       Derived d;" +
                        "       dontredirect: d = new Derived();" +
                        "       return d.fn();" +
                        "   }" +
                        "}\n",
                "Y.java",
                "public class Y {\n" +
                        "	String fn(){" +
                        "       X.$$Derived$fn = (ctx) -> {return \"\"+ctx.callingClassInstance.getClass()+\",\"+ ctx.calledClassInstance.getClass();};" +
                        "       return new X().fn();" +
                        "   }" +
                        "}\n"
        };

        String expectedOutput =
                "import helpers.Function1;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "public class X {\n" +
                        "   public static Function1<CallContext<X, Derived>, String> $$Derived$fn = (var0) -> {\n" +
                        "      return ((Derived)var0.calledClassInstance).fn();\n" +
                        "   };\n" +
                        "\n" +
                        "   String fn() {\n" +
                        "      Derived var1 = new Derived();\n" +
                        "      return (String)$$Derived$fn.apply(new CallContext(\"X\", \"Derived\", this, var1));\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

        Object className = invokeCompiledMethod("Y", "fn");
        assertEquals("class X,class Derived", className);

    }

    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallsWithOverloadGenerics() throws Exception {
        String[] task = {
                "X.java",
                "import java.util.*;\n" +
                        "public class X {\n" +
                        "	void fn(){" +
                        "       List<String> var1 = new ArrayList<>();" +
                        "       var1.add(\"1\");" +
                        "       List<String> var2 = new ArrayList<>(var1);" +
                        "       var2.addAll(var1);" +
                        "       List<Integer> var3 = new ArrayList<>();" +
                        "       var3.add(2);" +
                        "       List<Integer> var4 = new ArrayList<>(var3);" +
                        "       var4.addAll(var3);" +
                        "   }" +
                        "}\n"
        };

        String expectedOutput =
                "import helpers.Function1;\n" +
                "import helpers.Function2;\n" +
                "import java.util.ArrayList;\n" +
                "import java.util.List;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                        "   public static Function2<CallContext<X, ArrayList<Integer>>, List<Integer>, ArrayList<Integer>> $$java$util$ArrayList_java$lang$Integer_$new$$List = (var0, var1) -> {\n" +
                        "      return new ArrayList(var1);\n" +
                        "   };\n" +
                        "   public static Function2<CallContext<X, List<Integer>>, Integer, Boolean> $$java$util$List_java$lang$Integer_$add$$I = (var0, var1) -> {\n" +
                        "      return Boolean.valueOf(((List)var0.calledClassInstance).add(var1));\n" +
                        "   };\n" +
                        "   public static Function2<CallContext<X, List<Integer>>, List<Integer>, Boolean> $$java$util$List_java$lang$Integer_$addAll$$List = (var0, var1) -> {\n" +
                        "      return Boolean.valueOf(((List)var0.calledClassInstance).addAll(var1));\n" +
                        "   };\n" +
                        "   public static Function2<CallContext<X, ArrayList<String>>, List<String>, ArrayList<String>> $$java$util$ArrayList_java$lang$String_$new$$List = (var0, var1) -> {\n" +
                        "      return new ArrayList(var1);\n" +
                        "   };\n" +
                        "   public static Function1<CallContext<X, ArrayList<String>>, ArrayList<String>> $$java$util$ArrayList_java$lang$String_$new = (var0) -> {\n" +
                        "      return new ArrayList();\n" +
                        "   };\n" +
                        "   public static Function2<CallContext<X, List<String>>, List<String>, Boolean> $$java$util$List_java$lang$String_$addAll$$List = (var0, var1) -> {\n" +
                        "      return Boolean.valueOf(((List)var0.calledClassInstance).addAll(var1));\n" +
                        "   };\n" +
                        "   public static Function1<CallContext<X, ArrayList<Integer>>, ArrayList<Integer>> $$java$util$ArrayList_java$lang$Integer_$new = (var0) -> {\n" +
                        "      return new ArrayList();\n" +
                        "   };\n" +
                        "   public static Function2<CallContext<X, List<String>>, String, Boolean> $$java$util$List_java$lang$String_$add$$String = (var0, var1) -> {\n" +
                        "      return Boolean.valueOf(((List)var0.calledClassInstance).add(var1));\n" +
                        "   };\n" +
                        "\n" +
                        "   void fn() {\n" +
                        "      ArrayList var1 = (ArrayList)$$java$util$ArrayList_java$lang$String_$new.apply(new CallContext(\"X\", \"java.util.ArrayList<java.lang.String>\", this, (Object)null));\n" +
                        "      $$java$util$List_java$lang$String_$add$$String.apply(new CallContext(\"X\", \"java.util.List<java.lang.String>\", this, var1), \"1\");\n" +
                        "      ArrayList var2 = (ArrayList)$$java$util$ArrayList_java$lang$String_$new$$List.apply(new CallContext(\"X\", \"java.util.ArrayList<java.lang.String>\", this, (Object)null), var1);\n" +
                        "      $$java$util$List_java$lang$String_$addAll$$List.apply(new CallContext(\"X\", \"java.util.List<java.lang.String>\", this, var2), var1);\n" +
                        "      ArrayList var3 = (ArrayList)$$java$util$ArrayList_java$lang$Integer_$new.apply(new CallContext(\"X\", \"java.util.ArrayList<java.lang.Integer>\", this, (Object)null));\n" +
                        "      $$java$util$List_java$lang$Integer_$add$$I.apply(new CallContext(\"X\", \"java.util.List<java.lang.Integer>\", this, var3), Integer.valueOf(2));\n" +
                        "      ArrayList var4 = (ArrayList)$$java$util$ArrayList_java$lang$Integer_$new$$List.apply(new CallContext(\"X\", \"java.util.ArrayList<java.lang.Integer>\", this, (Object)null), var3);\n" +
                        "      $$java$util$List_java$lang$Integer_$addAll$$List.apply(new CallContext(\"X\", \"java.util.List<java.lang.Integer>\", this, var4), var3);\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));
        invokeCompiledMethod("X","fn");

    }

    @Test
    public void testTestabilityInjectFunctionField_ForLocalCallNoArgsInsideGenericType() throws Exception {

        String[] task = {
                "X.java",
                "import java.util.Comparator;\n" +
                "public class X {\n" +
                        "   int callee(){return 1;}\n" +
                        "	int fn(){\n" +
                        "      class C<T> implements Comparator<T> {\n" +
                        "         @Override\n" +
                        "         public int compare(T o1, T o2) {\n" +
                        "            return callee();\n" +
                        "         }\n" +
                        "      };\n" +
                        "      dontredirect: return new C<String>().compare(\"a\",\"b\");\n" +
                        "   }\n" +
                        "}\n",
                "Y.java",
                        "public class Y {\n" +
                        "   static int mockAndTest(){" +
                        "     X.$$X$callee = (ctx) -> 2;" +
                        "     return new X().fn();" +
                        "   }" +
                        "}"
        };
        String expectedOutput =
                "import X.1C;\n"+
                "import helpers.Function1;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                "   public static Function1<CallContext<1C, X>, Integer> $$X$callee = (var0) -> {\n" +
                "      return Integer.valueOf(((X)var0.calledClassInstance).callee());\n" +
                "   };\n\n" +
                "   int callee() {\n" +
                "      return 1;\n" +
                "   }\n\n" +
                "   int fn() {\n" +
                "      return (new 1C(this)).compare(\"a\", \"b\");\n" +
                "   }\n" +
                "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

        assertEquals(1, invokeCompiledMethod("X", "fn"));
        assertEquals(2, invokeCompiledMethod("Y", "mockAndTest"));

    }

    @Test
    public void testHasUniqueMatrix() throws Exception {
//TODO move to Util test
        String[][][][] inputShortLongExpectedCombos = {
                {//short descr in col1,2 is unique, col1,2,3
                        {
                                {"y","$fn"},
                                {"y","$fn"},
                        },
                        {
                                {"b.y",""},
                                {"a.y",""},
                        },
                        {
                                {"b.y","$fn"},
                                {"a.y","$fn"},
                        }
                },

                {//short descr in col1,2 is unique, col1,2,3
                        {
                                {"y","$fn",""},
                                {"y","$fn",""},
                                {"y","$new",""},
                                {"y","$new",""},
                        },
                        {
                                {"b.y","$FN",""},
                                {"a.y","$FN",""},
                                {"b.y","$NEW",""},
                                {"a.y","$NEW",""},
                        },
                        {
                                {"b.y","$fn",""},
                                {"a.y","$fn",""},
                                {"b.y","$new",""},
                                {"a.y","$new",""},
                        }
                },

                {//short descr in col1,2 is unique, col1,2,3
                        {
                                {"a","b","c"},
                                {"a","b","d"},
                        },
                        {
                                {"P1","Q1","R1"},
                                {"P2","Q2","R2"},
                        },
                        {
                                {"a","b","c"},
                                {"a","b","d"},
                        }
                },
                {//short descr in col1 is unique, use short descr
                        {
                                {"a"},
                                {"b"},
                        },
                        {
                                {"P"},
                                {"Q"},
                        },
                        {
                                {"a"},
                                {"b"},
                        }
                },
                {//short descr in col1 is not unique, use prefix
                        {
                                {"a"},
                                {"a"},
                        },
                        {
                                {"P"},
                                {"Q"},
                        },
                        {
                                {"P"},
                                {"Q"},
                        }
                },
                { //short descr in col1 is unique
                        {
                                {"a", "c"},
                                {"b", "d"},
                        },
                        {
                                {"P1", "Q1"},
                                {"P2", "Q2"},
                        },
                        {
                                {"a", "c"},
                                {"b", "d"},
                        }
                },

                { //short and long descr in col1 is not unique, but subsequent cols make it unique by short descr
                        {
                                {"a", "c"},
                                {"a", "d"},
                        },
                        {
                                {"P1", "Q1"},
                                {"P2", "Q2"},
                        },
                        {
                                {"a", "c"},
                                {"a", "d"},
                        }
                },



                {//short descr in col1,2 is unique, col1,2,3
                        {
                                {"a","b","c"},
                                {"a","b","d"},
                                {"x","z","q"},
                                {"y","t","r"},
                        },
                        {
                                {"A1","B1","C1"},
                                {"A2","B2","D1"},
                                {"X1","Z1","Q1"},
                                {"Y1","T1","R1"},
                        },
                        {
                                {"a","b","c"},
                                {"a","b","d"},
                                {"x","z","q"},
                                {"y","t","r"},

                        }
                },
                {//revisit descriptors needed
                        {

                                {"List", "$add", "$$", "I"},
                                {"List", "$addAll", "$$", "List"},
                                {"List", "$add", "$$", "String"},
                                {"List", "$addAll", "$$", "List"},

                                {"ArrayList", "$new", "$$", "List"},
                                {"ArrayList", "$new", "", ""},
                                {"ArrayList", "$new", "$$", "List"},
                                {"ArrayList", "$new", "", ""}
                        },
                        {

                                {"java$util$List_java$lang$Integer", "$add", "$$", "I"},
                                {"java$util$List_java$lang$Integer", "$addAll", "$$", "java$util$List"},
                                {"java$util$List_java$lang$String", "$add", "$$", "java$lang$String"},
                                {"java$util$List_java$lang$String", "$addAll", "$$", "java$util$List"},
                                {"java$util$ArrayList_java$lang$Integer", "$new", "$$", "java$util$List"},
                                {"java$util$ArrayList_java$lang$Integer", "$new", "", ""},
                                {"java$util$ArrayList_java$lang$String", "$new", "$$", "java$util$List"},
                                {"java$util$ArrayList_java$lang$String", "$new", "", ""}
                        },
                        {

                                {"java$util$List_java$lang$Integer", "$add", "$$", "I"},
                                {"java$util$List_java$lang$Integer", "$addAll", "$$", "List"},
                                {"java$util$List_java$lang$String", "$add", "$$", "String"},
                                {"java$util$List_java$lang$String", "$addAll", "$$", "List"},
                                {"java$util$ArrayList_java$lang$Integer", "$new", "$$", "List"},
                                {"java$util$ArrayList_java$lang$Integer", "$new", "", ""},
                                {"java$util$ArrayList_java$lang$String", "$new", "$$", "List"},
                                {"java$util$ArrayList_java$lang$String", "$new", "", ""}
                        },
                }

        };

        int iCase = 0;
        for (String [][][] inputShortLongExpected: inputShortLongExpectedCombos) {
            String[][] inputShort = inputShortLongExpected[0];
            String[][] inputLongPrefix = inputShortLongExpected[1];
            String[][] expected = inputShortLongExpected[2];

            String caseId = "case" + (iCase++);

            Optional<String[][]> actual = Util.uniqueMatrix(inputShort, inputLongPrefix);

            assertTrue(caseId + " return value", actual.isPresent());

            assertReflectionEquals(caseId, expected, actual.get());
        }
    }
    @Test
    public void testUniqueMatrix_NonUnique() throws Exception {

        String[][][] inputShortLong = {

                {
                        {"a"},
                        {"a"},
                },
                {
                        {"A"},
                        {"A"},
                },

        };

        String[][] inputShort = inputShortLong[0];
        String[][] inputPrefix = inputShortLong[1];

        assertFalse(Util.uniqueMatrix(inputShort, inputPrefix).isPresent());
    }
    @Test
    public void testUniqueMatrix_Unique_WhenEmpty() throws Exception {

        String[][][] inputShortPrefix = {

                {

                },
                {

                },

        };

        String[][] inputShort = inputShortPrefix[0];
        String[][] inputPrefix = inputShortPrefix[1];

        assertTrue(Util.uniqueMatrix(inputShort, inputPrefix).isPresent());

    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallWithArgsCast() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	public int fn(){int i1 = Integer.MAX_VALUE; long l2 = 2L; return Long.compare(i1, l2);}" +
                        "}\n"
        };

        String expectedOutput =
                "import helpers.Function3;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                        "   public static Function3<CallContext<X, Long>, Integer, Long, Integer> $$Long$compare$$I$J = (var0, var1, var2) -> {\n" +
                        "      return Integer.valueOf(Long.compare((long)var1.intValue(), var2.longValue()));\n" +
                        "   };\n\n" +
                        "   public int fn() {\n" +
                        "      int var1 = " + Integer.MAX_VALUE +";\n" +
                        "      long var2 = 2L;\n" +
                        "      return ((Integer)$$Long$compare$$I$J.apply(new CallContext(\"X\", \"java.lang.Long\", this, (Object)null), Integer.valueOf(var1), Long.valueOf(var2))).intValue();\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        Object actual = invokeCompiledMethod("X","fn");
        assertEquals(1, actual);

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

    }
    @Test
    public void testTestabilityInjectFunctionField_ForNewOperatorWithArgsCast() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	public Long fn(){int i1 = Integer.MAX_VALUE; return Long.valueOf(i1);}" +
                        "}\n"
        };

        String expectedOutput =
                "import helpers.Function2;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                        "   public static Function2<CallContext<X, Long>, Integer, Long> $$Long$valueOf$$I = (var0, var1) -> {\n" +
                        "      return Long.valueOf((long)var1.intValue());\n" +
                        "   };\n\n" +
                        "   public Long fn() {\n" +
                        "      int var1 = " + Integer.MAX_VALUE + ";\n" +
                        "      return (Long)$$Long$valueOf$$I.apply(new CallContext(\"X\", \"java.lang.Long\", this, (Object)null), Integer.valueOf(var1));\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        Object actual = invokeCompiledMethod("X", "fn");

        assertEquals((long)Integer.MAX_VALUE, actual);


        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallWithArgsCastToIntToChar() throws Exception {

        //here for some reason in the original call an int cast is done,
        // resulting in java.lang.ClassCastException: java.lang.Integer cannot be cast to java.lang.Character

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	public int fn(){char c1 = '1', c2 = '2'; return Character.compare(c1, c2);}" +
                        "}\n"
        };

        String expectedOutput =
                "import helpers.Function3;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                        "   public static Function3<CallContext<X, Character>, Character, Character, Integer> $$Character$compare$$C$C = (var0, var1, var2) -> {\n" +
                        "      return Integer.valueOf(Character.compare(var1.charValue(), var2.charValue()));\n" +
                        "   };\n\n" +
                        "   public int fn() {\n" +
                        "      char var1 = 49;\n" +
                        "      char var2 = 50;\n" +
                        "      return ((Integer)$$Character$compare$$C$C.apply(new CallContext(\"X\", \"java.lang.Character\", this, (Object)null), Character.valueOf(var1), Character.valueOf(var2))).intValue();\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        Object actual = invokeCompiledMethod("X", "fn");

        assertEquals(-1, actual);

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

    }
    @Test
    public void testTestabilityInjectFunctionField_ForNewOperatorWithArgsCastToIntToChar() throws Exception {

        //here for some reason in the original call an int cast is done,
        // resulting in java.lang.ClassCastException: java.lang.Integer cannot be cast to java.lang.Character

        String[] task = {
                "Y.java",
                "public class Y {\n" +
                        "	public char c;" +
                        "	public Y(char c){this.c = c;}" +
                        "}\n",
                "X.java",
                "public class X {\n" +
                        "	public char fn(){char c1 = '1'; return new Y(c1).c;}" +
                        "}\n"
        };

        String expectedOutput =
                "import helpers.Function2;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                        "   public static Function2<CallContext<X, Y>, Character, Y> $$Y$new$$C = (var0, var1) -> {\n" +
                        "      return new Y(var1.charValue());\n" +
                        "   };\n\n" +
                        "   public char fn() {\n" +
                        "      char var1 = 49;\n" +
                        "      return ((Y)$$Y$new$$C.apply(new CallContext(\"X\", \"Y\", this, (Object)null), Character.valueOf(var1))).c;\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Class<?> clazz = cl.loadClass("X");
        Method main = clazz.getDeclaredMethod("fn");
        main.setAccessible(true);
        assertEquals('1', main.invoke(clazz.newInstance()));

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallNoArgsFromStaticContentForNow() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "   static void fn() {\n" +
                        "      System.out.println();\n" +
                        "   }\n" +
                        "}"
        };
        String expectedOutput =
                "import helpers.Consumer1;\n" +
                "import java.io.PrintStream;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                "   public static Consumer1<CallContext<X, PrintStream>> $$PrintStream$println = (var0) -> {\n" +
                "      ((PrintStream)var0.calledClassInstance).println();\n" +
                "   };\n\n" +
                "   static void fn() {\n" +
                "      $$PrintStream$println.accept(new CallContext(\"X\", \"java.io.PrintStream\", (Object)null, System.out));\n" +
                "   }\n" +
                "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

    }
    @Test
    public void testTestabilityInjectFunctionField_DuplicateCalls() throws Exception {

        //expecting single field
        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn1(){System.out.println();}" +
                        "	void fn2(){System.out.println();}" +
                        "}\n"
        };
        String expectedOutput =
                        "import helpers.Consumer1;\n" +
                        "import java.io.PrintStream;\n" +
                        "import testablejava.CallContext;\n\n" +
                        "public class X {\n" +
                        "   public static Consumer1<CallContext<X, PrintStream>> $$PrintStream$println = (var0) -> {\n" +
                        "      ((PrintStream)var0.calledClassInstance).println();\n" +
                        "   };\n\n" +
                        "   void fn1() {\n" +
                        "      $$PrintStream$println.accept(new CallContext(\"X\", \"java.io.PrintStream\", this, System.out));\n" +
                        "   }\n\n" +
                        "   void fn2() {\n" +
                        "      $$PrintStream$println.accept(new CallContext(\"X\", \"java.io.PrintStream\", this, System.out));\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

    }
    @Test
    public void testTestabilityInjectFunctionField_PrimitiveType() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                "	void fn() throws Exception {" +
                "     int i = 434242342;" +
                "     System.out.write(i);" +
                "   }" +
                "   static public void exec() throws Exception {dontredirect: new X().fn();}\n" +
                "}\n"
        };

        String expectedOutput =
                "import helpers.Consumer2;\n" +
                "import java.io.PrintStream;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                "   public static Consumer2<CallContext<X, PrintStream>, Integer> $$PrintStream$write$$I = (var0, var1) -> {\n" +
                "      ((PrintStream)var0.calledClassInstance).write(var1.intValue());\n" +
                "   };\n\n" +
                "   void fn() throws Exception {\n" +
                "      int var1 = 434242342;\n" +
                "      $$PrintStream$write$$I.accept(new CallContext(\"X\", \"java.io.PrintStream\", this, System.out), Integer.valueOf(var1));\n" +
                "   }\n\n" +
                "   public static void exec() throws Exception {\n" +
                "      (new X()).fn();\n" +
                "   }\n" +
                "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        main.invoke(null);

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));
    }
    @Test
    public void testTestabilityInjectFunctionField_PrimitiveType_InGeneric() throws Exception {

        String[] task = {
                "X.java",
                "import java.util.*;\n" +
                "public class X {\n" +
                        "	void fn() throws Exception {" +
                        "     List<Integer> lst;\n" +
                        "     dontredirect: lst = new ArrayList<>();\n" +
                        "     int i = 434242342;" +
                        "     lst.add(i);" +
                        "   }" +
                        "}\n"
        };

        String expectedOutput =
                "import helpers.Function2;\n" +
                "import java.util.ArrayList;\n" +
                "import java.util.List;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                        "   public static Function2<CallContext<X, List<Integer>>, Integer, Boolean> $$List$add$$I = (var0, var1) -> {\n" +
                        "      return Boolean.valueOf(((List)var0.calledClassInstance).add(var1));\n" +
                        "   };\n\n" +
                        "   void fn() throws Exception {\n" +
                        "      ArrayList var1 = new ArrayList();\n" +
                        "      int var2 = 434242342;\n" +
                        "      $$List$add$$I.apply(new CallContext(\"X\", \"java.util.List<java.lang.Integer>\", this, var1), Integer.valueOf(var2));\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

        invokeCompiledMethod("X", "fn");
    }
    @Test
    public void testTestabilityInjectFunctionField_PrimitiveType_InNewOperator() throws Exception {

        String[] task = {
                "X.java",
                "import java.math.BigDecimal;\n" +
                "public class X {\n" +
                        "	BigDecimal fn() throws Exception {" +
                        "     int i = 434242342;" +
                        "     return new BigDecimal(i);" +
                        "   }" +
                        "}\n"
        };

        String expectedOutput =
                "import helpers.Function2;\n" +
                "import java.math.BigDecimal;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                "   public static Function2<CallContext<X, BigDecimal>, Integer, BigDecimal> $$BigDecimal$new$$I = (var0, var1) -> {\n" +
                "      return new BigDecimal(var1.intValue());\n" +
                "   };\n\n" +
                "   BigDecimal fn() throws Exception {\n" +
                "      int var1 = 434242342;\n" +
                "      return (BigDecimal)$$BigDecimal$new$$I.apply(new CallContext(\"X\", \"java.math.BigDecimal\", this, (Object)null), Integer.valueOf(var1));\n" +
                "   }\n" +
                "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

        Object actual = invokeCompiledMethod("X", "fn");
        assertEquals(new BigDecimal(434242342), actual);
    }
    @Test
    public void testTestabilityInjectFunctionField_PrimitiveTypeConst() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn() throws Exception {" +
                        "     System.out.write(434242342);" +
                        "   }" +
                        "   static public void exec() throws Exception {dontredirect: new X().fn();}\n" +
                        "}\n"
        };

        String expectedOutput =
                "import helpers.Consumer2;\n" +
                "import java.io.PrintStream;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                "   public static Consumer2<CallContext<X, PrintStream>, Integer> $$PrintStream$write$$I = (var0, var1) -> {\n" +
                "      ((PrintStream)var0.calledClassInstance).write(var1.intValue());\n" +
                "   };\n\n" +
                "   void fn() throws Exception {\n" +
                "      $$PrintStream$write$$I.accept(new CallContext(\"X\", \"java.io.PrintStream\", this, System.out), Integer.valueOf(434242342));\n" +
                "   }\n\n" +
                "   public static void exec() throws Exception {\n" +
                "      (new X()).fn();\n" +
                "   }\n"+
                "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        main.invoke(null);

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));
    }
    @Test
    public void testTestabilityInjectFunctionField_PrimitiveTypeConst_InNewOperator() throws Exception {

        String[] task = {
                "X.java",
                "import java.math.BigDecimal;\n" +
                "public class X {\n" +
                        "	BigDecimal fn() throws Exception {" +
                        "     return new BigDecimal(434242342);" +
                        "   }" +
                        "}\n"
        };

        String expectedOutput =
                "import helpers.Function2;\n" +
                "import java.math.BigDecimal;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                "   public static Function2<CallContext<X, BigDecimal>, Integer, BigDecimal> $$BigDecimal$new$$I = (var0, var1) -> {\n" +
                "      return new BigDecimal(var1.intValue());\n" +
                "   };\n\n" +
                "   BigDecimal fn() throws Exception {\n" +
                "      return (BigDecimal)$$BigDecimal$new$$I.apply(new CallContext(\"X\", \"java.math.BigDecimal\", this, (Object)null), Integer.valueOf(434242342));\n" +
                "   }\n" +
                "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

        Object actual = invokeCompiledMethod("X","fn");

        assertEquals(new BigDecimal(434242342), actual);
    }
    @Test
    public void testTestabilityInjectFunctionField_MultipleCalls() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn() throws Exception {" +
                        "     java.io.PrintStream x = System.out;" +
                        "     Integer i=0;" +
                        "     x.write(i);" +
                        "     x.close();" +
                        "   }" +
                        "}\n"
        };
        String expectedOutput =
                "import helpers.Consumer1;\n" +
                "import helpers.Consumer2;\n" +
                "import java.io.PrintStream;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                "   public static Consumer1<CallContext<X, PrintStream>> $$PrintStream$close = (var0) -> {\n" +
                "      ((PrintStream)var0.calledClassInstance).close();\n" +
                "   };\n" +
                "   public static Consumer2<CallContext<X, PrintStream>, Integer> $$PrintStream$write$$Integer = (var0, var1) -> {\n" +
                "      ((PrintStream)var0.calledClassInstance).write(var1.intValue());\n" +
                "   };\n\n" +
                "   void fn() throws Exception {\n" +
                "      PrintStream var1 = System.out;\n" +
                "      Integer var2 = Integer.valueOf(0);\n"+
                "      $$PrintStream$write$$Integer.accept(new CallContext(\"X\", \"java.io.PrintStream\", this, var1), var2.intValue());\n" +
                "      $$PrintStream$close.accept(new CallContext(\"X\", \"java.io.PrintStream\", this, var1));\n" +
                "   }\n" +
                "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));
    }
    @Test
    public void testTestabilityInjectFunctionField_ExternalCall_NameWithPackage() throws Exception {

        String[] task = {
                "a/Y.java",
                "package a;\n" +
                        "public class Y {\n" +
                        "	public void fn() {}" +
                        "}",
                "X.java",
                "public class X {\n" +
                        "	void fn() throws Exception {" +
                        "        a.Y ya;\n" +
                        "        dontredirect:  ya = new a.Y();\n" +
                        "        ya.fn();\n" +
                        "   }" +
                        "}\n"
        };
        String expectedOutput =
                "import a.Y;\n" +
                "import helpers.Consumer1;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                "   public static Consumer1<CallContext<X, Y>> $$Y$fn = (var0) -> {\n" +
                "      ((Y)var0.calledClassInstance).fn();\n" +
                "   };\n" +
                "\n" +
                "   void fn() throws Exception {\n" +
                "      Y var1 = new Y();\n" +
                "      $$Y$fn.accept(new CallContext(\"X\", \"a.Y\", this, var1));\n" +
                "   }\n" +
                "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));
    }
    @Test
    public void testTestabilityInjectFunctionField_ShortAndLongFieldNames() throws Exception {

        String[] task = {
                "a/Y.java",
                "package a;\n" +
                "public class Y {\n" +
                        "	public void fn() {}" +
                        "}",
                "b/Y.java",
                "package b;\n" +
                "public class Y {\n" +
                        "	public void fn() {}" +
                        "}",
                "X.java",
                "public class X {\n" +
                "	void fn() throws Exception {" +
                "        a.Y ya = new a.Y();\n" +
                "        b.Y yb = new b.Y();\n" +
                "        ya.fn();\n" +
                "        yb.fn();\n" +
                "   }" +
                "}\n"
        };
        String expectedOutput =
                "import b.Y;\n" +
                "import helpers.Consumer1;\n" +
                "import helpers.Function1;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                "   public static Consumer1<CallContext<X, Y>> $$b$Y$fn = (var0) -> {\n" +
                "      ((Y)var0.calledClassInstance).fn();\n" +
                "   };\n" +
                "   public static Consumer1<CallContext<X, a.Y>> $$a$Y$fn = (var0) -> {\n" +
                "      ((a.Y)var0.calledClassInstance).fn();\n" +
                "   };\n" +
                "   public static Function1<CallContext<X, Y>, Y> $$b$Y$new = (var0) -> {\n" +
                "      return new Y();\n" +
                "   };\n" +
                "   public static Function1<CallContext<X, a.Y>, a.Y> $$a$Y$new = (var0) -> {\n" +
                "      return new a.Y();\n" +
                "   };\n" +
                "\n" +
                "   void fn() throws Exception {\n" +
                "      a.Y var1 = (a.Y)$$a$Y$new.apply(new CallContext(\"X\", \"a.Y\", this, (Object)null));\n" +
                "      Y var2 = (Y)$$b$Y$new.apply(new CallContext(\"X\", \"b.Y\", this, (Object)null));\n" +
                "      $$a$Y$fn.accept(new CallContext(\"X\", \"a.Y\", this, var1));\n" +
                "      $$b$Y$fn.accept(new CallContext(\"X\", \"b.Y\", this, var2));\n" +
                "   }\n" +
                "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));
    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallPassingArgsThrough() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(String x){System.out.println(x);}" +
                        "}\n"
        };
        String expectedOutput =
                "import helpers.Consumer2;\n" +
                "import java.io.PrintStream;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                "   public static Consumer2<CallContext<X, PrintStream>, String> $$PrintStream$println$$String = (var0, var1) -> {\n" +
                "      ((PrintStream)var0.calledClassInstance).println(var1);\n" +
                "   };\n\n" +
                "   void fn(String var1) {\n" +
                "      $$PrintStream$println$$String.accept(new CallContext(\"X\", \"java.io.PrintStream\", this, System.out), var1);\n" +
                "   }\n" +
                "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));
    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallPassingInAConstant() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(){System.out.println(\"x\");}" +
                        "}\n"
        };
        String expectedOutput =
                "import helpers.Consumer2;\n" +
                "import java.io.PrintStream;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                "   public static Consumer2<CallContext<X, PrintStream>, String> $$PrintStream$println$$String = (var0, var1) -> {\n" +
                "      ((PrintStream)var0.calledClassInstance).println(var1);\n" +
                "   };\n\n" +
                "   void fn() {\n" +
                "      $$PrintStream$println$$String.accept(new CallContext(\"X\", \"java.io.PrintStream\", this, System.out), \"x\");\n" +
                "   }\n" +
                "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallBaseClass() throws Exception {

        //field named after method that is actually called (base class)
        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(){System.out.toString();}" +
                        "}\n"
        };

        String expectedOutput =
                "import helpers.Function1;\n" +
                "import java.io.PrintStream;\n" +
                "import testablejava.CallContext;\n\n"+
                "public class X {\n" +
                "   public static Function1<CallContext<X, PrintStream>, String> $$PrintStream$toString = (var0) -> {\n" +
                "      return ((PrintStream)var0.calledClassInstance).toString();\n" +
                "   };\n" +
                "\n" +
                "   void fn() {\n" +
                "      $$PrintStream$toString.apply(new CallContext(\"X\", \"java.io.PrintStream\", this, System.out));\n" +
                "   }\n" +
                "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

    }
    /**
     * Special 'function' types are injected into every compilation to ensure all methods can be represented with function field
     * This is in parallel to Java8 java.util.function.Function class which only supports unary or binary functions
     * @throws Exception
     */
    @Test
    public void testTestabilityInjectedFunctions() throws Exception {


        String[] task = {
                "X.java",
                "import helpers.Function0;\n" +
                "import helpers.Function1;\n" +
                "import helpers.Function2;\n" +
                "import helpers.Function21;\n" +
                "import helpers.Function3;\n\n" +
                "public class X {\n" +
                "   Function0<Boolean> x0;\n" +
                "   Function1<Integer, Boolean> x1;\n" +
                "   Function2<Integer, String, Boolean> x2;\n" +
                "   Function3<Integer, String, Float, Boolean> x3;\n" +
                "   Function21<Integer, Integer, Integer, Integer, Integer, Integer, Integer, Integer, " +
                "Integer, Integer, Integer, Integer, Integer, Integer, Integer, Integer, Integer, Integer, " +
                "Integer, Integer, Integer, Boolean> x21;\n" +
                "}"
        };

        String expectedOutput = task[1];

        String actual = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n"));
        assertEquals(expectedOutput, actual);

    }
    @Test
    public void testTestabilityInjectFunctionField_ForLocalVariable() throws Exception {
        //for dynamic call x.y() we need to pass instance x, then original call arguments

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(){" +
                        "      String s = \"\";" +
                        "      s.notify();" +
                        "   }" +
                        "}\n"
        };
        String expectedOutput =
                "import helpers.Consumer1;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                        "   public static Consumer1<CallContext<X, String>> $$String$notify = (var0) -> {\n" +
                        "      ((String)var0.calledClassInstance).notify();\n" +
                        "   };\n\n" +
                        "   void fn() {\n" +
                        "      String var1 = \"\";\n" +
                        "      $$String$notify.accept(new CallContext(\"X\", \"java.lang.String\", this, var1));\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

    }
    @Test
    public void testTestabilityInjectFunctionField_ForNewOperator() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(){new String();}" +
                        "}\n"
        };
        String expectedOutput =
                "import helpers.Function1;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                "   public static Function1<CallContext<X, String>, String> $$String$new = (var0) -> {\n" +
                "      return new String();\n" +
                "   };\n\n" +
                "   void fn() {\n" +
                "      $$String$new.apply(new CallContext(\"X\", \"java.lang.String\", this, (Object)null));\n" +
                "   }\n" +
                "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

    }
    @Test
    public void testTestabilityInjectFunctionField_ForNewOperator___explore_cast() throws Exception {

        String[] task = {
                "X.java",
                "import helpers.Function1;\n\n" +
                "public class X {\n" +
                        "   Function1<String, String> $$java$lang$String$new = (s) -> {\n" +
                        "      return new String(s);\n" +
                        "   };\n\n" +
                        "   String fn(String s) {\n" +
                        "      return this.$$java$lang$String$new.apply(s);\n" +
                        "   }\n" +
                        "}"

        };
        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        assertEquals("a", invokeCompiledMethod("X", "fn", "a"));
    }
    @Test
    public void testTestabilityInjectFunctionField_ForNewOperatorWithExecute() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	String fn(){return new String(\"x\");}" +
                        "   public static String exec(){return new X().fn();}" +
                        "}\n"
        };


        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals("x", (String)ret);


    }
    @Test
    public void testTestabilityInjectFunctionField_ForNewOperatorAssignedLambdaNotExpanded() throws Exception {

        //when we reassign a field, supplied code (lambda set in exec2) should not be subject to rewrite

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	String fn(){return new String(\"x\");}" +
                        "}\n",
                "Y.java",
                "public class Y {\n" +
                        "	String exec2(){" +
                        "     X x = new X();\n" +
                        "     X.$$String$new$$String = (ctx, arg) -> {return new String(\"redirected\");};" +
                        "     return x.fn();" +
                        "   }" +
                        "}"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        Object ret = invokeCompiledMethod("Y", "exec2");
        assertEquals("redirected", ret);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());

        Field[] fields = cl.loadClass("Y").getDeclaredFields();

        long redirectorFieldCountInY = Arrays.stream(fields).filter(f -> Testability.isTestabilityRedirectorFieldName(f.getName())).count();

        assertEquals("since Y manipulates redirector fields, its own code should have no substitution",0, redirectorFieldCountInY);


    }
    @Test
    public void testTestabilityInjectFunctionField_ForNewOperator_compilationOrder() throws Exception {

        //here you cannot compile X, then Y, since X needs Y to have instrumentation
        String[] task = {
                "Y.java",
                "public class Y {\n" +
                        "	String fn(){return new String(\"x\");}" +
                        "}\n",
                "X.java",
                "public class X {\n" +
                        "	public void fn(){" +
                        "      dontredirect: Y.$$String$new$$String.apply(null, \"new\");\n" +
                        "   }\n" +
                        "}"
        };
        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

    }
    @Test
    public void testTestabilityInjectFunctionField_ReplaceAllocation() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	String fn(){return new String(\"x\");}" +
                        "}\n",
                "Y.java",
                "public class Y {\n" +
                        "	String exec2(){" +
                        "     X x = new X();\n" +
                        "     X.$$String$new$$String = (ctx, arg) -> {return new String(\"redirected\");};" +
                        "     return x.fn();" +
                        "   }" +
                        "   public static String exec(){\n" +
                        "     return new Y().exec2();" +
                        "   }\n" +
                        "}"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("Y").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals("redirected", (String)ret);
    }
    @Test
    public void testTestabilityInjectFunctionField_RedirectInsideLambda() throws Exception {

        //why adding f.apply(arg111) fixes verifyerror?
        //correct code uses aload_0 to get this, then aload_1 to load local argument
        //incorrect code uses aload_0 for both
        //? determined by binding for SingleNameReference of lambda argument arg111, 0 grabs 'this'
        //LocalVariableBinding.resolvedPosition
        //look at blockscope.offset

        //?? when generating code, lambda expression arg111 binding is static. Working code does not have that
        //shouldCaptureInstance in referenceexpression set in analyseCode;
        // if in lambda.generateCode (from original method generate) for this lambda we set shouldCaptureInstance, problem disappears
        // -> looks like all lambdas would have to have shouldCaptureInstance set, because inside their code we may need to make substitution,
        //    which will fail if no this to access field
        String[] task = {
                "X.java",
                "import java.util.function.Function;" +
                "public class X {\n" +

                        "public Function<String, String> f = (x)->\"\";" +
                        "	String exec2(){" +
                        "     Function<String, String> x = (String arg111)->{return String.valueOf(arg111);};"+
                        "    dontredirect: return x.apply(\"value\");" +
                        "   }\n" +
                        "   public static String exec(){\n" +
                        "     return new X().exec2();" +
                        "   } "+
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals("value", (String)ret);
    }

    @Test
    public void testTestabilityInjectFunctionField_ReplaceRedirectorWithLambdaDefinedInStaticScope() throws Exception {

        String[] task = {
                "X.java",
                "import java.io.PrintStream;\n" +
                        "public class X {\n" +
                        "	PrintStream fn(){return System.out.append('c');}" +
                        "}\n",
                "Y.java",
                "import java.io.PrintStream;\n" +
                        "public class Y {\n" +
                        "   public static void exec(){" +
                        "      X x = new X(); " +
                        "      x.$$PrintStream$append$$C = (ps, c)-> {\n" +
                        "        return null;\n" +
                        "      };" +
                        "      x.fn();" +
                        "}   \n" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        assertNull(invokeCompiledMethod("Y", "exec"));
    }
    @Test
    public void testTestabilityInjectFunctionField_ReplaceRedirectorWithLambdaDefinedInDynamicScope() throws Exception {

        String[] task = {
                "X.java",
                "import java.io.PrintStream;\n" +
                        "import helpers.Function2;\n" +
                        "import testablejava.CallContext;\n\n" +
                        "public class X {\n" +
                        "   Function2<CallContext<X, PrintStream>, java.lang.Character, java.io.PrintStream> newRedirector = \n" +
                        "     (ps, c)-> {return null;};\n" +
                        "	PrintStream fn(){return System.out.append('c');}" +
                        "}\n",
                "Y.java",
                "import java.io.PrintStream;\n" +
                        "public class Y {\n" +
                        "   public static void exec(){" +
                        "      X x = new X(); " +
                        "      X.$$PrintStream$append$$C = x.newRedirector; \n" +
                        "      x.fn();" +
                        "}   \n" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        assertNull(invokeCompiledMethod("Y", "exec"));
    }

    @Test
    public void testTestabilityInjectFunctionField_RedirectAllocationInsideLambda() throws Exception {


        String[] task = {
                "X.java",
                "import java.util.function.Function;" +
                        "public class X {\n" +

                        "Function<String, String> f = (x)->\"\";" +
                        "	String exec2(){" +
                        "     Function<String, String> x = (String arg111)->{return new String(arg111);};"+
                        "    dontredirect: return x.apply(\"value\");" +
                        "   }\n" +
                        "   public static String exec(){\n" +
                        "     return new X().exec2();" +
                        "   } "+
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals("value", (String)ret);
    }
    @Test
    public void testTestabilityInjectFunctionField_RedirectAllocationInsideLambda_InStaticScope() throws Exception {

        String[] task = {
                "X.java",
                "import java.util.function.Function;" +
                        "public class X {\n" +
                        "static Function<String, String> x = (String arg111)->{return new String(arg111);};"+
                        "Function<String, String> f = (x)->\"\";" +
                        "	String exec2(){" +
                        "    dontredirect: return x.apply(\"value\");" +
                        "   }\n" +
                        "   public static String exec(){\n" +
                        "     return new X().exec2();" +
                        "   } "+
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals("value", (String)ret);
    }
    @Test
    public void testTestabilityInjectFunctionField_Reproduction2reference() throws Exception {


        String[] task = {
                "X.java",
                "import helpers.Function1;\n\n" +
                "public class X {\n" +
                        "Function1<String, String> f = (arg) -> {return \"\";};" +
                        "	String exec2(){" +
                        "     Function1<String, String> f2 = (arg) -> {dontredirect:return f.apply(\"\");};" +
                        "    return \"\";" +
                        "   }\n" +
                        "   public static String exec(){\n" +
                        "     return new X().exec2();" +
                        "   }}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals("", (String)ret);
    }
    @Test
    public void testTestabilityInjectFunctionField_Reproduction2b() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	String exec2(){" +
                        "     return String.valueOf(1);};" +
                        "   public static String exec(){\n" +
                        "     return new X().exec2();" +
                        "   }\n" +
                        "   }"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals("1", (String)ret);
    }
    @Test
    public void testTestabilityInjectFunctionField_ForNewOperatorInsideLambdaField() throws Exception {

        String[] task = {
                "X.java",
                "import helpers.Function0;\n\n" +
                "public class X {\n" +
                        "	String fn(){ " +
                        "     Function0<String> f = () -> {return new String(\"x\");};\n" +
                        "     dontredirect: return f.apply();" +
                        "   }\n" +
                        "}",
                "Y.java",
                "public class Y {\n" +
                        "	String test(){ " +
                        "     X.$$String$new$$String = (ctx, s) -> {return new String(\"y\");};\n" +
                        "     dontredirect: return new X().fn();" +
                        "   }" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        Object actual = invokeCompiledMethod("Y", "test");
        assertEquals("code in X.fn.f lambda contains redirection","y", actual);
    }
    @Test
    public void testTestabilityInjectFunctionField_ForParameterizedType() throws Exception {

        String[] task = {
                "X.java",
                "import helpers.Function0;\n\n" +
                        "public class X {\n" +
                        "	String fn(Function0<String> f){ " +
                        "     return f.apply();" +
                        "   }\n" +
                        "}",
                "Y.java",
                "public class Y {\n" +
                        "	String test(){ " +
                        "     X.$$Function0$apply = (ctx) -> {return \"y\";};\n" +
                        "     dontredirect: return new X().fn( () -> {return \"z\";});" +
                        "   }" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        Object actual = invokeCompiledMethod("Y", "test");
        assertEquals("code in X.fn.f lambda contains redirection","y", actual);
    }
    @Test
    public void testTestabilityInjectFunctionField_ForNewOperatorInsideInitializerLambdaField() throws Exception {

        //error reported at QualifiedNameReference#1031 this.indexOfFirstFieldBinding == 1
        String[] task = {
                "X.java",
                "import helpers.Function1;\n\n" +
                "public class X {\n" +
                        "	Function1<String, String> f = (arg) -> {return new String(\"x\");};" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
    }

    @Test
    public void testTestabilityInjectFunctionField_ForApply() throws Exception {

        String[] task = {
                "X.java",
                "import helpers.Function1;\n\n" +
                "public class X {\n" +
                        "   Function1<String, String> ff = (a) -> a + \"!\";" +
                        "	String fn(String fnarg){\n" +
                        "     Function1<String, String> f = (arg) -> {return ff.apply(arg);};\n" +
                        "     dontredirect: return f.apply(fnarg);" +
                        "   };" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        Object actual = invokeCompiledMethod("X","fn", "t");
        assertEquals("t!", actual);

    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallArrayOfPrimitiveTypeArg() throws Exception {

        String[] task = {
                "Y.java",
                "public class Y {\n" +
                "     public static int arrayArg(int[] ar){ return ar.length;}\n" +
                "}",
                "X.java",
                "public class X {\n" +
                        "	public int fn(){\n" +
                        "     int [] ar = {1, 2};\n" +
                        "     return Y.arrayArg(ar);\n" +
                        "   }\n" +
                        "}\n"
        };

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        String expectedOutput =
                "import helpers.Function2;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                "   public static Function2<CallContext<X, Y>, int[], Integer> $$Y$arrayArg$$ⒶI = (var0, var1) -> {\n" +
                "      return Integer.valueOf(Y.arrayArg(var1));\n" +
                "   };\n" +
                "\n" +
                "   public int fn() {\n" +
                "      int[] var1 = new int[]{1, 2};\n" +
                "      return ((Integer)$$Y$arrayArg$$ⒶI.apply(new CallContext(\"X\", \"Y\", this, (Object)null), var1)).intValue();\n" +
                "   }\n" +
                "}";
        Object actual = invokeCompiledMethod("X","fn");

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

        assertEquals(2, actual);
    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallArrayArg() throws Exception {

        String[] task = {
                "Y.java",
                "public class Y {\n" +
                        "     public static int arrayArg(String[] ar){ return ar.length;}\n" +
                        "}",
                "X.java",
                "public class X {\n" +
                        "	public int fn(){\n" +
                        "     String [] ar = {\"1\", \"2\"};\n" +
                        "     return Y.arrayArg(ar);\n" +
                        "   }\n" +
                        "}\n"
        };

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        String expectedOutput =
                "import helpers.Function2;\n" +
                "import testablejava.CallContext;\n\n" +
                        "public class X {\n" +
                        "   public static Function2<CallContext<X, Y>, String[], Integer> $$Y$arrayArg$$ⒶString = (var0, var1) -> {\n" +
                        "      return Integer.valueOf(Y.arrayArg(var1));\n" +
                        "   };\n" +
                        "\n" +
                        "   public int fn() {\n" +
                        "      String[] var1 = new String[]{\"1\", \"2\"};\n" +
                        "      return ((Integer)$$Y$arrayArg$$ⒶString.apply(new CallContext(\"X\", \"Y\", this, (Object)null), var1)).intValue();\n" +
                        "   }\n" +
                        "}";
        Object actual = invokeCompiledMethod("X","fn");

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

        assertEquals(2, actual);
    }
    @Test
    public void testTestabilityInjectFunctionField_BlockRedirectionForAllocation() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(){\n" +
                        "     int i=3;" +
                        "     while(i>0){dontredirect:new String(\"a\");i--;}" +
                        "     dontredirect:while(i>0){new String(\"b\");i--;}" +
                        "     dontredirectB:new String(\"c\");" +
                        "     new String(\"d\");" +
                        "   };" +
                        "}\n"
        };

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        String expectedOutput =
                "import helpers.Function2;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                        "   public static Function2<CallContext<X, String>, String, String> $$String$new$$String = (var0, var1) -> {\n" +
                        "      return new String(var1);\n" +
                        "   };\n\n" +
                        "   void fn() {\n" +
                        "      int var1;\n" +
                        "      for(var1 = 3; var1 > 0; --var1) {\n" +
                        "         new String(\"a\");\n" +
                        "      }\n" +
                        "\n" +
                        "      while(var1 > 0) {\n" +
                        "         new String(\"b\");\n" +
                        "         --var1;\n" +
                        "      }\n" +
                        "\n" +
                        "      new String(\"c\");\n" +
                        "      $$String$new$$String.apply(new CallContext(\"X\", \"java.lang.String\", this, (Object)null), \"d\");\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

    }
    @Test
    public void testTestabilityInjectFunctionField_BlockRedirectionFieldCreationForDontRedirectBlocks() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(){\n" +
                        "     dontredirect:new String(\"c\");" +
                        "     dontredirect:Integer.parseInt(\"1\");" +
                        "   };" +
                        "}\n"
        };

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        String expectedOutput =
                "public class X {\n" +
                        "   void fn() {\n" +
                        "      new String(\"c\");\n" +
                        "      Integer.parseInt(\"1\");\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));
    }
    @Test
    public void testTestabilityInjectFunctionField_BlockRedirectionForExternalCall() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(){\n" +
                        "     dontredirect:Integer.parseInt(\"0\");" + //corrupt file
                        "     Integer.parseInt(\"1\");" +
                        "   };" +
                        "}\n"
        };

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        String expectedOutput =
                "import helpers.Function2;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                        "   public static Function2<CallContext<X, Integer>, String, Integer> $$Integer$parseInt$$String = (var0, var1) -> {\n" +
                        "      return Integer.valueOf(Integer.parseInt(var1));\n" +
                        "   };\n\n" +
                        "   void fn() {\n" +
                        "      Integer.parseInt(\"0\");\n" +
                        "      $$Integer$parseInt$$String.apply(new CallContext(\"X\", \"java.lang.Integer\", this, (Object)null), \"1\");\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

    }
    @Test
    public void testTestabilityInjectFunctionField_ExternalCall_ReturningPrimitiveType() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	int fn(){\n" +
                        "     return Integer.parseInt(\"1\");" +
                        "   };\n" +
                        "   public static int exec() {\n" +
                        "     dontredirect: return new X().fn();\n" +
                        "   }\n" +
                        "}\n"
        };

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        String expectedOutput =
                "import helpers.Function2;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                        "   public static Function2<CallContext<X, Integer>, String, Integer> $$Integer$parseInt$$String = (var0, var1) -> {\n" +
                        "      return Integer.valueOf(Integer.parseInt(var1));\n" +
                        "   };\n\n" +
                        "   int fn() {\n" +
                        "      return ((Integer)$$Integer$parseInt$$String.apply(new CallContext(\"X\", \"java.lang.Integer\", this, (Object)null), \"1\")).intValue();\n" +
                        "   }\n\n"+
                        "   public static int exec() {\n" +
                        "      return (new X()).fn();\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));
        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals(1, (int)ret);
    }
    @Test
    public void testTestabilityInjectFunctionField_ForNewOperatorInsideLambdaWithArg() throws Exception {

        String[] task = {
                "X.java",
                "import helpers.Function1;\n\n" +
                "public class X {\n" +
                        "	boolean fn(){\n" +
                        "     Function1<String, String> f = (arg) -> {return new String(\"x\");};\n" +
                        "     return f != null;" +
                        "   };\n" +
                        "}\n"
        };

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        String expectedOutput =
                "import helpers.Function1;\n" +
                "import helpers.Function2;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                "   public static Function2<CallContext<X, String>, String, String> $$String$new$$String = (var0, var1) -> {\n" +
                "      return new String(var1);\n" +
                "   };\n\n" +
                "   boolean fn() {\n" +
                "      Function1 var1 = (var1) -> {\n" +
                "         return (String)$$String$new$$String.apply(new CallContext(\"X\", \"java.lang.String\", this, (Object)null), \"x\");\n" +
                "      };\n" +
                "      return var1 != null;\n" +
                "   }\n" +
                "}";

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

        Object actual = invokeCompiledMethod("X","fn");
        assertEquals(true, actual);

    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallWithExecute() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "   java.io.PrintStream fn(String x){return System.out.append(x);}\n" +
                        "   public static java.io.PrintStream exec(){return new X().fn(\"x\");}\n" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals(System.out, ret);
    }

    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallWithExecute_Vararg() throws Exception {

        //function s/be passing new Object[]{Integer.valueOf(var1) into format()
        String[] task = {
                "X.java",
                "public class X {\n" +
                        "   String fn(int x){return String.format(\"%d\", x);}\n" +
                        "   public static String exec(){dontredirect: return new X().fn(1);}\n" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals("1", ret);
    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallWithExecute_MultipleVararg() throws Exception {

        //function s/be passing new Object[] into format()
        String[] task = {
                "X.java",
                "public class X {\n" +
                        "   String fn(int x, long y){return String.format(\"%d-%d\", x, y);}\n" +
                        "   public static String exec(){dontredirect: return new X().fn(1, 2L);}\n" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals("1-2", ret);
    }
    @Test
    public void testTestabilityInjectFunctionField_ForNewOperator_Vararg() throws Exception {

        //function s/be passing new Object[]{Integer.valueOf(var1) into format()
        String[] task = {
                "Y.java",
                "public class Y {\n" +
                        "   Y(String f, Object... x){}\n" +
                        "}\n",
                "X.java",
                "public class X {\n" +
                        "   Y fn(int x){return new Y(\"\", x);}\n" +
                        "   public static Y exec(){dontredirect: return new X().fn(1);}\n" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals("Y", ret.getClass().getName());
    }
    @Test
    public void testTestabilityInjectFunctionField_ForNewOperator_MultipleVararg() throws Exception {

        //function s/be passing new Object[]{Integer.valueOf(var1) into format()
        String[] task = {
                "Y.java",
                "public class Y {\n" +
                        "   Y(String f, Object... x){}\n" +
                        "}\n",
                "X.java",
                "public class X {\n" +
                        "   Y fn(int x, long y){return new Y(\"\", x, y);}\n" +
                        "   public static Y exec(){dontredirect: return new X().fn(1, 2L);}\n" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals("Y", ret.getClass().getName());
    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallWithExecute_PrimitiveTypeArg() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "   String fn(int x){return Integer.toString(x);}\n" +
                        "   public static String exec(){return new X().fn(1);}\n" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals("1", ret);
    }

    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallWithExecute_PrimitiveTypeConst() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "   String fn(){return Integer.toString(1);}\n" +
                        "   public static String exec(){return new X().fn();}\n" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals("1", ret);
    }

    void fnZZ(){
        Integer.parseInt("0");
    }

    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallWithExecute_PrimitiveTypeVoidReturn() throws Exception {

        String[] task = {
                "Y.java",
                "public class Y {\n" +
                        "   static void takeInt(int i){}\n" +
                        "}\n",
                "X.java",
                "public class X {\n" +
                        "   void fn(){Y.takeInt(1);}\n" +
                        "   public static void exec(){new X().fn();}\n" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals(null, ret);
    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallWithExecute_PrimitiveTypeNonVoidReturn() throws Exception {

        String[] task = {
                "Y.java",
                "public class Y {\n" +
                        "   static String takeInt(int i){return \"\";}\n" +
                        "}\n",
                "X.java",
                "public class X {\n" +
                        "   String fn(){return Y.takeInt(1);}\n" +
                        "   public static String exec(){return new X().fn();}\n" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals("", ret);
    }



    @Test
    public void testTestabilityInjectFunctionField_ForNewOperatorPassingArgsThrough() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(StringBuilder x){new String(x);}" +
                        "}\n"
        };
        String expectedOutput =
                "import helpers.Function2;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                "   public static Function2<CallContext<X, String>, StringBuilder, String> $$String$new$$StringBuilder = (var0, var1) -> {\n" +
                "      return new String(var1);\n" +
                "   };\n\n" +
                "   void fn(StringBuilder var1) {\n" +
                "      $$String$new$$StringBuilder.apply(new CallContext(\"X\", \"java.lang.String\", this, (Object)null), var1);\n" +
                "   }\n" +
                "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));
    }
    @Test
    public void testTestabilityInjectFunctionField_ForNewOperatorReturn() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	String fn(){ return new String();}" +
                        "}\n"
        };
        String expectedOutput =
                "import helpers.Function1;\n" +
                "import testablejava.CallContext;\n\n"+
                "public class X {\n" +
                "   public static Function1<CallContext<X, String>, String> $$String$new = (var0) -> {\n" +
                "      return new String();\n" +
                "   };\n\n" +
                "   String fn() {\n" +
                "      return (String)$$String$new.apply(new CallContext(\"X\", \"java.lang.String\", this, (Object)null));\n" +
                "   }\n" +
                "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallReturn() throws Exception {

        String[] task = {
                "X.java",
                "import java.io.PrintStream;\n" +
                "public class X {\n" +
                        "	PrintStream fn(){return System.out.append('c');}" +
                        "}\n"
        };
        String expectedOutput =
                "import helpers.Function2;\n" +
                "import java.io.PrintStream;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                "   public static Function2<CallContext<X, PrintStream>, Character, PrintStream> $$PrintStream$append$$C = (var0, var1) -> {\n" +
                "      return ((PrintStream)var0.calledClassInstance).append(var1.charValue());\n" +
                "   };\n\n" +
                "   PrintStream fn() {\n" +
                "      return (PrintStream)$$PrintStream$append$$C.apply(new CallContext(\"X\", \"java.io.PrintStream\", this, System.out), Character.valueOf('c'));\n" +
                "   }\n" +
                "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        Object ret = invokeCompiledMethod("X", "fn");

        assertEquals(System.out, ret);

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallReturn_InnerClassCaller() throws Exception {

        String[] task = {
                "X.java",
                "import java.io.PrintStream;\n" +
                        "public class X {\n" +
                        "   public class Y {\n" +
                        "	   PrintStream fn(){return System.out.append('c');}\n" +
                        "   }\n;" +
                        "   PrintStream fnX(){dontredirect: return new Y().fn();}\n" +
                        "}\n"
        };
        String expectedOutputX =
                "import X.Y;\n" +
                "import helpers.Function2;\n" +
                "import java.io.PrintStream;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                "   public static Function2<CallContext<Y, PrintStream>, Character, PrintStream> $$PrintStream$append$$C = (var0, var1) -> {\n" +
                "      return ((PrintStream)var0.calledClassInstance).append(var1.charValue());\n" +
                "   };\n\n" +
                "   PrintStream fnX() {\n" +
                "      return (new Y(this)).fn();\n" +
                "   }\n" +
                "}";
        String expectedOutputY =
                "import java.io.PrintStream;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X$Y {\n" +
                "   public X$Y(X var1) {\n" +
                "      this.this$0 = var1;\n" +
                "   }\n\n" +
                "   PrintStream fn() {\n" +
                "      return (PrintStream)X.$$PrintStream$append$$C.apply(new CallContext(\"X.Y\", \"java.io.PrintStream\", this, System.out), Character.valueOf('c'));\n" +
                "   }\n" +
                "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        Object ret = invokeCompiledMethod("X", "fnX");

        assertEquals(System.out, ret);

        assertEquals(expectedOutputX, moduleMap.get("X").stream().collect(joining("\n")));
        assertEquals(expectedOutputY, moduleMap.get("X$Y").stream().collect(joining("\n")));

    }

    @Test
    public void testTestabilityInjectFunctionField_ForNewOperatorFromStaticContextForNow() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "   static void fn() {\n" +
                        "      new String();\n" +
                        "   }\n" +
                        "}"
        };
        String expectedOutput =
                "import helpers.Function1;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class X {\n" +
                "   public static Function1<CallContext<X, String>, String> $$String$new = (var0) -> {\n" +
                "      return new String();\n" +
                "   };\n\n" +
                "   static void fn() {\n" +
                "      $$String$new.apply(new CallContext(\"X\", \"java.lang.String\", (Object)null, (Object)null));\n" +
                "   }\n" +
                "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

    }
    //TODO re-enable
//    @Test
//    public void testTestabilityInjectFunctionField_getClass() throws Exception {
//        //incompatible CaptionBinding generated. getClass is specialcased, should not be bypassed
//
//        String[] task = {
//                "X.java",
//                "public class X {\n" +
//                        "	public String fn(){X x = this;" +
//                        "     Class<?> cl = x.getClass();" +
//                        "     return cl.getName();" +
//                        "   }" +
//                        "}\n"
//        };
//
//        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
//
//        Object actual = invokeCompiledMethod("X","fn");
//
//        assertEquals("X", actual);
//    }
    //TODO reen
//    @Test
//    public void testTestabilityInjectFunctionField_ForNewOperator_ForAnonymousInnerClass() throws Exception {
//        //should we move the body of anonymous class to inside the function implementing redirection?
//        //the signature should be of base class
//        //should we reflect in field name that this is for inner class? Can collide with field implementing regular new
//        String[] task = {
//                "Z.java",
//                "public class Z {}",
//                "Y.java",
//                "public class Y {\n" +
//                        "public helpers.Function0<Z> $$trythis = () -> {\n" +
//                        "        dontredirect:return new Z() {\n" +
//                        "        };\n" +
//                        "    };" +
//                "   Z fn(){return new Z(){};}\n" +
////                        "   Z fn2() {\n" +
////                        "      return (Z)this.$$trythis.apply();\n" +
////                        "   }\n" +
//                "}\n"
//        };
//        String expectedOutput =
//                "import Y.1;\n" +
//                "import helpers.Function0;\n" +
//                "\n" +
//                "public class Y {\n" +
//                "   public Function0<Z> $$Z$new = () -> {\n" +
//                "      return new 1();\n" +
//                "   };\n\n" +
//                "   Z fn() {\n" +
//                "      return (Z)this.$$Z$new.apply();\n" +
//                "   }\n" +
//                "}";
//
//        Map<String, List<String>> moduleMap = compileAndDisassemble(task,  INSERT_REDIRECTORS_ONLY);
//
//        Object ret = invokeCompiledMethod("Y", "fn");//main.invoke(null);
//
//        assertEquals("Z", ret.getClass().getSuperclass().getName());
//
//        assertEquals(expectedOutput, moduleMap.get("Y").stream().collect(joining("\n")));
//    }

    //TODO reen
//    @Test
//    public void testTestabilityInjectFunctionField_Reproduce() throws Exception {
//        //public class CallContext<Y,Class<capture#1-of ? extends CallContext#RAW>> and null dereference in expandInternalName because it is CaptureBinding where compoundName=null
//        String[] task = {
//                "Y.java",
//                "import helpers.Function1;\n" +
//                        "import testablejava.CallContext;\n" +
//                        "public class Y {\n" +
//                        "	String fn(){" +
//                        "       Function1<CallContext<Y, Y>, String> fn = (ctx) -> {return (String)ctx.getClass().getName();};" +
//                        "       return \"\" + fn;" +
//                        "   }" +
//                        "}\n"
//        };
//
//        String expectedOutput =
//                "";
//
//        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));
//
//        Object className = invokeCompiledMethod("Y", "fn");
//        assertEquals("", className);
//    }

    @Test
    public void testErrorPropagation() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	public void fn(){someerror}" +
                        "}\n"
        };

        try {
            compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
            fail("should except on error");
        } catch (Exception ex){

        }
    }
    @Test
    public void testTestabilityInjectFunctionField_LambdaExpressionsParameterType() throws Exception {

        String[] task = {
                "Y.java",
                "public class Y {\n" +
                        "	public void f() {new String();}" +
                        "}\n",
                "X.java",
                        "public class X {\n" +
                        "	public void withRedirectorReferences(){" +
                        "      Y y;" +
                        "      dontredirect: y = new Y();" +
                        "      Y.$$String$new.apply(null);" +
                        "   }" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_ALL);
    }
    @Test
    public void testTestabilityInjectFunctionField_skipsTestCode_invokingRedirector() throws Exception {

        String[] task = {
                "Y.java",
                "import java.lang.reflect.*;\n" +
                "import java.util.Arrays;\n" +
                "import java.util.List;\n" +
                "import java.util.stream.Collectors;\n" +
                "\n" +
                "public class Y {\n" +
                        "	public void f() {new String();}" +
                        "	public List<String> fieldNames(){dontredirect: return Arrays.stream(this.getClass().getDeclaredFields()).map(Field::getName).collect(Collectors.toList());}" +
                        "}\n",
                "X.java",
                "import java.lang.reflect.*;\n" +
                "import java.util.Arrays;\n" +
                "import java.util.List;\n" +
                "import java.util.stream.Collectors;\n" +
                "\n" +
                "public class X {\n" +
                        "	public void withRedirectorReferences(){Y y = new Y();Y.$$String$new.apply(null);}" +
                        "	public List<String> fieldNames(){dontredirect: return Arrays.stream(this.getClass().getDeclaredFields()).map(Field::getName).collect(Collectors.toList());}" +
                        "}\n"
        };

        //here X appears to be a test class since it references redirector field from Y

        compileAndDisassemble(task, INSERT_ALL);

        Object actualXFieldNames = invokeCompiledMethod("X","fieldNames");

        assertEquals(Collections.emptyList(), actualXFieldNames);

        Object actualYFieldNames = invokeCompiledMethod("Y","fieldNames");

        assertEquals(Arrays.asList("$$preCreate", "$$postCreate", "$$String$new"), actualYFieldNames);

    }
    @Test
    public void testTestabilityInjectFunctionField_skipsTestCode_reassigningRedirector() throws Exception {

        String[] task = {
                "Y.java",
                "import java.lang.reflect.*;\n" +
                        "import java.util.Arrays;\n" +
                        "import java.util.List;\n" +
                        "import java.util.stream.Collectors;\n" +
                        "\n" +
                        "public class Y {\n" +
                        "	public void f() {new String();}" +
                        "}\n",
                "X.java",
                "import java.lang.reflect.*;\n" +
                        "import java.util.Arrays;\n" +
                        "import java.util.List;\n" +
                        "import java.util.stream.Collectors;\n" +
                        "\n" +
                        "public class X {\n" +
                        "	public void withRedirectorReferences(){Y y = new Y();Y.$$String$new = (ctx)->\"new\";}" +
                        "	public List<String> fieldNames(){dontredirect: return Arrays.stream(this.getClass().getDeclaredFields()).map(Field::getName).collect(Collectors.toList());}" +
                        "}\n"
        };

        //here X appears to be a test class since it references redirector field from Y

        compileAndDisassemble(task, INSERT_ALL);

        Object actualX = invokeCompiledMethod("X","fieldNames");

        assertEquals(Collections.emptyList(), actualX);
    }
    @Test
    public void testTestabilityInjectFunctionField_skipsTestCode_withStaticReference() throws Exception {

        String[] task = {
                "Y.java",
                "public class Y {\n" +
                             "}\n",
                "X.java",
                "import java.lang.reflect.*;\n" +
                        "import java.util.Arrays;\n" +
                        "import java.util.List;\n" +
                        "import java.util.stream.Collectors;\n" +
                        "public class X {\n" +
                        "	public void withRefirectorReferences(){Y.$$postCreate = null;}" +
                        "	public List<String> fieldNames(){dontredirect: return Arrays.stream(this.getClass().getDeclaredFields()).map(Field::getName).collect(Collectors.toList());}" +
                        "}\n"
        };

        //here X appears to be a test class since it references redirector field from Y

        compileAndDisassemble(task, INSERT_ALL);

        Object actual = invokeCompiledMethod("X","fieldNames");

        assertEquals(Collections.emptyList(), actual);
    }
    @Test
    public void testCodeContainsForTestabilityFieldAccessExpression() throws Exception {

        List<String[]> withInstrumentationCalls = ImmutableList.of(
                new String[]{
                        "WithRedirectorAssignment.java",
                        "public class WithRedirectorAssignment {\n" +
                        "	public void fn(){Y.$$postCreate = null;}\n" +
                        "}\n"
                },
                new String[]{
                        "WithRedirectorAssignmentInInstance.java",
                        "public class WithRedirectorAssignmentInInstance {\n" +
                        "	public void fn(){new Y().$$String$new = null;}\n" +
                        "}\n"
                },
                new String[]{
                        "WithRedirectorCallInInstance.java",
                        "public class WithRedirectorCallInInstance {\n" +
                        "	public void fn(){new Y().$$String$new.apply();}\n" +
                        "}\n"
                },
                new String[]{
                        "WithRedirectorCall.java",
                        "public class WithRedirectorCall {\n" +
                        "	public void fn(){Y.$$postCreate.apply();}\n" +
                        "}\n"
                },
                new String[]{
                        "WithRedirectorArg.java",
                        "public class WithRedirectorArg {\n" +
                        "	public void fn2(Object o){}\n" +
                        "	public void fn(){fn2(Y.$$postCreate);}\n" +
                        "}\n"
                },
                new String[]{
                        "WithRedirectorInExpression.java",
                        "public class WithRedirectorInExpression {\n" +
                        "	public void fn(){boolean x = Y.$$postCreate==null?true:false;}\n" +
                        "}\n"
        }

                );
        withInstrumentationCalls.stream().forEach(task -> {
            CompilationUnitDeclaration fullParsedUnit = parse(task).entrySet().stream().findFirst().get().getValue();
            assertTrue(Arrays.toString(task), Testability.codeContainsTestabilityFieldAccessExpression(fullParsedUnit));
        });


    }
    @Test
    public void testTestabilityInjectFunctionField_ThrowingFromLambda() throws Exception {

        String[] task = {
                "X.java",
                "import java.io.PrintStream;\n" +
                        "public class X {\n" +
                        "	PrintStream fn(){return System.out.append('c');}" +
                        "   public static PrintStream exec(){return new X().fn();}\n" +
                        "}\n",
                "Y.java",
                "import java.io.PrintStream;\n" +
                        "public class Y {\n" +
                        "   public static void exec(){" +
                        "      X x = new X(); " +
                        "      x.$$PrintStream$append$$C = (ctx, c)-> {\n" +
                        "        testablejava.Helpers.uncheckedThrow(new java.io.IOException(\"from lambda\")); \n" +
                        "        return ctx.calledClassInstance;\n" +
                        "      };" +
                        "      x.fn();" +
                        "}\n" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        try {
            invokeCompiledMethod("Y", "exec");
            fail("should have thrown");
        } catch (InvocationTargetException ex){
            Throwable exOriginal = ex.getCause();
            assertTrue(exOriginal instanceof IOException);
            assertEquals("from lambda",exOriginal.getMessage());
        }
    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallWithExecute_InnerClassArg() throws Exception {

        String[] task = {
                "Y.java",
                "public class Y {\n" +
                        "   class Z { " +
                        "     Z zfn(){return this;}" +
                        "   };\n" +
                        "   Z fn(){return new Z().zfn();};\n" +
                        "}\n",
                "X.java",
                "public class X {\n" +
                        "   public static Y.Z exec(){dontredirect: return new Y().fn();}\n" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals("Y$Z", ret.getClass().getName());
    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallWithExecute_InnerClassWithOutsideBoundVariable() throws Exception {

        String[] task = {
                "Y.java",
                "public class Y {\n" +
                        "   String s = \"s\";" +
                        "   class Z { " +
                        "     String zfn(){return s;}" +
                        "   };\n" +
                        "   String fn(){return new Z().zfn();};\n" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);


        Object ret = invokeCompiledMethod("Y", "fn");
        assertEquals("s", ret);
    }
    @Test
    public void testTestabilityInjectFunctionField_ForNewOperator_ExpandsInsideInnerClass() throws Exception {

        //TODO pending correct calling class info
        String[] task = {
                "Y.java",
                "public class Y {\n" +
                "   class Z { " +
                "     String zfn(){return new String(\"1\");}" +
                "   };\n" +
                "   public String fn(){" +
                "     dontredirect2: return new Z().zfn();" +
                "   }\n" +
                "}\n"
        };

        String expectedOutputY = //note the field is created on outer class
                "import Y.Z;\n" +
                "import helpers.Function2;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class Y {\n" +
                "   public static Function2<CallContext<Z, String>, String, String> $$String$new$$String = (var0, var1) -> {\n" +
                "      return new String(var1);\n" +
                "   };\n\n" +
                "   public String fn() {\n" +
                "      return (new Z(this)).zfn();\n" +
                "   }\n" +
                "}";

        String expectedOutputZ =
                "import testablejava.CallContext;\n\n" +
                "class Y$Z {\n" +
                "   Y$Z(Y var1) {\n" +
                "      this.this$0 = var1;\n" +
                "   }\n\n" +
                "   String zfn() {\n" +
                "      return (String)Y.$$String$new$$String.apply(new CallContext(\"Y.Z\", \"java.lang.String\", this, (Object)null), \"1\");\n" +
                "   }\n" +
                "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        Object ret = invokeCompiledMethod("Y", "fn");

        assertEquals("1", ret);

        assertEquals(expectedOutputY, moduleMap.get("Y").stream().collect(joining("\n")));
        assertEquals(expectedOutputZ, moduleMap.get("Y$Z").stream().collect(joining("\n")));
    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallWithExecute_ExpandsInsideInnerClass() throws Exception {

        String[] task = {
                "Y.java",
                "public class Y {\n" +
                "   class Z { " +
                "     String zfn(){return String.valueOf(1);}" +
                "   };\n" +
                "   public String fn(){" +
                "     dontredirect: return new Z().zfn();" +
                "   }\n" +
                "}\n"
        };

        String expectedOutputY = //note the field is created on outer class
                "import Y.Z;\n" +
                "import helpers.Function2;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class Y {\n" +
                "   public static Function2<CallContext<Z, String>, Integer, String> $$String$valueOf$$I = (var0, var1) -> {\n" +
                "      return String.valueOf(var1);\n" +
                "   };\n\n" +
                "   public String fn() {\n" +
                "      return (new Z(this)).zfn();\n" +
                "   }\n" +
                "}";

        String expectedOutputZ =
                "import testablejava.CallContext;\n\n" +
                "class Y$Z {\n" +
                "   Y$Z(Y var1) {\n" +
                "      this.this$0 = var1;\n" +
                "   }\n\n" +
                "   String zfn() {\n" +
                "      return (String)Y.$$String$valueOf$$I.apply(new CallContext(\"Y.Z\", \"java.lang.String\", this, (Object)null), Integer.valueOf(1));\n" +
                "   }\n" +
                "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        Object ret = invokeCompiledMethod("Y", "fn");
        assertEquals("1", ret);

        assertEquals(expectedOutputY, moduleMap.get("Y").stream().collect(joining("\n")));
        assertEquals(expectedOutputZ, moduleMap.get("Y$Z").stream().collect(joining("\n")));
    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallWithExecute_AnonymousInnerClassArg() throws Exception {

        String[] task = {
                "Y.java",
                "public class Y {\n" +
                "   class Z { " +
                "     Z zfn(){return this;}" +
                "   };\n" +
                "   Z fn(){\n" +
                "     Z z;\n" +
                "     dontredirect: z = new Z(){};\n" +
                "     return z.zfn();\n" +
                "   };\n" +
                "}\n"
        };
        String expectedOutput =
                "import Y.1;\n" +
                "import Y.Z;\n" +
                "import helpers.Function1;\n" +
                "import testablejava.CallContext;\n\n" +
                "public class Y {\n" +
                "   public static Function1<CallContext<Y, Z>, Z> $$Z$zfn = (var0) -> {\n" +
                "      return ((Z)var0.calledClassInstance).zfn();\n" +
                "   };\n\n" +
                "   Z fn() {\n" +
                "      1 var1 = new 1(this, this);\n" +
                "      return (Z)$$Z$zfn.apply(new CallContext(\"Y\", \"Y.Z\", this, var1));\n" +
                "   }\n" +
                "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        Object ret = invokeCompiledMethod("Y", "fn");//main.invoke(null);

        assertEquals("Y$Z", ret.getClass().getSuperclass().getName());

        assertEquals(expectedOutput, moduleMap.get("Y").stream().collect(joining("\n")));
    }
    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallWithExecute_StaticInnerClass() throws Exception {

        //TODO pending fix of actual inner class type
        //here call from static class is redirected using instance field of Y
        String[] task = {
                "Y.java",
                "public class Y {\n" +
                        "   static class Z { " +
                        "     long zfn(){return System.currentTimeMillis();}" +
                        "   };\n" +
                        "   long fn(){\n" +
                        "     Z z;\n" +
                        "     dontredirect: z = new Z();\n" +
                        "     return z.zfn();\n" +
                        "   };\n" +
                        "}\n"
        };
        String expectedOutput =
                        "import Y.Z;\n" +
                        "import helpers.Function1;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "public class Y {\n" +
                        "   public static Function1<CallContext<Y, Z>, Long> $$Z$zfn = (var0) -> {\n" +
                        "      return Long.valueOf(((Z)var0.calledClassInstance).zfn());\n" +
                        "   };\n" +
                        "   public static Function1<CallContext<Z, System>, Long> $$System$currentTimeMillis = (var0) -> {\n" +
                        "      return Long.valueOf(System.currentTimeMillis());\n" +
                        "   };\n\n" +
                        "   long fn() {\n" +
                        "      Z var1 = new Z();\n" +
                        "      return ((Long)$$Z$zfn.apply(new CallContext(\"Y\", \"Y.Z\", this, var1))).longValue();\n" +
                        "   }\n" +
                        "}";
        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        Object ret = invokeCompiledMethod("Y", "fn");//main.invoke(null);

        assertEquals(0, (System.currentTimeMillis() - (Long)ret)/1000);

        assertEquals(expectedOutput, moduleMap.get("Y").stream().collect(joining("\n")));
    }
    @Test
    public void testTestabilityInjectFunctionField_ReproductionObjectCallerInsideInnerClass() throws Exception {

            String[] task = {
                    "X.java",
                    "class X {\n" +
                    "                public void getRequiredBridges() {\n" +
                    "\n" +
                    "                    class BridgeCollector {\n" +
                    "\n" +
                    "                        BridgeCollector() {\n" +
                    "                            collectBridges();\n" +
                    "                        }\n" +
                    "\n" +
                    "                        void collectBridges() {\n" +
                    "                        }\n" +
                    "                    }\n" +
                    "                }\n" +
                    "            }"
            };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
    }
    @Test
    public void testTestabilityInjectFunctionField_DontRedirectInBlock() throws Exception {

        String[] task = {
                "X.java",
                "import java.util.concurrent.atomic.*;" +
                        "public class X {\n" +
                        "  AtomicInteger ret;\n" +
                        "  {dontredirect: ret = new AtomicInteger();}\n" +
                        "}"
        };

        String expectedOutput =
                "import java.util.concurrent.atomic.AtomicInteger;\n" +
                "\n" +
                "public class X {\n" +
                "   AtomicInteger ret = new AtomicInteger();\n" +
                "}";
        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));


    }
    @Test
    public void testTestabilityInjectFunctionField_RedirectInsideNamedInnerClass() throws Exception {

        String[] task = {
                "X.java",
                "import java.util.concurrent.atomic.*;" +
                "public class X {\n" +
                "    AtomicInteger ret;\n" +
                "    {dontredirect: ret = new AtomicInteger();}\n" +
                "    public int getRequiredBridges() {\n" +
                "\n" +
                "       int i=2;\n" +
                "       int j=3;\n" +
                "       int k=4;\n" +
                "       class BridgeCollector {\n" +
                "\n" +
                "           BridgeCollector() {\n" +
                "              int c = collectBridges(i, j, k);\n" +
                "              dontredirect: ret.set(c);\n" +
                "           }\n" +
                "\n" +
                "           int collectBridges(int i, int j, int k) {\n" +
                "               return i + 10 * j + 100 * k;" +
                "           }\n" +
                "       }\n" +
                "       dontredirect: new BridgeCollector();" +
                "       dontredirect2: return ret.get();" +
                "   }\n" +
                "}\n",

                "Y.java",
                "import java.util.concurrent.atomic.*;" +
                "import testablejava.CallContext;" +
                "public class Y {" +
                "    CallContext<?, ?>[] ctx={null};\n" +
                "    int getRequiredBridgesWithRedirect() {\n" +
                "        X.$$BridgeCollector$collectBridges$$I$I$I = (a1, a2, a3, a4) -> 0;\n " +
                "        dontredirect: return new X().getRequiredBridges();\n" +
                "    }\n" +
                "    CallContext<?, ?> getRequiredBridgesCaptureCallContext() {\n" +
                "        X.$$BridgeCollector$collectBridges$$I$I$I = (a1, a2, a3, a4) -> {ctx[0] = a1; return 0;};\n " +
                "        dontredirect: {new X().getRequiredBridges();return ctx[0];}\n" +
                "    }\n" +
                "}"
        };

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals(432, invokeCompiledMethod("X", "getRequiredBridges"));
        assertEquals(0, invokeCompiledMethod("Y", "getRequiredBridgesWithRedirect"));
        CallContext ctx = (CallContext) invokeCompiledMethod("Y", "getRequiredBridgesCaptureCallContext");
        assertEquals("BridgeCollector", ctx.calledClass);
        assertEquals("BridgeCollector", ctx.callingClass);

    }
    @Test
    public void testTestabilityInjectFunctionField_Repro_nested_call() throws Exception {

        String[] task = {
                "X.java",
                "import java.util.concurrent.atomic.*;" +
                        "public class X {\n" +
                        "    void fn(){" +
                        "      AtomicInteger ret;" +
                        "      dontredirect: ret = new AtomicInteger();" +
                        "      ret.set(intReturn());\n" +
                        "    }\n" +
                        "    int intReturn() {return 0;}\n" +
                        "}"
        };

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        invokeCompiledMethod("X", "fn");
    }

 }
