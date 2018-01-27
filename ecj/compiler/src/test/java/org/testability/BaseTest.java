package org.testability;

import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jdt.core.compiler.CategorizedProblem;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.*;
import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.ast.CompilationUnitDeclaration;
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;
import org.eclipse.jdt.internal.compiler.batch.FileSystem;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.parser.Parser;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblem;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.eclipse.jdt.internal.compiler.problem.ProblemReporter;
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.stream.Stream;

import static java.util.stream.Collectors.*;

/**
 * Created by julianrozentur1 on 6/25/17.
 */
public class BaseTest {

    File classStoreDir = new File("target", "ecj-compiled");
    File destinationDir = new File("target", "ecj-decompiled");

    @Test
    public void testNothing() {
    }

    protected Map<String, List<String>> compileAndDisassemble(String[] task, Set<InstrumentationOptions> instrumenationOptions) throws Exception {

        deleteDir(classStoreDir);
        classStoreDir.mkdir();

        deleteDir(destinationDir);
        destinationDir.mkdir();

        Map<String, byte[]> compilationResult = compile(task, instrumenationOptions);

        return disassembleBytecode(compilationResult, classStoreDir, destinationDir);
    }

    Map<String, byte[]> compile(String[] taskLines, Set<InstrumentationOptions> instrumenationOptions) throws Exception {
        Map<String, String[]> fileToLines = taskLinesToFileMap(taskLines);

        return compile(fileToLines, instrumenationOptions);
    }

    Map<String, String[]> taskLinesToFileMap(String[] taskLines) {
        Map<String, String[]> fileToLines = new HashMap<>();
        String sourceFileName = null;

        List<String> codeLines = new ArrayList<>();

        for (String taskLine : taskLines) {
            if (taskLine.endsWith(".java")) {
                if (!codeLines.isEmpty()) {
                    fileToLines.put(sourceFileName, codeLines.toArray(new String[0]));
                }
                codeLines = new ArrayList<>();
                sourceFileName = taskLine;
            } else {
                codeLines.add(taskLine);
            }
        }

        if (!codeLines.isEmpty()) {
            fileToLines.put(sourceFileName, codeLines.toArray(new String[0]));
        }
        return fileToLines;
    }

    Map<String, byte[]> compile(Map<String, String[]> fileNameToCodeLines, Set<InstrumentationOptions> instrumenationOptions) throws Exception {

        List<Pair<String,String>> compilationUnitDatas = fileNameToCodeLines.entrySet().stream().
                map(entry -> new ImmutablePair<>( Stream.of(entry.getValue()).collect(joining("\n")),
                        entry.getKey()
                )).
                collect(toList());

        return compile(compilationUnitDatas, instrumenationOptions);
    }

