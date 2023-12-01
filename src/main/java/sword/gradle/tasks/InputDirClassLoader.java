package sword.gradle.tasks;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.IOException;

final class InputDirClassLoader extends ClassLoader {

    private final File mDirectory;

    InputDirClassLoader(File directory, ClassLoader parent) {
        super(parent);
        mDirectory = directory;
    }

    private String findFile(String className) throws FileNotFoundException {
        final String suffix = File.separator + className.replace('.', File.separatorChar) + ".class";
        final String file = mDirectory.toString() + suffix;
        if (new File(file).exists()) {
            return file;
        }

        throw new FileNotFoundException("File not found for class " + className);
    }

    private byte[] loadClassFromFile(String className) throws IOException {
        final String fileName = findFile(className);

        try (FileInputStream inStream = new FileInputStream(fileName)) {
            final ByteArrayOutputStream byteStream = new ByteArrayOutputStream();

            int nextValue = 0;
            while ((nextValue = inStream.read()) != -1) {
                byteStream.write(nextValue);
            }

            return byteStream.toByteArray();
        }
    }

    @Override
    public Class<?> findClass(String name) throws ClassNotFoundException {
        try {
            final byte[] buffer = loadClassFromFile(name);
            return defineClass(name, buffer, 0, buffer.length);
        }
        catch (IOException e) {
            throw new ClassNotFoundException("Unable to find class " + name, e);
        }
    }
}
