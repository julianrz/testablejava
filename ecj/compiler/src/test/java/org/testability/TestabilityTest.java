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
        assertEquals(expectedOutput, stringListMap.get("a/X").stream().collect(joining("\n")));
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
    public void testTestabilityInjectFunctionField_ReproFinalize() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn() throws Throwable {" +
                        "      super.finalize();" +
                        "   }" +
                        "}"
        };

        String expectedOutput =
                "import X.1;\n" +
                        "import helpers.Consumer1;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "public class X {\n" +
                        "   public static Consumer1<CallContext<Object>> $$Object$finalize = new 1();\n" +
                        "\n" +
                        "   void fn() throws Throwable {\n" +
                        "      $$Object$finalize.accept(new CallContext(\"X\", \"java.lang.Object\", this, this));\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        invokeCompiledMethod("X", "fn");
        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));
    }

    @Test
    public void testTestabilityInjectFunctionField_ReflectiveInstanceProtectedCall() throws Exception {

        String[] task = {
                "X.java",
                "package a;" +
                        "public class X {\n" +
                        "	protected int fn(String s) {dontredirect: return s.length();}" +
                        "   public int innerCaller(){return -1;}" +
                        "}",
                "Y.java",
                "public class Y  {\n" +
                        "	int caller() {" +
                        "      a.X x = new a.X(){" +
                        "          @Override public int innerCaller(){" +
                        "              return fn(\"abc\");" +
                        "          }" +
                        "      };" +
                        "      dontredirect: return x.innerCaller();" +
                        "   }" +
                        "}"
        };

        String expectedOutput =
                "import a.X;\n" +
                        "import helpers.Function2;\n" +
                        "import testablejava.CallContext;\n" +
                        "import testablejava.ReflectiveCaller;\n" +
                        "\n" +
                        "class Y$2 implements Function2<CallContext<X>, String, Integer> {\n" +
                        "   public Integer apply(CallContext<X> var1, String var2) {\n" +
                        "      return (Integer)(new ReflectiveCaller(((X)var1.calledClassInstance).getClass(), \"fn\", new Class[]{String.class})).apply(var1.calledClassInstance, new Object[]{var2});\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals(3, invokeCompiledMethod("Y", "caller"));
        assertEquals(expectedOutput, moduleMap.get("Y$2").stream().collect(joining("\n")));
        //TODO check reflection arg forming and valid return in various situations
    }
    @Test
    public void testTestabilityInjectFunctionField_CallOfMethodDefinedOnAnonType_ForceReflective() throws Exception {

        String[] task = {
                "X.java",
                        "public class X {\n" +
                        "   class Y{}\n" +
                        "	int fn() {" +
                        "       return new Y(){" +
                        "          int f(){return 1;}" +
                        "       }.f();" +
                        "   }" +
                        "}",
        };

        String expectedOutput =
                "import X.Y;\n" +
                        "import helpers.Function1;\n" +
                        "import testablejava.CallContext;\n" +
                        "import testablejava.ReflectiveCaller;\n" +
                        "\n" +
                        "class X$2 implements Function1<CallContext<Y>, Integer> {\n" +
                        "   public Integer apply(CallContext<Y> var1) {\n" +
                        "      return (Integer)(new ReflectiveCaller(((Y)var1.calledClassInstance).getClass(), \"f\", new Class[0])).apply(var1.calledClassInstance, new Object[0]);\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals(expectedOutput, moduleMap.get("X$2").stream().collect(joining("\n")));
        assertEquals(1, invokeCompiledMethod("X", "fn"));

    }
    @Test
    public void testTestabilityInjectFunctionField_CallOfMethodOverridenOnAnonType_NonReflective() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "   class Y{" +
                        "       String f(){return null;}" +
                        "   };\n" +
                        "	String fn() {" +
                        "       return new Y(){" +
                        "          String f(){return \"\";}" + //overrides
                        "       }.f();" +
                        "   }" +
                        "}",
        };

        String expectedOutput =
                "import X.1;\n" +
                        "import X.Y;\n" +
                        "import helpers.Function1;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "public class X {\n" +
                        "   public static Function1<CallContext<Y>, String> $$Y$f = (var0) -> {\n" +
                        "      return ((Y)var0.calledClassInstance).f();\n" +
                        "   };\n" +
                        "\n" +
                        "   String fn() {\n" +
                        "      return (String)$$Y$f.apply(new CallContext(\"X\", \"new X.Y(){}\", this, new 1(this, this)));\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals("", invokeCompiledMethod("X","fn"));
        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

    }

    @Test
    public void testTestabilityInjectFunctionField_ReflectiveStaticProtectedCall() throws Exception {

        String[] task = {
                "X.java",
                "package a;" +
                        "public class X {\n" +
                        "	protected static int fn(String s) {dontredirect: return s.length();}" +
                        "   public int innerCaller(){return -1;}" +
                        "}",
                "Y.java",
                "public class Y  {\n" +
                        "	int caller() {" +
                        "      a.X x = new a.X(){" +
                        "          @Override public int innerCaller(){" +
                        "              return a.X.fn(\"abc\");" +
                        "          }" +
                        "      };" +
                        "      dontredirect: return x.innerCaller();" +
                        "   }" +
                        "}"
        };

        String expectedOutput =
                "import a.X;\n" +
                        "import helpers.Function2;\n" +
                        "import testablejava.CallContext;\n" +
                        "import testablejava.ReflectiveCaller;\n" +
                        "\n" +
                        "class Y$2 implements Function2<CallContext<X>, String, Integer> {\n" +
                        "   public Integer apply(CallContext<X> var1, String var2) {\n" +
                        "      return (Integer)(new ReflectiveCaller(X.class, \"fn\", new Class[]{String.class})).apply((Object)null, new Object[]{var2});\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals(3, invokeCompiledMethod("Y", "caller"));
        assertEquals(expectedOutput, moduleMap.get("Y$2").stream().collect(joining("\n")));
    }

    @Test
    public void testTestabilityInjectFunctionField_InnerClassThisReferenceInCalledClassInstance() throws Exception {

        //no need to qualify 'this' when forming calledClassInstance for call that resides in subclass
        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(String s) {}" +
                        "}",
                "Y.java",
                "public class Y  {\n" +
                        "	void caller() {" +
                        "      new X(){" +
                        "          void innerCaller(){" +
                        "              fn(\"\");" +
                        "          }" +
                        "      };" +
                        "   }" +
                        "}"
        };

        String expectedOutput =
                "import testablejava.CallContext;\n" +
                        "\n" +
                        "class Y$1 extends X {\n" +
                        "   Y$1(Y var1) {\n" +
                        "      this.this$0 = var1;\n" +
                        "   }\n" +
                        "\n" +
                        "   void innerCaller() {\n" +
                        "      Y.$$X$fn$$String.accept(new CallContext(\"X\", \"new X(){}\", this, this), \"\");\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        assertEquals(expectedOutput, moduleMap.get("Y$1").stream().collect(joining("\n")));
    }


    //TODO reen
