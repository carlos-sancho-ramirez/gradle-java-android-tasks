package sword.gradle.tasks;

import org.xml.sax.Attributes;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParser;
import javax.xml.parsers.SAXParserFactory;

final class StringResourceUtils {

    private static int findRequiredTypedParamsCount(String text) {
        int result = 0;
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
                    result++;
                    readingPlaceholder = false;
                    dollarFound = false;
                }
                else if (ch == 'd') {
                    result++;
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
        final Map<String, String> result;

        String rootTag;
        boolean validRootTag;
        String definingStringName;
        StringBuilder definingStringText;

        ParserHandler(String fileName, Map<String, String> result) {
            this.fileName = fileName;
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
                        definingStringName = attr.getValue(attrIndex);
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

                if (result.put(name, text) != null) {
                    throw new RuntimeException("Duplicated string name '" + name + "' at " + fileName);
                }
            }
        }
    }

    public static Set<String> obtainKnownPlaceholderStrings(File resourceDir) throws IOException, ParserConfigurationException, SAXException {
        final Map<String, String> defaultResults = new HashMap<>();
        final SAXParserFactory saxParserFactory = SAXParserFactory.newInstance();

        if (resourceDir.isDirectory()) {
            for (String subDirName : resourceDir.list()) {
                if ("values".equals(subDirName)) {
                    final File defaultValuesDir = new File(resourceDir, subDirName);
                    if (defaultValuesDir.isDirectory()) {
                        for (String fileName : defaultValuesDir.list()) {
                            if (fileName.endsWith(".xml")) {
                                final File file = new File(defaultValuesDir, fileName);
                                try (InputStream inStream = new FileInputStream(file)) {
                                    final ParserHandler handler = new ParserHandler(fileName, defaultResults);
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

        final Set<String> knownPlaceholderStrings = new HashSet<>();
        for (String name : defaultResults.keySet()) {
            if (findRequiredTypedParamsCount(defaultResults.get(name)) > 0) {
                knownPlaceholderStrings.add(name);
            }
        }

        return knownPlaceholderStrings;
    }

    private StringResourceUtils() {
    }
}
