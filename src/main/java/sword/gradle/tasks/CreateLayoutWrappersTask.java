package sword.gradle.tasks;

import org.gradle.api.provider.MapProperty;
import org.gradle.api.provider.Property;
import org.xml.sax.SAXException;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.provider.ListProperty;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputDirectory;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.xml.sax.Attributes;
import org.xml.sax.helpers.DefaultHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.lang.reflect.Method;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

import static sword.gradle.tasks.CaseUtils.fromSnakeToPascalCase;
import static sword.gradle.tasks.StringResourceUtils.obtainKnownPlaceholderStrings;

public abstract class CreateLayoutWrappersTask extends DefaultTask {

    private final HashMap<String, String> mImplicitTagNames = new HashMap<>();

    @Input
    public abstract Property<String> getPackageName();

    @Input
    public abstract Property<String> getLayoutInterface();

    @Input
    public abstract Property<String> getAndroidResourceClass();

    @Input
    public abstract Property<String> getEnsureNonNullFunction();

    @Input
    public abstract ListProperty<File> getBootClassPath();

    @Input
    public abstract MapProperty<String, String> getKnownCasts();

    @InputDirectory
    public abstract DirectoryProperty getInterfacesClasspath();

    @InputDirectory
    public abstract DirectoryProperty getResourcesDir();

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    public CreateLayoutWrappersTask() {
        mImplicitTagNames.put("AutoCompleteTextView", "android.widget.AutoCompleteTextView");
        mImplicitTagNames.put("Button", "android.widget.Button");
        mImplicitTagNames.put("CheckBox", "android.widget.CheckBox");
        mImplicitTagNames.put("DatePicker", "android.widget.DatePicker");
        mImplicitTagNames.put("DigitalClock", "android.widget.DigitalClock");
        mImplicitTagNames.put("EditText", "android.widget.EditText");
        mImplicitTagNames.put("ExpandableListView", "android.widget.ExpandableListView");
        mImplicitTagNames.put("FrameLayout", "android.widget.FrameLayout");
        mImplicitTagNames.put("GridView", "android.widget.GridView");
        mImplicitTagNames.put("HorizontalScrollView", "android.widget.HorizontalScrollView");
        mImplicitTagNames.put("ImageButton", "android.widget.ImageButton");
        mImplicitTagNames.put("ImageView", "android.widget.ImageView");
        mImplicitTagNames.put("LinearLayout", "android.widget.LinearLayout");
        mImplicitTagNames.put("ListView", "android.widget.ListView");
        mImplicitTagNames.put("ProgressBar", "android.widget.ProgressBar");
        mImplicitTagNames.put("RadioButton", "android.widget.RadioButton");
        mImplicitTagNames.put("RelativeLayout", "android.widget.RelativeLayout");
        mImplicitTagNames.put("ScrollView", "android.widget.ScrollView");
        mImplicitTagNames.put("SeekBar", "android.widget.SeekBar");
        mImplicitTagNames.put("Spinner", "android.widget.Spinner");
        mImplicitTagNames.put("SurfaceView", "android.view.SurfaceView");
        mImplicitTagNames.put("TextView", "android.widget.TextView");
        mImplicitTagNames.put("TimePicker", "android.widget.TimePicker");
        mImplicitTagNames.put("VideoView", "android.widget.VideoView");
        mImplicitTagNames.put("View", "android.view.View");
        mImplicitTagNames.put("WebView", "android.webkit.WebView");
    }

    private interface Type {
    }

    private static final class ViewType implements Type {
        final String typeName;

        ViewType(String typeName) {
            this.typeName = typeName;
        }
    }

    private static final class LayoutType implements Type {
        final String layoutName;

        LayoutType(String layoutName) {
            this.layoutName = layoutName;
        }
    }

    private static final class ParserHandler extends DefaultHandler {

        final String fileName;
        final Set<String> knownPlaceholderStrings;

        final HashMap<String, Type> foundIdsAndTypes = new HashMap<>();
        final HashMap<String, String> idsAndWrappers = new HashMap<>();
        final HashSet<String> conflictingIds = new HashSet<>();
        final HashSet<String> foundLayouts = new HashSet<>();
        final HashSet<String> foundMultipleTimesLayout = new HashSet<>();

