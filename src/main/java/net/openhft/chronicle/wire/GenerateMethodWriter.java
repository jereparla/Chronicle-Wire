package net.openhft.chronicle.wire;

import net.openhft.chronicle.bytes.*;
import net.openhft.chronicle.core.Jvm;
import net.openhft.chronicle.core.io.Closeable;
import net.openhft.chronicle.wire.utils.JavaSouceCodeFormatter;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.lang.annotation.Annotation;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Parameter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

import static java.lang.String.format;
import static java.util.Arrays.stream;
import static net.openhft.compiler.CompilerUtils.CACHED_COMPILER;

@SuppressWarnings("StringBufferReplaceableByString")
public class GenerateMethodWriter {

    static final boolean DUMP_CODE = Jvm.getBoolean("dumpCode");
    private static final String DOCUMENT_CONTEXT = DocumentContext.class.getSimpleName();
    private static final String MARSHALLABLE_OUT = MarshallableOut.class.getSimpleName();
    private static final String METHOD_ID = MethodId.class.getSimpleName();
    private static final String VALUE_OUT = ValueOut.class.getSimpleName();
    private static final String CLOSEABLE = Closeable.class.getSimpleName();
    private static final String MARSHALLABLE = Marshallable.class.getSimpleName();
    private static final String UPDATE_INTERCEPTOR = "updateInterceptor";

    private final boolean metaData;
    private final boolean useMethodId;

    private final String packageName;
    private final Set<Class> interfaces;
    private final String className;
    private final ClassLoader classLoader;
    private final WireType wireType;
    private final String genericEvent;
    private final boolean useUpdateInterceptor;
    private ConcurrentMap<Class, String> methodWritersMap = new ConcurrentHashMap<>();
    private boolean hasMethodWriterListener;
    private AtomicInteger indent = new AtomicInteger();

    private GenerateMethodWriter(final String packageName,
                                 final Set<Class> interfaces,
                                 final String className,
                                 final ClassLoader classLoader,
                                 final WireType wireType,
                                 final String genericEvent,
                                 final boolean hasMethodWriterListener,
                                 final boolean metaData,
                                 final boolean useMethodId,
                                 final boolean useUpdateInterceptor) {

        this.packageName = packageName;
        this.interfaces = interfaces;
        this.className = className;
        this.classLoader = classLoader;
        this.wireType = wireType;
        this.genericEvent = genericEvent;
        this.hasMethodWriterListener = hasMethodWriterListener;
        this.metaData = metaData;
        this.useMethodId = useMethodId;
        this.useUpdateInterceptor = useUpdateInterceptor;
    }

    /**
     * @param interfaces           an interface class
     * @param classLoader
     * @param wireType
     * @param genericEvent
     * @param useUpdateInterceptor
     * @return a proxy class from an interface class or null if it can't be created
     */
    @Nullable
    public static Class newClass(String fullClassName,
                                 Set<Class> interfaces,
                                 ClassLoader classLoader,
                                 final WireType wireType,
                                 final String genericEvent,
                                 boolean hasMethodWriterListener,
                                 boolean metaData,
                                 boolean useMethodId,
                                 final boolean useUpdateInterceptor) {
        int lastDot = fullClassName.lastIndexOf('.');
        String packageName = "";
        String className = fullClassName;
        ;

        if (lastDot != -1) {
            packageName = fullClassName.substring(0, lastDot);
            className = fullClassName.substring(lastDot + 1);
        }

        return new GenerateMethodWriter(packageName,
                interfaces,
                className,
                classLoader,
                wireType,
                genericEvent,
                hasMethodWriterListener,
                metaData, useMethodId, useUpdateInterceptor)
                .createClass();
    }

    @SuppressWarnings("unused")
    public static DocumentContext acquireDocumentContext(boolean metaData,
                                                         ThreadLocal<DocumentContextHolder> documentContextTL,
                                                         MarshallableOut out) {
        DocumentContextHolder contextHolder = documentContextTL.get();
        if (!contextHolder.isClosed())
            return contextHolder;
        contextHolder.documentContext(
                out.writingDocument(metaData));
        return contextHolder;
    }

