package org.codehaus.plexus.compiler.eclipse;

/**
 * The MIT License
 *
 * Copyright (c) 2005, The Codehaus
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy of
 * this software and associated documentation files (the "Software"), to deal in
 * the Software without restriction, including without limitation the rights to
 * use, copy, modify, merge, publish, distribute, sublicense, and/or sell copies
 * of the Software, and to permit persons to whom the Software is furnished to do
 * so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

import org.codehaus.plexus.compiler.AbstractCompilerTest;
import org.codehaus.plexus.compiler.Compiler;
import org.codehaus.plexus.compiler.CompilerConfiguration;

import java.io.File;
import java.util.Arrays;
import java.util.Collection;
import java.util.Map;

import static org.codehaus.plexus.compiler.eclipse.EclipseCompilerTckTest.ROLEHINT;

/**
 * @author <a href="mailto:jason@plexus.org">Jason van Zyl</a>
 */
public class EclipseCompilerTest
    extends AbstractCompilerTest
{

    public void setUp()
        throws Exception
    {
        super.setUp();

        setCompilerDebug( true );
        setCompilerDeprecationWarnings( true );

        Compiler compiler = (Compiler) lookup( Compiler.ROLE, ROLEHINT);
        if (compiler instanceof TestableJavaCompiler)
            ((TestableJavaCompiler)compiler).instrumenationOptions.clear();

    }

    protected String getRoleHint()
    {
        return "testablejava";
    }

    protected int expectedErrors()
    {
        return 4;
    }

    protected int expectedWarnings()
    {
        return 2;
    }

    protected Collection<String> expectedOutputFiles()
    {
        return Arrays.asList( new String[] { "org/codehaus/foo/Deprecation.class", "org/codehaus/foo/ExternalDeps.class",
            "org/codehaus/foo/Person.class", "org/codehaus/foo/ReservedWord.class" } );
    }

    // The test is fairly meaningless as we can not validate anything
    public void testCustomArgument()
        throws Exception
    {
        org.codehaus.plexus.compiler.Compiler compiler = (Compiler) lookup( Compiler.ROLE, getRoleHint() );

        CompilerConfiguration compilerConfig = createMinimalCompilerConfig();

        compilerConfig.addCompilerCustomArgument( "-key", "value" );

        compiler.performCompile( compilerConfig );
    }

    public void testCustomArgumentCleanup()
    {
        TestableJavaCompiler compiler = new TestableJavaCompiler();

        CompilerConfiguration compilerConfig = createMinimalCompilerConfig();

        compilerConfig.addCompilerCustomArgument( "-key", "value" );
        compilerConfig.addCompilerCustomArgument( "cleanKey", "value" );

        Map<String, String> cleaned = compiler.cleanKeyNames( compilerConfig.getCustomCompilerArgumentsAsMap() );

        assertTrue( "Key should have been cleaned", cleaned.containsKey( "key" ) );

        assertFalse( "Key should have been cleaned", cleaned.containsKey( "-key" ) );

        assertTrue( "This key should not have been cleaned does not start with dash", cleaned.containsKey( "cleanKey" ) );

    }

    public void testInitializeWarningsForPropertiesArgument()
        throws Exception
    {
        org.codehaus.plexus.compiler.Compiler compiler = (Compiler) lookup( Compiler.ROLE, getRoleHint() );

        CompilerConfiguration compilerConfig = createMinimalCompilerConfig();

        compilerConfig.addCompilerCustomArgument( "-properties", "file_does_not_exist" );

        try
        {
            compiler.performCompile( compilerConfig );
            fail( "looking up the properties file should have thrown an exception" );
        }
        catch ( IllegalArgumentException e )
        {
            assertEquals( "Properties file not exist", e.getMessage() );
        }
    }
    public void testSourceCodeLocatorCaseSensitiveFileExists()
            throws Exception
    {
        File f = File.createTempFile("prefix", "SUFFIX");//this makes name case-sensitive
        f.delete();
        assertFalse(SourceCodeLocator.caseSensitiveFileExists(f));
        f.createNewFile();
        assertTrue(SourceCodeLocator.caseSensitiveFileExists(f));

        String name = f.getName();

        String nameLower = name.toLowerCase();
        File fLower = new File(f.getParentFile(), nameLower);

        if (fLower.exists()) { //ensure fs is case-insensitive
            assertFalse(SourceCodeLocator.caseSensitiveFileExists(fLower));
        }
    }
    private CompilerConfiguration createMinimalCompilerConfig()
    {
        CompilerConfiguration compilerConfig = new CompilerConfiguration();
        compilerConfig.setOutputLocation( getBasedir() + "/target/" + getRoleHint() + "/classes-CustomArgument" );
        return compilerConfig;
    }

}