//    @Test
//    public void testTestabilityInjectFunctionField_PrivateMethodCall() throws Exception {
//
//
////        new X().fnReflectiveCall();
//
//        String[] task = {
//                "Y.java",
//                "public class Y {\n" +
//                "	private void privateMethod(){}" +
//                "}",
//                "X.java",
//                "public class X {\n" +
//                "	void fn(){" +
//                "      Y y;" +
//                "      dontredirect: y = new Y();" +
//                "      y.privateMethod();" +
//                "   }" +
//                "}"
//        };
//
//        String expectedOutput =
//                "";
//
//        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
//        invokeCompiledMethod("X","fn");
//        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));
//    }
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
                "import java.util.function.Consumer;\n" +
                        "\n" +
                        "public class X {\n" +
                        "   public static Consumer<X> $$preCreate = (var0) -> {\n" +
                        "   };\n" +
                        "   public static Consumer<X> $$postCreate = (var0) -> {\n" +
                        "   };\n" +
                        "   static int i = 1;\n" +
                        "\n" +
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
                        "	X() {dontredirect:System.out.println();}\n" +
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
    public void testTestabilityInjectFunctionField_ForNewOperatorCallbackFromMemberClass() throws Exception {

        String[] task = {
                "X.java",
                "import java.util.Comparator;\n" +
                        "public class X {\n" +
                        "   class Y {}\n" +
                        "	X() {dontredirect:System.out.println();}\n" +
                        "	void fn() {new Comparator<String>(){\n" +
                        "            @Override\n" +
                        "            public int compare(String o1, String o2) {\n" +
                        "                return o1.compareTo(o2);\n" +
                        "            }\n" +
                        "        };}" +
                        "}\n"
        };

        String expectedOutputX =
                "import X.1;\n" +
                        "import X.Y;\n" +
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
                        "   };\n" +
                        "   public static Consumer<Y> $$Y$preCreate = (var0) -> {\n" +
                        "   };\n" +
                        "   public static Consumer<Y> $$Y$postCreate = (var0) -> {\n" +
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
        String expectedOutputY =
                "class X$Y {\n" +
                        "   X$Y(X var1) {\n" +
                        "      this.this$0 = var1;\n" +
                        "      X.$$Y$preCreate.accept(this);\n" +
                        "      X.$$Y$postCreate.accept(this);\n" +
                        "   }\n" +
                        "}";
        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_LISTENERS_ONLY);
        assertEquals(expectedOutputX, moduleMap.get("X").stream().collect(joining("\n")));
        assertEquals(expectedOutputY, moduleMap.get("X$Y").stream().collect(joining("\n")));
    }

    @Test
    public void testTestabilityInjectFunctionField_AnonymousTypeNewOperatorNotRedirected() throws Exception {
        String[] task = {
                "X.java",
                "import java.util.*;\n" +
                        "public class X {\n" +
                        "	void fn() {" +
                        "     new Comparator<Object>() {\n" +
                        "                public int compare(Object o1, Object o2) {\n" +
                        "                    return -1;\n" +
                        "                }\n" +
                        "            };" +
                        "  }" +
                        "}\n"
        };

        String expectedOutput = "import X.1;\n" +
                "\n" +
                "public class X {\n" +
                "   void fn() {\n" +
                "      new 1(this);\n" +
                "   }\n" +
                "}";
        String expectedOutputInner = "import java.util.Comparator;\n" +
                "\n" +
                "class X$1 implements Comparator<Object> {\n" +
                "   X$1(X var1) {\n" +
                "      this.this$0 = var1;\n" +
                "   }\n" +
                "\n" +
                "   public int compare(Object var1, Object var2) {\n" +
                "      return -1;\n" +
                "   }\n" +
                "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));
        assertEquals(expectedOutputInner, moduleMap.get("X$1").stream().collect(joining("\n")));

    }

    @Test
    public void testTestabilityInjectFunctionField_AnonymousTypeNewOperatorNotRedirectedWhenFieldExists() throws Exception {
        //here field should be created from new Y() and just one redirect of new Y(). Anonymous class 'new' operator not redirected
        String[] task = {
                "X.java",
                "import java.util.*;\n" +
                        "public class X {\n" +
                        "   class Y{}" +
                        "	void fn() {" +
                        "     new Y(); " +
                        "     new Y(){};" +
                        "  }" +
                        "}\n"
        };

        String expectedOutput = "import X.1;\n" +
                "import X.Y;\n" +
                "import helpers.Function1;\n" +
                "import testablejava.CallContext;\n" +
                "\n" +
                "public class X {\n" +
                "   public static Function1<CallContext<Y>, Y> $$Y$new = (var0) -> {\n" +
                "      X var10002 = (X)var0.enclosingInstances[0];\n" +
                "      ((X)var0.enclosingInstances[0]).getClass();\n" +
                "      return new Y(var10002);\n" +
                "   };\n" +
                "\n" +
                "   void fn() {\n" +
                "      $$Y$new.apply(new CallContext(\"X\", \"X.Y\", this, (Object)null, new Object[]{this}));\n" +
                "      new 1(this, this);\n" +
                "   }\n" +
                "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));
    }

    @Test
    public void testTestabilityInjectFunctionField_AnonymousTypeOrNamedTypeCreatesField() throws Exception {
        //here field should be created from new Y() even though it is 2nd call and just one redirect of new Y(). Anonymous class 'new' operator not redirected
        String[] task = {
                "X.java",
                "import java.util.*;\n" +
                        "public class X {\n" +
                        "   class Y{}" +
                        "	void fn() {" +
                        "     new Y(){};" +
                        "     new Y(); " +
                        "  }" +
                        "}\n"
        };

        String expectedOutput = "import X.1;\n" +
                "import X.Y;\n" +
                "import helpers.Function1;\n" +
                "import testablejava.CallContext;\n" +
                "\n" +
                "public class X {\n" +
                "   public static Function1<CallContext<Y>, Y> $$Y$new = (var0) -> {\n" +
                "      X var10002 = (X)var0.enclosingInstances[0];\n" +
                "      ((X)var0.enclosingInstances[0]).getClass();\n" +
                "      return new Y(var10002);\n" +
                "   };\n" +
                "\n" +
                "   void fn() {\n" +
                "      new 1(this, this);\n" +
                "      $$Y$new.apply(new CallContext(\"X\", \"X.Y\", this, (Object)null, new Object[]{this}));\n" +
                "   }\n" +
                "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));
    }

    @Test
    public void testTestabilityInjectFunctionField_LocalTypeNewOperatorNotRedirected() throws Exception {
        String[] task = {
                "X.java",
                "import java.util.*;\n" +
                        "public class X {\n" +
                        "	void fn() {" +
                        "     class Y{}" +
                        "     new Y();" +
                        "  }" +
                        "}\n"
        };

        String expectedOutput = "import X.1Y;\n" +
                "\n" +
                "public class X {\n" +
                "   void fn() {\n" +
                "      new 1Y(this);\n" +
                "   }\n" +
                "}";
        String expectedOutputInner = "class X$1Y {\n" +
                "   X$1Y(X var1) {\n" +
                "      this.this$0 = var1;\n" +
                "   }\n" +
                "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));
        assertEquals(expectedOutputInner, moduleMap.get("X$1Y").stream().collect(joining("\n")));

    }

    @Test
    public void testTestabilityInjectFunctionField_CompilationError() throws Exception {
        String[] task = {
                "X.java",
                "import java.util.*;\n" +
                        "public class X {\n" +
                        "	void fn() {" +
                        "     new Comparator<>() {\n" + //<> is syntax error
                        "                public int compare(Object o1, Object o2) {\n" +
                        "                    return -1;\n" +
                        "                }\n" +
                        "            };" +
                        "  }" +
                        "}\n"
        };

        try {
            compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        } catch (Exception ex) {
            assertFalse("should not continue instrumentation after compiler error", ex.getMessage().contains("a field cannot be created"));
        }

    }

    @Test
    public void testTestabilityInjectFunctionField_ReproductionErrorMethodNotApplicable() throws Exception {
        String[] task = {
                "X.java",
                "import java.util.*;\n" +
                        "public class X {\n" +
                        "	void fn() {" +
                        "     List missingTypes = null;\n" +
                        "     Collections.sort(missingTypes, new Comparator() {\n" +
                        "                public int compare(Object o1, Object o2) {\n" +
                        "                    return -1;\n" +
                        "                }\n" +
                        "            });" +
                        "  }" +
                        "}\n"
        };

        String expectedOutput =
                "import X.1;\n" +
                        "import helpers.Consumer3;\n" +
                        "import java.util.Collections;\n" +
                        "import java.util.Comparator;\n" +
                        "import java.util.List;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "public class X {\n" +
                        "   public static Consumer3<CallContext<Collections>, List, Comparator> $$Collections$sort$$List$X$1 = (var0, var1, var2) -> {\n" +
                        "      Collections.sort(var1, var2);\n" +
                        "   };\n" +
                        "\n" +
                        "   void fn() {\n" +
                        "      Object var1 = null;\n" +
                        "      $$Collections$sort$$List$X$1.accept(new CallContext(\"X\", \"java.util.Collections\", this, (Object)null), var1, new 1(this));\n" +
                        "   }\n" +
                        "}";
        String expectedOutputInner = "import java.util.Comparator;\n" +
                "\n" +
                "class X$1 implements Comparator {\n" +
                "   X$1(X var1) {\n" +
                "      this.this$0 = var1;\n" +
                "   }\n" +
                "\n" +
                "   public int compare(Object var1, Object var2) {\n" +
                "      return -1;\n" +
                "   }\n" +
                "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));
        assertEquals(expectedOutputInner, moduleMap.get("X$1").stream().collect(joining("\n")));

    }

    @Test
    public void testTestabilityInjectFunctionField_RedirectInsideMemberClassCallingItself() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "   static class Member {\n" +
                        "	  void fn() {" +
                        "	     thisCall();" +
                        "     }" +
                        "	  static void thisCall() {}" +
                        "   }" +
                        "}\n"
        };

        String expectedOutput = "import X.Member;\n" +
                "import helpers.Consumer1;\n" +
                "import testablejava.CallContext;\n" +
                "\n" +
                "public class X {\n" +
                "   public static Consumer1<CallContext<Member>> $$X$Member$thisCall = (var0) -> {\n" +
                "      Member.thisCall();\n" +
                "   };\n" +
                "}";
        String expectedOutputInner = "import testablejava.CallContext;\n" +
                "\n" +
                "class X$Member {\n" +
                "   void fn() {\n" +
                "      X.$$X$Member$thisCall.accept(new CallContext(\"X.Member\", \"X.Member\", this, (Object)null));\n" +
                "   }\n" +
                "\n" +
                "   static void thisCall() {\n" +
                "   }\n" +
                "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));
        assertEquals(expectedOutputInner, moduleMap.get("X$Member").stream().collect(joining("\n")));
    }

    @Test
    public void testTestabilityInjectFunctionField_RedirectInsideConstructorCall() throws Exception {
        //see https://docs.oracle.com/javase/specs/jls/se7/html/jls-8.html#jls-8.8.7.1
        String[] task = {
                "X.java",
                "public class X {\n" +
                        "   X(String s){}" +
                        "   X(){" +
                        "     this(String.valueOf(0));" +
                        "   }" +
                        "}\n"
        };

        String expectedOutput = "import helpers.Function2;\n" +
                "import testablejava.CallContext;\n" +
                "\n" +
                "public class X {\n" +
                "   public static Function2<CallContext<String>, Integer, String> $$String$valueOf$$I = (var0, var1) -> {\n" +
                "      return String.valueOf(var1);\n" +
                "   };\n" +
                "\n" +
                "   X(String var1) {\n" +
                "   }\n" +
                "\n" +
                "   X() {\n" +
                "      this((String)$$String$valueOf$$I.apply(new CallContext(\"X\", \"java.lang.String\", (Object)null, (Object)null), Integer.valueOf(0)));\n" +
                "   }\n" +
                "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

    }

    @Test
    public void testTestabilityInjectFunctionField_RedirectInsideConstructor() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "   X(){" +
                        "     String.valueOf(0);" +
                        "   }" +
                        "}\n"
        };

        String expectedOutput = "import helpers.Function2;\n" +
                "import testablejava.CallContext;\n" +
                "\n" +
                "public class X {\n" +
                "   public static Function2<CallContext<String>, Integer, String> $$String$valueOf$$I = (var0, var1) -> {\n" +
                "      return String.valueOf(var1);\n" +
                "   };\n" +
                "\n" +
                "   X() {\n" +
                "      $$String$valueOf$$I.apply(new CallContext(\"X\", \"java.lang.String\", this, (Object)null), Integer.valueOf(0));\n" +
                "   }\n" +
                "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

    }

    @Test
    public void testTestabilityInjectFunctionField_RedirectInsideAnonymousInnerClass() throws Exception {

        String[] task = {
                "X.java",
                "import java.util.*;\n" +
                        "public class X {\n" +
                        "	int fn() {" +
                        "     Comparator c;" +
                        "     dontredirect: c = " +
                        "            new Comparator<Object>() {\n" +
                        "                public int compare(Object o1, Object o2) {\n" +
                        "                    return myCompare(o1, o2);\n" +
                        "                }\n" +
                        "            };" +
                        "     dontredirect2: return c.compare(\"\",\"\"); " +
                        "   }" +
                        "	int myCompare(Object o1, Object o2) {return 1;}" +
                        "}\n"
        };

        String expectedOutput = "import X.1;\n" +
                "import helpers.Function3;\n" +
                "import testablejava.CallContext;\n" +
                "\n" +
                "public class X {\n" +
                "   public static Function3<CallContext<X>, Object, Object, Integer> $$X$myCompare$$Object$Object = (var0, var1, var2) -> {\n" +
                "      return Integer.valueOf(((X)var0.calledClassInstance).myCompare(var1, var2));\n" +
                "   };\n" +
                "\n" +
                "   int fn() {\n" +
                "      1 var1 = new 1(this);\n" +
                "      return var1.compare(\"\", \"\");\n" +
                "   }\n" +
                "\n" +
                "   int myCompare(Object var1, Object var2) {\n" +
                "      return 1;\n" +
                "   }\n" +
                "}";
        String expectedOutputInner = "import java.util.Comparator;\n" +
                "import testablejava.CallContext;\n" +
                "\n" +
                "class X$1 implements Comparator<Object> {\n" +
                "   X$1(X var1) {\n" +
                "      this.this$0 = var1;\n" +
                "   }\n" +
                "\n" +
                "   public int compare(Object var1, Object var2) {\n" +
                "      return ((Integer)X.$$X$myCompare$$Object$Object.apply(new CallContext(\"java.util.Comparator<java.lang.Object>\", \"X\", this, this.this$0), var1, var2)).intValue();\n" +
                "   }\n" +
                "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        assertEquals(1, invokeCompiledMethod("X", "fn"));

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));
        assertEquals(expectedOutputInner, moduleMap.get("X$1").stream().collect(joining("\n")));
    }

    @Test
    public void testTestabilityInjectFunctionField_RedirectInsideInnerClassCallingItself() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn() {" +
                        "     class Inner {" +
                        "	    void innerCallee() {}" +
                        "	    void innerCaller() {" +
                        "	      innerCallee();" +
                        "       }" +
                        "     }" +
                        "   }" +
                        "}\n"
        };

        String expectedOutput = "import X.1Inner;\n" +
                "import helpers.Consumer1;\n" +
                "import testablejava.CallContext;\n" +
                "\n" +
                "public class X {\n" +
                "   public static Consumer1<CallContext<1Inner>> $$Inner$innerCallee = (var0) -> {\n" +
                "      ((1Inner)var0.calledClassInstance).innerCallee();\n" +
                "   };\n" +
                "\n" +
                "   void fn() {\n" +
                "   }\n" +
                "}";
        String expectedOutputInner = "import testablejava.CallContext;\n" +
                "\n" +
                "class X$1Inner {\n" +
                "   X$1Inner(X var1) {\n" +
                "      this.this$0 = var1;\n" +
                "   }\n" +
                "\n" +
                "   void innerCallee() {\n" +
                "   }\n" +
                "\n" +
                "   void innerCaller() {\n" +
                "      X.$$Inner$innerCallee.accept(new CallContext(\"Inner\", \"Inner\", this, this));\n" +
                "   }\n" +
                "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));
        assertEquals(expectedOutputInner, moduleMap.get("X$1Inner").stream().collect(joining("\n")));
    }

    @Test
    public void testTestabilityInjectFunctionField_ForNewOperatorCallbackFromNamedInnerClass() throws Exception {

        String[] task = {
                "X.java",
                "import java.util.Comparator;\n" +
                        "import java.util.function.Consumer;\n\n" +
                        "public class X {\n" +
                        "	public X() {dontredirect:System.out.println();}\n" +
                        "	void fn() {" +
                        "      class C<T> implements Comparator<T> {\n" +
                        "         @Override\n" +
                        "         public int compare(T o1, T o2) {\n" +
                        "            return -1;\n" +
                        "         }\n" +
                        "      };\n" +
                        "      new C<String>();" +
                        "      new C<Integer>();" +
                        "   }\n" +
                        "}\n",
                "Y.java",
                "import java.util.List;\n" +
                        "import java.util.ArrayList;\n" +
                        "public class Y {\n" +
                        "	static List<Object> instances = new ArrayList<>();\n" +
                        "	static List<Object> setAndTest() {\n" +
                        "     X.$$C$postCreate = inst -> instances.add(inst);\n" +
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
                        "   public static Consumer<1C> $$C$preCreate = (var0) -> {\n" +
                        "   };\n" +
                        "   public static Consumer<1C> $$C$postCreate = (var0) -> {\n" +
                        "   };\n\n" +
                        "   public X() {\n" +
                        "      $$preCreate.accept(this);\n" +
                        "      System.out.println();\n" +
                        "      $$postCreate.accept(this);\n" +
                        "   }\n\n" +
                        "   void fn() {\n" +
                        "      new 1C(this);\n" +
                        "      new 1C(this);\n" +
                        "   }\n" +
                        "}";

        String expectedOutputInner = "import java.util.Comparator;\n" +
                "\n" +
                "class X$1C<T> implements Comparator<T> {\n" +
                "   X$1C(X var1) {\n" +
                "      this.this$0 = var1;\n" +
                "      X.$$C$preCreate.accept(this);\n" +
                "      X.$$C$postCreate.accept(this);\n" +
                "   }\n" +
                "\n" +
                "   public int compare(T var1, T var2) {\n" +
                "      return -1;\n" +
                "   }\n" +
                "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_LISTENERS_ONLY);

        invokeCompiledMethod("X", "fn");
        List<Object> instances = (List<Object>) invokeCompiledMethod("Y", "setAndTest");
        assertEquals(2, instances.size());
        assertEquals("C", instances.get(0).getClass().getSimpleName());
        assertEquals("C", instances.get(1).getClass().getSimpleName());

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));
        assertEquals(expectedOutputInner, moduleMap.get("X$1C").stream().collect(joining("\n")));
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
                        "   public static Consumer1<CallContext<PrintStream>> $$PrintStream$println = (var0) -> {\n" +
                        "      ((PrintStream)var0.calledClassInstance).println();\n" +
                        "   };\n\n" +
                        "   void fn() {\n" +
                        "      $$PrintStream$println.accept(new CallContext(\"X\", \"java.io.PrintStream\", this, System.out));\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));
    }

    @Test
    public void testTestabilityInjectFunctionField_FieldReuse() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "	void fn(){System.out.println();}" +
                        "   class Inner {" +
                        "	   void fnInner(){System.out.println();}" +
                        "   }" +
                        "	void withAnonymous(){" +
                        "      new Inner(){" +
                        "	      void fnAnon(){System.out.println();}" +
                        "      };" +
                        "   }" +
                        "}\n"
        };
        String expectedOutputX = "import X.1;\n" +
                "import helpers.Consumer1;\n" +
                "import java.io.PrintStream;\n" +
                "import testablejava.CallContext;\n" +
                "\n" +
                "public class X {\n" +
                "   public static Consumer1<CallContext<PrintStream>> $$PrintStream$println = (var0) -> {\n" +
                "      ((PrintStream)var0.calledClassInstance).println();\n" +
                "   };\n" +
                "\n" +
                "   void fn() {\n" +
                "      $$PrintStream$println.accept(new CallContext(\"X\", \"java.io.PrintStream\", this, System.out));\n" +
                "   }\n" +
                "\n" +
                "   void withAnonymous() {\n" +
                "      new 1(this, this);\n" +
                "   }\n" +
                "}";
        String expectedOutputXInner = "import testablejava.CallContext;\n" +
                "\n" +
                "class X$Inner {\n" +
                "   X$Inner(X var1) {\n" +
                "      this.this$0 = var1;\n" +
                "   }\n" +
                "\n" +
                "   void fnInner() {\n" +
                "      X.$$PrintStream$println.accept(new CallContext(\"X.Inner\", \"java.io.PrintStream\", this, System.out));\n" +
                "   }\n" +
                "}";
        String expectedOutputXAnon = "import X.Inner;\n" +
                "import testablejava.CallContext;\n" +
                "\n" +
                "class X$1 extends Inner {\n" +
                "   X$1(X var1, X var2) {\n" +
                "      super(var1);\n" +
                "      this.this$0 = var2;\n" +
                "   }\n" +
                "\n" +
                "   void fnAnon() {\n" +
                "      X.$$PrintStream$println.accept(new CallContext(\"X.Inner\", \"java.io.PrintStream\", this, System.out));\n" +
                "   }\n" +
                "}";


        assertEquals(expectedOutputX, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));
        assertEquals(expectedOutputXInner, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X$Inner").stream().collect(joining("\n")));
        assertEquals(expectedOutputXAnon, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X$1").stream().collect(joining("\n")));
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
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "public class X {\n" +
                        "   public static Consumer1<CallContext<PrintStream>> $$PrintStream$println;\n" +
                        "   static int ct = 0;\n" +
                        "\n" +
                        "   static {\n" +
                        "      ++ct;\n" +
                        "      $$PrintStream$println = (var0) -> {\n" +
                        "         ((PrintStream)var0.calledClassInstance).println();\n" +
                        "      };\n" +
                        "   }\n" +
                        "\n" +
                        "   int fn() {\n" +
                        "      $$PrintStream$println.accept(new CallContext(\"X\", \"java.io.PrintStream\", this, System.out));\n" +
                        "      return ct;\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));
        assertEquals("static initialization block was executed", 1, invokeCompiledMethod("X", "fn"));

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
                        "   public static Function1<CallContext<X>, String> $$X$callee = (var0) -> {\n" +
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
                        "   public static Function1<CallContext<X>, Integer> $$X$callee = (var0) -> {\n" +
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
                        "   public static Function2<CallContext<Integer>, Integer, Integer> $$Integer$valueOf$$I = (var0, var1) -> {\n" +
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
                        "   public static Function2<CallContext<Integer>, Integer, Integer> $$Integer$valueOf$$I = (var0, var1) -> {\n" +
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
                        "   public static Function2<CallContext<Integer>, Integer, Integer> $$Integer$valueOf$$I = (var0, var1) -> {\n" +
                        "      return Integer.valueOf(var1.intValue());\n" +
                        "   };\n" +
                        "   public static Function3<CallContext<Integer>, String, Integer, Integer> $$Integer$getInteger$$String$Integer = (var0, var1, var2) -> {\n" +
                        "      return Integer.getInteger(var1, var2);\n" +
                        "   };\n\n" +
                        "   void fn() {\n" +
                        "      $$Integer$getInteger$$String$Integer.apply(" +
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
                        "   public static Function2<CallContext<StringBuffer>, Integer, StringBuffer> $$StringBuffer$append$$I = (var0, var1) -> {\n" +
                        "      return ((StringBuffer)var0.calledClassInstance).append(var1);\n" +
                        "   };\n" +
                        "   public static Function2<CallContext<StringBuffer>, String, StringBuffer> $$StringBuffer$append$$String = (var0, var1) -> {\n" +
                        "      return ((StringBuffer)var0.calledClassInstance).append(var1);\n" +
                        "   };\n" +
                        "   public static Function2<CallContext<StringBuffer>, Integer, StringBuffer> $$StringBuffer$new$$I = (var0, var1) -> {\n" +
                        "      return new StringBuffer(var1.intValue());\n" +
                        "   };\n" +
                        "   public static Function1<CallContext<StringBuffer>, StringBuffer> $$StringBuffer$new = (var0) -> {\n" +
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
                "   public static Function2<CallContext<Y>, T, Y> $$Y$new$$T = (var0, var1) -> {\n" +
                "      return new Y(var1);\n" +
                "   };\n" +
                "\n" +
                "   Y fn() {\n" +
                "      Object var1 = null;\n" +
                "      return (Y)$$Y$new$$T.apply(new CallContext(\"X\", \"Y\", this, (Object)null), var1);\n" +
                "   }\n" +
                "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

        Object actual = invokeCompiledMethod("X", "fn");
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
                        "   public static Function2<CallContext<Y>, T, String> $$Y$fn$$T = (var0, var1) -> {\n" +
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
                        "   public static Function1<CallContext<Derived>, String> $$Derived$fn = (var0) -> {\n" +
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
                        "   public static Function1<CallContext<ArrayList<Integer>>, ArrayList<Integer>> $$java$util$ArrayList_java$lang$Integer_$new = (var0) -> {\n" +
                        "      return new ArrayList();\n" +
                        "   };\n" +
                        "   public static Function2<CallContext<ArrayList<Integer>>, List<Integer>, ArrayList<Integer>> $$java$util$ArrayList_java$lang$Integer_$new$$List = (var0, var1) -> {\n" +
                        "      return new ArrayList(var1);\n" +
                        "   };\n" +
                        "   public static Function2<CallContext<List<Integer>>, List<Integer>, Boolean> $$java$util$List_java$lang$Integer_$addAll$$List = (var0, var1) -> {\n" +
                        "      return Boolean.valueOf(((List)var0.calledClassInstance).addAll(var1));\n" +
                        "   };\n" +
                        "   public static Function2<CallContext<List<String>>, String, Boolean> $$java$util$List_java$lang$String_$add$$String = (var0, var1) -> {\n" +
                        "      return Boolean.valueOf(((List)var0.calledClassInstance).add(var1));\n" +
                        "   };\n" +
                        "   public static Function2<CallContext<List<String>>, List<String>, Boolean> $$java$util$List_java$lang$String_$addAll$$List = (var0, var1) -> {\n" +
                        "      return Boolean.valueOf(((List)var0.calledClassInstance).addAll(var1));\n" +
                        "   };\n" +
                        "   public static Function2<CallContext<ArrayList<String>>, List<String>, ArrayList<String>> $$java$util$ArrayList_java$lang$String_$new$$List = (var0, var1) -> {\n" +
                        "      return new ArrayList(var1);\n" +
                        "   };\n" +
                        "   public static Function1<CallContext<ArrayList<String>>, ArrayList<String>> $$java$util$ArrayList_java$lang$String_$new = (var0) -> {\n" +
                        "      return new ArrayList();\n" +
                        "   };\n" +
                        "   public static Function2<CallContext<List<Integer>>, Integer, Boolean> $$java$util$List_java$lang$Integer_$add$$I = (var0, var1) -> {\n" +
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
        invokeCompiledMethod("X", "fn");

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
                "import X.1C;\n" +
                        "import helpers.Function1;\n" +
                        "import testablejava.CallContext;\n\n" +
                        "public class X {\n" +
                        "   public static Function1<CallContext<X>, Integer> $$X$callee = (var0) -> {\n" +
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
                                {"y", "$fn"},
                                {"y", "$fn"},
                        },
                        {
                                {"b.y", ""},
                                {"a.y", ""},
                        },
                        {
                                {"b.y", "$fn"},
                                {"a.y", "$fn"},
                        }
                },

                {//short descr in col1,2 is unique, col1,2,3
                        {
                                {"y", "$fn", ""},
                                {"y", "$fn", ""},
                                {"y", "$new", ""},
                                {"y", "$new", ""},
                        },
                        {
                                {"b.y", "$FN", ""},
                                {"a.y", "$FN", ""},
                                {"b.y", "$NEW", ""},
                                {"a.y", "$NEW", ""},
                        },
                        {
                                {"b.y", "$fn", ""},
                                {"a.y", "$fn", ""},
                                {"b.y", "$new", ""},
                                {"a.y", "$new", ""},
                        }
                },

                {//short descr in col1,2 is unique, col1,2,3
                        {
                                {"a", "b", "c"},
                                {"a", "b", "d"},
                        },
                        {
                                {"P1", "Q1", "R1"},
                                {"P2", "Q2", "R2"},
                        },
                        {
                                {"a", "b", "c"},
                                {"a", "b", "d"},
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
                                {"a", "b", "c"},
                                {"a", "b", "d"},
                                {"x", "z", "q"},
                                {"y", "t", "r"},
                        },
                        {
                                {"A1", "B1", "C1"},
                                {"A2", "B2", "D1"},
                                {"X1", "Z1", "Q1"},
                                {"Y1", "T1", "R1"},
                        },
                        {
                                {"a", "b", "c"},
                                {"a", "b", "d"},
                                {"x", "z", "q"},
                                {"y", "t", "r"},

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
        for (String[][][] inputShortLongExpected : inputShortLongExpectedCombos) {
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
                        "   public static Function3<CallContext<Long>, Integer, Long, Integer> $$Long$compare$$I$J = (var0, var1, var2) -> {\n" +
                        "      return Integer.valueOf(Long.compare((long)var1.intValue(), var2.longValue()));\n" +
                        "   };\n\n" +
                        "   public int fn() {\n" +
                        "      int var1 = " + Integer.MAX_VALUE + ";\n" +
                        "      long var2 = 2L;\n" +
                        "      return ((Integer)$$Long$compare$$I$J.apply(new CallContext(\"X\", \"java.lang.Long\", this, (Object)null), Integer.valueOf(var1), Long.valueOf(var2))).intValue();\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        Object actual = invokeCompiledMethod("X", "fn");
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
                        "   public static Function2<CallContext<Long>, Integer, Long> $$Long$valueOf$$I = (var0, var1) -> {\n" +
                        "      return Long.valueOf((long)var1.intValue());\n" +
                        "   };\n\n" +
                        "   public Long fn() {\n" +
                        "      int var1 = " + Integer.MAX_VALUE + ";\n" +
                        "      return (Long)$$Long$valueOf$$I.apply(new CallContext(\"X\", \"java.lang.Long\", this, (Object)null), Integer.valueOf(var1));\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        Object actual = invokeCompiledMethod("X", "fn");

        assertEquals((long) Integer.MAX_VALUE, actual);

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
                        "   public static Function3<CallContext<Character>, Character, Character, Integer> $$Character$compare$$C$C = (var0, var1, var2) -> {\n" +
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
                        "   public static Function2<CallContext<Y>, Character, Y> $$Y$new$$C = (var0, var1) -> {\n" +
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
                        "   public static Consumer1<CallContext<PrintStream>> $$PrintStream$println = (var0) -> {\n" +
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
                        "   public static Consumer1<CallContext<PrintStream>> $$PrintStream$println = (var0) -> {\n" +
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
                        "   public static Consumer2<CallContext<PrintStream>, Integer> $$PrintStream$write$$I = (var0, var1) -> {\n" +
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
                        "   public static Function2<CallContext<List<Integer>>, Integer, Boolean> $$List$add$$I = (var0, var1) -> {\n" +
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
                        "   public static Function2<CallContext<BigDecimal>, Integer, BigDecimal> $$BigDecimal$new$$I = (var0, var1) -> {\n" +
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
                        "   public static Consumer2<CallContext<PrintStream>, Integer> $$PrintStream$write$$I = (var0, var1) -> {\n" +
                        "      ((PrintStream)var0.calledClassInstance).write(var1.intValue());\n" +
                        "   };\n\n" +
                        "   void fn() throws Exception {\n" +
                        "      $$PrintStream$write$$I.accept(new CallContext(\"X\", \"java.io.PrintStream\", this, System.out), Integer.valueOf(434242342));\n" +
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
                        "   public static Function2<CallContext<BigDecimal>, Integer, BigDecimal> $$BigDecimal$new$$I = (var0, var1) -> {\n" +
                        "      return new BigDecimal(var1.intValue());\n" +
                        "   };\n\n" +
                        "   BigDecimal fn() throws Exception {\n" +
                        "      return (BigDecimal)$$BigDecimal$new$$I.apply(new CallContext(\"X\", \"java.math.BigDecimal\", this, (Object)null), Integer.valueOf(434242342));\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

        Object actual = invokeCompiledMethod("X", "fn");

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
                        "   public static Consumer1<CallContext<PrintStream>> $$PrintStream$close = (var0) -> {\n" +
                        "      ((PrintStream)var0.calledClassInstance).close();\n" +
                        "   };\n" +
                        "   public static Consumer2<CallContext<PrintStream>, Integer> $$PrintStream$write$$Integer = (var0, var1) -> {\n" +
                        "      ((PrintStream)var0.calledClassInstance).write(var1.intValue());\n" +
                        "   };\n\n" +
                        "   void fn() throws Exception {\n" +
                        "      PrintStream var1 = System.out;\n" +
                        "      Integer var2 = Integer.valueOf(0);\n" +
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
                        "   public static Consumer1<CallContext<Y>> $$Y$fn = (var0) -> {\n" +
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
                "import a.Y;\n" +
                        "import helpers.Consumer1;\n" +
                        "import helpers.Function1;\n" +
                        "import testablejava.CallContext;\n\n" +
                        "public class X {\n" +
                        "   public static Function1<CallContext<Y>, Y> $$a$Y$new = (var0) -> {\n" +
                        "      return new Y();\n" +
                        "   };\n" +
                        "   public static Consumer1<CallContext<b.Y>> $$b$Y$fn = (var0) -> {\n" +
                        "      ((b.Y)var0.calledClassInstance).fn();\n" +
                        "   };\n" +
                        "   public static Function1<CallContext<b.Y>, b.Y> $$b$Y$new = (var0) -> {\n" +
                        "      return new b.Y();\n" +
                        "   };\n" +
                        "   public static Consumer1<CallContext<Y>> $$a$Y$fn = (var0) -> {\n" +
                        "      ((Y)var0.calledClassInstance).fn();\n" +
                        "   };\n" +
                        "\n" +
                        "   void fn() throws Exception {\n" +
                        "      Y var1 = (Y)$$a$Y$new.apply(new CallContext(\"X\", \"a.Y\", this, (Object)null));\n" +
                        "      b.Y var2 = (b.Y)$$b$Y$new.apply(new CallContext(\"X\", \"b.Y\", this, (Object)null));\n" +
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
                        "   public static Consumer2<CallContext<PrintStream>, String> $$PrintStream$println$$String = (var0, var1) -> {\n" +
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
                        "   public static Consumer2<CallContext<PrintStream>, String> $$PrintStream$println$$String = (var0, var1) -> {\n" +
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
                        "import testablejava.CallContext;\n\n" +
                        "public class X {\n" +
                        "   public static Function1<CallContext<PrintStream>, String> $$PrintStream$toString = (var0) -> {\n" +
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
     *
     * @throws Exception
     */
    @Test
    public void testTestabilityInjectedFunctions() throws Exception {

        String[] task = {
                "X.java",
                "import helpers.Function0;\n" +
                        "import helpers.Function1;\n" +
                        "import helpers.Function19;\n" +
                        "import helpers.Function2;\n" +
                        "import helpers.Function3;\n\n" +
                        "public class X {\n" +
                        "   Function0<Boolean> x0;\n" +
                        "   Function1<Integer, Boolean> x1;\n" +
                        "   Function2<Integer, String, Boolean> x2;\n" +
                        "   Function3<Integer, String, Float, Boolean> x3;\n" +
                        "   Function19<Integer, Integer, Integer, Integer, Integer, Integer, Integer, Integer, " +
                        "Integer, Integer, Integer, Integer, Integer, Integer, Integer, Integer, Integer, " +
                        "Integer, Integer, Boolean> x19;\n" +
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
                        "   public static Consumer1<CallContext<String>> $$String$notify = (var0) -> {\n" +
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
                        "   public static Function1<CallContext<String>, String> $$String$new = (var0) -> {\n" +
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
        assertEquals("x", (String) ret);


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

        assertEquals("since Y manipulates redirector fields, its own code should have no substitution", 0, redirectorFieldCountInY);


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
        assertEquals("redirected", (String) ret);
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
                        "     Function<String, String> x = (String arg111)->{return String.valueOf(arg111);};" +
                        "    dontredirect: return x.apply(\"value\");" +
                        "   }\n" +
                        "   public static String exec(){\n" +
                        "     return new X().exec2();" +
                        "   } " +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals("value", (String) ret);
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
                        "   Function2<CallContext<PrintStream>, java.lang.Character, java.io.PrintStream> newRedirector = \n" +
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
                        "     Function<String, String> x = (String arg111)->{return new String(arg111);};" +
                        "    dontredirect: return x.apply(\"value\");" +
                        "   }\n" +
                        "   public static String exec(){\n" +
                        "     return new X().exec2();" +
                        "   } " +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals("value", (String) ret);
    }

    @Test
    public void testTestabilityInjectFunctionField_RedirectAllocationInsideLambda_InStaticScope() throws Exception {

        String[] task = {
                "X.java",
                "import java.util.function.Function;" +
                        "public class X {\n" +
                        "static Function<String, String> x = (String arg111)->{return new String(arg111);};" +
                        "Function<String, String> f = (x)->\"\";" +
                        "	String exec2(){" +
                        "    dontredirect: return x.apply(\"value\");" +
                        "   }\n" +
                        "   public static String exec(){\n" +
                        "     return new X().exec2();" +
                        "   } " +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals("value", (String) ret);
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
        assertEquals("", (String) ret);
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
        assertEquals("1", (String) ret);
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
        assertEquals("code in X.fn.f lambda contains redirection", "y", actual);
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
        assertEquals("code in X.fn.f lambda contains redirection", "y", actual);
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
        Object actual = invokeCompiledMethod("X", "fn", "t");
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
                        "   public static Function2<CallContext<Y>, int[], Integer> $$Y$arrayArg$$ⒶI = (var0, var1) -> {\n" +
                        "      return Integer.valueOf(Y.arrayArg(var1));\n" +
                        "   };\n" +
                        "\n" +
                        "   public int fn() {\n" +
                        "      int[] var1 = new int[]{1, 2};\n" +
                        "      return ((Integer)$$Y$arrayArg$$ⒶI.apply(new CallContext(\"X\", \"Y\", this, (Object)null), var1)).intValue();\n" +
                        "   }\n" +
                        "}";

        Object actual = invokeCompiledMethod("X", "fn");

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));

        assertEquals(2, actual);
    }

    @Test
    public void testTestabilityInjectFunctionField_ForConflictingCallsWithThisTypeMix() throws Exception {

        //there are two calls; each should create a field, since they are invoked on different subclasses
        String[] task = {
                "X.java",
                "import java.io.PrintWriter;\n" +
                        "public class X extends Exception {\n" +
                        "   Throwable nestedException;\n" +
                        "   static final long serialVersionUID=1L;" +
                        "   " +
                        "   public void printStackTrace(PrintWriter output) {\n" +
                        "         super.printStackTrace(output);\n" +
                        "         nestedException.printStackTrace(output);\n" +
                        "   }\n" +
                        "}\n"
        };

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        String expectedOutput =
                "import helpers.Consumer2;\n" +
                        "import java.io.PrintWriter;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "public class X extends Exception {\n" +
                        "   public static Consumer2<CallContext<Throwable>, PrintWriter> $$Throwable$printStackTrace$$PrintWriter = (var0, var1) -> {\n" +
                        "      ((Throwable)var0.calledClassInstance).printStackTrace(var1);\n" +
                        "   };\n" +
                        "   public static Consumer2<CallContext<Exception>, PrintWriter> $$Exception$printStackTrace$$PrintWriter = (var0, var1) -> {\n" +
                        "      ((Exception)var0.calledClassInstance).printStackTrace(var1);\n" +
                        "   };\n" +
                        "   Throwable nestedException;\n" +
                        "   static final long serialVersionUID = 1L;\n" +
                        "\n" +
                        "   public void printStackTrace(PrintWriter var1) {\n" +
                        "      $$Exception$printStackTrace$$PrintWriter.accept(new CallContext(\"X\", \"java.lang.Exception\", this, this), var1);\n" +
                        "      $$Throwable$printStackTrace$$PrintWriter.accept(new CallContext(\"X\", \"java.lang.Throwable\", this, this.nestedException), var1);\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));
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
                        "   public static Function2<CallContext<Y>, String[], Integer> $$Y$arrayArg$$ⒶString = (var0, var1) -> {\n" +
                        "      return Integer.valueOf(Y.arrayArg(var1));\n" +
                        "   };\n" +
                        "\n" +
                        "   public int fn() {\n" +
                        "      String[] var1 = new String[]{\"1\", \"2\"};\n" +
                        "      return ((Integer)$$Y$arrayArg$$ⒶString.apply(new CallContext(\"X\", \"Y\", this, (Object)null), var1)).intValue();\n" +
                        "   }\n" +
                        "}";

        Object actual = invokeCompiledMethod("X", "fn");

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
                        "   public static Function2<CallContext<String>, String, String> $$String$new$$String = (var0, var1) -> {\n" +
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
                        "import testablejava.CallContext;\n" +
                        "import testablejava.Helpers;\n\n" +
                        "public class X {\n" +
                        "   public static Function2<CallContext<Integer>, String, Integer> $$Integer$parseInt$$String = (var0, var1) -> {\n" +
                        "      try {\n" +
                        "         return Integer.valueOf(Integer.parseInt(var1));\n" +
                        "      } catch (Throwable var3) {\n" +
                        "         Helpers.uncheckedThrow(var3);\n" +
                        "         return null;\n" +
                        "      }\n" +
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
                        "import testablejava.CallContext;\n" +
                        "import testablejava.Helpers;\n\n" +
                        "public class X {\n" +
                        "   public static Function2<CallContext<Integer>, String, Integer> $$Integer$parseInt$$String = (var0, var1) -> {\n" +
                        "      try {\n" +
                        "         return Integer.valueOf(Integer.parseInt(var1));\n" +
                        "      } catch (Throwable var3) {\n" +
                        "         Helpers.uncheckedThrow(var3);\n" +
                        "         return null;\n" +
                        "      }\n" +
                        "   };\n\n" +
                        "   int fn() {\n" +
                        "      return ((Integer)$$Integer$parseInt$$String.apply(new CallContext(\"X\", \"java.lang.Integer\", this, (Object)null), \"1\")).intValue();\n" +
                        "   }\n\n" +
                        "   public static int exec() {\n" +
                        "      return (new X()).fn();\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));
        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Method main = cl.loadClass("X").getMethod("exec");
        Object ret = main.invoke(null);
        assertEquals(1, (int) ret);
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
                        "   public static Function2<CallContext<String>, String, String> $$String$new$$String = (var0, var1) -> {\n" +
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

        Object actual = invokeCompiledMethod("X", "fn");
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

    void fnZZ() {
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
                        "   public static Function2<CallContext<String>, StringBuilder, String> $$String$new$$StringBuilder = (var0, var1) -> {\n" +
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
                        "import testablejava.CallContext;\n\n" +
                        "public class X {\n" +
                        "   public static Function1<CallContext<String>, String> $$String$new = (var0) -> {\n" +
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
                        "   public static Function2<CallContext<PrintStream>, Character, PrintStream> $$PrintStream$append$$C = (var0, var1) -> {\n" +
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
                        "   public static Function2<CallContext<PrintStream>, Character, PrintStream> $$PrintStream$append$$C = (var0, var1) -> {\n" +
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
                        "   public static Function1<CallContext<String>, String> $$String$new = (var0) -> {\n" +
                        "      return new String();\n" +
                        "   };\n\n" +
                        "   static void fn() {\n" +
                        "      $$String$new.apply(new CallContext(\"X\", \"java.lang.String\", (Object)null, (Object)null));\n" +
                        "   }\n" +
                        "}";

        assertEquals(expectedOutput, compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY).get("X").stream().collect(joining("\n")));

    }

    @Test
    public void testTestabilityInjectFunctionField_getClass() throws Exception {
        //incompatible CaptionBinding generated. getClass is specialcased, should not be bypassed

        String[] task = {
                "Y.java",
                "public class Y {}",
                "X.java",
                "public class X {\n" +
                        "	public String fn(){Y y; dontredirect: y = new Y();" +
                        "     Class<?> cl = y.getClass();" +
                        "     return cl.getName();" +
                        "   }" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        Object actual = invokeCompiledMethod("X", "fn");

        assertEquals("Y", actual);
    }

    @Test
    public void testTestabilityInjectFunctionField_experimentElidedLambdaArg() throws Exception {


        String[] task = {
                "X.java",
                "import helpers.*;" +
                        "import testablejava.CallContext;\n\n" +
                        "public class X {\n" +
                        "   public static Function1<CallContext<String>, String> $$String$new = (var0) -> {\n" +
                        "      return new String();\n" +
                        "   };\n\n" +
                        "}\n"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
    }
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
//        //public class CallContext<Class<capture#1-of ? extends CallContext#RAW>> and null dereference in expandInternalName because it is CaptureBinding where compoundName=null
//        String[] task = {
//                "Y.java",
//                "import helpers.Function1;\n" +
//                        "import testablejava.CallContext;\n" +
//                        "public class Y {\n" +
//                        "	String fn(){" +
//                        "       Function1<CallContext<Y>, String> fn = (ctx) -> {return (String)ctx.getClass().getName();};" +
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
    public void testTestabilityInjectFunctionField_RedirectGenericToGenericCall() throws Exception {
        String[] task = {
                "Y.java",
                "import java.util.*;\n" +
                        "public class Y {\n" +
                        "   public <T, K, G extends Calendar> T accept(List<? extends List<T>> lst, K k, T t, Map<K, T> m, Integer i, G g) {\n" +
                        "     return called(this, lst, k, t, m, i, g);\n" + //this call is to generic function passing generic args
                        "   }\n" +
                        "	public <T, K, G extends Calendar> T called(Y y, List<? extends List<T>> lst, K k, T t, Map<K, T> m, Integer i, G g){" +
                        "     return t;\n" +
                        "   }" +
                        "	public String fn(){" +
                        "     dontredirect: return accept(" +
                        "new ArrayList<ArrayList<String>>(), " +
                        "new Integer(1), " +
                        "new String(), " +
                        "new HashMap<Integer, String>(), " +
                        "new Integer(0), " +
                        "Calendar.getInstance()" +
                        ").getClass().getName();" +
                        "   }" +
                        "}\n"
        };


        String expectedOutputY =
                "import Y.1;\n" +
                        "import helpers.Function8_3;\n" +
                        "import java.util.ArrayList;\n" +
                        "import java.util.Calendar;\n" +
                        "import java.util.HashMap;\n" +
                        "import java.util.List;\n" +
                        "import java.util.Map;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "public class Y {\n" +
                        "   public static Function8_3<CallContext<Y>, Y, List, Object, Object, Map, Integer, Object, Object> $$Y$called$$Y$List$Object$Object$Map$Integer$Calendar = new 1();\n" +
                        "\n" +
                        "   public <T, K, G extends Calendar> T accept(List<? extends List<T>> var1, K var2, T var3, Map<K, T> var4, Integer var5, G var6) {\n" +
                        "      return (Object)$$Y$called$$Y$List$Object$Object$Map$Integer$Calendar.apply(new CallContext(\"Y\", \"Y\", this, this), this, var1, var2, var3, var4, var5, var6);\n" +
                        "   }\n" +
                        "\n" +
                        "   public <T, K, G extends Calendar> T called(Y var1, List<? extends List<T>> var2, K var3, T var4, Map<K, T> var5, Integer var6, G var7) {\n" +
                        "      return var4;\n" +
                        "   }\n" +
                        "\n" +
                        "   public String fn() {\n" +
                        "      return ((String)this.accept(new ArrayList(), new Integer(1), new String(), new HashMap(), new Integer(0), Calendar.getInstance())).getClass().getName();\n" +
                        "   }\n" +
                        "}";

        String expectedOutputInner =
                "import helpers.Function8_3;\n" +
                        "import java.util.Calendar;\n" +
                        "import java.util.List;\n" +
                        "import java.util.Map;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "class Y$1 implements Function8_3<CallContext<Y>, Y, List, Object, Object, Map, Integer, Object, Object> {\n" +
                        "   public <K, T, G> Object apply(CallContext<Y> var1, Y var2, List var3, Object var4, Object var5, Map var6, Integer var7, Object var8) {\n" +
                        "      return ((Y)var1.calledClassInstance).called(var2, var3, var4, var5, var6, var7, (Calendar)var8);\n" + //note: if it compiled, we generated called(var2, var3, (K)var4, (T)var5, var6, var7, (G)var8); decompilation simplifies things
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals(expectedOutputY, moduleMap.get("Y").stream().collect(joining("\n")));
        assertEquals(expectedOutputInner, moduleMap.get("Y$1").stream().collect(joining("\n")));

        Object className = invokeCompiledMethod("Y", "fn");
        assertEquals("java.lang.String", className);
    }

    @Test
    public void testTestabilityInjectFunctionField_RedirectGenericToGenericCallForParameterized() throws Exception {
        //parameterized type in call should be downgraded to raw, which does not require a cast when calling original method
        String[] task = {
                "Y.java",
                "import java.util.*;\n" +
                        "public class Y {\n" +
                        "   public <T> int accept(List<T> lst) {\n" +
                        "     return called(this, lst);\n" +
                        "   }\n" +
                        "	public <T> int called(Y y, List<T> lst){" +
                        "     return 1;\n" +
                        "   }" +
                        "	public int fn(){" +
                        "     dontredirect: return accept(new ArrayList<String>()); " +
                        "   }" +
                        "}\n",
                "Test.java",
                "import java.util.*;\n" +
                        "public class Test {\n" +
                        "   public int fnRedirectAndCall() {\n" +
                        "      Y.$$Y$called$$Y$List = (var0, var1, var2) -> var2.size();\n" +
                        "      dontredirect: return new Y().accept(Arrays.asList(1,2,3));\n" +
                        "   }" +
                        "}\n"

        };


        String expectedOutputY =
                "import helpers.Function3;\n" +
                        "import java.util.ArrayList;\n" +
                        "import java.util.List;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "public class Y {\n" +
                        "   public static Function3<CallContext<Y>, Y, List, Integer> $$Y$called$$Y$List = (var0, var1, var2) -> {\n" +
                        "      return Integer.valueOf(((Y)var0.calledClassInstance).called(var1, var2));\n" +
                        "   };\n" +
                        "\n" +
                        "   public <T> int accept(List<T> var1) {\n" +
                        "      return ((Integer)$$Y$called$$Y$List.apply(new CallContext(\"Y\", \"Y\", this, this), this, var1)).intValue();\n" +
                        "   }\n" +
                        "\n" +
                        "   public <T> int called(Y var1, List<T> var2) {\n" +
                        "      return 1;\n" +
                        "   }\n" +
                        "\n" +
                        "   public int fn() {\n" +
                        "      return this.accept(new ArrayList());\n" +
                        "   }\n" +
                        "}";


        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals(expectedOutputY, moduleMap.get("Y").stream().collect(joining("\n")));

        int res = (int) invokeCompiledMethod("Y", "fn");
        assertEquals(1, res);

        int res2 = (int) invokeCompiledMethod("Test", "fnRedirectAndCall");
        assertEquals(3, res2);

    }

    @Test
    public void testTestabilityInjectFunctionField_RedirectGenericToGenericCallNoReturn() throws Exception {
        String[] task = {
                "Y.java",
                "import java.util.*;\n" +
                        "public class Y {\n" +
                        "   public <T> T accept(List<T> lst, T t) {\n" +
                        "     called(this, t);" + //this call is to generic function passing generic arg
                        "     return t;\n" +
                        "   }\n" +
                        "	public <T> void called(Y y, T t){" +
                        "   }" +
                        "	public String fn(){" +
                        "     dontredirect: return accept(new ArrayList<String>(), \"\").getClass().getName();" +
                        "   }" +
                        "}\n"
        };

        String expectedOutputY =
                "import Y.1;\n" +
                        "import helpers.Consumer3_1;\n" +
                        "import java.util.ArrayList;\n" +
                        "import java.util.List;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "public class Y {\n" +
                        "   public static Consumer3_1<CallContext<Y>, Y, Object> $$Y$called$$Y$Object = new 1();\n" +
                        "\n" +
                        "   public <T> T accept(List<T> var1, T var2) {\n" +
                        "      $$Y$called$$Y$Object.accept(new CallContext(\"Y\", \"Y\", this, this), this, var2);\n" +
                        "      return var2;\n" +
                        "   }\n" +
                        "\n" +
                        "   public <T> void called(Y var1, T var2) {\n" +
                        "   }\n" +
                        "\n" +
                        "   public String fn() {\n" +
                        "      return ((String)this.accept(new ArrayList(), \"\")).getClass().getName();\n" +
                        "   }\n" +
                        "}";

        String expectedOutputInner =
                "import helpers.Consumer3_1;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "class Y$1 implements Consumer3_1<CallContext<Y>, Y, Object> {\n" +
                        "   public <T> void accept(CallContext<Y> var1, Y var2, Object var3) {\n" +
                        "      ((Y)var1.calledClassInstance).called(var2, var3);\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals(expectedOutputY, moduleMap.get("Y").stream().collect(joining("\n")));
        assertEquals(expectedOutputInner, moduleMap.get("Y$1").stream().collect(joining("\n")));

        Object className = invokeCompiledMethod("Y", "fn");
        assertEquals("java.lang.String", className);
    }

    //TODO reen
//    @Test
//    public void testTestabilityInjectFunctionField_RedirectGeneric_Reproduction() throws Exception {
//        //E1 cannot be resolved to a type
//        //Pb(17) Type mismatch: cannot convert from new Function2_1<CallContext<Map<Object,Set<Object>>>,Object,Boolean>(){} to
//        //                                              Function2_1<CallContext<Map<T1,Set<T2>>>,Object,Boolean>
//        String[] task = {
//                "Y.java",
//                "import java.util.*;\n" +
//                        "class Y<T1, T2> {\n" +
//                        " private final Map<T1, Set<T2>> _forward; " +
//                        " {dontredirect: _forward = new HashMap<>();}" +
//                        " public synchronized boolean containsKey(T1 key) {\n" +
//                        "  return _forward.containsKey(key);\n" +
//                        " }" +
//                        "}\n"
//        };
//
//        String expectedOutputY =
//                "";
//
//        String expectedOutputInner =
//                "";
//
//        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
//        assertEquals(expectedOutputY, moduleMap.get("Y").stream().collect(joining("\n")));
//        assertEquals(expectedOutputInner, moduleMap.get("Y$1").stream().collect(joining("\n")));
//
//        Object className = invokeCompiledMethod("Y", "fn");
//        assertEquals("java.lang.String", className);
//    }


    @Test
    public void testTestabilityInjectFunctionField_Cast_Reproduction() throws Exception {
        class X {
            void f(Map<Object, Set<Object>> a) {
            }

            void m() {
                Set<String> b = new HashSet<>();
                Map<String, Set<String>> m = new HashMap<>();
                f((Map) m);

            }
        }
    }

    @Test
    public void testTestabilityInjectFunctionField_MultipleRedirectionsRealistic() throws Exception {

        String[] task = {
                "ManyToMany.java",
                "import java.util.Collections;\n" +
                        "import java.util.HashMap;\n" +
                        "import java.util.HashSet;\n" +
                        "import java.util.Map;\n" +
                        "import java.util.Set;\n" +
                        "\n" +
                        "public class ManyToMany<T1, T2> {\n" +
                        "   \n" +
                        "   private final Map<T1, Set<T2>> _forward = null;//new HashMap<>();\n" +
                        "   private final Map<T2, Set<T1>> _reverse = new HashMap<>();\n" +
                        "   private boolean _dirty = false;\n" +
                        "   \n" +
                        "   public synchronized boolean clear() {\n" +
                        "      boolean hadContent = !_forward.isEmpty();// || !_reverse.isEmpty();\n" +
                        "      _reverse.clear();\n" +
                        "      _forward.clear();\n" +
                        "      _dirty |= hadContent;\n" +
                        "      return hadContent;\n" +
                        "   }\n" +
                        "   \n" +

                        "   public synchronized void clearDirtyBit() {\n" +
                        "      _dirty = false;\n" +
                        "   }\n" +
                        "   \n" +
                        "   public synchronized boolean containsKey(T1 key) {\n" +
                        "      return _forward.containsKey(key);\n" +
                        "   }\n" +
                        "   \n" +

                        "   public synchronized boolean containsKeyValuePair(T1 key, T2 value) {\n" +
                        "      Set<T2> values = _forward.get(key);\n" +
                        "      if (null == values) {\n" +
                        "         return false;\n" +
                        "      }\n" +
                        "      return values.contains(value);\n" +
                        "   }\n" +
                        "   \n" +

                        "   public synchronized boolean containsValue(T2 value) {\n" +
                        "      return _reverse.containsKey(value);\n" +
                        "   }\n" +
                        "   \n" +

                        "   public synchronized Set<T1> getKeys(T2 value) {\n" +
                        "      Set<T1> keys = _reverse.get(value);\n" +
                        "      if (null == keys) {\n" +
                        "         return Collections.emptySet();\n" +
                        "      }\n" +
                        "      return new HashSet<>(keys);\n" +
                        "   }\n" +
                        "   \n" +

                        "   public synchronized Set<T2> getValues(T1 key) {\n" +
                        "      Set<T2> values = _forward.get(key);\n" +
                        "      if (null == values) {\n" +
                        "         return Collections.emptySet();\n" +
                        "      }\n" +
                        "      return new HashSet<>(values);\n" +
                        "   }\n" +
                        "\n" +

                        "   public synchronized Set<T1> getKeySet() {\n" +
                        "      Set<T1> keys = new HashSet<>(_forward.keySet());\n" +
                        "      return keys;\n" +
                        "   }\n" +
                        "   \n" +

                        "   public synchronized Set<T2> getValueSet() {\n" +
                        "      Set<T2> values = new HashSet<>(_reverse.keySet());\n" +
                        "      return values;\n" +
                        "   }\n" +
                        "   \n" +

                        "   public synchronized boolean isDirty() {\n" +
                        "      return _dirty;\n" +
                        "   }\n" +
                        "   \n" +
                        "   public synchronized boolean keyHasOtherValues(T1 key, T2 value) {\n" +
                        "      Set<T2> values = _forward.get(key);\n" +
                        "      if (values == null)\n" +
                        "         return false;\n" +
                        "      int size = values.size();\n" +
                        "      if (size == 0)\n" +
                        "         return false;\n" +
                        "      else if (size > 1)\n" +
                        "         return true;\n" +
                        "      else // size == 1\n" +
                        "         return !values.contains(value);\n" +
                        "   }\n" +
                        "\n" +
                        "   public synchronized boolean put(T1 key, T2 value) {\n" +
                        "      // Add to forward map\n" +
                        "      Set<T2> values = _forward.get(key);\n" +
                        "      if (null == values) {\n" +
                        "         values = new HashSet<>();\n" +
                        "         _forward.put(key, values);\n" +
                        "      }\n" +
                        "      boolean added = values.add(value);\n" +
                        "      _dirty |= added;\n" +
                        "      \n" +
                        "      // Add to reverse map\n" +
                        "      Set<T1> keys = _reverse.get(value);\n" +
                        "      if (null == keys) {\n" +
                        "         keys = new HashSet<>();\n" +
                        "         _reverse.put(value, keys);\n" +
                        "      }\n" +
                        "      keys.add(key);\n" +
                        "      \n" +
                        "      assert checkIntegrity();\n" +
                        "      return added;\n" +
                        "   }\n" +
                        "   \n" +
                        "   public synchronized boolean remove(T1 key, T2 value) {\n" +
                        "      Set<T2> values = _forward.get(key);\n" +
                        "      if (values == null) {\n" +
                        "         assert checkIntegrity();\n" +
                        "         return false;\n" +
                        "      }\n" +
                        "      boolean removed = values.remove(value);\n" +
                        "      if (values.isEmpty()) {\n" +
                        "         _forward.remove(key);\n" +
                        "      }\n" +
                        "      if (removed) {\n" +
                        "         _dirty = true;\n" +
                        "         // it existed, so we need to remove from reverse map as well\n" +
                        "         Set<T1> keys = _reverse.get(value);\n" +
                        "         keys.remove(key);\n" +
                        "         if (keys.isEmpty()) {\n" +
                        "            _reverse.remove(value);\n" +
                        "         }\n" +
                        "      }\n" +
                        "      assert checkIntegrity();\n" +
                        "      return removed;\n" +
                        "   }\n" +
                        "\n" +
                        "   public synchronized boolean removeKey(T1 key) {\n" +
                        "      // Remove all back-references to key.\n" +
                        "      Set<T2> values = _forward.get(key);\n" +
                        "      if (null == values) {\n" +
                        "         // key does not exist in map.\n" +
                        "         assert checkIntegrity();\n" +
                        "         return false;\n" +
                        "      }\n" +
                        "      for (T2 value : values) {\n" +
                        "         Set<T1> keys = _reverse.get(value);\n" +
                        "         if (null != keys) {\n" +
                        "            keys.remove(key);\n" +
                        "            if (keys.isEmpty()) {\n" +
                        "               _reverse.remove(value);\n" +
                        "            }\n" +
                        "         }\n" +
                        "      }\n" +
                        "      // Now remove the forward references from key.\n" +
                        "      _forward.remove(key);\n" +
                        "      _dirty = true;\n" +
                        "      assert checkIntegrity();\n" +
                        "      return true;\n" +
                        "   }\n" +
                        "   \n" +
                        "   public synchronized boolean removeValue(T2 value) {\n" +
                        "      // Remove any forward references to value\n" +
                        "      Set<T1> keys = _reverse.get(value);\n" +
                        "      if (null == keys) {\n" +
                        "         // value does not exist in map.\n" +
                        "         assert checkIntegrity();\n" +
                        "         return false;\n" +
                        "      }\n" +
                        "      for (T1 key : keys) {\n" +
                        "         Set<T2> values = _forward.get(key);\n" +
                        "         if (null != values) {\n" +
                        "            values.remove(value);\n" +
                        "            if (values.isEmpty()) {\n" +
                        "               _forward.remove(key);\n" +
                        "            }\n" +
                        "         }\n" +
                        "      }\n" +
                        "      // Now remove the reverse references from value.\n" +
                        "      _reverse.remove(value);\n" +
                        "      _dirty = true;\n" +
                        "      assert checkIntegrity();\n" +
                        "      return true;\n" +
                        "   }\n" +
                        "   \n" +
                        "   public synchronized boolean valueHasOtherKeys(T2 value, T1 key) {\n" +
                        "      Set<T1> keys = _reverse.get(key);\n" +
                        "      if (keys == null)\n" +
                        "         return false;\n" +
                        "      int size = keys.size();\n" +
                        "      if (size == 0)\n" +
                        "         return false;\n" +
                        "      else if (size > 1)\n" +
                        "         return true;\n" +
                        "      else // size == 1\n" +
                        "         return !keys.contains(key);\n" +
                        "   }\n" +
                        "\n" +
                        "   private boolean checkIntegrity() {\n" +
                        "      // For every T1->T2 mapping in the forward map, there should be a corresponding\n" +
                        "      // T2->T1 mapping in the reverse map.\n" +
                        "      for (Map.Entry<T1, Set<T2>> entry : _forward.entrySet()) {\n" +
                        "         Set<T2> values = entry.getValue();\n" +
                        "         if (values.isEmpty()) {\n" +
                        "            throw new IllegalStateException(\"Integrity compromised: forward map contains an empty set\"); //$NON-NLS-1$\n" +
                        "         }\n" +
                        "         for (T2 value : values) {\n" +
                        "            Set<T1> keys = _reverse.get(value);\n" +
                        "            if (null == keys || !keys.contains(entry.getKey())) {\n" +
                        "               throw new IllegalStateException(\"Integrity compromised: forward map contains an entry missing from reverse map: \" + value); //$NON-NLS-1$\n" +
                        "            }\n" +
                        "         }\n" +
                        "      }\n" +
                        "      // And likewise in the other direction.\n" +
                        "      for (Map.Entry<T2, Set<T1>> entry : _reverse.entrySet()) {\n" +
                        "         Set<T1> keys = entry.getValue();\n" +
                        "         if (keys.isEmpty()) {\n" +
                        "            throw new IllegalStateException(\"Integrity compromised: reverse map contains an empty set\"); //$NON-NLS-1$\n" +
                        "         }\n" +
                        "         for (T1 key : keys) {\n" +
                        "            Set<T2> values = _forward.get(key);\n" +
                        "            if (null == values || !values.contains(entry.getKey())) {\n" +
                        "               throw new IllegalStateException(\"Integrity compromised: reverse map contains an entry missing from forward map: \" + key); //$NON-NLS-1$\n" +
                        "            }\n" +
                        "         }\n" +
                        "      }\n" +
                        "      return true;\n" +
                        "   }\n" +
                        "\n" +
                        "}"
        };
        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
    }

    @Test
    public void testTestabilityInjectFunctionField_ForNewOperatorInFieldInitializer() throws Exception {

        String[] task = {
                "Y.java",
                "import java.util.*;\n" +
                        "class Y<T1, T2> {\n" +
                        "   private final Map<T1, Set<T2>> _forward = new HashMap<>();" +
                        "}\n"
        };

        String expectedOutputY =
                "import helpers.Function1;\n" +
                        "import java.util.HashMap;\n" +
                        "import java.util.Map;\n" +
                        "import java.util.Set;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "class Y<T1, T2> {\n" +
                        "   public static Function1<CallContext<HashMap<Object, Set<Object>>>, HashMap<Object, Set<Object>>> $$HashMap$new = (var0) -> {\n" +
                        "      return new HashMap();\n" +
                        "   };\n" +
                        "   private final Map<T1, Set<T2>> _forward;\n" +
                        "\n" +
                        "   Y() {\n" +
                        "      this._forward = (HashMap)$$HashMap$new.apply(new CallContext(\"Y<T1,T2>\", \"java.util.HashMap<T1,java.util.Set<T2>>\", this, (Object)null));\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals(expectedOutputY, moduleMap.get("Y").stream().collect(joining("\n")));
    }

    @Test
    public void testTestabilityInjectFunctionField_ForCallInFieldInitializer() throws Exception {

        String[] task = {
                "Y.java",
                "import java.util.*;\n" +
                        "class Y<T1, T2> {\n" +
                        "   Map<T1, Set<T2>> init(){dontredirect: return new HashMap<>();}\n" +
                        "   private final Map<T1, Set<T2>> _forward = init();\n" +
                        "}\n"
        };

        String expectedOutputY =
                "import helpers.Function1;\n" +
                        "import java.util.HashMap;\n" +
                        "import java.util.Map;\n" +
                        "import java.util.Set;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "class Y<T1, T2> {\n" +
                        "   public static Function1<CallContext<Y<Object, Object>>, Map> $$Y$init = (var0) -> {\n" +
                        "      return ((Y)var0.calledClassInstance).init();\n" +
                        "   };\n" +
                        "   private final Map<T1, Set<T2>> _forward;\n" +
                        "\n" +
                        "   Y() {\n" +
                        "      this._forward = (Map)$$Y$init.apply(new CallContext(\"Y<T1,T2>\", \"Y<T1,T2>\", this, this));\n" +
                        "   }\n" +
                        "\n" +
                        "   Map<T1, Set<T2>> init() {\n" +
                        "      return new HashMap();\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals(expectedOutputY, moduleMap.get("Y").stream().collect(joining("\n")));
    }

    @Test
    public void testTestabilityInjectFunctionField_FieldOrder() throws Exception {

        String[] task = {
                "Y.java",
                "import java.util.*;\n" +
                        "class Y {\n" +
                        " static void fn3(){} \n" +
                        " static void fn1(){} \n" +
                        " static { " +
                        "   fn1();\n" +
                        "   fn2();\n" + //forward call, field needs to be previously defined
                        "   fn3();\n" +
                        " };\n" +
                        " static void fn2(){} \n" +
                        "}\n"
        };

        String expectedOutputY =
                "import helpers.Consumer1;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "class Y {\n" +
                        "   public static Consumer1<CallContext<Y>> $$Y$fn1;\n" +
                        "   public static Consumer1<CallContext<Y>> $$Y$fn2;\n" +
                        "   public static Consumer1<CallContext<Y>> $$Y$fn3;\n" +
                        "\n" +
                        "   static {\n" +
                        "      $$Y$fn1.accept(new CallContext(\"Y\", \"Y\", (Object)null, (Object)null));\n" +
                        "      $$Y$fn2.accept(new CallContext(\"Y\", \"Y\", (Object)null, (Object)null));\n" +
                        "      $$Y$fn3.accept(new CallContext(\"Y\", \"Y\", (Object)null, (Object)null));\n" +
                        "      $$Y$fn1 = (var0) -> {\n" +
                        "         fn1();\n" +
                        "      };\n" +
                        "      $$Y$fn2 = (var0) -> {\n" +
                        "         fn2();\n" +
                        "      };\n" +
                        "      $$Y$fn3 = (var0) -> {\n" +
                        "         fn3();\n" +
                        "      };\n" +
                        "   }\n" +
                        "\n" +
                        "   static void fn3() {\n" +
                        "   }\n" +
                        "\n" +
                        "   static void fn1() {\n" +
                        "   }\n" +
                        "\n" +
                        "   static void fn2() {\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals(expectedOutputY, moduleMap.get("Y").stream().collect(joining("\n")));
    }
    @Test
    public void testTestabilityInjectFunctionField_RedirectGenericToGenericAllocation() throws Exception {
        String[] task = {
                "Called.java",
                "class Called {\n" +
                        "	public <T> Called(Y y, T t){\n" +
                        "   }" +
                        "}",
                "Y.java",
                "import java.util.*;\n" +
                        "public class Y {\n" +
                        "   public <T> T accept(List<T> lst, T t) {\n" +
                        "     new Called(this, t);" +
                        "     return t;\n" + //this call is to generic function passing generic arg
                        "   }\n" +
                        "	public String fn(){" +
                        "     dontredirect: return accept(new ArrayList<String>(), \"\").getClass().getName();" +
                        "   }" +
                        "}\n"
        };

        String expectedOutputY =
                "import Y.1;\n" +
                        "import helpers.Function3_1;\n" +
                        "import java.util.ArrayList;\n" +
                        "import java.util.List;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "public class Y {\n" +
                        "   public static Function3_1<CallContext<Called>, Y, Object, Called> $$Called$new$$YObject = new 1();\n" +
                        "\n" +
                        "   public <T> T accept(List<T> var1, T var2) {\n" +
                        "      $$Called$new$$YObject.apply(new CallContext(\"Y\", \"Called\", this, (Object)null), this, var2);\n" +
                        "      return var2;\n" +
                        "   }\n" +
                        "\n" +
                        "   public String fn() {\n" +
                        "      return ((String)this.accept(new ArrayList(), \"\")).getClass().getName();\n" +
                        "   }\n" +
                        "}";

        String expectedOutputInner =
                "import helpers.Function3_1;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "class Y$1 implements Function3_1<CallContext<Called>, Y, Object, Called> {\n" +
                        "   public <E1> Called apply(CallContext<Called> var1, Y var2, Object var3) {\n" +
                        "      return new Called(var2, var3);\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals(expectedOutputY, moduleMap.get("Y").stream().collect(joining("\n")));
        assertEquals(expectedOutputInner, moduleMap.get("Y$1").stream().collect(joining("\n")));

        Object className = invokeCompiledMethod("Y", "fn");
        assertEquals("java.lang.String", className);
    }

    @Test
    public void testTestabilityInjectFunctionField_ListenersForNonLambdaRedirectors() throws Exception {
        String[] task = {
                "Y.java",
                "import java.util.*;\n" +
                        "public class Y {\n" +
                        "   public <T> T accept(List<T> lst, T t) {\n" +
                        "     called(this, t);" + //this call is to generic function passing generic arg
                        "     return t;\n" +
                        "   }\n" +
                        "	public <T> void called(Y y, T t){" +
                        "   }" +
                        "	public String fn(){" +
                        "     dontredirect: return accept(new ArrayList<String>(), \"\").getClass().getName();" +
                        "   }" +
                        "}\n"
        };

        String expectedOutputY =
                "import Y.1;\n" +
                        "import helpers.Consumer3_1;\n" +
                        "import java.util.ArrayList;\n" +
                        "import java.util.List;\n" +
                        "import java.util.function.Consumer;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "public class Y {\n" +
                        "   public static Consumer<Y> $$preCreate = (var0) -> {\n" +
                        "   };\n" +
                        "   public static Consumer<Y> $$postCreate = (var0) -> {\n" +
                        "   };\n" +
                        "   public static Consumer3_1<CallContext<Y>, Y, Object> $$Y$called$$Y$Object = new 1();\n" +
                        "\n" +
                        "   public Y() {\n" +
                        "      $$preCreate.accept(this);\n" +
                        "      $$postCreate.accept(this);\n" +
                        "   }\n" +
                        "\n" +
                        "   public <T> T accept(List<T> var1, T var2) {\n" +
                        "      $$Y$called$$Y$Object.accept(new CallContext(\"Y\", \"Y\", this, this), this, var2);\n" +
                        "      return var2;\n" +
                        "   }\n" +
                        "\n" +
                        "   public <T> void called(Y var1, T var2) {\n" +
                        "   }\n" +
                        "\n" +
                        "   public String fn() {\n" +
                        "      return ((String)this.accept(new ArrayList(), \"\")).getClass().getName();\n" +
                        "   }\n" +
                        "}";

        String expectedOutputInner =
                "import helpers.Consumer3_1;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "class Y$1 implements Consumer3_1<CallContext<Y>, Y, Object> {\n" +
                        "   public <T> void accept(CallContext<Y> var1, Y var2, Object var3) {\n" +
                        "      ((Y)var1.calledClassInstance).called(var2, var3);\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_ALL);
        assertEquals(expectedOutputY, moduleMap.get("Y").stream().collect(joining("\n")));
        assertEquals(expectedOutputInner, moduleMap.get("Y$1").stream().collect(joining("\n")));

        Object className = invokeCompiledMethod("Y", "fn");
        assertEquals("java.lang.String", className);
    }

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
        } catch (Exception ex) {

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

        Object actualXFieldNames = invokeCompiledMethod("X", "fieldNames");

        assertEquals(Collections.emptyList(), actualXFieldNames);

        Object actualYFieldNames = invokeCompiledMethod("Y", "fieldNames");

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

        Object actualX = invokeCompiledMethod("X", "fieldNames");

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

        Object actual = invokeCompiledMethod("X", "fieldNames");

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
        } catch (InvocationTargetException ex) {
            Throwable exOriginal = ex.getCause();
            assertTrue(exOriginal instanceof IOException);
            assertEquals("from lambda", exOriginal.getMessage());
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
    public void testTestabilityInjectFunctionField_ForNewOperatorForMemberClass() throws Exception {

        String[] task = {
                "Y.java",
                "public class Y {\n" +
                        "   class Z { " +
                        "      Z fnI(){return new Z();};\n" +
                        "   };\n" +
                        "   Z fn(){return new Z();};\n" +
                        "   Z fnExample(){dontredirect: return this.new Z();};\n" +
                        "}\n",

        };
        String expectedOutputY = "import Y.Z;\n" +
                "import helpers.Function1;\n" +
                "import testablejava.CallContext;\n" +
                "\n" +
                "public class Y {\n" +
                "   public static Function1<CallContext<Z>, Z> $$Z$new = (var0) -> {\n" +
                "      Y var10002 = (Y)var0.enclosingInstances[0];\n" +
                "      ((Y)var0.enclosingInstances[0]).getClass();\n" +
                "      return new Z(var10002);\n" +
                "   };\n" +
                "\n" +
                "   Z fn() {\n" +
                "      return (Z)$$Z$new.apply(new CallContext(\"Y\", \"Y.Z\", this, (Object)null, new Object[]{this}));\n" +
                "   }\n" +
                "\n" +
                "   Z fnExample() {\n" +
                "      this.getClass();\n" +
                "      return new Z(this);\n" +
                "   }\n" +
                "}";
        String expectedOutputZ = "import testablejava.CallContext;\n" +
                "\n" +
                "class Y$Z {\n" +
                "   Y$Z(Y var1) {\n" +
                "      this.this$0 = var1;\n" +
                "   }\n" +
                "\n" +
                "   Y$Z fnI() {\n" +
                "      return (Y$Z)Y.$$Z$new.apply(new CallContext(\"Y.Z\", \"Y.Z\", this, (Object)null, new Object[]{this.this$0}));\n" +
                "   }\n" +
                "}";//note an instance of Y passed into context as enclosing instance

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        assertEquals(expectedOutputY, moduleMap.get("Y").stream().collect(joining("\n")));
        assertEquals(expectedOutputZ, moduleMap.get("Y$Z").stream().collect(joining("\n")));
    }

    @Test
    public void testTestabilityInjectFunctionField_ForNewOperatorForStaticMemberClass() throws Exception {

        String[] task = {
                "Y.java",
                "public class Y {\n" +
                        "   static class Z { " +
                        "   };\n" +
                        "   Z fn(){return new Z();};\n" +
                        "   Z fnExample(){dontredirect: return new Z();};\n" + //from dynamic context new Y().new Z() is illegal
                        "}\n",

        };
        String expectedOutputY = "import Y.Z;\n" +
                "import helpers.Function1;\n" +
                "import testablejava.CallContext;\n" +
                "\n" +
                "public class Y {\n" +
                "   public static Function1<CallContext<Z>, Z> $$Z$new = (var0) -> {\n" +
                "      return new Z();\n" +
                "   };\n" +
                "\n" +
                "   Z fn() {\n" +
                "      return (Z)$$Z$new.apply(new CallContext(\"Y\", \"Y.Z\", this, (Object)null));\n" +
                "   }\n" +
                "\n" +
                "   Z fnExample() {\n" +
                "      return new Z();\n" +
                "   }\n" +
                "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        assertEquals(expectedOutputY, moduleMap.get("Y").stream().collect(joining("\n")));
    }

    @Test
    public void testTestabilityInjectFunctionField_ForNewOperatorForMemberClassFromWithinInnerClass() throws Exception {
        //Pb(2)  cannot be resolved to a type
        String[] task = {
                "Y.java",
                "public class Y {\n" +
                        "   class Z {" +
                        "      Z zfn(){return this;}" +
                        "   }" +
                        "   abstract class R {" +
                        "      abstract public Z run();" +
                        "   }" +
                        "	Z caller() {" +
                        "      R r =  new R(){" +
                        "          public Z run(){" +
                        "              return new Z();" +
                        "          }" +
                        "      };" +
                        "      dontredirect: return r.run();" +
                        "   }" +
                        "}"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals("Y$Z", invokeCompiledMethod("Y", "caller").getClass().getName());
    }
    @Test
    public void testTestabilityInjectFunctionField_ListenerForMemberClassNested() throws Exception {
        //Pb(2)  cannot be resolved to a type
        String[] task = {
                "Y.java",
                "public class Y {\n" +
                        "   class L1 {" +
                        "      class L2 {" +
                        "         class L3 {" +
                        "         }" +
                        "      }" +
                        "   }" +
                        "	L1.L2.L3 caller() {" +
                        "      dontredirect: return new L1().new L2().new L3();" +
                        "   }" +
                        "}"
        };
        String expectedOutputY = "import Y.L1;\n" +
                "import Y.L1.L2;\n" +
                "import Y.L1.L2.L3;\n" +
                "import java.util.function.Consumer;\n" +
                "\n" +
                "public class Y {\n" +
                "   public static Consumer<Y> $$preCreate = (var0) -> {\n" +
                "   };\n" +
                "   public static Consumer<Y> $$postCreate = (var0) -> {\n" +
                "   };\n" +
                "   public static Consumer<L1> $$L1$preCreate = (var0) -> {\n" +
                "   };\n" +
                "   public static Consumer<L1> $$L1$postCreate = (var0) -> {\n" +
                "   };\n" +
                "   public static Consumer<L2> $$L2$preCreate = (var0) -> {\n" +
                "   };\n" +
                "   public static Consumer<L2> $$L2$postCreate = (var0) -> {\n" +
                "   };\n" +
                "   public static Consumer<L3> $$L3$preCreate = (var0) -> {\n" +
                "   };\n" +
                "   public static Consumer<L3> $$L3$postCreate = (var0) -> {\n" +
                "   };\n" +
                "\n" +
                "   public Y() {\n" +
                "      $$preCreate.accept(this);\n" +
                "      $$postCreate.accept(this);\n" +
                "   }\n" +
                "\n" +
                "   L3 caller() {\n" +
                "      L1 var10004 = new L1(this);\n" +
                "      var10004.getClass();\n" +
                "      L2 var10002 = new L2(var10004);\n" +
                "      var10002.getClass();\n" +
                "      return new L3(var10002);\n" +
                "   }\n" +
                "}";
        String expectedOutputY$L1$L2$L3 = "import Y.L1.L2;\n" +
                "\n" +
                "class Y$L1$L2$L3 {\n" +
                "   Y$L1$L2$L3(L2 var1) {\n" +
                "      this.this$2 = var1;\n" +
                "      Y.$$L3$preCreate.accept(this);\n" +
                "      Y.$$L3$postCreate.accept(this);\n" +
                "   }\n" +
                "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_LISTENERS_ONLY);

        assertEquals(expectedOutputY, moduleMap.get("Y").stream().collect(joining("\n")));

        assertEquals(expectedOutputY$L1$L2$L3, moduleMap.get("Y$L1$L2$L3").stream().collect(joining("\n")));

        assertEquals("Y$L1$L2$L3", invokeCompiledMethod("Y", "caller").getClass().getName());
    }

    @Test
    public void testTestabilityInjectFunctionField_ForNewOperatorForMemberClassFromWithinInnerClassMultilevel() throws Exception {
        //Pb(2)  cannot be resolved to a type
        String[] task = {
                "L1.java",
                "public class L1 {\n" +
                        "   class L2 {" +
                        "    class L3 {" +
                        "     class L4 {" +
                        "      public L4 run(){" +
                        "        return new L4();" +
                        "      }" +
                        "     }" +
                        "    }" +
                        "   }" +
                        "	String caller() {" +
                        "      dontredirect: return new L2().new L3().new L4().run().getClass().getName();" +
                        "   }" +
                        "}"
        };

        compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals("L1$L2$L3$L4", invokeCompiledMethod("L1", "caller"));
    }

//TODO reenable
//    @Test
//    public void testTestabilityInjectFunctionField_ForNewOperatorForInnerClass() throws Exception {
//
//
//
//        String[] task = {
//                "Y.java",
//                "public class Y {\n" +
//                        "   void fn(){" +
//                        "     class Z { " +
//                        "     };\n" +
//                        "     new Z();" +
//                        "   };\n" +
//                        "}\n",
//
//        };
//        String expectedOutputY = "import Y.Z;\n" +
//                "import helpers.Function1;\n" +
//                "import testablejava.CallContext;\n" +
//                "\n" +
//                "public class Y {\n" +
//                "   public static Function1<CallContext<Z>, Z> $$Z$new = (var0) -> {\n" +
//                "      return new Z();\n" +
//                "   };\n" +
//                "\n" +
//                "   Z fn() {\n" +
//                "      return (Z)$$Z$new.apply(new CallContext(\"Y\", \"Y.Z\", this, (Object)null));\n" +
//                "   }\n" +
//                "\n" +
//                "   Z fnExample() {\n" +
//                "      return new Z();\n" +
//                "   }\n" +
//                "}";
//
//        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_NONE);// INSERT_REDIRECTORS_ONLY);
//
//        assertEquals(expectedOutputY, moduleMap.get("Y").stream().collect(joining("\n")));
//    }
//
//    @Test
//    public void testTestabilityInjectFunctionField_ForNewOperatorForFinalInnerClass() throws Exception {
//
//        String[] task = {
//                "Y.java",
//                "public class Y {\n" +
//                        "   void fn(){" +
//                        "     final class Z { " +
//                        "     };\n" +
//                        "     new Z();" +
//                        "   };\n" +
//                        "}\n",
//
//        };
//        String expectedOutputY = "import Y.Z;\n" +
//                "import helpers.Function1;\n" +
//                "import testablejava.CallContext;\n" +
//                "\n" +
//                "public class Y {\n" +
//                "   public static Function1<CallContext<Z>, Z> $$Z$new = (var0) -> {\n" +
//                "      return new Z();\n" +
//                "   };\n" +
//                "\n" +
//                "   Z fn() {\n" +
//                "      return (Z)$$Z$new.apply(new CallContext(\"Y\", \"Y.Z\", this, (Object)null));\n" +
//                "   }\n" +
//                "\n" +
//                "   Z fnExample() {\n" +
//                "      return new Z();\n" +
//                "   }\n" +
//                "}";
//
//        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
//
//        assertEquals(expectedOutputY, moduleMap.get("Y").stream().collect(joining("\n")));
//    }

    @Test
    public void testTestabilityInjectFunctionField_ForExternalCallWithExecute_InnerClassArg_TwoFields() throws Exception {
        //must generate two separate fields on Y, one with container1.new Z(), another with container2.new Z()
        String[] task = {
                "Y.java",
                "public class Y {\n" +
                        "   class Container1 {\n" +
                        "      class Z {};\n" +
                        "      Z fn(){return new Z();};\n" +
                        "   }\n",
                "   class Container2 {\n" +
                        "      class Z { " +
                        "      };\n" +
                        "      Z fn(){return new Z();};\n" +
                        "   }" +
                        "}\n",
        };
        String expectedOutputY = "import Y.Container1;\n" +
                "import Y.Container2;\n" +
                "import Y.Container1.Z;\n" +
                "import helpers.Function1;\n" +
                "import testablejava.CallContext;\n" +
                "\n" +
                "public class Y {\n" +
                "   public static Function1<CallContext<Z>, Z> $$Y$Container1$Z$new = (var0) -> {\n" +
                "      Container1 var10002 = (Container1)var0.enclosingInstances[0];\n" +
                "      ((Container1)var0.enclosingInstances[0]).getClass();\n" +
                "      return new Z(var10002);\n" +
                "   };\n" +
                "   public static Function1<CallContext<Y.Container2.Z>, Y.Container2.Z> $$Y$Container2$Z$new = (var0) -> {\n" +
                "      Container2 var10002 = (Container2)var0.enclosingInstances[0];\n" +
                "      ((Container2)var0.enclosingInstances[0]).getClass();\n" +
                "      return new Y.Container2.Z(var10002);\n" +
                "   };\n" +
                "}";
        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals(expectedOutputY, moduleMap.get("Y").stream().collect(joining("\n")));
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
                        "   public static Function2<CallContext<String>, String, String> $$String$new$$String = (var0, var1) -> {\n" +
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
                        "   public static Function2<CallContext<String>, Integer, String> $$String$valueOf$$I = (var0, var1) -> {\n" +
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
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "public class Y {\n" +
                        "   public static Function1<CallContext<Z>, Z> $$Z$zfn = (var0) -> {\n" +
                        "      return ((Z)var0.calledClassInstance).zfn();\n" +
                        "   };\n" +
                        "\n" +
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
                        "   public static Function1<CallContext<Z>, Long> $$Z$zfn = (var0) -> {\n" +
                        "      return Long.valueOf(((Z)var0.calledClassInstance).zfn());\n" +
                        "   };\n" +
                        "   public static Function1<CallContext<System>, Long> $$System$currentTimeMillis = (var0) -> {\n" +
                        "      return Long.valueOf(System.currentTimeMillis());\n" +
                        "   };\n\n" +
                        "   long fn() {\n" +
                        "      Z var1 = new Z();\n" +
                        "      return ((Long)$$Z$zfn.apply(new CallContext(\"Y\", \"Y.Z\", this, var1))).longValue();\n" +
                        "   }\n" +
                        "}";
        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        Object ret = invokeCompiledMethod("Y", "fn");//main.invoke(null);

        assertEquals(0, (System.currentTimeMillis() - (Long) ret) / 1000);

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
                        "            return i + 10 * j + 100 * k;" +
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
                        "    CallContext<?>[] ctx={null};\n" +
                        "    int getRequiredBridgesWithRedirect() {\n" +
                        "        X.$$BridgeCollector$collectBridges$$I$I$I = (a1, a2, a3, a4) -> 0;\n " +
                        "        dontredirect: return new X().getRequiredBridges();\n" +
                        "    }\n" +
                        "    CallContext<?> getRequiredBridgesCaptureCallContext() {\n" +
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

    @Test
    public void testTestabilityInjectFunctionField_NotInstrumentingInsideEnum() throws Exception {

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "    enum Stage {\n" +
                        "         OuterLess,\n" +
                        "         InnerOfProcessed,\n" +
                        "         InnerOfNotEnclosing,\n" +
                        "         AtExit\n" +
                        "    }" +
                        "    private Stage stage = Stage.OuterLess;" +
                        "}"
        };
        String expectedOutput =
                "import X.Stage;\n" +
                        "import java.util.function.Consumer;\n" +
                        "\n" +
                        "public class X {\n" +
                        "   public static Consumer<X> $$preCreate = (var0) -> {\n" +
                        "   };\n" +
                        "   public static Consumer<X> $$postCreate = (var0) -> {\n" +
                        "   };\n" +
                        "   private Stage stage;\n" +
                        "\n" +
                        "   public X() {\n" +
                        "      this.stage = Stage.OuterLess;\n" +
                        "      $$preCreate.accept(this);\n" +
                        "      $$postCreate.accept(this);\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_ALL);

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));
    }

    @Test
    public void testTestabilityInjectFunctionField_NotInstrumentingInsideEnum_Realistic() throws Exception {

        String[] task = {
                "ExpressionContext.java",
                "    public enum ExpressionContext {\n" +
                        "        ASSIGNMENT_CONTEXT {\n" +
                        "            public String toString() {\n" +
                        "                return \"assignment context\"; //$NON-NLS-1$\n" +
                        "            }\n" +
                        "            public boolean definesTargetType() {\n" +
                        "                return true;\n" +
                        "            }\n" +
                        "        },\n" +
                        "        INVOCATION_CONTEXT {\n" +
                        "            public String toString() {\n" +
                        "                return \"invocation context\"; //$NON-NLS-1$\n" +
                        "            }\n" +
                        "            public boolean definesTargetType() {\n" +
                        "                return true;\n" +
                        "            }\n" +
                        "        },\n" +
                        "        CASTING_CONTEXT {\n" +
                        "            public String toString() {\n" +
                        "                return \"casting context\"; //$NON-NLS-1$\n" +
                        "            }\n" +
                        "            public boolean definesTargetType() {\n" +
                        "                return false;\n" +
                        "            }\n" +
                        "        },\n" +
                        "        VANILLA_CONTEXT {\n" +
                        "            public String toString() {\n" +
                        "                return \"vanilla context\"; //$NON-NLS-1$\n" +
                        "            }\n" +
                        "            public boolean definesTargetType() {\n" +
                        "                return false;\n" +
                        "            }\n" +
                        "        };\n" +
                        "    }"

        };
        String expectedOutput =
                "public enum ExpressionContext {\n" +
                        "   ASSIGNMENT_CONTEXT,\n" +
                        "   INVOCATION_CONTEXT,\n" +
                        "   CASTING_CONTEXT,\n" +
                        "   VANILLA_CONTEXT;\n" +
                        "\n" +
                        "   private ExpressionContext() {\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_LISTENERS_ONLY);

        assertEquals(expectedOutput, moduleMap.get("ExpressionContext").stream().collect(joining("\n")));
    }

    @Test
    public void testTestabilityInjectFunctionField_EnumDefinedAsInnerInsideGenericIsNotGeneric() throws Exception {

        //error: Kind is not generic; it cannot be parameterized with arguments <>
        //enum defined as parameterized member class shows as parameterized (unlike normal member class which shows RAW)- special case

        String[] task = {
                "X.java",
                "public class X {\n" +
                        "  public void printMessage(Param.Kind kind) {\n" +
                        "    printMessage(kind, \"\");\n" +
                        " }\n" +
                        "  public void printMessage(Param.Kind kind, CharSequence msg) {\n" +
                        "  }\n" +
                        "}" +
                        "class Param<S>{" +
                        "   enum Kind {};" +
                        "}"
        };
        String expectedOutput =
                "import Param.Kind;\n" +
                        "import helpers.Consumer3;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "public class X {\n" +
                        "   public static Consumer3<CallContext<X>, Kind, String> $$X$printMessage$$Param$Kind$String = (var0, var1, var2) -> {\n" +
                        "      ((X)var0.calledClassInstance).printMessage(var1, var2);\n" +
                        "   };\n" +
                        "\n" +
                        "   public void printMessage(Kind var1) {\n" +
                        "      $$X$printMessage$$Param$Kind$String.accept(new CallContext(\"X\", \"X\", this, this), var1, \"\");\n" +
                        "   }\n" +
                        "\n" +
                        "   public void printMessage(Kind var1, CharSequence var2) {\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));
    }

    @Test
    public void testTestabilityInjectFunctionField_ReturningParameterizedCall() throws Exception {
        // case where generic argument for call is inferred from return type, which will not work a cast is returned

        String[] task = {
                "X.java",
                "import java.util.*;\n" +
                        "public class X {\n" +
                        "  public List<String> makeEmptyList() {\n" +
                        "    return Collections.emptyList();\n" +
                        " }\n" +
                        "}"

        };
        String expectedOutput =
                "import helpers.Function1;\n" +
                        "import java.util.Collections;\n" +
                        "import java.util.List;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "public class X {\n" +
                        "   public static Function1<CallContext<Collections>, List<String>> $$Collections$emptyList = (var0) -> {\n" +
                        "      return Collections.emptyList();\n" +
                        "   };\n" +
                        "\n" +
                        "   public List<String> makeEmptyList() {\n" +
                        "      return (List)$$Collections$emptyList.apply(new CallContext(\"X\", \"java.util.Collections\", this, (Object)null));\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));
    }

    @Test
    public void testTestabilityInjectFunctionField_ReproductionTypeParamMismatch() throws Exception {

        String[] task = {

                "Base.java",
                "import java.lang.annotation.Annotation;\n" +
                        "public class Base {\n" +
                        " public <A extends Annotation> A getAnnotation(Class<A> annotationType) {\n" +
                        " return null;" +
                        " }" +
                        "}",
                "X.java",
                "import java.lang.annotation.Annotation;\n" +
                        "import java.util.*;\n" +
                        "public class X extends Base {\n" +
//                        "public static helpers.Function2_1<testablejava.CallContext<X>, java.lang.Class<Object>, java.lang.Integer> $$reproduction = " +
//                        " new helpers.Function2_1<testablejava.CallContext<X>, java.lang.Class<Object>, java.lang.Integer>() {\n" +
//                        "  public <T>java.lang.Integer apply(testablejava.CallContext<X>  arg0, java.lang.Class<Object>  arg1) {\n" +
//                        "    testabilitylabel: ;\n" +
//                        "    return  arg0.calledClassInstance.calendarClassOfT((Class<T>)  arg1);\n" +
//                        "  }\n" +
//                        "};" +
                        "  public <T extends Calendar>java.lang.Integer apply(testablejava.CallContext<X>  arg0, java.lang.Class<Object>  arg1) {\n" +
                        "    testabilitylabel: ;\n" +
                        "    return  arg0.calledClassInstance.calendarClassOfT((Class)arg1);\n" +
                        "  }\n" +

                        "@Override\n" +
                        " public <A extends Annotation> A getAnnotation(Class<A> annotationType) {\n" +
                        "  return super.getAnnotation(annotationType);\n" +
                        " }" +
                        " public int listOfString(List<String> list) {\n" +
                        "   return 1;\n" +
                        " }" +
                        " public int listOfInteger(List<Integer> list) {\n" +
                        "   return 2;\n" +
                        " }" +
                        " public <T> int listOfT(List<T> list) {\n" +
                        "   return 3;\n" +
                        " }" +

                        " public int classOfString(Class<String> cl) {\n" +
                        "   return 4;\n" +
                        " }" +
                        " public int classOfInteger(Class<Integer> cl) {\n" +
                        "   return 5;\n" +
                        " }" +
                        " public <T> int classOfT(Class<T> cl) {\n" +
                        "   return 6;\n" +
                        " }" +
                        " public <T extends Calendar> int calendarClassOfT(Class<T> cl) {\n" +
                        "   return 7;\n" +
                        " }" +


                        " public int callListOfString(List<String> list) {\n" +
                        "   return listOfString(list);\n" +
                        " }" +
                        " public int callListOfInteger(List<Integer> list) {\n" +
                        "   return listOfInteger(list);\n" +
                        " }" +
                        " public <T> int callListOfT(List<T> list) {\n" +
                        "   return listOfT(list);\n" +
                        " }" +

                        " public int callClassOfString(Class<String> cl) {\n" +
                        "   return classOfString(cl);\n" +
                        " }" +
                        " public int callClassOfInteger(Class<Integer> cl) {\n" +
                        "   return classOfInteger(cl);\n" +
                        " }" +


                        " public <T> int callClassOfT(Class<T> cl) {\n" +
                        "   return classOfT(cl);\n" +
                        " }" +
                        " public <T extends Calendar> int callCalendarClassOfT(Class<T> cl) {\n" +
                        "   return calendarClassOfT(cl);\n" +
                        " }" +

                        "}"
        };
        String expectedOutput =
                "import helpers.Function2;\n" +
                        "import java.lang.annotation.Annotation;\n" +
                        "import java.util.Calendar;\n" +
                        "import java.util.List;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "public class X extends Base {\n" +
                        "   public static Function2<CallContext<X>, List<Integer>, Integer> $$X$listOfInteger$$List = (var0, var1) -> {\n" +
                        "      return Integer.valueOf(((X)var0.calledClassInstance).listOfInteger(var1));\n" +
                        "   };\n" +
                        "   public static Function2<CallContext<X>, Class, Integer> $$X$calendarClassOfT$$Class = (var0, var1) -> {\n" +
                        "      return Integer.valueOf(((X)var0.calledClassInstance).calendarClassOfT(var1));\n" +
                        "   };\n" +
                        "   public static Function2<CallContext<X>, Class<Integer>, Integer> $$X$classOfInteger$$Class = (var0, var1) -> {\n" +
                        "      return Integer.valueOf(((X)var0.calledClassInstance).classOfInteger(var1));\n" +
                        "   };\n" +
                        "   public static Function2<CallContext<X>, List, Integer> $$X$listOfT$$List = (var0, var1) -> {\n" +
                        "      return Integer.valueOf(((X)var0.calledClassInstance).listOfT(var1));\n" +
                        "   };\n" +
                        "   public static Function2<CallContext<X>, Class<String>, Integer> $$X$classOfString$$Class = (var0, var1) -> {\n" +
                        "      return Integer.valueOf(((X)var0.calledClassInstance).classOfString(var1));\n" +
                        "   };\n" +
                        "   public static Function2<CallContext<X>, Class, Integer> $$X$classOfT$$Class = (var0, var1) -> {\n" +
                        "      return Integer.valueOf(((X)var0.calledClassInstance).classOfT(var1));\n" +
                        "   };\n" +
                        "   public static Function2<CallContext<X>, List<String>, Integer> $$X$listOfString$$List = (var0, var1) -> {\n" +
                        "      return Integer.valueOf(((X)var0.calledClassInstance).listOfString(var1));\n" +
                        "   };\n" +
                        "   public static Function2<CallContext<Base>, Class, Object> $$Base$getAnnotation$$Class = (var0, var1) -> {\n" +
                        "      return ((Base)var0.calledClassInstance).getAnnotation(var1);\n" +
                        "   };\n" +
                        "\n" +
                        "   public <T extends Calendar> Integer apply(CallContext<X> var1, Class<Object> var2) {\n" +
                        "      return Integer.valueOf(((Integer)$$X$calendarClassOfT$$Class.apply(new CallContext(\"X\", \"X\", this, (X)var1.calledClassInstance), var2)).intValue());\n" +
                        "   }\n" +
                        "\n" +
                        "   public <A extends Annotation> A getAnnotation(Class<A> var1) {\n" +
                        "      return (Annotation)$$Base$getAnnotation$$Class.apply(new CallContext(\"X\", \"Base\", this, this), var1);\n" +
                        "   }\n" +
                        "\n" +
                        "   public int listOfString(List<String> var1) {\n" +
                        "      return 1;\n" +
                        "   }\n" +
                        "\n" +
                        "   public int listOfInteger(List<Integer> var1) {\n" +
                        "      return 2;\n" +
                        "   }\n" +
                        "\n" +
                        "   public <T> int listOfT(List<T> var1) {\n" +
                        "      return 3;\n" +
                        "   }\n" +
                        "\n" +
                        "   public int classOfString(Class<String> var1) {\n" +
                        "      return 4;\n" +
                        "   }\n" +
                        "\n" +
                        "   public int classOfInteger(Class<Integer> var1) {\n" +
                        "      return 5;\n" +
                        "   }\n" +
                        "\n" +
                        "   public <T> int classOfT(Class<T> var1) {\n" +
                        "      return 6;\n" +
                        "   }\n" +
                        "\n" +
                        "   public <T extends Calendar> int calendarClassOfT(Class<T> var1) {\n" +
                        "      return 7;\n" +
                        "   }\n" +
                        "\n" +
                        "   public int callListOfString(List<String> var1) {\n" +
                        "      return ((Integer)$$X$listOfString$$List.apply(new CallContext(\"X\", \"X\", this, this), var1)).intValue();\n" +
                        "   }\n" +
                        "\n" +
                        "   public int callListOfInteger(List<Integer> var1) {\n" +
                        "      return ((Integer)$$X$listOfInteger$$List.apply(new CallContext(\"X\", \"X\", this, this), var1)).intValue();\n" +
                        "   }\n" +
                        "\n" +
                        "   public <T> int callListOfT(List<T> var1) {\n" +
                        "      return ((Integer)$$X$listOfT$$List.apply(new CallContext(\"X\", \"X\", this, this), var1)).intValue();\n" +
                        "   }\n" +
                        "\n" +
                        "   public int callClassOfString(Class<String> var1) {\n" +
                        "      return ((Integer)$$X$classOfString$$Class.apply(new CallContext(\"X\", \"X\", this, this), var1)).intValue();\n" +
                        "   }\n" +
                        "\n" +
                        "   public int callClassOfInteger(Class<Integer> var1) {\n" +
                        "      return ((Integer)$$X$classOfInteger$$Class.apply(new CallContext(\"X\", \"X\", this, this), var1)).intValue();\n" +
                        "   }\n" +
                        "\n" +
                        "   public <T> int callClassOfT(Class<T> var1) {\n" +
                        "      return ((Integer)$$X$classOfT$$Class.apply(new CallContext(\"X\", \"X\", this, this), var1)).intValue();\n" +
                        "   }\n" +
                        "\n" +
                        "   public <T extends Calendar> int callCalendarClassOfT(Class<T> var1) {\n" +
                        "      return ((Integer)$$X$calendarClassOfT$$Class.apply(new CallContext(\"X\", \"X\", this, this), var1)).intValue();\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);

        assertEquals(1, invokeCompiledMethod("X", "callListOfString", new ArrayList<String>()));
        assertEquals(2, invokeCompiledMethod("X", "callListOfInteger", new ArrayList<Integer>()));
        assertEquals(3, invokeCompiledMethod("X", "callListOfT", new ArrayList<String>()));

        assertEquals(4, invokeCompiledMethod("X", "callClassOfString", new String().getClass()));
        assertEquals(5, invokeCompiledMethod("X", "callClassOfInteger", new Integer(1).getClass()));
        assertEquals(6, invokeCompiledMethod("X", "callClassOfT", new String().getClass()));

        assertEquals(7, invokeCompiledMethod("X", "callCalendarClassOfT", Calendar.getInstance().getClass()));

        assertEquals(expectedOutput, moduleMap.get("X").stream().collect(joining("\n")));
    }
//
//    @Test
//    public void testTestabilityInjectFunctionField_Reproduction_CannotCastFromSetObjectToSetElement() throws Exception {
//
//        String[] task = {
//                "Depends.java",
//                "import javax.annotation.processing.RoundEnvironment;\n" +
//                        "import javax.lang.model.AnnotatedConstruct;\n" +
//                        "import javax.lang.model.element.Element;\n" +
//                        "import javax.lang.model.element.ElementKind;\n" +
//                        "import javax.lang.model.element.TypeElement;\n" +
//                        "import javax.lang.model.util.ElementFilter;\n" +
//                        "import javax.lang.model.util.Elements;\n" +
//                        "import java.lang.annotation.Annotation;\n" +
//                        "import java.util.Collections;\n" +
//                        "import java.util.HashSet;\n" +
//                        "import java.util.Set;\n" +
//                "class Factory {\n" +
//                        "    public static AnnotationBinding[] getPackedAnnotationBindings(Object annotations) {\n" +
//                        "        return new AnnotationBinding[0];\n" +
//                        "    }\n" +
//                        "\n" +
//                        "    public Element newElement(Object annotationType) {\n" +
//                        "        return null;\n" +
//                        "    }\n" +
//                        "}\n" +
//                        "\n" +
//                        "class TypeElementImpl {\n" +
//                        "    public Binding _binding;\n" +
//                        "}\n" +
//                        "\n" +
//                        "class ManyToMany<T, E extends AnnotatedConstruct> {\n" +
//                        "    public void put(E anno, E element) {\n" +
//                        "\n" +
//                        "    }\n" +
//                        "\n" +
//                        "    public Set<T> getKeySet() {\n" +
//                        "        return null;\n" +
//                        "    }\n" +
//                        "\n" +
//                        "    public Set<E> getValues(E a) {\n" +
//                        "        return null;\n" +
//                        "    }\n" +
//                        "}\n" +
//                        "\n" +
//                        "class Scope {\n" +
//                        "    public SourceTypeBinding[] topLevelTypes;\n" +
//                        "}\n" +
//                        "\n" +
//                        "class CompilationUnitDeclaration {\n" +
//                        "    public Scope scope;\n" +
//                        "\n" +
//                        "    public void traverse(AnnotationDiscoveryVisitor visitor, Object scope) {\n" +
//                        "\n" +
//                        "    }\n" +
//                        "}\n" +
//                        "\n" +
//                        "class AnnotationBinding {\n" +
//                        "    public Object getAnnotationType() {\n" +
//                        "        return null;\n" +
//                        "    }\n" +
//                        "}\n" +
//                        "\n" +
//                        "class Binding {\n" +
//                        "    public int getAnnotationTagBits() {\n" +
//                        "        return 0;\n" +
//                        "    }\n" +
//                        "\n" +
//                        "    public boolean isClass() {\n" +
//                        "        return false;\n" +
//                        "    }\n" +
//                        "\n" +
//                        "    public ReferenceBinding superclass() {\n" +
//                        "        return null;\n" +
//                        "    }\n" +
//                        "}\n" +
//                        "\n" +
//                        "class FieldBinding {\n" +
//                        "    private Object annotations;\n" +
//                        "\n" +
//                        "    public Object getAnnotations() {\n" +
//                        "        return annotations;\n" +
//                        "    }\n" +
//                        "}\n" +
//                        "\n" +
//                        "class MethodBinding {\n" +
//                        "    public Object getAnnotations() {\n" +
//                        "        return null;\n" +
//                        "    }\n" +
//                        "}\n" +
//                        "\n" +
//                        "class ParameterizedTypeBinding extends ReferenceBinding {\n" +
//                        "    public ReferenceBinding genericType() {\n" +
//                        "        return null;\n" +
//                        "    }\n" +
//                        "}\n" +
//                        "\n" +
//                        "class ReferenceBinding extends Binding {\n" +
//                        "    public Object getAnnotations() {\n" +
//                        "        return null;\n" +
//                        "    }\n" +
//                        "\n" +
//                        "    public FieldBinding[] fields() {\n" +
//                        "        return new FieldBinding[0];\n" +
//                        "    }\n" +
//                        "\n" +
//                        "    public MethodBinding[] methods() {\n" +
//                        "        return new MethodBinding[0];\n" +
//                        "    }\n" +
//                        "\n" +
//                        "    public ReferenceBinding[] memberTypes() {\n" +
//                        "        return new ReferenceBinding[0];\n" +
//                        "    }\n" +
//                        "}\n" +
//                        "\n" +
//                        "class SourceTypeBinding {\n" +
//                        "}\n" +
//                        "\n" +
//                        "class TagBits {\n" +
//                        "    public static int AnnotationInherited;\n" +
//                        "}\n" +
//                        "\n" +
//                        "class BaseProcessingEnvImpl {\n" +
//                        "    public Factory getFactory() {\n" +
//                        "        return null;\n" +
//                        "    }\n" +
//                        "\n" +
//                        "    public boolean errorRaised() {\n" +
//                        "        return false;\n" +
//                        "    }\n" +
//                        "\n" +
//                        "    public Elements getElementUtils() {\n" +
//                        "        return null;\n" +
//                        "    }\n" +
//                        "}\n" +
//                        "\n" +
//                        "class AnnotationDiscoveryVisitor {\n" +
//                        "    public ManyToMany<TypeElement, Element> _annoToElement;\n" +
//                        "\n" +
//                        "    public AnnotationDiscoveryVisitor(BaseProcessingEnvImpl processingEnv) {\n" +
//                        "\n" +
//                        "    }\n" +
//                        "}\n" +
//                        "\n",
//
//
//                        "RoundEnvImpl.java",
//                                "import javax.annotation.processing.RoundEnvironment;\n" +
//                                "import javax.lang.model.AnnotatedConstruct;\n" +
//                                "import javax.lang.model.element.Element;\n" +
//                                "import javax.lang.model.element.ElementKind;\n" +
//                                "import javax.lang.model.element.TypeElement;\n" +
//                                "import javax.lang.model.util.ElementFilter;\n" +
//                                "import javax.lang.model.util.Elements;\n" +
//                                "import java.lang.annotation.Annotation;\n" +
//                                "import java.util.Collections;\n" +
//                                "import java.util.HashSet;\n" +
//                                "import java.util.Set;\n" +
//                                "\n" +
//                                "class RoundEnvImpl implements RoundEnvironment {\n" +
//                                "    private final BaseProcessingEnvImpl _processingEnv;\n" +
//                                "    private final boolean _isLastRound;\n" +
//                                "    private final CompilationUnitDeclaration[] _units;\n" +
//                                "    private final ManyToMany<TypeElement, Element> _annoToUnit;\n" +
//                                "    private final ReferenceBinding[] _binaryTypes;\n" +
//                                "    private final Factory _factory;\n" +
//                                "    private Set<Element> _rootElements = null;\n" +
//                                "\n" +
//                                "    public RoundEnvImpl(CompilationUnitDeclaration[] units, ReferenceBinding[] binaryTypeBindings, boolean isLastRound, BaseProcessingEnvImpl env) {\n" +
//                                "        _processingEnv = env;\n" +
//                                "        _isLastRound = isLastRound;\n" +
//                                "        _units = units;\n" +
//                                "        _factory = _processingEnv.getFactory();\n" +
//                                "\n" +
//                                "        // Discover the annotations that will be passed to Processor.process()\n" +
//                                "        AnnotationDiscoveryVisitor visitor = new AnnotationDiscoveryVisitor(_processingEnv);\n" +
//                                "        if (_units != null) {\n" +
//                                "            for (CompilationUnitDeclaration unit : _units) {\n" +
//                                "\n" +
//                                "                unit.traverse(visitor, unit.scope);\n" +
//                                "\n" +
//                                "            }\n" +
//                                "        }\n" +
//                                "        _annoToUnit = visitor._annoToElement;\n" +
//                                "        if (binaryTypeBindings != null) collectAnnotations(binaryTypeBindings);\n" +
//                                "        _binaryTypes = binaryTypeBindings;\n" +
//                                "    }\n" +
//                                "\n" +
//                                "    private void collectAnnotations(ReferenceBinding[] referenceBindings) {\n" +
//                                "        for (ReferenceBinding referenceBinding : referenceBindings) {\n" +
//                                "            // collect all annotations from the binary types\n" +
//                                "            if (referenceBinding instanceof ParameterizedTypeBinding) {\n" +
//                                "                referenceBinding = ((ParameterizedTypeBinding) referenceBinding).genericType();\n" +
//                                "            }\n" +
//                                "            AnnotationBinding[] annotationBindings = Factory.getPackedAnnotationBindings(referenceBinding.getAnnotations());\n" +
//                                "            for (AnnotationBinding annotationBinding : annotationBindings) {\n" +
//                                "                TypeElement anno = (TypeElement) _factory.newElement(annotationBinding.getAnnotationType());\n" +
//                                "                Element element = _factory.newElement(referenceBinding);\n" +
//                                "                _annoToUnit.put(anno, element);\n" +
//                                "            }\n" +
//                                "            FieldBinding[] fieldBindings = referenceBinding.fields();\n" +
//                                "            for (FieldBinding fieldBinding : fieldBindings) {\n" +
//                                "                annotationBindings = Factory.getPackedAnnotationBindings(fieldBinding.getAnnotations());\n" +
//                                "                for (AnnotationBinding annotationBinding : annotationBindings) {\n" +
//                                "                    TypeElement anno = (TypeElement) _factory.newElement(annotationBinding.getAnnotationType());\n" +
//                                "                    Element element = _factory.newElement(fieldBinding);\n" +
//                                "                    _annoToUnit.put(anno, element);\n" +
//                                "                }\n" +
//                                "            }\n" +
//                                "            MethodBinding[] methodBindings = referenceBinding.methods();\n" +
//                                "            for (MethodBinding methodBinding : methodBindings) {\n" +
//                                "                annotationBindings = Factory.getPackedAnnotationBindings(methodBinding.getAnnotations());\n" +
//                                "                for (AnnotationBinding annotationBinding : annotationBindings) {\n" +
//                                "                    TypeElement anno = (TypeElement) _factory.newElement(annotationBinding.getAnnotationType());\n" +
//                                "                    Element element = _factory.newElement(methodBinding);\n" +
//                                "                    _annoToUnit.put(anno, element);\n" +
//                                "                }\n" +
//                                "            }\n" +
//                                "            ReferenceBinding[] memberTypes = referenceBinding.memberTypes();\n" +
//                                "            collectAnnotations(memberTypes);\n" +
//                                "        }\n" +
//                                "    }\n" +
//                                "\n" +
//                                "    /**\n" +
//                                "     * Return the set of annotation types that were discovered on the root elements.\n" +
//                                "     * This does not include inherited annotations, only those directly on the root\n" +
//                                "     * elements.\n" +
//                                "     *\n" +
//                                "     * @return a set of annotation types, possibly empty.\n" +
//                                "     */\n" +
//                                "    public Set getRootAnnotations() {\n" +
//                                "        return Collections.unmodifiableSet(_annoToUnit.getKeySet());\n" +
//                                "    }\n" +
//                                "\n" +
//                                "    @Override\n" +
//                                "    public boolean errorRaised() {\n" +
//                                "        return _processingEnv.errorRaised();\n" +
//                                "    }\n" +
//                                "\n" +
//                                "    /**\n" +
//                                "     * From the set of root elements and their enclosed elements, return the subset that are annotated\n" +
//                                "     * with {@code a}.  If {@code a} is annotated with the {@link java.lang.annotation.Inherited}\n" +
//                                "     * annotation, include those elements that inherit the annotation from their superclasses.\n" +
//                                "     * Note that {@link java.lang.annotation.Inherited} only applies to classes (i.e. TypeElements).\n" +
//                                "     */\n" +
//                                "    @Override\n" +
//                                "    public Set<? extends Element> getElementsAnnotatedWith(TypeElement a) {\n" +
//                                "        if (a.getKind() != ElementKind.ANNOTATION_TYPE) {\n" +
//                                "            throw new IllegalArgumentException(\"Argument must represent an annotation type\"); //$NON-NLS-1$\n" +
//                                "        }\n" +
//                                "        Binding annoBinding = ((TypeElementImpl) a)._binding;\n" +
//                                "        if (0 != (annoBinding.getAnnotationTagBits() & TagBits.AnnotationInherited)) {\n" +
//                                "            Set<Element> annotatedElements = new HashSet<>(_annoToUnit.getValues(a));\n" +
//                                "            // For all other root elements that are TypeElements, and for their recursively enclosed\n" +
//                                "            // types, add each element if it has a superclass are annotated with 'a'\n" +
//                                "            ReferenceBinding annoTypeBinding = (ReferenceBinding) annoBinding;\n" +
//                                "            for (TypeElement element : ElementFilter.typesIn(getRootElements())) {\n" +
//                                "                ReferenceBinding typeBinding = (ReferenceBinding) ((TypeElementImpl) element)._binding;\n" +
//                                "                addAnnotatedElements(annoTypeBinding, typeBinding, annotatedElements);\n" +
//                                "            }\n" +
//                                "            return Collections.unmodifiableSet(annotatedElements);\n" +
//                                "        }\n" +
//                                "        return Collections.unmodifiableSet(_annoToUnit.getValues(a));\n" +
//                                "    }\n" +
//                                "\n" +
//                                "    /**\n" +
//                                "     * For every type in types that is a class and that is annotated with anno, either directly or by inheritance,\n" +
//                                "     * add that type to result.  Recursively descend on each types's child classes as well.\n" +
//                                "     *\n" +
//                                "     * @param anno   the compiler binding for an annotation type\n" +
//                                "     * @param type   a type, not necessarily a class\n" +
//                                "     * @param result must be a modifiable Set; will accumulate annotated classes\n" +
//                                "     */\n" +
//                                "    private void addAnnotatedElements(ReferenceBinding anno, ReferenceBinding type, Set<Element> result) {\n" +
//                                "        if (type.isClass()) {\n" +
//                                "            if (inheritsAnno(type, anno)) {\n" +
//                                "                result.add(_factory.newElement(type));\n" +
//                                "            }\n" +
//                                "        }\n" +
//                                "        for (ReferenceBinding element : type.memberTypes()) {\n" +
//                                "            addAnnotatedElements(anno, element, result);\n" +
//                                "        }\n" +
//                                "    }\n" +
//                                "\n" +
//                                "    /**\n" +
//                                "     * Check whether an element has a superclass that is annotated with an @Inherited annotation.\n" +
//                                "     *\n" +
//                                "     * @param element must be a class (not an interface, enum, etc.).\n" +
//                                "     * @param anno    must be an annotation type, and must be @Inherited\n" +
//                                "     * @return true if element has a superclass that is annotated with anno\n" +
//                                "     */\n" +
//                                "    private boolean inheritsAnno(ReferenceBinding element, ReferenceBinding anno) {\n" +
//                                "        ReferenceBinding searchedElement = element;\n" +
//                                "        do {\n" +
//                                "            if (searchedElement instanceof ParameterizedTypeBinding) {\n" +
//                                "                searchedElement = ((ParameterizedTypeBinding) searchedElement).genericType();\n" +
//                                "            }\n" +
//                                "            AnnotationBinding[] annos = Factory.getPackedAnnotationBindings(searchedElement.getAnnotations());\n" +
//                                "            for (AnnotationBinding annoBinding : annos) {\n" +
//                                "                if (annoBinding.getAnnotationType() == anno) { //$IDENTITY-COMPARISON$\n" +
//                                "                    // element is annotated with anno\n" +
//                                "                    return true;\n" +
//                                "                }\n" +
//                                "            }\n" +
//                                "        } while (null != (searchedElement = searchedElement.superclass()));\n" +
//                                "        return false;\n" +
//                                "    }\n" +
//                                "\n" +
//                                "    @Override\n" +
//                                "    public Set<? extends Element> getElementsAnnotatedWith(Class<? extends Annotation> a) {\n" +
//                                "        String canonicalName = a.getCanonicalName();\n" +
//                                "        if (canonicalName == null) {\n" +
//                                "            // null for anonymous and local classes or an array of those\n" +
//                                "            throw new IllegalArgumentException(\"Argument must represent an annotation type\"); //$NON-NLS-1$\n" +
//                                "        }\n" +
//                                "        TypeElement annoType = _processingEnv.getElementUtils().getTypeElement(canonicalName);\n" +
//                                "        if (annoType == null) {\n" +
//                                "            return Collections.emptySet();\n" +
//                                "        }\n" +
//                                "        return getElementsAnnotatedWith(annoType);\n" +
//                                "    }\n" +
//                                "\n" +
//                                "    @Override\n" +
//                                "    public Set<? extends Element> getRootElements() {\n" +
//                                "        if (_units == null) {\n" +
//                                "            return Collections.emptySet();\n" +
//                                "        }\n" +
//                                "        if (_rootElements == null) {\n" +
//                                "            Set<Element> elements = new HashSet<>(_units.length);\n" +
//                                "            for (CompilationUnitDeclaration unit : _units) {\n" +
//                                "                if (null == unit.scope || null == unit.scope.topLevelTypes)\n" +
//                                "                    continue;\n" +
//                                "                for (SourceTypeBinding binding : unit.scope.topLevelTypes) {\n" +
//                                "                    Element element = _factory.newElement(binding);\n" +
//                                "                    if (null == element) {\n" +
//                                "                        throw new IllegalArgumentException(\"Top-level type binding could not be converted to element: \" + binding); //$NON-NLS-1$\n" +
//                                "                    }\n" +
//                                "                    elements.add(element);\n" +
//                                "                }\n" +
//                                "            }\n" +
//                                "            if (this._binaryTypes != null) {\n" +
//                                "                for (ReferenceBinding typeBinding : _binaryTypes) {\n" +
//                                "                    Element element = _factory.newElement(typeBinding);\n" +
//                                "                    if (null == element) {\n" +
//                                "                        throw new IllegalArgumentException(\"Top-level type binding could not be converted to element: \" + typeBinding); //$NON-NLS-1$\n" +
//                                "                    }\n" +
//                                "                    elements.add(element);\n" +
//                                "                }\n" +
//                                "            }\n" +
//                                "            _rootElements = elements;\n" +
//                                "        }\n" +
//                                "        return _rootElements;\n" +
//                                "    }\n" +
//                                "\n" +
//                                "    @Override\n" +
//                                "    public boolean processingOver() {\n" +
//                                "        return _isLastRound;\n" +
//                                "    }\n" +
//                                "\n" +
//                                "}\n" +
//                                "\n\n"
//        };
//        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
//
//        String expectedOutput="";
//        assertEquals(expectedOutput, moduleMap.get("RoundEnvImpl").stream().collect(joining("\n")));
//    }


    @Test
    public void testTestabilityInjectFunctionField_ForCallInferringTypeParameterFromLvalue() throws Exception {

        String[] task = {
                "Y.java",
                "import java.util.*;\n" +
                        "class Y {\n" +
                        "   <T> List<T> init(){dontredirect: return new ArrayList<>();}\n" +
                        "   List<String> _forward = init();\n" +
                        "}\n"
        };

        String expectedOutputY =
                "import helpers.Function1;\n" +
                        "import java.util.ArrayList;\n" +
                        "import java.util.List;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "class Y {\n" +
                        "   public static Function1<CallContext<Y>, List<String>> $$Y$init = (var0) -> {\n" +
                        "      return ((Y)var0.calledClassInstance).init();\n" +
                        "   };\n" +
                        "   List<String> _forward;\n" +
                        "\n" +
                        "   Y() {\n" +
                        "      this._forward = (List)$$Y$init.apply(new CallContext(\"Y\", \"Y\", this, this));\n" +
                        "   }\n" +
                        "\n" +
                        "   <T> List<T> init() {\n" +
                        "      return new ArrayList();\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals(expectedOutputY, moduleMap.get("Y").stream().collect(joining("\n")));
    }



    @Test
    public void testTestabilityInjectFunctionField_ForCallInferringTypeParameterFromMethodReturn() throws Exception {

        String[] task = {
                "Y.java",
                "import java.util.*;\n" +
                        "class Y {\n" +
                        "   Set<String> fn() {\n" +
                        "        return Collections.emptySet();\n" +
                        "   }\n" +
                        "}\n"
        };

        String expectedOutputY =
                "import helpers.Function1;\n" +
                        "import java.util.Collections;\n" +
                        "import java.util.Set;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "class Y {\n" +
                        "   public static Function1<CallContext<Collections>, Set<String>> $$Collections$emptySet = (var0) -> {\n" +
                        "      return Collections.emptySet();\n" +
                        "   };\n" +
                        "\n" +
                        "   Set<String> fn() {\n" +
                        "      return (Set)$$Collections$emptySet.apply(new CallContext(\"Y\", \"java.util.Collections\", this, (Object)null));\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals(expectedOutputY, moduleMap.get("Y").stream().collect(joining("\n")));
    }
    @Test
    public void testTestabilityInjectFunctionField_ArgIsArrayOfparameterizedType() throws Exception {

        String[] task = {
                "Y.java",
                "import java.util.*;\n" +
                        "class Y {\n" +
                        "   void fn() {\n" +
                        "        ArrayList[] list = {};" +
                        "        ArrayList<String>[] list2 = list;" +
                        "        Arrays.sort(list2, new Comparator<List<String>>() {\n" +
                        "               public int compare(List<String> o1, List<String> o2) {\n" +
                        "                  return 0;\n" +
                        "               }\n" +
                        "            });\n" +
                        "   }\n" +
                        "}\n"
        };

        String expectedOutputY =
                "import Y.1;\n" +
                        "import helpers.Consumer3;\n" +
                        "import java.util.ArrayList;\n" +
                        "import java.util.Arrays;\n" +
                        "import java.util.Comparator;\n" +
                        "import java.util.List;\n" +
                        "import testablejava.CallContext;\n" +
                        "\n" +
                        "class Y {\n" +
                        "   public static Consumer3<CallContext<Arrays>, ArrayList<String>[], Comparator<List<String>>> $$Arrays$sort$$ⒶArrayList$Y$1 = (var0, var1, var2) -> {\n" +
                        "      Arrays.sort(var1, var2);\n" +
                        "   };\n" +
                        "\n" +
                        "   void fn() {\n" +
                        "      ArrayList[] var1 = new ArrayList[0];\n" +
                        "      $$Arrays$sort$$ⒶArrayList$Y$1.accept(new CallContext(\"Y\", \"java.util.Arrays\", this, (Object)null), var1, new 1(this));\n" +
                        "   }\n" +
                        "}";

        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
        assertEquals(expectedOutputY, moduleMap.get("Y").stream().collect(joining("\n")));
    }
    @Test
    public void testTestabilityInjectFunctionField_WrongCalledType_Reproduction() throws Exception {

        String[] task = {

                "DefaultProblemFactory.java",
                "import java.util.Locale;" +
                "class DefaultProblemFactory {void setLocale(Locale locale){}}\n",

                "EclipseCompilerImpl.java",
                "import javax.tools.Diagnostic;\n" +
                        "import javax.tools.DiagnosticListener;\n" +
                        "import javax.tools.JavaFileObject;\n" +
                        "import java.util.Locale;" +
                "class EclipseCompilerImpl {\n" +
                        "\n" +
                        "\n" +
                        "    public void getProblemFactory() {\n" +
                        "         new DefaultProblemFactory() {\n" +
                        "\n" +
                        "            public void createProblem(){\n" +
                        "\n" +
                        "                DiagnosticListener<? super JavaFileObject> diagListener = null;\n" +
                        "                if (diagListener != null) {\n" +
                        "                    dontredirect: diagListener.report(new Diagnostic<JavaFileObject>() {\n" +
                        "                        @Override\n" +
                        "                        public String getCode() {\n" +
                        "                            return null;\n" +
                        "                        }\n" +
                        "                        @Override\n" +
                        "                        public long getColumnNumber() {\n" +
                        "                            return 0;\n" +
                        "                        }\n" +
                        "                        @Override\n" +
                        "                        public long getEndPosition() {\n" +
                        "                            return 0;\n" +
                        "                        }\n" +
                        "                        @Override\n" +
                        "                        public long getLineNumber() {\n" +
                        "                            return 0;\n" +
                        "                        }\n" +
                        "                        @Override\n" +
                        "                        public long getStartPosition() {\n" +
                        "                            return 0;\n" +
                        "                        }\n" +
                        "                        @Override\n" +
                        "                        public long getPosition() {\n" +
                        "                            return 0;\n" +
                        "                        }\n" +
                        "                        @Override\n" +
                        "                        public JavaFileObject getSource() {return null;}\n" +
                        "                        public Kind getKind() {\n" +
                        "                            return Diagnostic.Kind.ERROR;\n" +
                        "                        }\n" +
                        "                        @Override\n" +
                        "                        public String getMessage(Locale locale) {\n" +
                        "                            if (locale != null) {\n" +
                        "                                setLocale(locale);\n" +
                        "                            }\n" +
                        "                            return \"\";\n" +
                        "                        }\n" +
                        "                    });\n" +
                        "                }\n" +
                        "\n" +
                        "            }\n" +
                        "        };\n" +
                        "    }\n" +
                        "}\n"
        };
        Map<String, List<String>> moduleMap = compileAndDisassemble(task, INSERT_REDIRECTORS_ONLY);
    }


}
