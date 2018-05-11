# Testable Java

## Executive Summary

***A standards-based Java compiler enhanced to trivialize testing in isolation***

- Removes trickery and promotes clean, straightforward test code
- Eliminates the need for mocking frameworks
- Facilitates maintenance of legacy code
- Eliminates the need to structure code dependencies in a special way
- Allows to mock code deemed unmockable
- Allows to break hardwired dependencies

## Motivation

Testing involves isolation and mocking of parts. This is easier said than done. Actually you will find yourself working AGAINST language modularity features, since you need to break apart code designed to be atomic

Modularity is essential, but it is often a ***liability*** during testing

Examples of modularity working against you in testing (access restrictions):

- non-public (also final) fields, methods
- inner classes

In addition to modularity-induced constraints, there is also lack of flexibility in code inter-connect. For example, any call of a static method is ***hardwired*** and cannot be changed, preventing mocking( That includes 'new' operator calls)

There is an additional complexity in mocking. A mock needs to be 'put in place', which means you need to have access to that place...

In practice, you are calling code that is calling some other code, that is instantiating an object that, as one of its dependencies, calls code that you need to mock... Getting access to an arbitrary place in the code graph is not trivial at all, and once you are outside of your code and in an external library code, this gets even harder!

Only if you structure your code in a certain way you can efficiently use mocking:

```

class Main {
    Dependency dependency;
    void call() {
      dependency.call();
    } 
}
class Dependency {
    void call() {
     ...
    }
}

```

Here dependency is stored in a **field**, and can easily be replaced by mock:

```
Main main = new Main();
main.dependency = mock(...); //put the mock in place
main.call(); //will call the mock indirectly
```

It is great in a simple case, but in practice not all code can be structured like this. In fact, if you are invoking external libraries or dealing with legacy code, you may have no choice but to call static methods and  'new' directly; also packaging such dependencies as fields will be impractical: it would make otherwise simple code quite convoluted!

A note about mocks: mocking ***classes*** is often an overkill, there are cases where you want to mock a method. With most mocking libraries you are forced to mock the whole class where this method resides. This mocks all other methods in this class, unless you are making a 'partial mock'. Now, in some cases, you will need to mock method A in this class, and in others method B, so you will end up with multiple partial mocks, or a mock with a switch that makes each method live or mock... It is way too complicated!

**There must be a better way...**

There is a decades-old technique that wraps every call into a method that a test class can override:

```

class Main {
     Dependency dependency;
     void call() {
       wrapperDependencyCall(); //instead of calling dependency.call(), have a local method that wraps it
     } 
     void wrapperDependencyCall() {  
       dependency.call();
     }
}
class Dependency {
     void call() {
     ...
     }
}

```

Instead of mocking Dependency we will simply override wrapperDependencyCall in a test class, and make it do whatever we want for testing purposes, such as throw an exception:

```
class TestMain extends Main {
    @Override void wrapperDependencyCall() {
      //does not do dependency.call(), this is effectively a mock
      throw new RuntimeException();
    }

}

//in a testcase:
new TestMain().call(); //now throws, which means the mock was invoked
``` 
 
This technique lets you 'wrap' any call made inside Main class, including static and 'new' calls, allowing to mock through override. In theory. But in practice wrapping every call like that makes things quite convoluted...

Why can't the compiler automatically create the wrappers, and make them invisible?

**This is, in essence, what Testable Java does!**

We started with a **standards-based Java compiler** (EJC) from the Eclipse project. It powers the Eclipse IDE, and this is what is used to compile everything inside the IDE by default. It is a badly beaten Java compiler and it works!

Then we **modified** the compiler to **auto-add a wrapper/redirector** around every call. The redirectors can be controlled externally - code that was making a method call now calls a function stored in a ***static field*** on the class making the call. At runtime, you can replace that with your test code, thus making a mock. Or you can leave the original call intact - the original call code is 'stored' in the field

There is no complexity in putting that mock in place, all you need to know is: which class is making the call? Then it contains a static field with redirector, that you can **replace**. And it does not matter if the original call was local, to another class in your codebase, or to a library - it is wrapped in the same way...

The beauty of this approach is that you do not need to structure your code specifically to facilitate mocking. You do not need to wire every dependency as a bean, and there is no need to instruct DI framework to wire some test beans and some main beans, etc. This comes handy when you are dealing with legacy code and need to add tests - restructuring the code for testability is a noble, but cost-prohibitive thing to do!

**A side note**: quite often during testing it is easy to provide meaningful(and stable) high-level inputs, but as those inputs are making it through the code they become low-level and meaningless(also volatile: implementation detail). However, given those high-level inputs, you can often observe meaningful outputs, even in low-level code, e.g. "this high level input should result in this low-level code being called". You can easily catch it in low-level code using Testable Java. This is especially useful in legacy code



### Dynamic call redirection

Given the code

```
class A {
  void fn() {
    System.out.println();
  }
}
```

compilation results in (decompiled byte code)

```
class A {
  public static Consumer... $$PrintStream$println = 
    (CallContext ctx) -> ctx.calledClassInstance.println(); //original call packaged as a lambda

  void fn() {
    $$PrintStream$println.accept(
      new CallContext("A", "java.io.PrintStream", this, System.out)); 
      //System.out becomes calledClassInstance
  }
}
```
Note that there is a new static field **$$PrintStream$println**, that is conceptually a function, implemented as a lambda, performing the original call. Field name starts with $$ (indicating it is a redirector). Next token is the called class is **PrintStream**, and finally the method: **println**