    @SuppressWarnings("unused")
    @Deprecated
    public static void addComment(Bytes<?> bytes, Object arg) {
        if (arg instanceof Marshallable)
            bytes.comment(arg.getClass().getSimpleName());
        else
            bytes.comment(String.valueOf(arg));
    }

    private static CharSequence toString(Class type) {
        if (boolean.class.equals(type)) {
            return "bool";
        } else if (byte.class.equals(type)) {
            return "writeByte";
        } else if (char.class.equals(type)) {
            return "character";
        } else if (short.class.equals(type)) {
            return "int16";
        } else if (int.class.equals(type)) {
            return "fixedInt32";
        } else if (long.class.equals(type)) {
            return "fixedInt64";
        } else if (float.class.equals(type)) {
            return "fixedFloat32";
        } else if (double.class.equals(type)) {
            return "fixedFloat64";
        } else if (CharSequence.class.isAssignableFrom(type)) {
            return "text";
        } else if (Marshallable.class.isAssignableFrom(type)) {
            return "marshallable";
        }
        return "object";
    }

    @NotNull
    private static String nameForClass(Class type) {
        return type.getName().replace('$', '.');
    }

    @NotNull
    private static String nameForClass(Set<String> importSet, Class type) {
        String s = nameForClass(type);
        Package aPackage = type.getPackage();
        if (aPackage != null)
            if (importSet.contains(s)
                    || "java.lang".equals(aPackage.getName())
                    || (!type.getName().contains("$")
                    && importSet.contains(aPackage.getName() + ".*")))
                return type.getSimpleName();
        return s;
    }

    @NotNull
    private Appendable methodSignature(SortedSet<String> importSet, final Method dm, final int len) throws IOException {

        Appendable result = new JavaSouceCodeFormatter(this.indent);
        for (int j = 0; j < len; j++) {
            Parameter p = dm.getParameters()[j];
            final String className = nameForClass(importSet, p.getType());

            Optional<String> intConversion = stream(p.getAnnotations())
                    .filter(a -> a.annotationType() == IntConversion.class)
                    .map(x -> (((IntConversion) x).value().getName()))
                    .findFirst();

            Optional<String> longConversion = stream(p.getAnnotations())
                    .filter(a -> a.annotationType() == LongConversion.class)
                    .map(x -> (((LongConversion) x).value().getName()))
                    .findFirst();

            if (intConversion.isPresent())
                result.append("@IntConversion(").append(intConversion.get()).append(".class) ");
            else longConversion.ifPresent(s -> {
                try {
                    result.append("@LongConversion(").append(s).append(".class) ");
                } catch (Exception w) {
                    throw Jvm.rethrow(w);
                }
            });

            result.append("final ");
            result.append(className);

            result.append(' ')
                    .append(p.getName());
            if (j == len - 1)
                break;

            result.append(',');
        }
        return result;
    }

