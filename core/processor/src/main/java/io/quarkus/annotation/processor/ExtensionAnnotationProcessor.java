package io.quarkus.annotation.processor;

import static io.quarkus.annotation.processor.Constants.ANNOTATION_CONFIG_GROUP;
import static io.quarkus.annotation.processor.Constants.ANNOTATION_CONFIG_MAPPING;
import static javax.lang.model.util.ElementFilter.constructorsIn;
import static javax.lang.model.util.ElementFilter.fieldsIn;
import static javax.lang.model.util.ElementFilter.methodsIn;
import static javax.lang.model.util.ElementFilter.typesIn;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.FileVisitor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import javax.annotation.processing.AbstractProcessor;
import javax.annotation.processing.Completion;
import javax.annotation.processing.Filer;
import javax.annotation.processing.RoundEnvironment;
import javax.lang.model.SourceVersion;
import javax.lang.model.element.AnnotationMirror;
import javax.lang.model.element.AnnotationValue;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.ExecutableElement;
import javax.lang.model.element.Modifier;
import javax.lang.model.element.Name;
import javax.lang.model.element.PackageElement;
import javax.lang.model.element.TypeElement;
import javax.lang.model.element.VariableElement;
import javax.lang.model.type.DeclaredType;
import javax.lang.model.type.PrimitiveType;
import javax.lang.model.type.TypeKind;
import javax.lang.model.type.TypeMirror;
import javax.tools.Diagnostic;
import javax.tools.FileObject;
import javax.tools.StandardLocation;

import org.jboss.jdeparser.FormatPreferences;
import org.jboss.jdeparser.JAssignableExpr;
import org.jboss.jdeparser.JCall;
import org.jboss.jdeparser.JClassDef;
import org.jboss.jdeparser.JDeparser;
import org.jboss.jdeparser.JExprs;
import org.jboss.jdeparser.JFiler;
import org.jboss.jdeparser.JMethodDef;
import org.jboss.jdeparser.JMod;
import org.jboss.jdeparser.JSourceFile;
import org.jboss.jdeparser.JSources;
import org.jboss.jdeparser.JType;
import org.jboss.jdeparser.JTypes;

import io.quarkus.annotation.processor.generate_doc.ConfigDocGeneratedOutput;
import io.quarkus.annotation.processor.generate_doc.ConfigDocItemScanner;
import io.quarkus.annotation.processor.generate_doc.ConfigDocWriter;
import io.quarkus.annotation.processor.generate_doc.DocGeneratorUtil;
import io.quarkus.bootstrap.util.PropertyUtils;

public class ExtensionAnnotationProcessor extends AbstractProcessor {

    private static final Pattern REMOVE_LEADING_SPACE = Pattern.compile("^ ", Pattern.MULTILINE);
    private static final String QUARKUS_GENERATED = "io.quarkus.Generated";

    private final ConfigDocWriter configDocWriter = new ConfigDocWriter();
    private final ConfigDocItemScanner configDocItemScanner = new ConfigDocItemScanner();
    private final Set<String> generatedAccessors = new ConcurrentHashMap<String, Boolean>().keySet(Boolean.TRUE);
    private final Set<String> generatedJavaDocs = new ConcurrentHashMap<String, Boolean>().keySet(Boolean.TRUE);
    private final boolean generateDocs = !(Boolean.getBoolean("skipDocs") || Boolean.getBoolean("quickly"));

    private final Map<String, Boolean> ANNOTATION_USAGE_TRACKER = new ConcurrentHashMap<>();

    public ExtensionAnnotationProcessor() {
    }

    @Override
    public Set<String> getSupportedOptions() {
        return Collections.emptySet();
    }

    @Override
    public Set<String> getSupportedAnnotationTypes() {
        return Constants.SUPPORTED_ANNOTATIONS_TYPES;
    }

    @Override
    public SourceVersion getSupportedSourceVersion() {
        return SourceVersion.latest();
    }

    @Override
    public boolean process(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        try {
            doProcess(annotations, roundEnv);
            if (roundEnv.processingOver()) {
                doFinish();
            }
            return true;
        } finally {
            JDeparser.dropCaches();
        }
    }

    @Override
    public Iterable<? extends Completion> getCompletions(Element element, AnnotationMirror annotation, ExecutableElement member,
            String userText) {
        return Collections.emptySet();
    }

