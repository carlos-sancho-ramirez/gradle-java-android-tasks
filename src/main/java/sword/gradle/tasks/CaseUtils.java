package sword.gradle.tasks;

final class CaseUtils {

    static String fromSnakeToPascalCase(String name) {
        final StringBuilder sb = new StringBuilder();
        final int length = name.length();
        boolean underscorePresent = false;
        for (int i = 0; i < length; i++) {
            final char ch = name.charAt(i);
            if (ch == '_') {
                underscorePresent = true;
            }
            else if ((underscorePresent || i == 0) && ch >= 'a' && ch <= 'z') {
                sb.append((char) (ch - 0x20));
                underscorePresent = false;
            }
            else {
                sb.append(ch);
                underscorePresent = false;
            }
        }

        return sb.toString();
    }

    private CaseUtils() {
    }
}