    @SuppressWarnings("StringConcatenationInsideStringBufferAppend")
    private Class createClass() {

        JavaSouceCodeFormatter interfaceMethods = new JavaSouceCodeFormatter(1);
        JavaSouceCodeFormatter imports = new JavaSouceCodeFormatter();

        try {
            imports.append("package " + packageName + ";\n\n");
            SortedSet<String> importSet = new TreeSet<>();
            importSet.add(IntConversion.class.getName());
            importSet.add(LongConversion.class.getName());
            importSet.add(GenerateMethodWriter.class.getName());
            importSet.add(MessageHistory.class.getName());
            importSet.add(MethodReader.class.getName());
            importSet.add(UpdateInterceptor.class.getName());
            importSet.add(MethodId.class.getName());
            importSet.add(GenerateMethodWriter.class.getName());
            importSet.add(DocumentContext.class.getName());
            importSet.add(MethodWriterInvocationHandlerSupplier.class.getName());
            importSet.add(Jvm.class.getName());
            importSet.add(Closeable.class.getName());
            importSet.add(DocumentContextHolder.class.getName());
            importSet.add(MethodWriterListener.class.getName());
            importSet.add(java.lang.reflect.InvocationHandler.class.getName());
            importSet.add(java.lang.reflect.Method.class.getName());
            importSet.add(java.util.stream.IntStream.class.getName());
            importSet.add(java.util.ArrayList.class.getName());
            importSet.add(java.util.List.class.getName());
            for (Class interfaceClazz : interfaces) {

                importSet.add(nameForClass(interfaceClazz));

                if (!interfaceClazz.isInterface())
                    throw new IllegalArgumentException("expecting and interface instead of class=" + interfaceClazz.getName());

                for (Method dm : interfaceClazz.getMethods()) {
                    if (dm.isDefault() || Modifier.isStatic(dm.getModifiers()))
                        continue;
                    for (Class pType : dm.getParameterTypes()) {
                        if (pType.isPrimitive() || pType.isArray() || pType.getPackage().getName().equals("java.lang"))
                            continue;
                        importSet.add(nameForClass(pType));
                    }
                }
            }
            importSet.removeIf(s -> s.startsWith("net.openhft.chronicle.bytes"));
            importSet.add("net.openhft.chronicle.bytes.*");
            importSet.removeIf(s -> s.startsWith("net.openhft.chronicle.wire"));
            importSet.add("net.openhft.chronicle.wire.*");

            for (String s : importSet) {
                imports.append("import ").append(s).append(";\n");
            }

            imports.append("\npublic final class ")
                    .append(className)
                    .append(" implements ");

            for (Class interfaceClazz : interfaces) {

                String interfaceName = nameForClass(importSet, interfaceClazz);
                imports.append(interfaceName);
                imports.append(",");

                if (!interfaceClazz.isInterface())
                    throw new IllegalArgumentException("expecting and interface instead of class=" + interfaceClazz.getName());

                for (Method dm : interfaceClazz.getMethods()) {
                    if (dm.isDefault() || Modifier.isStatic(dm.getModifiers()))
                        continue;

                    interfaceMethods.append(createMethod(importSet, dm, interfaceClazz));
                }
            }

            imports.setLength(imports.length() - 1);
            imports.append("{\n\n");
            imports.append(constructorAndFields(className));
            imports.append(interfaceMethods);
            imports.append("\n}\n");

            if (DUMP_CODE)
                System.out.println(imports);

            return CACHED_COMPILER.loadFromJava(classLoader, packageName + '.' + className, imports.toString());

        } catch (LinkageError e) {
            try {
                return Class.forName(packageName + '.' + className, true, classLoader);
            } catch (ClassNotFoundException x) {
                throw Jvm.rethrow(x);
            }
        } catch (Throwable e) {
            throw Jvm.rethrow(new ClassNotFoundException(e.getMessage() + '\n' + imports, e));
        }
    }

    private CharSequence constructorAndFields(final String className) {

        final StringBuilder result = new StringBuilder("// result\n");
        result.append("private transient final ")
                .append(CLOSEABLE).append(" closeable;\n");
        result.append("private transient final MethodWriterListener methodWriterListener;\n");
        result.append("private transient final " + UpdateInterceptor.class.getSimpleName() + " " + UPDATE_INTERCEPTOR + ";\n");

        result.append("private transient final ")
                .append(MARSHALLABLE_OUT).append(" out;\n");
        for (Map.Entry<Class, String> e : methodWritersMap.entrySet()) {
            result.append(format("private transient ThreadLocal %s;\n", e.getValue()));
        }
        result.append("\n");

        result.append(format("// constructor\npublic %s(" + MARSHALLABLE_OUT + " out, "
                + CLOSEABLE + " closeable, MethodWriterListener methodWriterListener, " +
                UpdateInterceptor.class.getSimpleName() + " " + UPDATE_INTERCEPTOR + ") {\n" +

                "this.methodWriterListener = methodWriterListener;\n" +
                "this." + UPDATE_INTERCEPTOR + "= " + UPDATE_INTERCEPTOR + ";\n" +
                "this.out = out;\n" +
                "this.closeable = closeable;\n", className));
        for (Map.Entry<Class, String> e : methodWritersMap.entrySet()) {
            result.append(format("%s = ThreadLocal.withInitial(() -> out.methodWriter(%s.class));\n", e.getValue(), nameForClass(e.getKey())));
        }

        result.append("}\n\n");
        return result;
    }