        String rootTag;
        LinkedList<String> idHierarchy = new LinkedList<>();

        ParserHandler(String fileName, Set<String> knownPlaceholderStrings) {
            this.fileName = fileName;
            this.knownPlaceholderStrings = knownPlaceholderStrings;
        }

        private void assertValidId(String id) {
            final int length = id.length();
            if (id.charAt(0) < 'a' || id.charAt(0) > 'z') {
                final String message = "View id must start with lower case, but '" + id + "' in " + fileName + " does not.";
                System.err.println(message);
                throw new RuntimeException(message);
            }

            for (int i = 1; i < length; i++) {
                final char ch = id.charAt(i);
                if ((ch < 'a' || ch > 'z') && (ch < 'A' || ch > 'Z') && (ch < '0' || ch > '9')) {
                    final String message = "View id only can contain characters from a-z, A-Z or 0-9. Id '" + id + "' in " + fileName + " does not follow the rule.";
                    System.err.println(message);
                    throw new RuntimeException(message);
                }
            }
        }

        private void register(String id, Type value) {
            assertValidId(id);
            if (!conflictingIds.contains(id)) {
                if (foundIdsAndTypes.containsKey(id)) {
                    conflictingIds.add(id);
                    foundIdsAndTypes.remove(id);
                    idsAndWrappers.remove(id);
                }
                else {
                    foundIdsAndTypes.put(id, value);
                    String wrappingId = null;
                    if (idHierarchy.size() >= 2) {
                        for (String wid : idHierarchy) {
                            if (wid != null) {
                                wrappingId = wid;
                                break;
                            }
                        }
                    }

                    if (wrappingId != null) {
                        idsAndWrappers.put(id, wrappingId);
                    }
                }
            }
        }

        @Override
        public void startElement(String uri, String lName, String qName, Attributes attr) {
            if (rootTag == null) {
                rootTag = qName;
            }

            if ("fragment".equals(qName)) {
                // Let's ignore it for now
                idHierarchy.addFirst(null);
            }
            else if ("include".equals(qName)) {
                String id = null;
                String layout = null;
                final int attrCount = (attr != null) ? attr.getLength() : 0;
                for (int attrIndex = 0; attrIndex < attrCount && !(id != null && layout != null); attrIndex++) {
                    if ("android:id".equals(attr.getQName(attrIndex))) {
                        final String value = attr.getValue(attrIndex);
                        if (value.startsWith("@+id/")) {
                            id = value.substring(5);
                        }
                        else if (value.startsWith("@id/")) {
                            id = value.substring(4);
                        }
                    }
                    else if ("layout".equals(attr.getQName(attrIndex))) {
                        final String value = attr.getValue(attrIndex);
                        if (value.startsWith("@layout/")) {
                            layout = value.substring(8);
                        }
                    }
                }

                if (layout != null) {
                    if (foundLayouts.contains(layout)) {
                        foundMultipleTimesLayout.add(layout);
                    }
                    else {
                        foundLayouts.add(layout);
                    }

                    if (id != null) {
                        register(id, new LayoutType(layout));
                    }
                }
                idHierarchy.addFirst(id);
            }
            else {
                final int attrCount = (attr != null) ? attr.getLength() : 0;
                String id = null;
                for (int attrIndex = 0; attrIndex < attrCount; attrIndex++) {
                    final String value = attr.getValue(attrIndex);
                    if (value != null && value.startsWith("@string/")) {
                        final String stringName = value.substring(8);
                        if (knownPlaceholderStrings.contains(stringName)) {
                            throw new RuntimeException("Invalid string reference " + value + " in " + fileName + ". String requires placeholders.");
                        }
                    }

                    if ("android:id".equals(attr.getQName(attrIndex))) {
                        if (value.startsWith("@+id/")) {
                            id = value.substring(5);
                            register(id, new ViewType(qName));
                        }
                        else if (value.startsWith("@id/")) {
                            id = value.substring(4);
                            register(id, new ViewType(qName));
                        }
                    }
                }
                idHierarchy.addFirst(id);
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            idHierarchy.removeFirst();
        }
    }

