package org.testability;

import junit.framework.TestCase;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.Pair;
import org.eclipse.jdt.internal.compiler.*;
import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.batch.CompilationUnit;
import org.eclipse.jdt.internal.compiler.batch.FileSystem;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileConstants;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.impl.CompilerOptions;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;
import org.jetbrains.java.decompiler.main.decompiler.ConsoleDecompiler;
import org.jetbrains.java.decompiler.main.extern.IFernflowerPreferences;
import org.junit.Test;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static java.util.stream.Collectors.joining;
import static java.util.stream.Collectors.toList;

/**
 * Created by julianrozentur1 on 6/25/17.
 */
public class BaseTest extends TestCase {

    File classStoreDir = new File("target", "ecj-compiled");
    File destinationDir = new File("target", "ecj-decompiled");

    public BaseTest(String name) {
        super(name);
    }

    @Test
    public void testNothing() {
    }

    protected Map<String, List<String>> compileAndDisassemble(String[] task) throws Exception {

        deleteDir(classStoreDir);
        classStoreDir.mkdir();

        deleteDir(destinationDir);
        destinationDir.mkdir();

        return disassembleBytecode(compile(task), classStoreDir, destinationDir);
    }

    Map<String, byte[]> compile(String[] taskLines) throws Exception {
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

        return compile(fileToLines);
    }

    Map<String, byte[]> compile(Map<String, String[]> fileNameToCodeLines) throws Exception {

        List<Pair<String,String>> compilationUnitDatas = fileNameToCodeLines.entrySet().stream().
                map(entry -> new ImmutablePair<>( Stream.of(entry.getValue()).collect(joining("\n")),
                        entry.getKey()
                )).
                collect(toList());

        return compile(compilationUnitDatas);
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
     * @param compilationUnitDatas list of pairs code, filename
     * @return
     * @throws Exception
     */
    public HashMap<String, byte[]> compile(List<Pair<String,String>> compilationUnitDatas) throws Exception {
        //see http://www.mentics.com/wp/java-2/compiling-on-the-fly-with-the-eclipse-compiler.html/

        final HashMap<String, byte[]> classMap = new HashMap<>();

        List<CompilationResult> compilationProblems = new ArrayList<>();

        ArrayList<FileSystem.Classpath> cp = new ArrayList<>();
        org.eclipse.jdt.internal.compiler.util.Util.collectRunningVMBootclasspath(cp);
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
                    compilationProblems.add(result);
                else {
                    ClassFile[] cfs = result.getClassFiles();
                    for (ClassFile cf :cfs){
                        String className = toHumanReadableClassName(cf.getCompoundName());
                        classMap.put(className, cf.getBytes());
                    }
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
                options, requestor, new DefaultProblemFactory());

        compiler.compile(units.toArray(new ICompilationUnit[0]));

        if (!compilationProblems.isEmpty()){
            throw new Exception("Compilation problems: "+compilationProblems.stream().map(Object::toString).collect(joining()));
        }

        return classMap;
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
        decompiler.addSpace(file, true);

        decompiler.decompileContext();

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
}

