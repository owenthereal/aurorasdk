/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.auroraide.server.jci.compilers;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.StringTokenizer;

import org.apache.commons.jci.compilers.AbstractJavaCompiler;
import org.apache.commons.jci.compilers.JavaCompilerSettings;
import org.apache.commons.jci.problems.CompilationProblem;
import org.apache.commons.jci.readers.ResourceReader;
import org.apache.commons.jci.stores.ResourceStore;
import org.apache.commons.jci.utils.ConversionUtils;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.eclipse.jdt.core.compiler.IProblem;
import org.eclipse.jdt.internal.compiler.ClassFile;
import org.eclipse.jdt.internal.compiler.CompilationResult;
import org.eclipse.jdt.internal.compiler.Compiler;
import org.eclipse.jdt.internal.compiler.DefaultErrorHandlingPolicies;
import org.eclipse.jdt.internal.compiler.ICompilerRequestor;
import org.eclipse.jdt.internal.compiler.IErrorHandlingPolicy;
import org.eclipse.jdt.internal.compiler.IProblemFactory;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFileReader;
import org.eclipse.jdt.internal.compiler.classfmt.ClassFormatException;
import org.eclipse.jdt.internal.compiler.env.ICompilationUnit;
import org.eclipse.jdt.internal.compiler.env.INameEnvironment;
import org.eclipse.jdt.internal.compiler.env.NameEnvironmentAnswer;
import org.eclipse.jdt.internal.compiler.problem.DefaultProblemFactory;

/**
 * Eclipse compiler implemenation
 * 
 * @author tcurdt
 */
public final class EclipseJavaCompiler extends AbstractJavaCompiler {

    private final Log log = LogFactory.getLog(EclipseJavaCompiler.class);
    private final EclipseJavaCompilerSettings defaultSettings;

    public EclipseJavaCompiler() {
        this(new EclipseJavaCompilerSettings());
    }

    public EclipseJavaCompiler(final Map<String,String> pSettings) {
        defaultSettings = new EclipseJavaCompilerSettings(pSettings);
    }

    public EclipseJavaCompiler(final EclipseJavaCompilerSettings pSettings) {
        defaultSettings = pSettings;
    }

    final class CompilationUnit implements ICompilationUnit {

        final private String clazzName;
        final private String fileName;
        final private char[] typeName;
        final private char[][] packageName;
        final private ResourceReader reader;

        CompilationUnit( final ResourceReader pReader, final String pSourceFile ) {
            reader = pReader;
            clazzName = ConversionUtils.convertResourceToClassName(pSourceFile);
            fileName = pSourceFile;
            int dot = clazzName.lastIndexOf('.');
            if (dot > 0) {
                typeName = clazzName.substring(dot + 1).toCharArray();
            } else {
                typeName = clazzName.toCharArray();
            }
            
            log.debug("className=" + clazzName);
            log.debug("fileName=" + fileName);
            log.debug("typeName=" + new String(typeName)); 
            
            final StringTokenizer izer = new StringTokenizer(clazzName, ".");
            packageName = new char[izer.countTokens() - 1][];
            for (int i = 0; i < packageName.length; i++) {
                packageName[i] = izer.nextToken().toCharArray();
                log.debug("package[" + i + "]=" + new String(packageName[i]));
            }
        }

        public char[] getFileName() {
            return fileName.toCharArray();
        }

        public char[] getContents() {
            final byte[] content = reader.getBytes(fileName);

            if (content == null) {
                return null;
                //throw new RuntimeException("resource " + fileName + " could not be found");
            }

            return new String(content).toCharArray();
        }

        public char[] getMainTypeName() {
            return typeName;
        }