    Map<String, List<String>> disassembleBytecode(Map<String, byte[]> classMap, File classTempStoreDir, File destinationDir) throws IOException {

        Map<String, List<String>> ret = new HashMap<>();

        for (Map.Entry<String, byte[]> entry: classMap.entrySet()){
            String name = entry.getKey();
            byte [] bytecode = entry.getValue();
            FileOutputStream fo;
            try {
                String fileName = name + ".class";
                File file = new File(classTempStoreDir, fileName);
                fo = new FileOutputStream(file);
                fo.write(bytecode);
                fo.close();

                ret.put(name, callDisassembler(
                        new File(classTempStoreDir, fileName),
                        destinationDir
                        ));
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        return ret;
    }

    /**
     *
     * @param instrumentationOptions
     * @param compilationUnitDatas list of pairs code, filename
     * @param instrumenationOptions
     * @return
     * @throws Exception
     */
    public HashMap<String, byte[]> compile(
            List<Pair<String, String>> compilationUnitDatas,
            Set<InstrumentationOptions> instrumenationOptions) throws Exception {
        //see http://www.mentics.com/wp/java-2/compiling-on-the-fly-with-the-eclipse-compiler.html/

        final HashMap<String, byte[]> classMap = new HashMap<>();

        List<CompilationResult> compilationResultsWithProblems = new ArrayList<>();

        ArrayList<FileSystem.Classpath> cp = new ArrayList<>();
        org.eclipse.jdt.internal.compiler.util.Util.collectRunningVMBootclasspath(cp);

        System.out.println("adding Helpers to test compiler classpath: " +
                new File("../helpers/target/classes").getAbsolutePath());

        cp.add(FileSystem.getClasspath("../helpers/target/classes", null, false,
        null, null, Collections.emptyMap()));

        INameEnvironment env =
                new FileSystem(
                        cp.toArray(new FileSystem.Classpath[cp.size()]),
                        null,
                        false
                ){};

        ICompilerRequestor requestor = new ICompilerRequestor() {
            @Override
            public void acceptResult(CompilationResult result) {

                if (result.problemCount>0)
                    compilationResultsWithProblems.add(result);

                ClassFile[] cfs = result.getClassFiles();
                for (ClassFile cf :cfs){
                    String className = toHumanReadableClassName(cf.getCompoundName());
                    classMap.put(className, cf.getBytes());
                }
            }

            String toHumanReadableClassName(char[][] compoundName) {
                StringBuilder b = new StringBuilder();
                for (char [] chunk:compoundName){
                    if (b.length()!=0)
                        b.append('.');
                    b.append(chunk);
                }
                return b.toString();
            }
        };

        List<ICompilationUnit> units =
                compilationUnitDatas.stream().map(
                        (Pair<String,String> t) -> {
                            String code  = t.getLeft();
                            String filename = t.getRight();
                            return new CompilationUnit(
                                    code.toCharArray(),
                                    filename,
                                    null
                            );
                        }
                ).collect(toList());

        CompilerOptions options = new CompilerOptions();
        options.complianceLevel = ClassFileConstants.JDK1_8;
        options.originalSourceLevel = ClassFileConstants.JDK1_8;
        options.sourceLevel = ClassFileConstants.JDK1_8;
        options.targetJDK = ClassFileConstants.JDK1_8;
//        options.verbose = true;

        Compiler compiler = new Compiler(env, DefaultErrorHandlingPolicies.exitAfterAllProblems(),
                options, requestor, new DefaultProblemFactory()){
            @Override
            protected Set<InstrumentationOptions> getInstrumentationOptions() {
                return instrumenationOptions;
            }
        };

        List<CategorizedProblem> individualProblems = new ArrayList<>();
        Exception compilerException = null;
        try {
            compiler.compile(units.toArray(new ICompilationUnit[0]));
        } catch (Exception ex) {
            compilerException = ex;
        } finally {

            individualProblems = compilationResultsWithProblems.stream().
                    filter(cr -> cr.problems != null).
                    flatMap(cr -> Arrays.stream(cr.problems)).
                    filter(Objects::nonNull).collect(toList());

            individualProblems.forEach(problem -> {
                String fileName = "";
                String severity = "";
                if (problem instanceof DefaultProblem) {
                    fileName = new String(problem.getOriginatingFileName());
                    if (problem.isError())
                        severity = "ERROR";
                    else if (problem.isWarning())
                        severity = "WARN";
                    else if (problem.isInfo())
                        severity = "INFO";
                }
                System.out.printf("File %s has %s:\t%s\n",
                        fileName, severity, problem);
            });
        }
        if (compilerException!=null || individualProblems.stream().
                anyMatch(IProblem::isError)
                ) {
            String message = "Compilation problems: " +
                    compilationResultsWithProblems.stream().
                            map(Object::toString).
                            collect(joining());
            throw new Exception(message, compilerException);
        }


        return classMap;
    }

    public HashMap<String, CompilationUnitDeclaration> parse(String[] taskLines) {
        List<Pair<String,String>> unitDatas = taskLinesToFileMap(taskLines).entrySet().stream().
                map(entry -> new ImmutablePair<>( Stream.of(entry.getValue()).collect(joining("\n")),
                        entry.getKey()
                )).
                collect(toList());
        return parse(unitDatas);
    }

    public HashMap<String, CompilationUnitDeclaration> parse(List<Pair<String, String>> compilationUnitDatas){

        CompilerOptions options = new CompilerOptions();
        options.complianceLevel = ClassFileConstants.JDK1_8;
        options.originalSourceLevel = ClassFileConstants.JDK1_8;
        options.sourceLevel = ClassFileConstants.JDK1_8;
        options.targetJDK = ClassFileConstants.JDK1_8;

        DefaultProblemFactory problemFactory = new DefaultProblemFactory();

        ProblemReporter problemReporter = new ProblemReporter(
                DefaultErrorHandlingPolicies.exitAfterAllProblems(), options, problemFactory);

        Parser parser = new Parser(problemReporter, options.parseLiteralExpressionsAsConstants);

        List<Pair<String, ICompilationUnit>> nameAndUnit =
                compilationUnitDatas.stream().
                    map( (Pair<String,String> t) -> {
                        String code = t.getLeft();
                        String filename = t.getRight();
                        ICompilationUnit unit = new CompilationUnit(
                            code.toCharArray(),
                            filename,
                            null
                        );
                        return new ImmutablePair<>(filename, unit);
                    }
                ).collect(toList());

        return nameAndUnit.stream().
                collect(toMap(
                        (Pair<String, ICompilationUnit> nu) -> nu.getLeft(),
                        (Pair<String, ICompilationUnit> nu) -> {
                            ICompilationUnit sourceUnit = nu.getRight();
                            CompilationResult fullParseUnitResult =
                                    new CompilationResult(sourceUnit, 0, 1, options.maxProblemsPerUnit);

                            CompilationUnitDeclaration fullParsedUnit = parser.parse(sourceUnit, fullParseUnitResult);
                            return fullParsedUnit;
                        },
                        (x, y) -> x,
                        () -> new HashMap<>()
                        )
                );

    }
    /**
     * disassemble given class file and place resulting java file into destinationDir
     * @param file
     * @param destinationDir
     * @return disassembly result: java source lines
     * @throws IOException
     */
    List<String> callDisassembler(File file, File destinationDir) throws IOException {
        return callFernFlowerDisassembler(file, destinationDir);
    }

    /**
     * disassemble given class file and place resulting java file into destinationDir
     * @param file
     * @param destinationDir
     * @return disassembly result: java source lines
     * @throws IOException
     */
    List<String> callFernFlowerDisassembler(File file, File destinationDir) throws IOException {

        Map<String, Object> options = new HashMap<>();
        options.put(IFernflowerPreferences.LOG_LEVEL, "warn");
        options.put(IFernflowerPreferences.DECOMPILE_GENERIC_SIGNATURES, "1");
        options.put(IFernflowerPreferences.REMOVE_SYNTHETIC, "1");
        options.put(IFernflowerPreferences.REMOVE_BRIDGE, "1");
        options.put(IFernflowerPreferences.LITERALS_AS_IS, "1");
//        options.put(IFernflowerPreferences.UNIT_TEST_MODE, "1");
//        options.put(IFernflowerPreferences.DECOMPILE_INNER, "0");


        ConsoleDecompiler decompiler = new ConsoleDecompiler(destinationDir, options);
        String fileName = file.getName();
        System.out.println("decompiling " + fileName);
        decompiler.addSpace(file, true);

        decompiler.decompileContext();
        System.out.println("decompiled " + fileName);

        File decompiledFile = new File(destinationDir, fileName.replace(".class",".java"));

        byte[] bytes = Files.readAllBytes(decompiledFile.toPath());
        String[] lines = new String(bytes, "utf-8").split("\n");

        return Stream.of(lines).collect(toList());
    }

    void deleteDir(File dir) throws IOException {

        Path directory = Paths.get(dir.getAbsolutePath());

        if (!directory.toFile().exists())
            return;

        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {

            @Override
            public FileVisitResult visitFile(Path file,
                                             BasicFileAttributes attrs) {
                try {
                    Files.delete(file);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) {
                try {
                    Files.delete(dir);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    Object invokeCompiledMethod(String className, String methodName, Object... args) throws Exception {
        URLClassLoader cl = new URLClassLoader(new URL[]{classStoreDir.toURL()}, this.getClass().getClassLoader());
        Class<?> clazz = cl.loadClass(className);
        Method[] methods = clazz.getDeclaredMethods();

        Optional<Method> optMethod = Arrays.stream(methods).
                filter(m -> m.getName().equals(methodName)).
                findFirst();

        Method method = optMethod.orElseThrow(()->new RuntimeException("method not found: " + methodName));
        method.setAccessible(true);
        Object instance = clazz.newInstance();
        return method.invoke(instance, args);
    }
}