    private CharSequence createMethod(SortedSet<String> importSet, final Method dm, final Class<?> interfaceClazz) throws IOException {

        if (Modifier.isStatic(dm.getModifiers()))
            return "";

        if (dm.getParameterTypes().length == 0 && dm.isDefault())
            return "";

        int parameterCount = dm.getParameterCount();
        Parameter[] parameters = dm.getParameters();
        final int len = parameters.length;
        Class<?> returnType = dm.getReturnType();
        final String typeName = nameForClass(importSet, returnType);

        final StringBuilder body = new StringBuilder();
        String methodIDAnotation = "";
        if (dm.getReturnType() == void.class && "close".equals(dm.getName()) && parameterCount == 0) {
            body.append("if (this.closeable != null){\n this.closeable.close();\n}\n");
        } else {

            if (parameterCount >= 1 && useUpdateInterceptor) {
                Class<?> type = parameters[parameterCount - 1].getType();
                if (!type.isPrimitive()) {
                    String name = parameters[parameterCount - 1].getName();
                    body.append("// updateInterceptor\nthis." + UPDATE_INTERCEPTOR + ".update(\"" + dm.getName() + "\", " + name + ");\n");
                }
            }

            boolean terminating = returnType == Void.class || returnType == void.class || returnType.isPrimitive();
            if (terminating)
                body.append("try (");
            body.append("final " + DOCUMENT_CONTEXT + " dc = this.out.acquireWritingDocument(")
                    .append(metaData)
                    .append(")");
            if (terminating)
                body.append(") {\n");
            else
                body.append(";\n");
            body.append("if (out.recordHistory()) MessageHistory.writeHistory(dc);\n");

            int startJ = 0;

            final String eventName;
            if (parameterCount > 0 && dm.getName().equals(genericEvent)) {
                // this is used when we are processing the genericEvent
                eventName = parameters[0].getName();
                startJ = 1;
            } else {
                eventName = '\"' + dm.getName() + '\"';
            }

            retainsComments(dm, body);
            methodIDAnotation = writeEventNameOrId(dm, body, eventName);

            if (parameterCount - startJ == 1) {
                addFieldComments(dm, body);
            }

            if (hasMethodWriterListener && parameterCount > 0)
                createMethodWriterListener(dm, body);
            else if (parameters.length > 0)
                writeArrayOfParameters(dm, len, body, startJ);

            if (dm.getParameterTypes().length == 0)
                body.append("valueOut.text(\"\");\n");

            if (terminating) {
                body.append("}\n");
            }

        }

        return format("\n%s public %s %s(%s){\n %s%s}\n",
                methodIDAnotation,
                typeName,
                dm.getName(),
                methodSignature(importSet, dm, len),
                body,
                methodReturn(importSet, dm, interfaceClazz));
    }

    private void retainsComments(final Method dm, final StringBuilder body) {
//        body.append(format("if (dc.wire().bytes().retainsComments()){\n dc.wire().bytes().comment(\"%s\");\n}\n", dm.getName()));
    }

    private String writeEventNameOrId(final Method dm, final StringBuilder body, final String eventName) {
        String methodID = "";
        final Optional<Annotation> methodId = useMethodId ? stream(dm.getAnnotations()).filter(f -> f instanceof MethodId).findFirst() : Optional.empty();
        if ((wireType != WireType.TEXT && wireType != WireType.YAML) && methodId.isPresent()) {

            long value = ((MethodId) methodId.get()).value();
            body.append(format("final " + VALUE_OUT + " valueOut = dc.wire().writeEventId(%s, %d);\n", eventName, value));
            methodID = format("@" + METHOD_ID + "(%d)\n", value);

        } else
            body.append(format("final " + VALUE_OUT + " valueOut = dc.wire().writeEventName(%s);\n", eventName));
        return methodID;
    }

