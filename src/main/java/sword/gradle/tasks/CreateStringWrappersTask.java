package sword.gradle.tasks;

import org.gradle.api.DefaultTask;
import org.gradle.api.file.ConfigurableFileCollection;
import org.gradle.api.file.DirectoryProperty;
import org.gradle.api.file.FileCollection;
import org.gradle.api.provider.Property;
import org.gradle.api.tasks.Input;
import org.gradle.api.tasks.InputFiles;
import org.gradle.api.tasks.OutputDirectory;
import org.gradle.api.tasks.TaskAction;
import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintWriter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

public abstract class CreateStringWrappersTask extends DefaultTask {

    private ConfigurableFileCollection resourceDirs = getProject().getObjects().fileCollection();

    @Input
    public abstract Property<String> getPackageName();

    @Input
    public abstract Property<String> getContextInterface();

    @Input
    public abstract Property<String> getAndroidResourceClass();

    @Input
    public abstract Property<String> getSimpleClassName();

    /**
     * Returns the directories for the resources to be wrapped.
     */
    @InputFiles
    public FileCollection getResourceDirs() {
        return resourceDirs;
    }

    /**
     * Set the directories for the resources to be wrapped.
     */
    public void setResourceDirs(ArrayList files) {
        resourceDirs = resourceDirs.from(files);
    }

    @OutputDirectory
    public abstract DirectoryProperty getOutputDir();

    private static List<String> findRequiredTypedParams(String text) {
        final ArrayList<String> result = new ArrayList<>();
        final int length = text.length();
        boolean readingPlaceholder = false;
        boolean dollarFound = false;
        boolean lastWasBackslash = false;
        Integer number = null;
        for (int i = 0; i < length; i++) {
            final char ch = text.charAt(i);
            if (lastWasBackslash) {
                if (ch != 'n' && ch != '\'' && ch != '"' && ch != '@') {
                    throw new RuntimeException("Found unexpected escaped character '\\" + ch + "' in text " + text);
                }
                lastWasBackslash = false;
            }
            else if (readingPlaceholder) {
                if (ch == 's') {
                    // TODO: We should check any potential value in cypher
                    result.add("String");
                    readingPlaceholder = false;
                    dollarFound = false;
                }
                else if (ch == 'd') {
                    // TODO: We should check any potential value in cypher
                    result.add("int");
                    readingPlaceholder = false;
                    dollarFound = false;
                }
                else if (ch >= '0' && ch <= '9') {
                    if (dollarFound) {
                        throw new UnsupportedOperationException("Unable to include numbers after dollar in text " + text);
                    }

                    final int cypher = ch - '0';
                    number = (number != null)? number * 10 + cypher : cypher;
                }
                else if (ch == '$') {
                    if (dollarFound) {
                        throw new RuntimeException("Multiple dollar symbol found in the same placeholder in text " + text);
                    }
                    dollarFound = true;
                }
                else {
                    throw new UnsupportedOperationException("Unexpected character '" + ch + "' in placeholder in text " + text);
                }
            }
            else if (ch == '%') {
                readingPlaceholder = true;
            }
            else if (ch == '\\') {
                lastWasBackslash = true;
            }
            else if (ch == '\'' || ch == '"') {
                throw new RuntimeException("Found unexpected character " + ch + " in text " + text + ". It needs to be escaped.");
            }
            else if (i == 0 && ch == '@' && !text.startsWith("@string/")) {
                throw new RuntimeException("Found unexpected character @ at the beginning of text " + text + ". @ is reserved to reference other resources like '@string/abc'. If @ is expected to be displayed on the screen and it is in the very first position of the string, then it needs to be escaped '\\@'.");
            }
        }

        return result;
    }

    private static final class ParserHandler extends DefaultHandler {

        final String fileName;
        final Map<String, String> defaultResults;
        final Map<String, String> result;

        String rootTag;
        boolean validRootTag;
        String definingStringName;
        StringBuilder definingStringText;

        ParserHandler(String fileName, Map<String, String> defaultResults, Map<String, String> result) {
            this.fileName = fileName;
            this.defaultResults = defaultResults;
            this.result = result;
        }

