package org.testability;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;
import org.eclipse.jdt.internal.compiler.InstrumentationOptions;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.joining;


/**
 * Created by julianrozentur1 on 6/23/17.
 */
//TODO feature: all object allocations go through static callback inside its class. Typically we redirect via caller, but this case is different. Allows to find and instrument objects easier if multiple creators exist. Note: handle reflective create

public class TestabilityTest extends BaseTest {

    public static final ImmutableSet<InstrumentationOptions> INSERT_REDIRECTORS_ONLY = ImmutableSet.of(InstrumentationOptions.INSERT_REDIRECTORS);
    public static final ImmutableSet<InstrumentationOptions> INSERT_LISTENERS_ONLY = ImmutableSet.of(InstrumentationOptions.INSERT_LISTENERS);
    public static final ImmutableSet<InstrumentationOptions> INSERT_ALL = ImmutableSet.of(InstrumentationOptions.INSERT_REDIRECTORS, InstrumentationOptions.INSERT_LISTENERS);

    public TestabilityTest(String name) {
        super(name);
    }

    public void testShortenNames(){
        //List<Expression>

    }
//TODO reen/fix
//    public void testPackageCollideWithType() throws Exception {
//
//        String[] task = {
//                "X.java",
//                "package a;\n" +
//                "public class X {}",
//
//                "A.java",
//                "package X;\n" +
//                "public class A {}"
//
//        };
//        String expectedOutput = task[1];
//
//        Map<String, List<String>> stringListMap = compileAndDisassemble(task);
//        assertEquals(expectedOutput, stringListMap.get("a.X").stream().collect(joining("\n")));
//    }

    public void testTestabilityInjectFunctionField_ForNewOperatorCallback() throws Exception {
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

    public void testTestabilityInjectFunctionField_NotExpandingInsideRedirectedFields() throws Exception {

        String[] task = {
                "X.java",
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

    public void testTestabilityInjectFunctionField_ForExternalCallNoArgs() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(){System.out.println();}" +
                        "}\n"
        };
        String expectedOutput =
                "import java.io.PrintStream;\n\n" +
                "public class X {\n" +
                        "   public Function1<PrintStream, Void> $$PrintStream$println = (var1) -> {\n" +
                        "      var1.println();\n" +
                        "      return null;\n" +
                        "   };\n\n" +
                        "   void fn() {\n" +
                        "      this.$$PrintStream$println.apply(System.out);\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

    }
    public void testTestabilityInjectFunctionField_ForExternalCallWithClassReceiver() throws Exception {


        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(){Integer.valueOf(2);}" +
                        "   static public void exec() throws Exception {new X().fn();}" +
                        "}\n"
        };
        String expectedOutput =
                        "public class X {\n" +
                        "   public Function1<Integer, Integer> $$Integer$valueOf = (var1) -> {\n" +
                        "      return Integer.valueOf(var1.intValue());\n" +
                        "   };\n\n" +
                        "   void fn() {\n" +
                        "      this.$$Integer$valueOf.apply(Integer.valueOf(2));\n" +
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
    public void testTestabilityInjectFunctionField_ForExternalCallWithClassReceiver_StaticallyImported() throws Exception {

        //here receiver appears empty, should not resolve to 'this'

        String[] task = {
                "X.java",
                "import static java.lang.Integer.valueOf;" +
                "public class X {\n" +
                        "	void fn(){valueOf(2);}" +
                        "   static public void exec() throws Exception {new X().fn();}" +
                        "}\n"
        };
        String expectedOutput =
                "public class X {\n" +
                        "   public Function1<Integer, Integer> $$Integer$valueOf = (var1) -> {\n" +
                        "      return Integer.valueOf(var1.intValue());\n" +
                        "   };\n\n" +
                        "   void fn() {\n" +
                        "      this.$$Integer$valueOf.apply(Integer.valueOf(2));\n" +
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

    public void testTestabilityInjectFunctionField_ForExternalCallWithArgs() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(){Integer.getInteger(\"1\", Integer.valueOf(2));}" +
                        "}\n"
        };

        String expectedOutput =

                        "public class X {\n" +
                        "   public Function1<Integer, Integer> $$Integer$valueOf = (var1) -> {\n" +
                        "      return Integer.valueOf(var1.intValue());\n" +
                        "   };\n" +
                        "   public Function2<String, Integer, Integer> $$Integer$getInteger = (var1, var2) -> {\n" +
                        "      return Integer.getInteger(var1, var2);\n" +
                        "   };\n\n" +
                        "   void fn() {\n" +
                        "      this.$$Integer$getInteger.apply(\"1\", (Integer)this.$$Integer$valueOf.apply(Integer.valueOf(2)));\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

    }
    public void testTestabilityInjectFunctionField_ForExternalCallWithArgsCast() throws Exception {


        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	public int fn(){int i1 = Integer.MAX_VALUE; long l2 = 2L; return Long.compare(i1, l2);}" +
                        "}\n"
        };

        String expectedOutput =
                "public class X {\n" +
                        "   public Function2<Long, Long, Integer> $$Long$compare = (var1, var2) -> {\n" +
                        "      return Integer.valueOf(Long.compare(var1.longValue(), var2.longValue()));\n" +
                        "   };\n\n" +
                        "   public int fn() {\n" +
                        "      int var1 = " + Integer.MAX_VALUE +";\n" +
                        "      long var2 = 2L;\n" +
                        "      return ((Integer)this.$$Long$compare.apply(Long.valueOf((long)var1), Long.valueOf(var2))).intValue();\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        Object actual = invokeCompiledMethod("X","fn");
        assertEquals(1, actual);

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

    }

    public void testTestabilityInjectFunctionField_ForNewOperatorWithArgsCast() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	public Long fn(){int i1 = Integer.MAX_VALUE; return Long.valueOf(i1);}" +
                        "}\n"
        };

