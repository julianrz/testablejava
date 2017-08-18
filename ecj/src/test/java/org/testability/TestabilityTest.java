package org.testability;

import static java.util.stream.Collectors.joining;

/**
 * Created by julianrozentur1 on 6/23/17.
 */
public class TestabilityTest extends BaseTest {
    public TestabilityTest(String name) {
        super(name);
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

        assertEquals(expectedOutput, compileAndDisassemble(task).get("X").stream().collect(joining("\n")));
    }

    public void testTestabilityInjectFunctionField_ForStaticCallNoArgs() throws Exception {

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
    public void testTestabilityInjectFunctionField_ForStaticCallPassingArgsThrough() throws Exception {

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

    public void testTestabilityInjectFunctionField_ForStaticCallPassingInAConstant() throws Exception {

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
    public void testTestabilityInjectFunctionField_ForStaticCallBaseClass() throws Exception {

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
                        "      var0.toString();\n" +
                        "      return null;\n" +
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

}