The lambda receives an instance of CallContext, which contains calledClassInstance, which is needed to make the call

Testable Java has repackaged the original call as lambda and exposed it as a field. Field name is called class name and method name joined together

Suppose now you want to mock the call to println. All you have to do is to assign a different function to the field, from any external code:

```
A.$$PrintStream$println = ctx -> Logger.warn(....);
```

and then any call any code will make to A.fn will log a warning instead of printing

Note: the scope of this change is class A and any caller of code from class A.

Note: the redirector fields are always on the top level class when the redirected code is in a nested class. Field names reflect the originating class

### Static call redirection

Given the code

```
class A {
  void fn(){
    long timestamp = System.currentTimeMillis();
  }
}
```

compilation results in (decompiled byte code)

```
class A {
  public static Function... $$System$currentTimeMillis = (CallContext ctx) -> System.currentTimeMillis(); //original call packed into a lambda

  void fn() {
    long timestamp = $$System$currentTimeMillis.apply(new CallContext("A", "java.lang.System", this, null)); //calledClassInstance is null on static call
  }
}
```

Suppose now you want to mock the call to currentTimeMillis to return a fixed value. All you have to do is to assign a different lambda to the field, from any external code:

```
A.$$System$currentTimeMillis = ctx -> 123334435L;
```

Note: field types are helpers.FunctionN for calls that return value(and take N arguments), or helpers.ConsumerN for calls that are void.
But by using lambdas you do not have to deal with field typing. First arguments in lambda is always an instance of testablejava.CallContext, which contains info on calling and called instances

### A note on use of lambdas
It is convenient to assign lambdas to the redirector field:

```
$$System$currentTimeMillis = (CallContext ctx) -> ...

```

But you do not have to. You can create the redirect code as a Function/Consumer, but then you need to know the type parameters. Testable Java is using its own FunctionN<> and ConsumerN<> generic classes

```
$$System$currentTimeMillis = 
 new helpers.Function1<CallContext<System>, Long>(){ 
 //original call has 0 arguments, plus added CallContext, so Function of 1 argument is Function1
  public Long apply(CallContext<System> ctx) { 
   //CallContext parameterized with called class, returning Long: boxed long returned by the original call
   return 123334435L;
  } 
 };

```
Lambdas take care of all the details for you

### A note on lambdas wrapping methods that need to throw
If you are redirecting a method that throws an exception (other than RuntimeException), you need a special technique. FunctionN apply method does not declare a thrown exception. So in order to throw an exception you need a helper function:

```
testablejava.Helpers.uncheckedThrow(new NumberFormatException());
```

The uncheckedThrow method does not declare that it throws an exception. But it will throw any provided Throwable, and will make it look like is was thrown directly from the calling method, so it is fully equivalent to 

```
throw new NumberFormatException();
```
without having to declare the exception thrown

## How Testable Java solves common mocking problems?

**Hard to put a mock in place** => Testable Java redirectors are essentially mocks. They are already in place, but they are calling original code. The redirectors are static fields, so you can change their behavior without knowing anything about how they will be invoked

**Cannot mock code called statically** => Testable Java redirects any call, including static calls, allowing you to replace any call with a mock method call

**Cannot mock recursive code** => Testable Java redirects calls, it does not replace actual methods, so if your method calls itself, you can make it call a mock method instead, invoke the original method and check arguments passed to the mock method to test the inductive step 

**Cannot mock inner classes** => Testable Java redirects calls that are made in nested classes (including anonymous inner classes). The redirector fields are put on the outermost class


## Examples

Look at **functional tests under examples/** 

Also look at **unit tests under compiler/** to see the apparent transformation of code: most tests use Java decompiler to show resulting byte code

## Q & A

**Q**: How do I compile my code with Testable Java compiler?

**A**: You compile your main code using Testable Java compiler and your test code with any standard Java compiler (including Testable Java if you want) - there is no restriction

Testable Java is a modified Eclipse Java Compiler(ECJ). You can use it on command line similarly to ECJ; For Maven, there is a Plexus wrapper for it; also see below the question on IDE integration

**Q**: Can I use Testable Java in my IDE?

**A**: Yes, and this way you will be able to browse injected redirector fields names. You can replace your IntelliJ ECJ jar with similar Testable Java jar. In Eclipse IDE you can do a similar thing (slightly more complex since there is no separate ECJ jar on Eclipse)

**Q**: Does it preprocess my code?

**A**: No, your main Java sources are not changed, but code generation is altered

**Q**: Does it use byte code manipulation?

**A**: No, it generates byte code just like any other Java compiler, and that code is used in tests directly

**Q**: Can I ship my product compiled with Testable Java compiler?

**A**: It is not recommended. Compile using normal compiler (Oracle, OpenJDK) and ship that. Compile separately using Testable Java and use the result for testing only


### How to build/release with Testable Java?
If you look at the samples/ directory, there is a maven project with some sample code. Important things to note:

- Build normally and run your unit tests that do not require redirectors
- Compile with Testable Java Compiler and run tests that require redirectors. Maven project provides the 'testable' profile for that: 

```
mvn -P testable clean test
```
- Rebuild clean with standard Java and release. You do not want to release with instrumentation!

### Note on compatibility

Testable Java is based on a battle-tested Java compiler that generates standard byte code. The modifications we made are minimal. There is nothing in resulting code that is not standards-compliant. There is no special 'test' language to learn. Your IDE understands the resulting code. The price to pay is the need to separately compile main code for testing purposes