    private static URL defineClassDirUrl(String directoryPath) {
        try {
            return new URL("file://" + directoryPath + "/");
        }
        catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    private static URL defineJarUrl(String jarFilePath) {
        try {
            return new URL("jar", "", "file:" + jarFilePath + "!/");
        }
        catch (MalformedURLException e) {
            throw new RuntimeException(e);
        }
    }

    private String tagNameToType(String tagName) {
        final String value = mImplicitTagNames.get(tagName);
        return (value == null)? tagName : value;
    }

    private String resolve(Map<String, ParserHandler> handlers, String handlerId, Map<String, String> typesResult, Map<String, String> wrapResult, String nowWrapping, Set<String> conflictingIds) {
        final ParserHandler handler = handlers.get(handlerId);
        final HashSet<String> includedLayouts = new HashSet<>();
        for (Map.Entry<String, Type> entry : handler.foundIdsAndTypes.entrySet()) {
            final String id = entry.getKey();
            if (entry.getValue() instanceof ViewType) {
                if (typesResult.containsKey(id)) {
                    typesResult.remove(id);
                    wrapResult.remove(id);
                    conflictingIds.add(id);
                }
                else if (!conflictingIds.contains(id)) {
                    typesResult.put(id, ((ViewType) entry.getValue()).typeName);
                    final String wrapping = handler.idsAndWrappers.getOrDefault(id, nowWrapping);
                    if (wrapping != null) {
                        wrapResult.put(id, wrapping);
                    }
                }
            }
            else {
                final String layoutName = ((LayoutType) entry.getValue()).layoutName;
                includedLayouts.add(layoutName);
                final String includeType = resolve(handlers, layoutName, typesResult, wrapResult, id, conflictingIds);

                if (typesResult.containsKey(id)) {
                    typesResult.remove(id);
                    wrapResult.remove(id);
                    conflictingIds.add(id);
                }
                else if (!conflictingIds.contains(id)) {
                    typesResult.put(id, includeType);
                    final String wrapping = handler.idsAndWrappers.getOrDefault(id, nowWrapping);
                    if (wrapping != null) {
                        wrapResult.put(id, wrapping);
                    }
                }
            }
        }

        for (String layoutName : handler.foundLayouts) {
            if (!includedLayouts.contains(layoutName)) {
                resolve(handlers, layoutName, typesResult, wrapResult, nowWrapping, conflictingIds);
            }
        }

        return handler.rootTag;
    }

    private void findInterfaceCandidates(File folder, HashSet<String> candidates, String packageName) {
        for (String fileName : folder.list()) {
            final File file = new File(folder, fileName);
            if (fileName.endsWith(".class") && !file.isDirectory()) {
                candidates.add(packageName + "." + fileName.substring(0, fileName.length() - 6));
            }
            else if (file.isDirectory()) {
                findInterfaceCandidates(file, candidates, (packageName == null)? fileName : packageName + "." + fileName);
            }
        }
    }

    private static final class InterfaceInfo {
        final List<String> extendingInterfaces;
        final Map<String, String> methodNameAndType;

        InterfaceInfo(List<String> extendingInterfaces, Map<String, String> methodNameAndType) {
            this.extendingInterfaces = extendingInterfaces;
            this.methodNameAndType = methodNameAndType;
        }
    }

    private boolean resolveInterface(String interfaceName, Map<String, InterfaceInfo> interfaceInfo, Map<String, String> result) {
        final InterfaceInfo info = interfaceInfo.get(interfaceName);
        for (String extending : info.extendingInterfaces) {
            if (!interfaceInfo.containsKey(extending) || !resolveInterface(extending, interfaceInfo, result)) {
                return false;
            }
        }
        result.putAll(info.methodNameAndType);

        return true;
    }

    private final HashMap<String, HashSet<String>> mCastable = new HashMap<>();

    private boolean canBeCasted(ClassLoader loader, String source, String target) {
        if (source.equals(target)) {
            return true;
        }
        else if ("java.lang.Object".equals(source)) {
            return false;
        }

        HashSet<String> castable = mCastable.get(source);
        if (castable == null) {
            castable = new HashSet<>();
            castable.add("java.lang.Object");

            final String knownCast = getKnownCasts().get().get(source);
            if (knownCast != null) {
                castable.add(knownCast);
                mCastable.put(source, castable);
            }
            else {
                try {
                    final Class<?> cls = loader.loadClass(source);
                    for (Class<?> i : cls.getInterfaces()) {
                        castable.add(i.getName());
                    }

                    final Class<?> superClass = cls.getSuperclass();
                    if (superClass != null) {
                        castable.add(superClass.getName());
                    }
                    mCastable.put(source, castable);
                }
                catch (ClassNotFoundException e) {
                    return false;
                }
            }
        }

        for (String newSource : castable) {
            if (canBeCasted(loader, newSource, target)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Generates all Java classes wrapping the defined layouts
     */
    @TaskAction
    public void createLayoutWrappers() {
        final File interfacesClasspath = getInterfacesClasspath().get().getAsFile();
        final HashSet<String> interfaceCandidates = new HashSet<>();
        findInterfaceCandidates(interfacesClasspath, interfaceCandidates, null);

        final ArrayList<URL> urlList = new ArrayList<>();
        for (File file : getBootClassPath().get()) {
            urlList.add(file.toString().endsWith(".jar")? defineJarUrl(file.toString()) : defineClassDirUrl(file.toString()));
        }

        final URL[] urls = urlList.toArray(new URL[0]);

        try {
            final InputDirClassLoader loader = new InputDirClassLoader(interfacesClasspath, new URLClassLoader(urls));
            final HashMap<String, InterfaceInfo> interfaceInfo = new HashMap<>();
            for (String candidate : interfaceCandidates) {
                final Class<?> cls = loader.loadClass(candidate);
                if (cls.isInterface()) {
                    final ArrayList<String> extendingInterfaces = new ArrayList<>();
                    for (Class<?> extending : cls.getInterfaces()) {
                        extendingInterfaces.add(extending.getName());
                    }

                    final HashMap<String, String> methodNameAndType = new HashMap<>();
                    boolean allGetters = true;
                    for (Method method : cls.getDeclaredMethods()) {
                        if (!method.isDefault()) {
                            if (method.getParameterCount() != 0) {
                                allGetters = false;
                            }
                            else {
                                methodNameAndType.put(method.getName(), method.getReturnType().getName());
                            }
                        }
                    }

                    if (allGetters) {
                        interfaceInfo.put(candidate, new InterfaceInfo(extendingInterfaces, methodNameAndType));
                    }
                }
            }

            final HashMap<String, Map<String, String>> resolvedInterfaces = new HashMap<>();
            for (String interfaceName : interfaceInfo.keySet()) {
                final HashMap<String, String> methodNameAndType = new HashMap<>();
                if (resolveInterface(interfaceName, interfaceInfo, methodNameAndType)) {
                    resolvedInterfaces.put(interfaceName, methodNameAndType);
                }
            }

            final File resourceDir = getResourcesDir().get().getAsFile();
            final ArrayList<String> variants = new ArrayList<>();
            boolean defaultLayoutFolderFound = false;
            for (String fileName : resourceDir.list()) {
                if (fileName.startsWith("layout")) {
                    final File dir = new File(resourceDir, fileName);
                    if (dir.isDirectory()) {
                        if ("layout".equals(fileName)) {
                            defaultLayoutFolderFound = true;
                        }
                        else if (fileName.startsWith("layout-")) {
                            variants.add(fileName.substring(7));
                        }
                    }
                }
            }

            if (!defaultLayoutFolderFound) {
                throw new RuntimeException("Unable to find subfolder 'layout' in " + resourceDir.toString());
            }

            final HashMap<String, ArrayList<String>> variantFileNames = new HashMap<>();
            for (String variantName : variants) {
                final File subfolder = new File(resourceDir, "layout-" + variantName);
                final ArrayList<String> fileNames = new ArrayList<>();
                for (String fileName : subfolder.list()) {
                    if (fileName.endsWith(".xml")) {
                        final int length = fileName.length();
                        fileNames.add(fileName.substring(0, length));
                    }
                }
                variantFileNames.put(variantName, fileNames);
            }

            final File defaultLayoutsDir = new File(resourceDir, "layout");
            final String packageName = getPackageName().get();
            final File outputDir = getOutputDir().get().getAsFile();
            File currentFile = outputDir;
            for (String split : packageName.split("\\.")) {
                currentFile = new File(currentFile, split);
            }

            final File packageFile = currentFile;
            packageFile.mkdirs();

            final Set<String> knownPlaceholderStrings = obtainKnownPlaceholderStrings(getResourcesDir().get().getAsFile());
            final SAXParserFactory saxParserFactory = SAXParserFactory.newInstance();
            final Map<String, ParserHandler> parseResults = new HashMap<>();
            for (String fileName : defaultLayoutsDir.list()) {
                if (fileName.endsWith(".xml")) {
                    final File file = new File(defaultLayoutsDir, fileName);
                    try (InputStream inStream = new FileInputStream(file)) {
                        final ParserHandler handler = new ParserHandler(fileName, knownPlaceholderStrings);
                        final SAXParser parser = saxParserFactory.newSAXParser();
                        parser.parse(inStream, handler);

                        if (!handler.conflictingIds.isEmpty()) {
                            throw new RuntimeException("Duplicated id " + handler.conflictingIds.stream().reduce("", (a, b) -> a + ", " + b) + " in " + file);
                        }

                        final int fileNameLength = fileName.length();
                        final String layoutName = fileName.substring(0, fileNameLength - 4);
                        parseResults.put(layoutName, handler);
                    }
                }
            }

            for (Map.Entry<String, ParserHandler> resultEntry : parseResults.entrySet()) {
                final ParserHandler handler = resultEntry.getValue();
                final HashMap<String, String> idsAndTypes = new HashMap<>();
                final HashMap<String, String> idsAndWrappers = new HashMap<>();
                final HashSet<String> conflictingIds = new HashSet<>();
                resolve(parseResults, resultEntry.getKey(), idsAndTypes, idsAndWrappers, null, conflictingIds);

                final HashMap<String, String> idsAndTypesToMatch = new HashMap<>(idsAndTypes);
                idsAndTypesToMatch.put("view", handler.rootTag);

                final HashSet<String> matchingInterfaces = new HashSet<>();
                for (String interfaceName : resolvedInterfaces.keySet()) {
                    final Map<String, String> interfMap = resolvedInterfaces.get(interfaceName);
                    boolean allMatching = true;
                    for (String methodName : interfMap.keySet()) {
                        if (!idsAndTypesToMatch.containsKey(methodName) || !canBeCasted(loader, tagNameToType(idsAndTypesToMatch.get(methodName)), interfMap.get(methodName))) {
                            allMatching = false;
                            break;
                        }
                    }

                    if (allMatching) {
                        matchingInterfaces.add(interfaceName);
                    }
                }

                final String rootType = tagNameToType(handler.rootTag);
                final String layoutName = resultEntry.getKey();
                final String classSimpleName = fromSnakeToPascalCase(layoutName) + "Layout";

                final String ensureNonNullFunction = getEnsureNonNullFunction().get();
                final int ensureNonNullFunctionLastDotIndex = ensureNonNullFunction.lastIndexOf('.');
                final String ensureNonNullFunctionName = (ensureNonNullFunctionLastDotIndex >= 0)? ensureNonNullFunction.substring(ensureNonNullFunctionLastDotIndex + 1) : ensureNonNullFunction;

                final String androidResourceClassName = getAndroidResourceClass().get();
                if (androidResourceClassName.length() <= 2 && !androidResourceClassName.endsWith(".R")) {
                    throw new UnsupportedOperationException("Android resource class is expected to be a full qualified class reference where the class is called 'R'. But it was " + androidResourceClassName);
                }

                final File outFile = new File(packageFile, classSimpleName + ".java");
                try (PrintWriter writer = new PrintWriter(new FileOutputStream(outFile), true)) {
                    writer.println("// This file is autogenerated. Please do not edit it.");
                    writer.println("package " + packageName + ";");
                    writer.println();
                    writer.println("import " + getLayoutInterface().get() + ";");
                    writer.println("import " + androidResourceClassName + ";");
                    writer.println();
                    writer.println("import android.content.Context;");
                    writer.println("import android.view.ContextThemeWrapper;");
                    writer.println("import android.view.LayoutInflater;");
                    writer.println("import android.view.ViewGroup;");
                    writer.println();
                    writer.println("import androidx.annotation.NonNull;");
                    writer.println("import androidx.annotation.StyleRes;");
                    if (ensureNonNullFunctionLastDotIndex >= 0) {
                        writer.println();
                        writer.println("import static " + ensureNonNullFunction + ";");
                    }
                    writer.println();

                    final String extensions;
                    if (matchingInterfaces.isEmpty()) {
                        extensions = "";
                    }
                    else {
                        StringBuilder sb = null;
                        for (String interfName : matchingInterfaces) {
                            if (sb == null) {
                                sb = new StringBuilder(" implements ");
                            }
                            else {
                                sb.append(", ");
                            }
                            sb.append(interfName);
                        }
                        extensions = sb.toString();
                    }

                    writer.println("public final class " + classSimpleName + extensions + " {");
                    writer.println();

                    writer.println("    @NonNull");
                    writer.println("    private final " + rootType + " mRoot;");
                    for (Map.Entry<String, String> entry : idsAndTypes.entrySet()) {
                        writer.println("    private " + tagNameToType(entry.getValue()) + ' ' + entry.getKey() + ";");
                    }

                    writer.println();
                    writer.println("    private " + classSimpleName + "(@NonNull " + rootType + " root) {");
                    writer.println("        " + ensureNonNullFunctionName + "(root);");
                    writer.println("        mRoot = root;");
                    writer.println("    }");

                    writer.println();
                    writer.println("    @NonNull");
                    writer.println("    public " + rootType + " view() {");
                    writer.println("        return mRoot;");
                    writer.println("    }");

                    for (Map.Entry<String, String> entry : idsAndTypes.entrySet()) {
                        writer.println();
                        writer.println("    @NonNull");
                        writer.println("    public " + tagNameToType(entry.getValue()) + ' ' + entry.getKey() + "() {");
                        writer.println("        if (" + entry.getKey() + " == null) {");
                        final String wrapping = idsAndWrappers.get(entry.getKey());
                        writer.println("            " + entry.getKey() + " = " + ((wrapping != null)? wrapping + "()" : "mRoot") + ".findViewById(R.id." + entry.getKey() + ");");
                        writer.println("        }");
                        writer.println();
                        writer.println("        return " + entry.getKey() + ";");
                        writer.println("    }");
                    }

                    writer.println();
                    writer.println("    @NonNull");
                    writer.println("    public static " + classSimpleName + " attachWithLayoutInflater(@NonNull LayoutInflater inflater, @NonNull ViewGroup parent) {");
                    writer.println("        final int position = parent.getChildCount();");
                    writer.println("        inflater.inflate(R.layout." + layoutName + ", parent, true);");
                    writer.println("        return new " + classSimpleName + "((" + rootType + ") parent.getChildAt(position));");
                    writer.println("    }");
                    writer.println();
                    writer.println("    @NonNull");
                    writer.println("    public static " + classSimpleName + " createWithLayoutInflater(@NonNull LayoutInflater inflater, ViewGroup parent) {");
                    writer.println("        return new " + classSimpleName + "((" + rootType + ") inflater.inflate(R.layout." + layoutName + ", parent, false));");
                    writer.println("    }");
                    writer.println();
                    writer.println("    @NonNull");
                    writer.println("    public static " + classSimpleName + " create(@NonNull ViewGroup parent) {");
                    writer.println("        return createWithLayoutInflater(LayoutInflater.from(parent.getContext()), parent);");
                    writer.println("    }");
                    writer.println();
                    writer.println("    @NonNull");
                    writer.println("    public static " + classSimpleName + " createWithTheme(@StyleRes int styleResId, @NonNull ViewGroup parent) {");
                    writer.println("        final Context context = parent.getContext();");
                    writer.println("        final Context themedContext = new ContextThemeWrapper(context, styleResId);");
                    writer.println("        return createWithLayoutInflater(LayoutInflater.from(themedContext), parent);");
                    writer.println("    }");
                    writer.println("}");
                }
            }
        }
        catch (ClassNotFoundException | IOException | ParserConfigurationException | SAXException e) {
            throw new UnsupportedOperationException("Failure on creating layout wrappers", e);
        }
    }
}