        @Override
        public void startElement(String uri, String lName, String qName, Attributes attr) {
            if (rootTag == null) {
                rootTag = qName;
                if ("resources".equals(qName)) {
                    validRootTag = true;
                }
            }
            else if (validRootTag && "string".equals(qName)) {
                if (definingStringName != null) {
                    throw new RuntimeException("'string' tag defined inside another string tag at " + fileName);
                }

                final int attrCount = (attr != null) ? attr.getLength() : 0;
                for (int attrIndex = 0; attrIndex < attrCount; attrIndex++) {
                    if ("name".equals(attr.getQName(attrIndex))) {
                        final String name = attr.getValue(attrIndex);
                        if (defaultResults != null && !defaultResults.containsKey(name)) {
                            throw new RuntimeException("Found string with name '" + name + "' at " + fileName + ", but there is no default string with that name");
                        }

                        definingStringName = name;
                        break;
                    }
                }

                if (definingStringName == null) {
                    throw new RuntimeException("Found 'string' resource without 'name' attribute at " + fileName);
                }
            }
            else if (validRootTag && definingStringName != null) {
                // According to the Android string resource documentation, there are other HTML tags allowed here.
                // TODO: Include all allowed tags here, when required
                if (!"i".equals(qName) && !"b".equals(qName) && !"u".equals(qName)) {
                    throw new RuntimeException("Found tag '" + qName + "' inside 'string' tag with name '" + definingStringName + "' at " + fileName);
                }
            }
        }

        @Override
        public void characters(char[] ch, int start, int length) {
            if (validRootTag && definingStringName != null) {
                if (definingStringText == null) {
                    definingStringText = new StringBuilder();
                }

                for (int i = 0; i < length; i++) {
                    definingStringText.append(ch[start + i]);
                }
            }
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            if (validRootTag && "string".equals(qName)) {
                if (definingStringName == null) {
                    throw new RuntimeException("Found closing tag for 'string' without an starting matching one at " + fileName);
                }

                final String name = definingStringName;
                final String text = (definingStringText == null)? "" : definingStringText.toString();
                definingStringName = null;
                definingStringText = null;

                if (defaultResults != null) {
                    final List<String> defaultRequiredParams = findRequiredTypedParams(defaultResults.get(name));
                    final List<String> actualRequiredParams = findRequiredTypedParams(text);
                    final int defaultRequiredParamsCount = defaultRequiredParams.size();
                    final int actualRequiredParamsCount = actualRequiredParams.size();
                    if (defaultRequiredParamsCount != actualRequiredParamsCount) {
                        throw new RuntimeException("String with name '" + name + "' requires " + actualRequiredParamsCount + " parameters at " + fileName + ", but the default requires " + defaultRequiredParamsCount);
                    }

                    for (int i = 0; i < defaultRequiredParamsCount; i++) {
                        if (!defaultRequiredParams.get(i).equals(actualRequiredParams.get(i))) {
                            throw new RuntimeException("String with name '" + name + "' at " + fileName + " does not match with its default placeholder types");
                        }
                    }
                }

                if (result.put(name, text) != null) {
                    throw new RuntimeException("Duplicated string name '" + name + "' at " + fileName);
                }
            }
        }
    }