        String expectedOutput =
                "public class X {\n" +
                        "   public Function1<Long, Long> $$Long$valueOf = (var1) -> {\n" +
                        "      return Long.valueOf(var1.longValue());\n" +
                        "   };\n\n" +
                        "   public Long fn() {\n" +
                        "      int var1 = " + Integer.MAX_VALUE + ";\n" +
                        "      return (Long)this.$$Long$valueOf.apply(Long.valueOf((long)var1));\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        Object actual = invokeCompiledMethod("X", "fn");

        assertEquals((long)Integer.MAX_VALUE, actual);

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

    }
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
                "public class X {\n" +
                        "   public Function2<Character, Character, Integer> $$Character$compare = (var1, var2) -> {\n" +
                        "      return Integer.valueOf(Character.compare(var1.charValue(), var2.charValue()));\n" +
                        "   };\n\n" +
                        "   public int fn() {\n" +
                        "      char var1 = 49;\n" +
                        "      char var2 = 50;\n" +
                        "      return ((Integer)this.$$Character$compare.apply(Character.valueOf(var1), Character.valueOf(var2))).intValue();\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        Object actual = invokeCompiledMethod("X", "fn");

        assertEquals(-1, actual);

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

    }
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
                "public class X {\n" +
                        "   public Function1<Character, Y> $$Y$new = (var1) -> {\n" +
                        "      return new Y(var1.charValue());\n" +
                        "   };\n\n" +
                        "   public char fn() {\n" +
                        "      char var1 = 49;\n" +
                        "      return ((Y)this.$$Y$new.apply(Character.valueOf(var1))).c;\n" +
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

    public void testTestabilityInjectFunctionField_ForExternalCallNoArgsFromStaticContentForNow() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "   static void fn() {\n" +
                        "      System.out.println();\n" +
                        "   }\n" +
                        "}"
        };
        String expectedOutput = task[1];

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

    }
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
                "import java.io.PrintStream;\n\n" +
                        "public class X {\n" +
                        "   public Function1<PrintStream, Void> $$PrintStream$println = (var1) -> {\n" +
                        "      var1.println();\n" +
                        "      return null;\n" +
                        "   };\n\n" +
                        "   void fn1() {\n" +
                        "      this.$$PrintStream$println.apply(System.out);\n" +
                        "   }\n\n" +
                        "   void fn2() {\n" +
                        "      this.$$PrintStream$println.apply(System.out);\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

    }

    public void testTestabilityInjectFunctionField_PrimitiveType() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn() throws Exception {" +
                        "     int i = 434242342;" +
                        "     System.out.write(i);" +
                        "   }" +
                        "   static public void exec() throws Exception {new X().fn();}\n" +
                        "}\n"
        };

        String expectedOutput =
                "import java.io.PrintStream;\n\n" +
                        "public class X {\n" +
                        "   public Function2<PrintStream, Integer, Void> $$PrintStream$write = (var1, var2) -> {\n" +
                        "      var1.write(var2.intValue());\n" +
                        "      return null;\n" +
                        "   };\n\n" +
                        "   void fn() throws Exception {\n" +
                        "      int var1 = 434242342;\n" +
                        "      this.$$PrintStream$write.apply(System.out, Integer.valueOf(var1));\n" +
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
    public void testTestabilityInjectFunctionField_PrimitiveType_InNewOperator() throws Exception {

        String[] task = {
                "X.java",
                "import java.math.BigDecimal;\n" +
                "public class X {\n" +
                        "	void fn() throws Exception {" +
                        "     int i = 434242342;" +
                        "     new BigDecimal(i);" +
                        "   }" +
                        "   static public void exec() throws Exception {new X().fn();}\n" +
                        "}\n"
        };

        String expectedOutput =
                "import java.math.BigDecimal;\n\n" +
                        "public class X {\n" +
                        "   public Function1<Integer, BigDecimal> $$BigDecimal$new = (var1) -> {\n" +
                        "      return new BigDecimal(var1.intValue());\n" +
                        "   };\n\n" +
                        "   void fn() throws Exception {\n" +
                        "      int var1 = 434242342;\n" +
                        "      this.$$BigDecimal$new.apply(Integer.valueOf(var1));\n" +
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
    public void testTestabilityInjectFunctionField_PrimitiveTypeConst() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn() throws Exception {" +
                        "     System.out.write(434242342);" +
                        "   }" +
                        "   static public void exec() throws Exception {new X().fn();}\n" +
                        "}\n"
        };

        String expectedOutput =
                "import java.io.PrintStream;\n\n" +
                        "public class X {\n" +
                        "   public Function2<PrintStream, Integer, Void> $$PrintStream$write = (var1, var2) -> {\n" +
                        "      var1.write(var2.intValue());\n" +
                        "      return null;\n" +
                        "   };\n\n" +
                        "   void fn() throws Exception {\n" +
                        "      this.$$PrintStream$write.apply(System.out, Integer.valueOf(434242342));\n" +
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
    public void testTestabilityInjectFunctionField_PrimitiveTypeConst_InNewOperator() throws Exception {

        String[] task = {
                "X.java",
                "import java.math.BigDecimal;\n" +
                "public class X {\n" +
                        "	void fn() throws Exception {" +
                        "     new BigDecimal(434242342);" +
                        "   }" +
                        "   static public void exec() throws Exception {new X().fn();}\n" +
                        "}\n"
        };

        String expectedOutput =
                "import java.math.BigDecimal;\n\n" +
                        "public class X {\n" +
                        "   public Function1<Integer, BigDecimal> $$BigDecimal$new = (var1) -> {\n" +
                        "      return new BigDecimal(var1.intValue());\n" +
                        "   };\n\n" +
                        "   void fn() throws Exception {\n" +
                        "      this.$$BigDecimal$new.apply(Integer.valueOf(434242342));\n" +
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
                "import java.io.PrintStream;\n\n" +
                        "public class X {\n" +
                        "   public Function2<PrintStream, Integer, Void> $$PrintStream$write = (var1, var2) -> {\n" +
                        "      var1.write(var2.intValue());\n" +
                        "      return null;\n" +
                        "   };\n" +
                        "   public Function1<PrintStream, Void> $$PrintStream$close = (var1) -> {\n" +
                        "      var1.close();\n" +
                        "      return null;\n" +
                        "   };\n\n" +
                        "   void fn() throws Exception {\n" +
                        "      PrintStream var1 = System.out;\n" +
                        "      Integer var2 = Integer.valueOf(0);\n"+
                        "      this.$$PrintStream$write.apply(var1, var2.intValue());\n" +
                        "      this.$$PrintStream$close.apply(var1);\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));
    }

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
                        "\n" +
                        "public class X {\n" +
                        "   public Function1<Y, Void> $$Y$fn = (var1) -> {\n" +
                        "      var1.fn();\n" +
                        "      return null;\n" +
                        "   };\n" +
                        "\n" +
                        "   void fn() throws Exception {\n" +
                        "      Y var1 = new Y();\n" +
                        "      this.$$Y$fn.apply(var1);\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));
    }


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
                        "\n" +
                        "public class X {\n" +
                        "   public Function0<Y> $$b$Y$new = () -> {\n" +
                        "      return new Y();\n" +
                        "   };\n" +
                        "   public Function1<a.Y, Void> $$a$Y$fn = (var1) -> {\n" +
                        "      var1.fn();\n" +
                        "      return null;\n" +
                        "   };\n" +
                        "   public Function1<Y, Void> $$b$Y$fn = (var1) -> {\n" +
                        "      var1.fn();\n" +
                        "      return null;\n" +
                        "   };\n" +
                        "   public Function0<a.Y> $$a$Y$new = () -> {\n" +
                        "      return new a.Y();\n" +
                        "   };\n" +
                        "\n" +
                        "   void fn() throws Exception {\n" +
                        "      a.Y var1 = (a.Y)this.$$a$Y$new.apply();\n" +
                        "      Y var2 = (Y)this.$$b$Y$new.apply();\n" +
                        "      this.$$a$Y$fn.apply(var1);\n" +
                        "      this.$$b$Y$fn.apply(var2);\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));
    }

    public void testTestabilityInjectFunctionField_ForExternalCallPassingArgsThrough() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(String x){System.out.println(x);}" +
                        "}\n"
        };
        String expectedOutput =
                "import java.io.PrintStream;\n\n" +
                "public class X {\n" +
                        "   public Function2<PrintStream, String, Void> $$PrintStream$println = (var1, var2) -> {\n" +
                        "      var1.println(var2);\n" +
                        "      return null;\n" +
                        "   };\n\n" +
                        "   void fn(String var1) {\n" +
                        "      this.$$PrintStream$println.apply(System.out, var1);\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));
    }

    public void testTestabilityInjectFunctionField_ForExternalCallPassingInAConstant() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(){System.out.println(\"x\");}" +
                        "}\n"
        };
        String expectedOutput =
                "import java.io.PrintStream;\n\n" +
                "public class X {\n" +
                        "   public Function2<PrintStream, String, Void> $$PrintStream$println = (var1, var2) -> {\n" +
                        "      var1.println(var2);\n" +
                        "      return null;\n" +
                        "   };\n\n" +
                        "   void fn() {\n" +
                        "      this.$$PrintStream$println.apply(System.out, \"x\");\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

    }
    public void testTestabilityInjectFunctionField_ForExternalCallBaseClass() throws Exception {

        //field named after method that is actually called (base class)
        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(){System.out.toString();}" +
                        "}\n"
        };

        String expectedOutput =
                "import java.io.PrintStream;\n\n" +
                "public class X {\n" +
                        "   public Function1<PrintStream, String> $$Object$toString = (var1) -> {\n" +
                        "      return var1.toString();\n" +
                        "   };\n" +
                        "\n" +
                        "   void fn() {\n" +
                        "      this.$$Object$toString.apply(System.out);\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

    }

    /**
     * Special 'function' types are injected into every compilation to ensure all methods can be represented with function field
     * This is in parallel to Java8 java.util.function.Function class which only supports unary or binary functions
     * @throws Exception
     */
    public void testTestabilityInjectedFunctions() throws Exception {


        String[] task = {
                "X.java",
                "public class X {\n" +
                        "   Function0<Boolean> x0;\n" +
                        "   Function1<Integer, Boolean> x1;\n" +
                        "   Function2<Integer, String, Boolean> x2;\n" +
                        "   Function3<Integer, String, Float, Boolean> x3;\n" +
//                        "Function21<Integer,Integer,Integer,Integer,Integer,Integer,Integer,Integer," +
//                        "Integer,Integer,Integer,Integer,Integer,Integer,Integer,Integer,Integer,Integer," +
//                        "Integer,Integer,Integer,Boolean> x21;\n" +
                        "}"
        };

        String expectedOutput = task[1];

        String actual = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n"));
        assertEquals(expectedOutput, actual);

    }
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
                "public class X {\n" +
                        "   public Function1<String, Void> $$Object$notify = (var1) -> {\n" +
                        "      var1.notify();\n" +
                        "      return null;\n"+
                        "   };\n\n" +
                        "   void fn() {\n" +
                        "      String var1 = \"\";\n" +
                        "      this.$$Object$notify.apply(var1);\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

    }
    public void testTestabilityInjectFunctionField_ForNewOperator() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(){new String();}" +
                        "}\n"
        };
        String expectedOutput =
                        "public class X {\n" +
                        "   public Function0<String> $$String$new = () -> {\n" +
                        "      return new String();\n" +
                        "   };\n\n" +
                        "   void fn() {\n" +
                        "      this.$$String$new.apply();\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

    }

    public void testTestabilityInjectFunctionField_ForNewOperator___explore_cast() throws Exception {

        String[] task = {
                "X.java",
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
    }


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
                        "     x.$$String$new = (arg) -> {dontredirect:return new String(\"redirected\");};" +
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
                        "      dontredirect: new Y().$$String$new.apply(\"new\");\n" +
                        "   }\n" +
                        "}"
        };
        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

    }
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
                        "     x.$$String$new = (arg) -> {return new String(\"redirected\");};" +
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

    public void testTestabilityInjectFunctionField_Reproduction2reference() throws Exception {


        String[] task = {
                "X.java",
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
    public void testTestabilityInjectFunctionField_ForNewOperatorInsideLambdaField() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(){ Function1<String, String> f = (arg) -> {return new String(\"x\");};}" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
    }
    public void testTestabilityInjectFunctionField_ForNewOperatorInsideInitializerLambdaField() throws Exception {

        //error reported at QualifiedNameReference#1031 this.indexOfFirstFieldBinding == 1
        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	Function1<String, String> f = (arg) -> {return new String(\"x\");};" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
    }

//    public void testTestabilityInjectFunctionField_ForNewOperatorInsideInitializerLambdaField_Reproduction() throws Exception {
//        //TODO Pb(75) Cannot reference a field before it is defined
//        //methodScope.lastVisibleFieldId = -1 when working, no check; this is 'scope' field of LambdaExpression, created in its resolveType
//        //which is based on its methodScope -e.g fn() when good
//        // ->initial value 0 when bad
//        String[] task = {
//                "X.java",
//                "public class X {\n" +
//                        "   Function1<String, String> ff = (a) -> \"\";" +
//
//                        "   Function1<String, String> f = (arg) -> {dontredirect:return ff.apply(\"\");};\n" +
//
//
//                        "}\n"
//        };
//
//        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
//
//    }

    public void testTestabilityInjectFunctionField_ForApply() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "   Function1<String, String> ff = (a) -> a;" +
                        "	void fn(){\n" +
                        "     Function1<String, String> f = (arg) -> {ff.apply(\"\");return \"\";};\n" +
                        "     assert f!=null;" +
                        "   };" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

    }
    public void testTestabilityInjectFunctionField_ForExternalCallArrayArg() throws Exception {

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

        String expectedOutput = "public class X {\n" +
                "   public Function1<int[], Integer> $$Y$arrayArg = (var1) -> {\n" +
                "      return Integer.valueOf(Y.arrayArg(var1));\n" +
                "   };\n" +
                "\n" +
                "   public int fn() {\n" +
                "      int[] var1 = new int[]{1, 2};\n" +
                "      return ((Integer)this.$$Y$arrayArg.apply(var1)).intValue();\n" +
                "   }\n" +
                "}";

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

        Object actual = invokeCompiledMethod("X","fn");
        assertEquals(2, actual);

    }

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
                "public class X {\n" +
                        "   public Function1<String, String> $$String$new = (var1) -> {\n" +
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
                        "      this.$$String$new.apply(\"d\");\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

    }
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
                "public class X {\n" +
                        "   public Function1<String, Integer> $$Integer$parseInt = (var1) -> {\n" +
                        "      return Integer.valueOf(Integer.parseInt(var1));\n" +
                        "   };\n\n" +
                        "   void fn() {\n" +
                        "      Integer.parseInt(\"0\");\n" +
                        "      this.$$Integer$parseInt.apply(\"1\");\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

    }
    public void testTestabilityInjectFunctionField_ExternalCall_ReturningPrimitiveType() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	int fn(){\n" +
                        "     return Integer.parseInt(\"1\");" +
                        "   };\n" +
                        "   public static int exec() {\n" +
                        "     return new X().fn();\n" +
                        "   }\n" +
                        "}\n"
        };

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        String expectedOutput =
                "public class X {\n" +
                        "   public Function1<String, Integer> $$Integer$parseInt = (var1) -> {\n" +
                        "      return Integer.valueOf(Integer.parseInt(var1));\n" +
                        "   };\n\n" +
                        "   int fn() {\n" +
                        "      return ((Integer)this.$$Integer$parseInt.apply(\"1\")).intValue();\n" +
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

    public void testTestabilityInjectFunctionField_ForNewOperatorInsideLambdaWithArg() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(){\n" +
                        "     Function1<String, String> f = (arg) -> {return new String(\"x\");};\n" +
                        "     assert f!=null;" +
                        "   };" +
                        "   public static void exec(){\n" +
                        "     new X().fn();" +
                        "   }\n" +
                        "}\n"
        };

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        String expectedOutput =
                "public class X {\n" +
                "   public Function1<String, String> $$String$new = (var1) -> {\n" +
                "      return new String(var1);\n" +
                "   };\n" +
                "\n" +
                "   void fn() {\n" +
                "      Function1 var1 = (var1) -> {\n" +
                "         return (String)this.$$String$new.apply(\"x\");\n" +
                "      };\n" +
                "\n" +
                "      assert var1 != null;\n" +
                "\n" +
                "   }\n" +
                "\n" +
                "   public static void exec() {\n" +
                "      (new X()).fn();\n" +
                "   }\n" +
                "}";

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        main.invoke(null);
    }
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
    public void testTestabilityInjectFunctionField_ForNewOperatorPassingArgsThrough() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(StringBuilder x){new String(x);}" +
                        "}\n"
        };
        String expectedOutput =
                        "public class X {\n" +
                        "   public Function1<StringBuilder, String> $$String$new = (var1) -> {\n" +
                        "      return new String(var1);\n" +
                        "   };\n\n" +
                        "   void fn(StringBuilder var1) {\n" +
                        "      this.$$String$new.apply(var1);\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));
    }
    public void testTestabilityInjectFunctionField_ForNewOperatorReturn() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	String fn(){ return new String();}" +
                        "}\n"
        };
        String expectedOutput =
                "public class X {\n" +
                        "   public Function0<String> $$String$new = () -> {\n" +
                        "      return new String();\n" +
                        "   };\n\n" +
                        "   String fn() {\n" +
                        "      return (String)this.$$String$new.apply();\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

    }



    public void testTestabilityInjectFunctionField_ForExternalCallReturn() throws Exception {

        String[] task = {
                "X.java",
                "import java.io.PrintStream;\n" +
                "public class X {\n" +
                        "	PrintStream fn(){return System.out.append('c');}" +
                        "   public static PrintStream exec(){return new X().fn();}\n" +
                        "}\n"
        };
        String expectedOutput =
                "import java.io.PrintStream;\n\n" +
                        "public class X {\n" +
                        "   public Function2<PrintStream, Character, PrintStream> $$PrintStream$append = (var1, var2) -> {\n" +
                        "      return var1.append(var2.charValue());\n" +
                        "   };\n\n" +
                        "   PrintStream fn() {\n" +
                        "      return (PrintStream)this.$$PrintStream$append.apply(System.out, Character.valueOf('c'));\n" +
                        "   }\n\n" +
                        "   public static PrintStream exec() {\n" +
                        "      return (new X()).fn();\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals(System.out, ret);


        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

    }
    public void testTestabilityInjectFunctionField_ForNewOperatorFromStaticContextForNow() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "   static void fn() {\n" +
                        "      new String();\n" +
                        "   }\n" +
                        "}"
        };
        String expectedOutput = task[1];

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

    }

    public void testTestabilityInjectFunctionField_getClass() throws Exception {
        //incompatible CaptionBinding generated. getClass is specialcased, should not be bypassed

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	public void fn(){X x = this;x.getClass();}" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        Object actual = invokeCompiledMethod("X","fn");

        assertEquals(null, actual);
    }
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

    public void testTestabilityInjectFunctionField_reproduceLambdaExpressionsParameterType() throws Exception {
// File X.java has ERROR:	Pb(657) Lambda expression's parameter  arg0 is expected to be of type Function0<String>
// File X.java has ERROR:	Pb(657) Lambda expression's parameter  arg0 is expected to be of type Function0<String>
// File X.java has ERROR:	Pb(657) Lambda expression's parameter  arg0 is expected to be of type Consumer<X>
// is it reference to:
//  injected field: Function1<Function0<java.lang.String>, java.lang.String> $$Function0$apply = (Function0<java.lang.String>  arg0) -> {
//        testabilitylabel: ;
//        return  arg0.apply();
//    };
// in y.$$String$new.apply() in passing field value (instance) before empty args?
// -> no, in field decl. TypeBinding.notEquals(argumentType, expectedParameterType). ids are different, although they look same: 179, 177
        //is it because we do not store/retrieve type from cache, see TypeSystem?
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
                        "      y.$$String$new.apply();" +
                        "   }" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_ALL);
    }
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
                        "	public void withRedirectorReferences(){Y y = new Y();y.$$String$new.apply();}" +
                        "	public List<String> fieldNames(){dontredirect: return Arrays.stream(this.getClass().getDeclaredFields()).map(Field::getName).collect(Collectors.toList());}" +
                        "}\n"
        };

        //here X appears to be a test class since it references redirector field from Y

        compileAndDisassemble(task, INSERT_ALL);

        Object actualX = invokeCompiledMethod("X","fieldNames");

        assertEquals(Collections.emptyList(), actualX);

        Object actualY = invokeCompiledMethod("Y","fieldNames");

        assertEquals(Arrays.asList("$$preCreate", "$$postCreate", "$$String$new"), actualY);

    }
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
                        "	public void withRedirectorReferences(){Y y = new Y();y.$$String$new = ()->\"new\";}" +
                        "	public List<String> fieldNames(){dontredirect: return Arrays.stream(this.getClass().getDeclaredFields()).map(Field::getName).collect(Collectors.toList());}" +
                        "}\n"
        };

        //here X appears to be a test class since it references redirector field from Y

        compileAndDisassemble(task, INSERT_ALL);

        Object actualX = invokeCompiledMethod("X","fieldNames");

        assertEquals(Collections.emptyList(), actualX);
    }
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


}