    private void writeArrayOfParameters(final Method dm, final int len, final StringBuilder body, final int startJ) {
        if (dm.getParameterTypes().length > startJ + 1)
            body.append("valueOut.array(v -> {\n");
        for (int j = startJ; j < len; j++) {

            final Parameter p = dm.getParameters()[j];

            final Optional<String> intConversion = stream(p.getAnnotations())
                    .filter(a -> a.annotationType() == IntConversion.class)
                    .map(x -> (((IntConversion) x).value().getName()))
                    .findFirst();

            final Optional<String> longConversion = stream(p.getAnnotations())
                    .filter(a -> a.annotationType() == LongConversion.class)
                    .map(x -> (((LongConversion) x).value().getName()))
                    .findFirst();

            final String name = intConversion.orElseGet(() -> (longConversion.orElse("")));

            if (!name.isEmpty() && (WireType.TEXT == wireType || WireType.YAML == wireType))
                body.append(format("//todo improve this\nvalueOut.rawText(new %s().asText(%s));\n", name, p.getName()));
            else if (p.getType().isPrimitive() || CharSequence.class.isAssignableFrom(p.getType())) {
                body.append(format("%s.%s(%s);\n", dm.getParameterTypes().length > startJ + 1 ? "v" : "valueOut", toString(p.getType()), p.getName()));
            } else
                writeValue(dm, body, startJ, p);
        }

        if (dm.getParameterTypes().length > startJ + 1)
            body.append("}, Object[].class);\n");
    }

    private void writeValue(final Method dm, final StringBuilder body, final int startJ, final Parameter p) {
        String className = p.getType().getTypeName().replace('$', '.');

        body
                .append(dm.getParameterTypes().length > startJ + 1 ? "v" : "valueOut")
                .append(".object(")
                .append(className)
                .append(".class, ")
                .append(p.getName())
                .append(");\n");
    }

    private void addFieldComments(final Method dm, final StringBuilder body) {
//        body.append(String.format("if (dc.wire().bytes().retainsComments()){\n " + GENERATE_METHOD_WRITER
//                + ".addComment(dc.wire().bytes(), %s);\n}\n", dm.getParameters()[0].getName()));
    }

    private void createMethodWriterListener(final Method dm, final StringBuilder body) {
        body.append("Object[] args$$ = new Object[]{\n");
        for (int i1 = 0; i1 < dm.getParameters().length; i1++) {
            body.append("(Object)").append(dm.getParameters()[i1].getName());
            if (i1 < dm.getParameters().length - 1)
                body.append(",\n");
        }
        body.append("\n};\n");

        body.append(format("this.methodWriterListener.onWrite(\"%s\",args$$);\n", dm.getName()));
        if (dm.getParameterCount() == 1) {
            if (Marshallable.class.isAssignableFrom(dm.getParameterTypes()[0])) {
                body.append(format("if (args$$[0].getClass() == %s.class){\n", dm.getParameterTypes()[0].getName().replace('$', '.')));
                body.append("valueOut.marshallable((")
                        .append(MARSHALLABLE)
                        .append(")args$$[0]);\n");
                body.append("} else {\n");
                body.append("valueOut.object(args$$[0]);\n}\n");
            } else {
                body.append("valueOut.object(args$$[0]);\n");
            }

        } else {
            body.append("valueOut.object(args$$);\n");
        }
    }

    private StringBuilder methodReturn(Set<String> importSet, final Method dm, final Class<?> interfaceClazz) {
        final StringBuilder result = new StringBuilder();
        if (dm.getReturnType() == Void.class || dm.getReturnType() == void.class)
            return result;

        if (dm.getReturnType().isAssignableFrom(interfaceClazz) || dm.getReturnType() == interfaceClazz) {
            result.append("return this;\n");

        } else if (dm.getReturnType().isInterface()) {
            String index = methodWritersMap.computeIfAbsent(dm.getReturnType(), k -> "methodWriter" + k.getSimpleName() + "TL");
            result.append("// method return\n");
            String aClass = nameForClass(importSet, dm.getReturnType());
            result.append(format("return (%s)%s.get();\n", aClass, aClass, index));

        } else if (!dm.getReturnType().isPrimitive()) {
            result.append("return null;\n");
        } else if (dm.getReturnType() == boolean.class) {
            result.append("return false;\n");
        } else if (dm.getReturnType() == byte.class) {
            result.append("return (byte)0;\n");
        } else {
            result.append("return 0;\n");
        }

        return result;
    }

}