    /**
     * Create the Java class wrapping all text resources
     */
    @TaskAction
    public void createStringWrappers() {
        try {
            final Map<String, String> defaultResults = new HashMap<>();
            final SAXParserFactory saxParserFactory = SAXParserFactory.newInstance();

            for (File resourceDir : resourceDirs) {
                if (resourceDir.isDirectory()) {
                    for (String subDirName : resourceDir.list()) {
                        if ("values".equals(subDirName)) {
                            final File defaultValuesDir = new File(resourceDir, subDirName);
                            if (defaultValuesDir.isDirectory()) {
                                for (String fileName : defaultValuesDir.list()) {
                                    if (fileName.endsWith(".xml")) {
                                        final File file = new File(defaultValuesDir, fileName);
                                        try (InputStream inStream = new FileInputStream(file)) {
                                            final ParserHandler handler = new ParserHandler(fileName, null, defaultResults);
                                            final SAXParser parser = saxParserFactory.newSAXParser();
                                            parser.parse(inStream, handler);
                                        }
                                    }
                                }

                                break;
                            }
                        }
                    }
                }
            }

            final HashMap<String, Map<String, String>> variantResults = new HashMap<>();
            for (File resourceDir : resourceDirs) {
                if (resourceDir.isDirectory()) {
                    for (String subDirName : resourceDir.list()) {
                        if (subDirName.startsWith("values-")) {
                            final File subDir = new File(resourceDir, subDirName);
                            if (subDir.isDirectory()) {
                                final String variantName = subDirName.substring(7);

                                Map<String, String> results = variantResults.get(variantName);
                                if (results == null) {
                                    results = new HashMap<>();
                                }

                                for (String fileName : subDir.list()) {
                                    if (fileName.endsWith(".xml")) {
                                        final File file = new File(subDir, fileName);
                                        try (InputStream inStream = new FileInputStream(file)) {
                                            final ParserHandler handler = new ParserHandler(fileName, defaultResults, results);
                                            final SAXParser parser = saxParserFactory.newSAXParser();
                                            parser.parse(inStream, handler);
                                        }
                                    }
                                }

                                if (results.size() > 0) {
                                    variantResults.put(variantName, results);
                                }
                            }
                        }
                    }
                }
            }

            final String packageName = getPackageName().get();
            final String contextInterfaceClassName = getContextInterface().get();
            final int contextInterfaceLastDotIndex = contextInterfaceClassName.lastIndexOf('.');
            final String contextInterfaceSimpleClassName = (contextInterfaceLastDotIndex >= 0)? contextInterfaceClassName.substring(contextInterfaceLastDotIndex + 1) : contextInterfaceClassName;
            final String androidResourceClassName = getAndroidResourceClass().get();
            if (androidResourceClassName.length() <= 2 && !androidResourceClassName.endsWith(".R")) {
                throw new UnsupportedOperationException("Android resource class is expected to be a full qualified class reference where the class is called 'R'. But it was " + androidResourceClassName);
            }

            final File outputDir = getOutputDir().get().getAsFile();
            File currentFile = outputDir;
            for (String split : packageName.split("\\.")) {
                currentFile = new File(currentFile, split);
            }

            final File packageFile = currentFile;
            packageFile.mkdirs();

            final String classSimpleName = getSimpleClassName().get();
            final File outFile = new File(packageFile, classSimpleName + ".java");
            try (PrintWriter writer = new PrintWriter(new FileOutputStream(outFile), true)) {
                writer.println("// This file is autogenerated. Please do not edit it.");
                writer.println("package " + packageName + ";");
                writer.println();
                writer.println("import " + androidResourceClassName + ";");
                if (contextInterfaceLastDotIndex >= 0) {
                    writer.println();
                    writer.println("import " + contextInterfaceClassName + ";");
                }
                writer.println();
                writer.println("import androidx.annotation.NonNull;");
                writer.println();
                writer.println("public final class " + classSimpleName + " {");

                for (String name : defaultResults.keySet()) {
                    writer.println();
                    writer.println("    @NonNull");
                    final List<String> requiredParams = findRequiredTypedParams(defaultResults.get(name));
                    final int requiredParamsCount = requiredParams.size();

                    String methodSignature = "    public static String " + name + "(@NonNull " + contextInterfaceSimpleClassName + " context";
                    for (int i = 0; i < requiredParamsCount; i++) {
                        methodSignature += ", " + requiredParams.get(i) + " arg" + i;
                    }
                    writer.println(methodSignature + ") {");

                    String methodBody = "        return context.getString(R.string." + name;
                    for (int i = 0; i < requiredParamsCount; i++) {
                        methodBody += ", arg" + i;
                    }
                    writer.println(methodBody + ");");
                    writer.println("    }");
                }

                writer.println();
                writer.println("    private " + classSimpleName + "() {");
                writer.println("    }");
                writer.println("}");
            }
        }
        catch (IOException | ParserConfigurationException | SAXException e) {
            throw new UnsupportedOperationException("Failure on creating string wrappers", e);
        }
    }
}