        public char[][] getPackageName() {
            return packageName;
        }
    }

    
    public org.apache.commons.jci.compilers.CompilationResult compile(
            final String[] pSourceFiles,
            final ResourceReader pReader,
            final ResourceStore pStore,
            final ClassLoader pClassLoader,
            final JavaCompilerSettings pSettings
            ) {

        final Map<?, ?> settingsMap = ((EclipseJavaCompilerSettings) defaultSettings).getMap();
        
        final Collection<CompilationProblem> problems = new ArrayList<CompilationProblem>();
        
        final ICompilationUnit[] compilationUnits = new ICompilationUnit[pSourceFiles.length];
        for (int i = 0; i < compilationUnits.length; i++) {
            final String sourceFile = pSourceFiles[i];
            
            if (pReader.isAvailable(sourceFile)) {            
                compilationUnits[i] = new CompilationUnit(pReader, sourceFile);
                log.debug("compiling " + sourceFile);
            } else {
                // log.error("source not found " + sourceFile);

                final CompilationProblem problem = new CompilationProblem() {

                    public int getEndColumn() {
                        return 0;
                    }

                    public int getEndLine() {
                        return 0;
                    }

                    public String getFileName() {
                        return sourceFile;
                    }

                    public String getMessage() {
                        return "Source " + sourceFile + " could not be found";
                    }

                    public int getStartColumn() {
                        return 0;
                    }

                    public int getStartLine() {
                        return 0;
                    }

                    public boolean isError() {
                        return true;
                    }
                    
                    public String toString() {
                        return getMessage();
                    }
                };

                if (problemHandler != null) {
                    problemHandler.handle(problem);
                }
                
                problems.add(problem);
            }
        }

        if (problems.size() > 0) {
            final CompilationProblem[] result = new CompilationProblem[problems.size()];
            problems.toArray(result);
            return new org.apache.commons.jci.compilers.CompilationResult(result);
        }
        
        final IErrorHandlingPolicy policy = DefaultErrorHandlingPolicies.proceedWithAllProblems();
        final IProblemFactory problemFactory = new DefaultProblemFactory(Locale.getDefault());
        final INameEnvironment nameEnvironment = new INameEnvironment() {

            public NameEnvironmentAnswer findType( final char[][] pCompoundTypeName ) {
                final StringBuffer result = new StringBuffer();
                for (int i = 0; i < pCompoundTypeName.length; i++) {
                    if (i != 0) {
                        result.append('.');
                    }
                    result.append(pCompoundTypeName[i]);
                }

                //log.debug("finding compoundTypeName=" + result.toString());

                return findType(result.toString());
            }

            public NameEnvironmentAnswer findType( final char[] pTypeName, final char[][] pPackageName ) {
                final StringBuffer result = new StringBuffer();
                for (int i = 0; i < pPackageName.length; i++) {
                    result.append(pPackageName[i]);
                    result.append('.');
                }
                
//                log.debug("finding typeName=" + new String(typeName) + " packageName=" + result.toString());

                result.append(pTypeName);
                return findType(result.toString());
            }

            private NameEnvironmentAnswer findType( final String pClazzName ) {
                
                if (isPackage(pClazzName)) {
                    return null;
                }
                
                log.debug("finding " + pClazzName);
                
                final String resourceName = ConversionUtils.convertClassToResourcePath(pClazzName);
                
                final byte[] clazzBytes = pStore.read(pClazzName);
                if (clazzBytes != null) {
                    log.debug("loading from store " + pClazzName);

                    final char[] fileName = pClazzName.toCharArray();
                    try {
                        final ClassFileReader classFileReader = new ClassFileReader(clazzBytes, fileName, true);
                        return new NameEnvironmentAnswer(classFileReader, null);
                    } catch (final ClassFormatException e) {
                        log.error("wrong class format", e);
                        return null;
                    }
                }
                
                log.debug("not in store " + pClazzName);
                
                final InputStream is = pClassLoader.getResourceAsStream(resourceName);
                if (is == null) {
                    log.debug("class " + pClazzName + " not found");
                    return null;
                }

                final byte[] buffer = new byte[8192];
                final ByteArrayOutputStream baos = new ByteArrayOutputStream(buffer.length);
                int count;
                try {
                    while ((count = is.read(buffer, 0, buffer.length)) > 0) {
                        baos.write(buffer, 0, count);
                    }
                    baos.flush();
                    final char[] fileName = pClazzName.toCharArray();
                    final ClassFileReader classFileReader = new ClassFileReader(baos.toByteArray(), fileName, true);
                    return new NameEnvironmentAnswer(classFileReader, null);
                } catch (final IOException e) {
                    log.error("could not read class", e);
                    return null;
                } catch (final ClassFormatException e) {
                    log.error("wrong class format", e);
                    return null;
                } finally {
                    try {
                        baos.close();
                    } catch (final IOException oe) {
                        log.error("could not close output stream", oe);
                    }
                    try {
                        is.close();
                    } catch (final IOException ie) {
                        log.error("could not close input stream", ie);
                    }
                }
            }

            private boolean isPackage( final String pClazzName ) {
                
                final InputStream is = pClassLoader.getResourceAsStream(ConversionUtils.convertClassToResourcePath(pClazzName));
                if (is != null) {
                    log.debug("found the class for " + pClazzName + "- no package");
                    return false;
                }
                
                // FIXME: this should not be tied to the extension
                final String source = pClazzName.replace('.', '/') + ".java";
                if (pReader.isAvailable(source)) {
                    log.debug("found the source " + source + " for " + pClazzName + " - no package ");
                    return false;
                }
                
                return true;
            }

            public boolean isPackage( char[][] parentPackageName, char[] pPackageName ) {
                final StringBuffer result = new StringBuffer();
                if (parentPackageName != null) {
                    for (int i = 0; i < parentPackageName.length; i++) {
                        if (i != 0) {
                            result.append('.');
                        }
                        result.append(parentPackageName[i]);
                    }
                }
                
//                log.debug("isPackage parentPackageName=" + result.toString() + " packageName=" + new String(packageName));
                
                if (parentPackageName != null && parentPackageName.length > 0) {
                    result.append('.');
                }
                result.append(pPackageName);
                return isPackage(result.toString());
            }

            public void cleanup() {
                log.debug("cleanup");
            }
        };

        final ICompilerRequestor compilerRequestor = new ICompilerRequestor() {
            public void acceptResult( final CompilationResult pResult ) {
                if (pResult.hasProblems()) {
                    final IProblem[] iproblems = pResult.getProblems();
                    for (int i = 0; i < iproblems.length; i++) {
                        final IProblem iproblem = iproblems[i];
                        final CompilationProblem problem = new EclipseCompilationProblem(iproblem); 
                        if (problemHandler != null) {
                            problemHandler.handle(problem);
                        }
                        problems.add(problem);
                    }
                }
                if (!pResult.hasErrors()) {
                    final ClassFile[] clazzFiles = pResult.getClassFiles();
                    for (int i = 0; i < clazzFiles.length; i++) {
                        final ClassFile clazzFile = clazzFiles[i];
                        final char[][] compoundName = clazzFile.getCompoundName();
                        final StringBuffer clazzName = new StringBuffer();
                        for (int j = 0; j < compoundName.length; j++) {
                            if (j != 0) {
                                clazzName.append('.');
                            }
                            clazzName.append(compoundName[j]);
                        }
                        pStore.write(clazzName.toString().replace('.', '/') + ".class", clazzFile.getBytes());
                    }
                }
            }
        };

        final Compiler compiler = new Compiler(nameEnvironment, policy, settingsMap, compilerRequestor, problemFactory, false);

        compiler.compile(compilationUnits);

        final CompilationProblem[] result = new CompilationProblem[problems.size()];
        problems.toArray(result);
        return new org.apache.commons.jci.compilers.CompilationResult(result);
    }

    public JavaCompilerSettings createDefaultSettings() {
        return defaultSettings;
    }

	
}
