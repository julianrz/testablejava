package org.testability;

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

//    public void testTestabilityInjectFunctionField_Reproduction() throws Exception {
//
//        String[] task = {
//                "X.java",
//                "public class X {\n" +
//                        "   public static void main(String[]args){\n" +
//                        "       new X();\n" +
//                        "   }" +
//                        "}\n"
//        };
//        String expectedOutput = task[1];
//
//        assertEquals(expectedOutput, compileAndDisassemble(task).get("X").stream().collect(joining("\n")));
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
                        "      this.$$java$io$PrintStream$write.apply(System.out, var1);\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task).get("X").stream().collect(joining("\n")));
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
                        "      return this.$$java$lang$String$new.apply();\n" +
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
                        "}\n"
        };
        String expectedOutput =
                "import java.io.PrintStream;\n\n" +
                        "public class X {\n" +
                        "   Function2<PrintStream, Character, PrintStream> $$java$io$PrintStream$append = (var0, var1) -> {\n" +
                        "      return var0.append(var1.charValue());\n" +
                        "   };\n\n" +
                        "   PrintStream fn() {\n" +
                        "      return this.$$java$io$PrintStream$append.apply(System.out, 'c');\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task).get("X").stream().collect(joining("\n")));

    }

}
