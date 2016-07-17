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
import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.WeakHashMap;

import static net.openhft.compiler.CompilerUtils.writeBytes;
import static net.openhft.compiler.CompilerUtils.writeText;

@SuppressWarnings("StaticNonFinalField")
public class CachedCompiler {
    private static final Logger LOG = LoggerFactory.getLogger(CachedCompiler.class);
    private static final Map<ClassLoader, Map<String, Class>> loadedClassesMap = new WeakHashMap<ClassLoader, Map<String, Class>>();

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

    public static void close() {
        try {
            CompilerUtils.s_fileManager.close();
        } catch (IOException e) {
            throw new AssertionError(e);
        }
    }

    public Class loadFromJava(@NotNull String className, @NotNull String javaCode, DiagnosticListener<? super JavaFileObject> diagnosticListener) throws ClassNotFoundException {
        return loadFromJava(getClass().getClassLoader(), className, javaCode, diagnosticListener);
    }

    private boolean errors;

    @NotNull
    Map<String, byte[]> compileFromJava(@NotNull String className, @NotNull String javaCode, DiagnosticListener<? super JavaFileObject> diagnosticListener) {
        Iterable<? extends JavaFileObject> compilationUnits;
        if (sourceDir != null) {
            String filename = className.replaceAll("\\.", '\\' + File.separator) + ".java";
            File file = new File(sourceDir, filename);
            writeText(file, javaCode);
            compilationUnits = CompilerUtils.s_standardJavaFileManager.getJavaFileObjects(file);

        } else {
            javaFileObjects.put(className, new JavaSourceFromString(className, javaCode));
            compilationUnits = javaFileObjects.values();
        }
        // reuse the same file manager to allow caching of jar files
        CompilerUtils.s_compiler.getTask(null, CompilerUtils.s_fileManager, diagnosticListener, null, null, compilationUnits).call();
        Map<String, byte[]> result = CompilerUtils.s_fileManager.getAllBuffers();
        if (errors) {
            // compilation error, so we want to exclude this file from future compilation passes
            if (sourceDir == null)
                javaFileObjects.remove(className);
        }
        return result;
    }

    public Class loadFromJava(@NotNull ClassLoader classLoader, @NotNull String className, @NotNull String javaCode, DiagnosticListener<? super JavaFileObject> diagnosticListener) throws ClassNotFoundException {
        Class clazz = null;
        Map<String, Class> loadedClasses;
        synchronized (loadedClassesMap) {
            loadedClasses = loadedClassesMap.get(classLoader);
            if (loadedClasses == null)
                loadedClassesMap.put(classLoader, loadedClasses = new LinkedHashMap<String, Class>());
            else
                clazz = loadedClasses.get(className);
        }
        if (clazz != null)
            return clazz;
        for (Map.Entry<String, byte[]> entry : compileFromJava(className, javaCode, diagnosticListener).entrySet()) {
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