    public void doProcess(Set<? extends TypeElement> annotations, RoundEnvironment roundEnv) {
        for (TypeElement annotation : annotations) {
            switch (annotation.getQualifiedName().toString()) {
                case Constants.ANNOTATION_BUILD_STEP:
                    trackAnnotationUsed(Constants.ANNOTATION_BUILD_STEP);
                    processBuildStep(roundEnv, annotation);
                    break;
                case Constants.ANNOTATION_CONFIG_GROUP:
                    trackAnnotationUsed(Constants.ANNOTATION_CONFIG_GROUP);
                    processConfigGroup(roundEnv, annotation);
                    break;
                case Constants.ANNOTATION_CONFIG_ROOT:
                    trackAnnotationUsed(Constants.ANNOTATION_CONFIG_ROOT);
                    processConfigRoot(roundEnv, annotation);
                    break;
                case Constants.ANNOTATION_RECORDER:
                    trackAnnotationUsed(Constants.ANNOTATION_RECORDER);
                    processRecorder(roundEnv, annotation);
                    break;
                case Constants.ANNOTATION_CONFIG_MAPPING:
                    trackAnnotationUsed(Constants.ANNOTATION_CONFIG_MAPPING);
                    break;
            }
        }
    }

    void doFinish() {
        validateAnnotationUsage();

        final Filer filer = processingEnv.getFiler();
        final FileObject tempResource;
        try {
            tempResource = filer.createResource(StandardLocation.SOURCE_OUTPUT, Constants.EMPTY, "ignore.tmp");
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Unable to create temp output file: " + e);
            return;
        }
        final URI uri = tempResource.toUri();
        //        tempResource.delete();
        Path path;
        try {
            path = Paths.get(uri).getParent();
        } catch (RuntimeException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Resource path URI is invalid: " + uri);
            return;
        }
        Collection<String> bscListClasses = new TreeSet<>();
        Collection<String> crListClasses = new TreeSet<>();
        Properties javaDocProperties = new Properties();

        try {
            Files.walkFileTree(path, new FileVisitor<Path>() {
                public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                    return FileVisitResult.CONTINUE;
                }

                public FileVisitResult visitFile(final Path file, final BasicFileAttributes attrs) {
                    final String nameStr = file.getFileName().toString();
                    if (nameStr.endsWith(".bsc")) {
                        readFile(file, bscListClasses);
                    } else if (nameStr.endsWith(".cr")) {
                        readFile(file, crListClasses);
                    } else if (nameStr.endsWith(".jdp")) {
                        final Properties p = new Properties();
                        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                            p.load(br);
                        } catch (IOException e) {
                            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                                    "Failed to read file " + file + ": " + e);
                        }
                        final Set<String> names = p.stringPropertyNames();
                        for (String name : names) {
                            javaDocProperties.setProperty(name, p.getProperty(name));
                        }
                    }

                    return FileVisitResult.CONTINUE;
                }

                public FileVisitResult visitFileFailed(final Path file, final IOException exc) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Failed to visit file " + file + ": " + exc);
                    return FileVisitResult.CONTINUE;
                }

