/*
 * Copyright 2014 Higher Frequency Trading
 *
 * http://www.higherfrequencytrading.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.openhft.compiler;

import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticListener;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import java.io.Closeable;
import java.io.File;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.*;

import static net.openhft.compiler.CompilerUtils.*;

@SuppressWarnings("StaticNonFinalField")
public class CachedCompiler implements Closeable {
    private static final Logger LOG = LoggerFactory.getLogger(CachedCompiler.class);
    private static final PrintWriter DEFAULT_WRITER = new PrintWriter(System.err);

    private final Map<ClassLoader, Map<String, Class>> loadedClassesMap = Collections.synchronizedMap(new WeakHashMap<>());
    private final Map<ClassLoader, MyJavaFileManager> fileManagerMap = Collections.synchronizedMap(new WeakHashMap<>());

    @Nullable
    private final File sourceDir;
    @Nullable
    private final File classDir;

    private final Map<String, JavaFileObject> javaFileObjects =
            new HashMap<String, JavaFileObject>();

    public CachedCompiler(@Nullable File sourceDir, @Nullable File classDir) {
        this.sourceDir = sourceDir;
        this.classDir = classDir;
    }

    public void close() {
        try {
            for (MyJavaFileManager fileManager : fileManagerMap.values()) {
                fileManager.close();
            }
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    public Class loadFromJava(@NotNull String className, @NotNull String javaCode) throws ClassNotFoundException {
        return loadFromJava(className, javaCode, null);
    }

    public Class loadFromJava(@NotNull String className, @NotNull String javaCode, DiagnosticListener<? super JavaFileObject> diagnosticListener) throws ClassNotFoundException {
        return loadFromJava(getClass().getClassLoader(), className, javaCode, diagnosticListener);
    }

    public Class loadFromJava(@NotNull ClassLoader classLoader,
                              @NotNull String className,
                              @NotNull String javaCode) throws ClassNotFoundException {
        return loadFromJava(classLoader, className, javaCode, DEFAULT_WRITER, null);
    }

    public Class loadFromJava(@NotNull ClassLoader classLoader,
                              @NotNull String className,
                              @NotNull String javaCode,
                              @Nullable DiagnosticListener<? super JavaFileObject> diagnosticListener) throws ClassNotFoundException {
        return loadFromJava(classLoader, className, javaCode, DEFAULT_WRITER, diagnosticListener);
    }

    public Class loadFromJava(@NotNull ClassLoader classLoader,
                              @NotNull String className,
                              @NotNull String javaCode,
                              @Nullable PrintWriter writer) throws ClassNotFoundException {
        return loadFromJava(classLoader, className, javaCode, writer, null);
    }

    @NotNull

    Map<String, byte[]> compileFromJava(@NotNull String className, @NotNull String javaCode, MyJavaFileManager fileManager, DiagnosticListener<? super JavaFileObject> diagnosticListener) {
        return compileFromJava(className, javaCode, DEFAULT_WRITER, fileManager, diagnosticListener);
    }

    @NotNull
    Map<String, byte[]> compileFromJava(@NotNull String className,
                                        @NotNull String javaCode,
                                        final @NotNull PrintWriter writer,
                                        MyJavaFileManager fileManager,
                                        DiagnosticListener<? super JavaFileObject> diagnosticListener) {
        Iterable<? extends JavaFileObject> compilationUnits;
        if (sourceDir != null) {
            String filename = className.replaceAll("\\.", '\\' + File.separator) + ".java";
            File file = new File(sourceDir, filename);
            writeText(file, javaCode);
            compilationUnits = s_standardJavaFileManager.getJavaFileObjects(file);

        } else {
            javaFileObjects.put(className, new JavaSourceFromString(className, javaCode));
            compilationUnits = javaFileObjects.values();
        }
        if (diagnosticListener == null) {
            diagnosticListener = new DiagnosticListener<JavaFileObject>() {
                @Override
                public void report(Diagnostic<? extends JavaFileObject> diagnostic) {
                    if (diagnostic.getKind() == Diagnostic.Kind.ERROR) {
                        writer.println(diagnostic);
                    }
                }
            };
        }
        // reuse the same file manager to allow caching of jar files
        boolean ok = s_compiler.getTask(writer, fileManager, diagnosticListener, null, null, compilationUnits).call();
        Map<String, byte[]> result = fileManager.getAllBuffers();
        if (!ok) {
            // compilation error, so we want to exclude this file from future compilation passes
            if (sourceDir == null)
                javaFileObjects.remove(className);

            // nothing to return due to compiler error
            return Collections.emptyMap();
        }
        return result;
    }

    public Class loadFromJava(@NotNull ClassLoader classLoader,
                              @NotNull String className,
                              @NotNull String javaCode,
                              @Nullable PrintWriter writer,
                              @Nullable DiagnosticListener<? super JavaFileObject> diagnosticListener) throws ClassNotFoundException {
        Class clazz = null;
        Map<String, Class> loadedClasses;
        synchronized (loadedClassesMap) {
            loadedClasses = loadedClassesMap.get(classLoader);
            if (loadedClasses == null)
                loadedClassesMap.put(classLoader, loadedClasses = new LinkedHashMap<String, Class>());
            else
                clazz = loadedClasses.get(className);
        }
        PrintWriter printWriter = (writer == null ? DEFAULT_WRITER : writer);
        if (clazz != null)
            return clazz;

        MyJavaFileManager fileManager = fileManagerMap.get(classLoader);
        if (fileManager == null) {
            StandardJavaFileManager standardJavaFileManager = s_compiler.getStandardFileManager(null, null, null);
            fileManagerMap.put(classLoader, fileManager = new MyJavaFileManager(standardJavaFileManager));
        }
        for (Map.Entry<String, byte[]> entry : compileFromJava(className, javaCode, printWriter, fileManager, diagnosticListener).entrySet()) {
            String className2 = entry.getKey();
            synchronized (loadedClassesMap) {
                if (loadedClasses.containsKey(className2))
                    continue;
            }
            byte[] bytes = entry.getValue();
            if (classDir != null) {
                String filename = className2.replaceAll("\\.", '\\' + File.separator) + ".class";
                boolean changed = writeBytes(new File(classDir, filename), bytes);
                if (changed) {
                    LOG.info("Updated {} in {}", className2, classDir);
                }
            }
            Class clazz2 = CompilerUtils.defineClass(classLoader, className2, bytes);
            synchronized (loadedClassesMap) {
                loadedClasses.put(className2, clazz2);
            }
        }
        synchronized (loadedClassesMap) {
            loadedClasses.put(className, clazz = classLoader.loadClass(className));
        }
        return clazz;
    }
}
