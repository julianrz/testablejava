package org.testability;

import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.List;
import java.util.Map;

import static java.util.stream.Collectors.joining;

/**
 * Created by julianrozentur1 on 6/23/17.
 */
public class TestabilityTest extends BaseTest {
    public TestabilityTest(String name) {
        super(name);
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

        assertEquals(expectedOutput, compileAndDisassemble(task).get("X").stream().collect(joining("\n")));
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
                        "   Function1<PrintStream, Void> $$java$io$PrintStream$println = (var0) -> {\n" +
                        "      var0.println();\n" +
                        "      return null;\n" +
                        "   };\n\n" +
                        "   void fn() {\n" +
                        "      this.$$java$io$PrintStream$println.apply(System.out);\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task).get("X").stream().collect(joining("\n")));

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
                        "   Function1<Integer, Integer> $$java$lang$Integer$valueOf = (var0) -> {\n" +
                        "      return Integer.valueOf(var0.intValue());\n" +
                        "   };\n\n" +
                        "   void fn() {\n" +
                        "      this.$$java$lang$Integer$valueOf.apply(Integer.valueOf(2));\n" +
                        "   }\n\n" +
                        "   public static void exec() throws Exception {\n" +
                        "      (new X()).fn();\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task);

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
                        "   Function1<Integer, Integer> $$java$lang$Integer$valueOf = (var0) -> {\n" +
                        "      return Integer.valueOf(var0.intValue());\n" +
                        "   };\n\n" +
                        "   void fn() {\n" +
                        "      this.$$java$lang$Integer$valueOf.apply(Integer.valueOf(2));\n" +
                        "   }\n\n" +
                        "   public static void exec() throws Exception {\n" +
                        "      (new X()).fn();\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task);

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
                        "   Function2<String, Integer, Integer> $$java$lang$Integer$getInteger = (var0, var1) -> {\n" +
                        "      return Integer.getInteger(var0, var1);\n" +
                        "   };\n" +
                        "   Function1<Integer, Integer> $$java$lang$Integer$valueOf = (var0) -> {\n" +
                        "      return Integer.valueOf(var0.intValue());\n" +
                        "   };\n\n" +
                        "   void fn() {\n" +
                        "      this.$$java$lang$Integer$getInteger.apply(\"1\", (Integer)this.$$java$lang$Integer$valueOf.apply(Integer.valueOf(2)));\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task).get("X").stream().collect(joining("\n")));

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

        assertEquals(expectedOutput, compileAndDisassemble(task).get("X").stream().collect(joining("\n")));

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
                        "   Function1<PrintStream, Void> $$java$io$PrintStream$println = (var0) -> {\n" +
                        "      var0.println();\n" +
                        "      return null;\n" +
                        "   };\n\n" +
                        "   void fn1() {\n" +
                        "      this.$$java$io$PrintStream$println.apply(System.out);\n" +
                        "   }\n\n" +
                        "   void fn2() {\n" +
                        "      this.$$java$io$PrintStream$println.apply(System.out);\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task).get("X").stream().collect(joining("\n")));

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
                        "   Function2<PrintStream, Integer, Void> $$java$io$PrintStream$write = (var0, var1) -> {\n" +
                        "      var0.write(var1.intValue());\n" +
                        "      return null;\n" +
                        "   };\n\n" +
                        "   void fn() throws Exception {\n" +
                        "      int var1 = 434242342;\n" +
                        "      this.$$java$io$PrintStream$write.apply(System.out, Integer.valueOf(var1));\n" +
                        "   }\n\n" +
                        "   public static void exec() throws Exception {\n" +
                        "      (new X()).fn();\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task);
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
                        "   Function1<Integer, BigDecimal> $$java$math$BigDecimal$new = (var0) -> {\n" +
                        "      return new BigDecimal(var0.intValue());\n" +
                        "   };\n\n" +
                        "   void fn() throws Exception {\n" +
                        "      int var1 = 434242342;\n" +
                        "      this.$$java$math$BigDecimal$new.apply(Integer.valueOf(var1));\n" +
                        "   }\n\n" +
                        "   public static void exec() throws Exception {\n" +
                        "      (new X()).fn();\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task);
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
                        "   Function2<PrintStream, Integer, Void> $$java$io$PrintStream$write = (var0, var1) -> {\n" +
                        "      var0.write(var1.intValue());\n" +
                        "      return null;\n" +
                        "   };\n\n" +
                        "   void fn() throws Exception {\n" +
                        "      this.$$java$io$PrintStream$write.apply(System.out, Integer.valueOf(434242342));\n" +
                        "   }\n\n" +
                        "   public static void exec() throws Exception {\n" +
                        "      (new X()).fn();\n" +
                        "   }\n"+
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task);
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
                        "   Function1<Integer, BigDecimal> $$java$math$BigDecimal$new = (var0) -> {\n" +
                        "      return new BigDecimal(var0.intValue());\n" +
                        "   };\n\n" +
                        "   void fn() throws Exception {\n" +
                        "      this.$$java$math$BigDecimal$new.apply(Integer.valueOf(434242342));\n" +
                        "   }\n\n" +
                        "   public static void exec() throws Exception {\n" +
                        "      (new X()).fn();\n" +
                        "   }\n"+
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task);
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
                        "   Function2<PrintStream, Integer, Void> $$java$io$PrintStream$write = (var0, var1) -> {\n" +
                        "      var0.write(var1.intValue());\n" +
                        "      return null;\n" +
                        "   };\n" +
                        "   Function1<PrintStream, Void> $$java$io$PrintStream$close = (var0) -> {\n" +
                        "      var0.close();\n" +
                        "      return null;\n" +
                        "   };\n\n" +
                        "   void fn() throws Exception {\n" +
                        "      PrintStream var1 = System.out;\n" +
                        "      Integer var2 = Integer.valueOf(0);\n"+
                        "      this.$$java$io$PrintStream$write.apply(var1, var2.intValue());\n" +
                        "      this.$$java$io$PrintStream$close.apply(var1);\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task).get("X").stream().collect(joining("\n")));
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
                        "   Function2<PrintStream, String, Void> $$java$io$PrintStream$println = (var0, var1) -> {\n" +
                        "      var0.println(var1);\n" +
                        "      return null;\n" +
                        "   };\n\n" +
                        "   void fn(String var1) {\n" +
                        "      this.$$java$io$PrintStream$println.apply(System.out, var1);\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task).get("X").stream().collect(joining("\n")));
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
                        "   Function2<PrintStream, String, Void> $$java$io$PrintStream$println = (var0, var1) -> {\n" +
                        "      var0.println(var1);\n" +
                        "      return null;\n" +
                        "   };\n\n" +
                        "   void fn() {\n" +
                        "      this.$$java$io$PrintStream$println.apply(System.out, \"x\");\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task).get("X").stream().collect(joining("\n")));

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
                        "   Function1<PrintStream, String> $$java$lang$Object$toString = (var0) -> {\n" +
                        "      return var0.toString();\n" +
                        "   };\n" +
                        "\n" +
                        "   void fn() {\n" +
                        "      this.$$java$lang$Object$toString.apply(System.out);\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task).get("X").stream().collect(joining("\n")));

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

        String actual = compileAndDisassemble(task).get("X").stream().collect(joining("\n"));
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
                        "   Function1<String, Void> $$java$lang$Object$notify = (var0) -> {\n" +
                        "      var0.notify();\n" +
                        "      return null;\n"+
                        "   };\n\n" +
                        "   void fn() {\n" +
                        "      String var1 = \"\";\n" +
                        "      this.$$java$lang$Object$notify.apply(var1);\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task).get("X").stream().collect(joining("\n")));

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
                        "   Function0<String> $$java$lang$String$new = () -> {\n" +
                        "      return new String();\n" +
                        "   };\n\n" +
                        "   void fn() {\n" +
                        "      this.$$java$lang$String$new.apply();\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task).get("X").stream().collect(joining("\n")));

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
        compileAndDisassemble(task);
    }


    public void testTestabilityInjectFunctionField_ForNewOperatorWithExecute() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	String fn(){return new String(\"x\");}" +
                        "   public static String exec(){return new X().fn();}" +
                        "}\n"
        };


        compileAndDisassemble(task);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals((String)ret, "x");


    }
    public void testTestabilityInjectFunctionField_ForNewOperatorAssignedLambdaNotExpanded() throws Exception {

        //when we reassign a field, supplied code (lambda set in exec2) should not be subject to rewrite
        //TODO need a way to mark code so that it is not rewritten
        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	String fn(){return new String(\"x\");}" +
                        "}\n",
                "Y.java",
                "public class Y {\n" +
                        "	String exec2(){" +
                        "     X x = new X();\n" +
                        "     x.$$java$lang$String$new = (arg) -> new String(\"redirected\");" +
                        "     return x.fn();" +
                        "   }" +
                        "   public static String exec(){\n" +
                        "     return new Y().exec2();" +
                        "   }\n" +
                        "}"
        };

        compileAndDisassemble(task);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("Y").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals((String)ret, "redirected");


    }
    public void testTestabilityInjectFunctionField_ForExternalCallWithExecute() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "   java.io.PrintStream fn(String x){return System.out.append(x);}\n" +
                        "   public static java.io.PrintStream exec(){return new X().fn(\"x\");}\n" +
                        "}\n"
        };

        compileAndDisassemble(task);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals(ret, System.out);
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
                        "   Function1<StringBuilder, String> $$java$lang$String$new = (var0) -> {\n" +
                        "      return new String(var0);\n" +
                        "   };\n\n" +
                        "   void fn(StringBuilder var1) {\n" +
                        "      this.$$java$lang$String$new.apply(var1);\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task).get("X").stream().collect(joining("\n")));
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
                        "   Function0<String> $$java$lang$String$new = () -> {\n" +
                        "      return new String();\n" +
                        "   };\n\n" +
                        "   String fn() {\n" +
                        "      return (String)this.$$java$lang$String$new.apply();\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task).get("X").stream().collect(joining("\n")));

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
                        "   Function2<PrintStream, Character, PrintStream> $$java$io$PrintStream$append = (var0, var1) -> {\n" +
                        "      return var0.append(var1.charValue());\n" +
                        "   };\n\n" +
                        "   PrintStream fn() {\n" +
                        "      return (PrintStream)this.$$java$io$PrintStream$append.apply(System.out, Character.valueOf('c'));\n" +
                        "   }\n\n" +
                        "   public static PrintStream exec() {\n" +
                        "      return (new X()).fn();\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals(ret, System.out);


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

        assertEquals(expectedOutput, compileAndDisassemble(task).get("X").stream().collect(joining("\n")));

    }

}