                public FileVisitResult postVisitDirectory(final Path dir, final IOException exc) {
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "File walk failed: " + e);
        }
        if (!bscListClasses.isEmpty())
            try {
                final FileObject listResource = filer.createResource(StandardLocation.CLASS_OUTPUT, "",
                        "META-INF/quarkus-build-steps.list");
                writeListResourceFile(bscListClasses, listResource);
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed to write build steps listing: " + e);
                return;
            }
        if (!crListClasses.isEmpty()) {
            try {
                final FileObject listResource = filer.createResource(StandardLocation.CLASS_OUTPUT, "",
                        "META-INF/quarkus-config-roots.list");
                writeListResourceFile(crListClasses, listResource);
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed to write config roots listing: " + e);
                return;
            }
        }

        if (!javaDocProperties.isEmpty()) {
            try {
                final FileObject listResource = filer.createResource(StandardLocation.CLASS_OUTPUT, "",
                        "META-INF/quarkus-javadoc.properties");
                try (OutputStream os = listResource.openOutputStream()) {
                    try (BufferedOutputStream bos = new BufferedOutputStream(os)) {
                        try (OutputStreamWriter osw = new OutputStreamWriter(bos, StandardCharsets.UTF_8)) {
                            try (BufferedWriter bw = new BufferedWriter(osw)) {
                                PropertyUtils.store(javaDocProperties, bw);
                            }
                        }
                    }
                }
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed to write javadoc properties: " + e);
                return;
            }
        }

        try {
            if (generateDocs) {
                final Set<ConfigDocGeneratedOutput> outputs = configDocItemScanner
                        .scanExtensionsConfigurationItems(javaDocProperties, isAnnotationUsed(ANNOTATION_CONFIG_MAPPING));
                for (ConfigDocGeneratedOutput output : outputs) {
                    DocGeneratorUtil.sort(output.getConfigDocItems()); // sort before writing
                    configDocWriter.writeAllExtensionConfigDocumentation(output);
                }
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed to generate extension doc: " + e);
            return;

        }
    }

    private void validateAnnotationUsage() {
        if (isAnnotationUsed(Constants.ANNOTATION_BUILD_STEP) && isAnnotationUsed(Constants.ANNOTATION_RECORDER)) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Detected use of @Recorder annotation in 'deployment' module. Classes annotated with @Recorder must be part of the extension's 'runtime' module");
        }
    }

    private boolean isAnnotationUsed(String annotation) {
        return ANNOTATION_USAGE_TRACKER.getOrDefault(annotation, false);
    }

    private void trackAnnotationUsed(String annotation) {
        ANNOTATION_USAGE_TRACKER.put(annotation, true);
    }

    private void writeListResourceFile(Collection<String> crListClasses, FileObject listResource) throws IOException {
        try (OutputStream os = listResource.openOutputStream()) {
            try (BufferedOutputStream bos = new BufferedOutputStream(os)) {
                try (OutputStreamWriter osw = new OutputStreamWriter(bos, StandardCharsets.UTF_8)) {
                    try (BufferedWriter bw = new BufferedWriter(osw)) {
                        for (String item : crListClasses) {
                            bw.write(item);
                            bw.newLine();
                        }
                    }
                }
            }
        }
    }

    private void readFile(Path file, Collection<String> bscListClasses) {
        try (BufferedReader br = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            String line;
            while ((line = br.readLine()) != null) {
                line = line.trim();
                if (!line.isEmpty()) {
                    bscListClasses.add(line);
                }
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Failed to read file " + file + ": " + e);
        }
    }

    private void processBuildStep(RoundEnvironment roundEnv, TypeElement annotation) {
        final Set<String> processorClassNames = new HashSet<>();

        for (ExecutableElement i : methodsIn(roundEnv.getElementsAnnotatedWith(annotation))) {
            final TypeElement clazz = getClassOf(i);
            if (clazz == null) {
                continue;
            }

            final PackageElement pkg = processingEnv.getElementUtils().getPackageOf(clazz);
            if (pkg == null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Element " + clazz + " has no enclosing package");
                continue;
            }

            final String binaryName = processingEnv.getElementUtils().getBinaryName(clazz).toString();
            if (processorClassNames.add(binaryName)) {
                validateRecordBuildSteps(clazz);
                recordConfigJavadoc(clazz);
                generateAccessor(clazz);
                final StringBuilder rbn = getRelativeBinaryName(clazz, new StringBuilder());
                try {
                    final FileObject itemResource = processingEnv.getFiler().createResource(
                            StandardLocation.SOURCE_OUTPUT,
                            pkg.getQualifiedName().toString(),
                            rbn.toString() + ".bsc",
                            clazz);
                    writeResourceFile(binaryName, itemResource);
                } catch (IOException e1) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Failed to create " + rbn + " in " + pkg + ": " + e1, clazz);
                }
            }
        }
    }

    private void validateRecordBuildSteps(TypeElement clazz) {
        for (Element e : clazz.getEnclosedElements()) {
            if (e.getKind() != ElementKind.METHOD) {
                continue;
            }
            ExecutableElement ex = (ExecutableElement) e;
            if (!isAnnotationPresent(ex, Constants.ANNOTATION_BUILD_STEP)) {
                continue;
            }
            if (!isAnnotationPresent(ex, Constants.ANNOTATION_RECORD)) {
                continue;
            }

            boolean hasRecorder = false;
            boolean allTypesResolvable = true;
            for (VariableElement parameter : ex.getParameters()) {
                String parameterClassName = parameter.asType().toString();
                TypeElement parameterTypeElement = processingEnv.getElementUtils().getTypeElement(parameterClassName);
                if (parameterTypeElement == null) {
                    allTypesResolvable = false;
                } else {
                    if (isAnnotationPresent(parameterTypeElement, Constants.ANNOTATION_RECORDER)) {
                        if (parameterTypeElement.getModifiers().contains(Modifier.FINAL)) {
                            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                                    "Class '" + parameterTypeElement.getQualifiedName()
                                            + "' is annotated with @Recorder and therefore cannot be made as a final class.");
                        } else if (getPackageName(clazz).equals(getPackageName(parameterTypeElement))) {
                            processingEnv.getMessager().printMessage(Diagnostic.Kind.WARNING,
                                    "Build step class '" + clazz.getQualifiedName()
                                            + "' and recorder '" + parameterTypeElement
                                            + "' share the same package. This is highly discouraged as it can lead to unexpected results.");
                        }
                        hasRecorder = true;
                        break;
                    }
                }
            }

            if (!hasRecorder && allTypesResolvable) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Build Step '" + clazz.getQualifiedName() + "#"
                        + ex.getSimpleName()
                        + "' which is annotated with '@Record' does not contain a method parameter whose type is annotated with '@Recorder'.");
            }
        }
    }

    private Name getPackageName(TypeElement clazz) {
        return processingEnv.getElementUtils().getPackageOf(clazz).getQualifiedName();
    }

    private StringBuilder getRelativeBinaryName(TypeElement te, StringBuilder b) {
        final Element enclosing = te.getEnclosingElement();
        if (enclosing instanceof TypeElement) {
            getRelativeBinaryName((TypeElement) enclosing, b);
            b.append('$');
        }
        b.append(te.getSimpleName());
        return b;
    }

    private TypeElement getClassOf(Element e) {
        Element t = e;
        while (!(t instanceof TypeElement)) {
            if (t == null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Element " + e + " has no enclosing class");
                return null;
            }
            t = t.getEnclosingElement();
        }
        return (TypeElement) t;
    }

    private void recordConfigJavadoc(TypeElement clazz) {
        String className = clazz.getQualifiedName().toString();
        if (!generatedJavaDocs.add(className))
            return;
        Properties javadocProps = new Properties();
        for (Element e : clazz.getEnclosedElements()) {
            switch (e.getKind()) {
                case FIELD: {
                    if (isDocumentedConfigItem(e)) {
                        processFieldConfigItem((VariableElement) e, javadocProps, className);
                    }
                    break;
                }
                case CONSTRUCTOR: {
                    final ExecutableElement ex = (ExecutableElement) e;
                    if (hasParameterDocumentedConfigItem(ex)) {
                        processCtorConfigItem(ex, javadocProps, className);
                    }
                    break;
                }
                case METHOD: {
                    final ExecutableElement ex = (ExecutableElement) e;
                    if (hasParameterDocumentedConfigItem(ex)) {
                        processMethodConfigItem(ex, javadocProps, className);
                    }
                    break;
                }
                case ENUM:
                    e
                            .getEnclosedElements()
                            .stream()
                            .filter(e1 -> e1.getKind() == ElementKind.ENUM_CONSTANT)
                            .forEach(ec -> processEnumConstant(ec, javadocProps, className));
                    break;
                default:
            }
        }
        writeJavadocProperties(clazz, javadocProps);
    }

    private void recordMappingJavadoc(TypeElement clazz) {
        String className = clazz.getQualifiedName().toString();
        if (!generatedJavaDocs.add(className))
            return;
        if (!isAnnotationPresent(clazz, ANNOTATION_CONFIG_MAPPING)) {
            if (generateDocs) {
                configDocItemScanner.addConfigGroups(clazz);
            }
        }
        Properties javadocProps = new Properties();
        recordMappingJavadoc(clazz, javadocProps);
        writeJavadocProperties(clazz, javadocProps);
    }

    private void recordMappingJavadoc(final TypeElement clazz, final Properties javadocProps) {
        String className = clazz.getQualifiedName().toString();
        for (Element e : clazz.getEnclosedElements()) {
            switch (e.getKind()) {
                case INTERFACE: {
                    recordMappingJavadoc(((TypeElement) e));
                    break;
                }

                case METHOD: {
                    if (!isConfigMappingMethodIgnored(e)) {
                        processMethodConfigMapping((ExecutableElement) e, javadocProps, className);
                    }
                    break;
                }
                default:
            }
        }
    }

    private boolean isEnclosedByMapping(Element clazz) {
        if (clazz.getKind().equals(ElementKind.INTERFACE)) {
            Element enclosingElement = clazz.getEnclosingElement();
            if (enclosingElement.getKind().equals(ElementKind.INTERFACE)) {
                if (isAnnotationPresent(enclosingElement, ANNOTATION_CONFIG_MAPPING)) {
                    return true;
                } else {
                    isEnclosedByMapping(enclosingElement);
                }
            }
        }
        return false;
    }

    private void writeJavadocProperties(final TypeElement clazz, final Properties javadocProps) {
        if (javadocProps.isEmpty())
            return;
        final PackageElement pkg = processingEnv.getElementUtils().getPackageOf(clazz);
        final String rbn = getRelativeBinaryName(clazz, new StringBuilder()).append(".jdp").toString();
        try {
            FileObject file = processingEnv.getFiler().createResource(
                    StandardLocation.SOURCE_OUTPUT,
                    pkg.getQualifiedName().toString(),
                    rbn,
                    clazz);
            try (Writer writer = file.openWriter()) {
                javadocProps.store(writer, Constants.EMPTY);
            }
        } catch (IOException e) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed to persist resource " + rbn + ": " + e);
        }
    }

    private void processFieldConfigItem(VariableElement field, Properties javadocProps, String className) {
        javadocProps.put(className + Constants.DOT + field.getSimpleName().toString(), getRequiredJavadoc(field));
    }

    private void processEnumConstant(Element field, Properties javadocProps, String className) {
        String javaDoc = getJavadoc(field);
        if (javaDoc != null && !javaDoc.isBlank()) {
            javadocProps.put(className + Constants.DOT + field.getSimpleName().toString(), javaDoc);
        }
    }

    private void processCtorConfigItem(ExecutableElement ctor, Properties javadocProps, String className) {
        final String docComment = getRequiredJavadoc(ctor);
        final StringBuilder buf = new StringBuilder();
        appendParamTypes(ctor, buf);
        javadocProps.put(className + Constants.DOT + buf, docComment);
    }

    private void processMethodConfigItem(ExecutableElement method, Properties javadocProps, String className) {
        final String docComment = getRequiredJavadoc(method);
        final StringBuilder buf = new StringBuilder();
        buf.append(method.getSimpleName().toString());
        appendParamTypes(method, buf);
        javadocProps.put(className + Constants.DOT + buf, docComment);
    }

    private void processMethodConfigMapping(ExecutableElement method, Properties javadocProps, String className) {
        if (method.getModifiers().contains(Modifier.ABSTRACT)) {
            // Skip toString method, because mappings can include it and generate it
            if (method.getSimpleName().contentEquals("toString") && method.getParameters().size() == 0) {
                return;
            }

            String docComment = getRequiredJavadoc(method);
            javadocProps.put(className + Constants.DOT + method.getSimpleName().toString(), docComment);

            // Find groups without annotation
            TypeMirror returnType = method.getReturnType();
            if (TypeKind.DECLARED.equals(returnType.getKind())) {
                DeclaredType declaredType = (DeclaredType) returnType;
                if (!isAnnotationPresent(declaredType.asElement(), ANNOTATION_CONFIG_GROUP)) {
                    TypeElement type = unwrapConfigGroup(returnType);
                    if (type != null && ElementKind.INTERFACE.equals(type.getKind())) {
                        recordMappingJavadoc(type);
                        configDocItemScanner.addConfigGroups(type);
                    }
                }
            }
        }
    }

    private TypeElement unwrapConfigGroup(TypeMirror typeMirror) {
        if (typeMirror == null) {
            return null;
        }

        DeclaredType declaredType = (DeclaredType) typeMirror;
        String name = declaredType.asElement().toString();
        List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
        if (typeArguments.size() == 0) {
            if (!name.startsWith("java.")) {
                return (TypeElement) declaredType.asElement();
            }
        } else if (typeArguments.size() == 1) {
            if (name.equals(Optional.class.getName()) ||
                    name.equals(List.class.getName()) ||
                    name.equals(Set.class.getName())) {
                return unwrapConfigGroup(typeArguments.get(0));
            }
        } else if (typeArguments.size() == 2) {
            if (name.equals(Map.class.getName())) {
                return unwrapConfigGroup(typeArguments.get(1));
            }
        }
        return null;
    }

    private void processConfigGroup(RoundEnvironment roundEnv, TypeElement annotation) {
        final Set<String> groupClassNames = new HashSet<>();
        for (TypeElement i : typesIn(roundEnv.getElementsAnnotatedWith(annotation))) {
            if (groupClassNames.add(i.getQualifiedName().toString())) {
                generateAccessor(i);
                if (isEnclosedByMapping(i) || i.getKind().equals(ElementKind.INTERFACE)) {
                    recordMappingJavadoc(i);
                } else {
                    recordConfigJavadoc(i);
                }
                if (generateDocs) {
                    configDocItemScanner.addConfigGroups(i);
                }
            }
        }
    }

    private void processConfigRoot(RoundEnvironment roundEnv, TypeElement annotation) {
        final Set<String> rootClassNames = new HashSet<>();

        for (TypeElement clazz : typesIn(roundEnv.getElementsAnnotatedWith(annotation))) {
            final PackageElement pkg = processingEnv.getElementUtils().getPackageOf(clazz);
            if (pkg == null) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                        "Element " + clazz + " has no enclosing package");
                continue;
            }

            if (generateDocs) {
                configDocItemScanner.addConfigRoot(pkg, clazz);
            }

            final String binaryName = processingEnv.getElementUtils().getBinaryName(clazz).toString();
            if (rootClassNames.add(binaryName)) {
                // new class
                if (isAnnotationPresent(clazz, ANNOTATION_CONFIG_MAPPING)) {
                    recordMappingJavadoc(clazz);
                } else if (isAnnotationPresent(clazz, Constants.ANNOTATION_CONFIG_ROOT)) {
                    recordConfigJavadoc(clazz);
                    generateAccessor(clazz);
                }
                final StringBuilder rbn = getRelativeBinaryName(clazz, new StringBuilder());
                try {
                    final FileObject itemResource = processingEnv.getFiler().createResource(
                            StandardLocation.SOURCE_OUTPUT,
                            pkg.getQualifiedName().toString(),
                            rbn + ".cr",
                            clazz);
                    writeResourceFile(binaryName, itemResource);
                } catch (IOException e1) {
                    processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                            "Failed to create " + rbn + " in " + pkg + ": " + e1, clazz);
                }
            }
        }
    }

    private void writeResourceFile(String binaryName, FileObject itemResource) throws IOException {
        try (OutputStream os = itemResource.openOutputStream()) {
            try (BufferedOutputStream bos = new BufferedOutputStream(os)) {
                try (OutputStreamWriter osw = new OutputStreamWriter(bos, StandardCharsets.UTF_8)) {
                    try (BufferedWriter bw = new BufferedWriter(osw)) {
                        bw.write(binaryName);
                        bw.newLine();
                    }
                }
            }
        }
    }

    private void processRecorder(RoundEnvironment roundEnv, TypeElement annotation) {
        final Set<String> groupClassNames = new HashSet<>();
        for (TypeElement i : typesIn(roundEnv.getElementsAnnotatedWith(annotation))) {
            if (groupClassNames.add(i.getQualifiedName().toString())) {
                generateAccessor(i);
                recordConfigJavadoc(i);
            }
        }
    }

    private void generateAccessor(final TypeElement clazz) {
        if (!generatedAccessors.add(clazz.getQualifiedName().toString()))
            return;
        final FormatPreferences fp = new FormatPreferences();
        final JSources sources = JDeparser.createSources(JFiler.newInstance(processingEnv.getFiler()), fp);
        final PackageElement packageElement = processingEnv.getElementUtils().getPackageOf(clazz);
        final String className = getRelativeBinaryName(clazz, new StringBuilder()).append("$$accessor").toString();
        final JSourceFile sourceFile = sources.createSourceFile(packageElement.getQualifiedName().toString(), className);
        JType clazzType = JTypes.typeOf(clazz.asType());
        if (clazz.asType() instanceof DeclaredType) {
            DeclaredType declaredType = ((DeclaredType) clazz.asType());
            TypeMirror enclosingType = declaredType.getEnclosingType();
            if (enclosingType != null && enclosingType.getKind() == TypeKind.DECLARED
                    && clazz.getModifiers().contains(Modifier.STATIC)) {
                // Ugly workaround for Eclipse APT and static nested types
                clazzType = unnestStaticNestedType(declaredType);
            }
        }
        final JClassDef classDef = sourceFile._class(JMod.PUBLIC | JMod.FINAL, className);
        classDef.constructor(JMod.PRIVATE); // no construction
        classDef.annotate(QUARKUS_GENERATED).value("Quarkus annotation processor");
        final JAssignableExpr instanceName = JExprs.name(Constants.INSTANCE_SYM);
        boolean isEnclosingClassPublic = clazz.getModifiers().contains(Modifier.PUBLIC);
        // iterate fields
        boolean generationNeeded = false;
        for (VariableElement field : fieldsIn(clazz.getEnclosedElements())) {
            final Set<Modifier> mods = field.getModifiers();
            if (mods.contains(Modifier.PRIVATE) || mods.contains(Modifier.STATIC) || mods.contains(Modifier.FINAL)) {
                // skip it
                continue;
            }
            final TypeMirror fieldType = field.asType();
            if (mods.contains(Modifier.PUBLIC) && isEnclosingClassPublic) {
                // we don't need to generate a method accessor when the following conditions are met:
                // 1) the field is public
                // 2) the enclosing class is public
                // 3) the class type of the field is public
                if (fieldType instanceof DeclaredType) {
                    final DeclaredType declaredType = (DeclaredType) fieldType;
                    final TypeElement typeElement = (TypeElement) declaredType.asElement();
                    if (typeElement.getModifiers().contains(Modifier.PUBLIC)) {
                        continue;
                    }
                } else {
                    continue;
                }

            }
            generationNeeded = true;

            final JType realType = JTypes.typeOf(fieldType);
            final JType publicType = fieldType instanceof PrimitiveType ? realType : JType.OBJECT;

            final String fieldName = field.getSimpleName().toString();
            final JMethodDef getter = classDef.method(JMod.PUBLIC | JMod.STATIC, publicType, "get_" + fieldName);
            getter.annotate(SuppressWarnings.class).value("unchecked");
            getter.param(JType.OBJECT, Constants.INSTANCE_SYM);
            getter.body()._return(instanceName.cast(clazzType).field(fieldName));
            final JMethodDef setter = classDef.method(JMod.PUBLIC | JMod.STATIC, JType.VOID, "set_" + fieldName);
            setter.annotate(SuppressWarnings.class).value("unchecked");
            setter.param(JType.OBJECT, Constants.INSTANCE_SYM);
            setter.param(publicType, fieldName);
            final JAssignableExpr fieldExpr = JExprs.name(fieldName);
            setter.body().assign(instanceName.cast(clazzType).field(fieldName),
                    (publicType.equals(realType) ? fieldExpr : fieldExpr.cast(realType)));
        }

        // we need to generate an accessor if the class isn't public
        if (!isEnclosingClassPublic) {
            for (ExecutableElement ctor : constructorsIn(clazz.getEnclosedElements())) {
                if (ctor.getModifiers().contains(Modifier.PRIVATE)) {
                    // skip it
                    continue;
                }
                generationNeeded = true;
                StringBuilder b = new StringBuilder();
                for (VariableElement parameter : ctor.getParameters()) {
                    b.append('_');
                    b.append(parameter.asType().toString().replace('.', '_'));
                }
                String codedName = b.toString();
                final JMethodDef ctorMethod = classDef.method(JMod.PUBLIC | JMod.STATIC, JType.OBJECT, "construct" + codedName);
                final JCall ctorCall = clazzType._new();
                for (VariableElement parameter : ctor.getParameters()) {
                    final TypeMirror paramType = parameter.asType();
                    final JType realType = JTypes.typeOf(paramType);
                    final JType publicType = paramType instanceof PrimitiveType ? realType : JType.OBJECT;
                    final String name = parameter.getSimpleName().toString();
                    ctorMethod.param(publicType, name);
                    final JAssignableExpr nameExpr = JExprs.name(name);
                    ctorCall.arg(publicType.equals(realType) ? nameExpr : nameExpr.cast(realType));
                }
                ctorMethod.body()._return(ctorCall);
            }
        }

        // if no constructor or field access is needed, don't generate anything
        if (generationNeeded) {
            try {
                sources.writeSources();
            } catch (IOException e) {
                processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Failed to generate source file: " + e, clazz);
            }
        }
    }

    private JType unnestStaticNestedType(DeclaredType declaredType) {
        final TypeElement typeElement = (TypeElement) declaredType.asElement();

        final String name = typeElement.getQualifiedName().toString();
        final JType rawType = JTypes.typeNamed(name);
        final List<? extends TypeMirror> typeArguments = declaredType.getTypeArguments();
        if (typeArguments.isEmpty()) {
            return rawType;
        }
        JType[] args = new JType[typeArguments.size()];
        for (int i = 0; i < typeArguments.size(); i++) {
            final TypeMirror argument = typeArguments.get(i);
            args[i] = JTypes.typeOf(argument);
        }
        return rawType.typeArg(args);
    }

    private void appendParamTypes(ExecutableElement ex, final StringBuilder buf) {
        final List<? extends VariableElement> params = ex.getParameters();
        if (params.isEmpty()) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR, "Expected at least one parameter", ex);
            return;
        }
        VariableElement param = params.get(0);
        DeclaredType dt = (DeclaredType) param.asType();
        String typeName = processingEnv.getElementUtils().getBinaryName(((TypeElement) dt.asElement())).toString();
        buf.append('(').append(typeName);
        for (int i = 1; i < params.size(); ++i) {
            param = params.get(i);
            dt = (DeclaredType) param.asType();
            typeName = processingEnv.getElementUtils().getBinaryName(((TypeElement) dt.asElement())).toString();
            buf.append(',').append(typeName);
        }
        buf.append(')');
    }

    private String getRequiredJavadoc(Element e) {
        String javaDoc = getJavadoc(e);

        if (javaDoc == null) {
            processingEnv.getMessager().printMessage(Diagnostic.Kind.ERROR,
                    "Unable to find javadoc for config item " + e.getEnclosingElement() + " " + e, e);
            return "";
        }
        return javaDoc;
    }

    private String getJavadoc(Element e) {
        String docComment = processingEnv.getElementUtils().getDocComment(e);

        if (docComment == null) {
            return null;
        }

        // javax.lang.model keeps the leading space after the "*" so we need to remove it.

        return REMOVE_LEADING_SPACE.matcher(docComment).replaceAll("").trim();
    }

    private static boolean isDocumentedConfigItem(Element element) {
        boolean hasAnnotation = false;
        for (AnnotationMirror annotationMirror : element.getAnnotationMirrors()) {
            String annotationName = ((TypeElement) annotationMirror.getAnnotationType().asElement())
                    .getQualifiedName().toString();
            if (Constants.ANNOTATION_CONFIG_ITEM.equals(annotationName)) {
                hasAnnotation = true;
                Object generateDocumentation = getAnnotationAttribute(annotationMirror, "generateDocumentation()");
                if (generateDocumentation != null && !(Boolean) generateDocumentation) {
                    // Documentation is explicitly disabled
                    return false;
                }
            } else if (Constants.ANNOTATION_CONFIG_DOC_SECTION.equals(annotationName)) {
                hasAnnotation = true;
            }
        }
        return hasAnnotation;
    }

    private static boolean isConfigMappingMethodIgnored(Element element) {
        for (AnnotationMirror annotationMirror : element.getAnnotationMirrors()) {
            String annotationName = ((TypeElement) annotationMirror.getAnnotationType().asElement())
                    .getQualifiedName().toString();
            if (Constants.ANNOTATION_CONFIG_DOC_IGNORE.equals(annotationName)) {
                return true;
            }
        }
        return false;
    }

    private static Object getAnnotationAttribute(AnnotationMirror annotationMirror, String attributeName) {
        for (Map.Entry<? extends ExecutableElement, ? extends AnnotationValue> entry : annotationMirror
                .getElementValues().entrySet()) {
            final String key = entry.getKey().toString();
            final Object value = entry.getValue().getValue();
            if (attributeName.equals(key)) {
                return value;
            }
        }
        return null;
    }

    private static boolean hasParameterDocumentedConfigItem(ExecutableElement ex) {
        for (VariableElement param : ex.getParameters()) {
            if (isDocumentedConfigItem(param)) {
                return true;
            }
        }

        return false;
    }

    private static boolean isAnnotationPresent(Element element, String... annotationNames) {
        Set<String> annotations = new HashSet<>(Arrays.asList(annotationNames));
        for (AnnotationMirror i : element.getAnnotationMirrors()) {
            String annotationName = ((TypeElement) i.getAnnotationType().asElement()).getQualifiedName().toString();
            if (annotations.contains(annotationName)) {
                return true;
            }
        }
        return false;
    }
}
