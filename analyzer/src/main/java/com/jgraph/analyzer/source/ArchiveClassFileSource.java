package com.jgraph.analyzer.source;

import java.io.BufferedInputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Objects;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;

/**
 * The class files of one archive, {@code .jar}, {@code .war} or {@code .ear}, and of the archives nested in it
 * (ADR-0012): {@code BOOT-INF/lib} of a Spring Boot fat jar, {@code WEB-INF/lib} of a WAR, the modules and
 * {@code lib/} of an EAR.
 * <ul>
 *   <li>Every entry ending in {@code .class} is a class file, at any depth of folders: the classes of a fat jar live in
 *       {@code BOOT-INF/classes/}. {@code module-info.class} and {@code package-info.class} are not classes.</li>
 *   <li>Every entry ending in {@code .jar} or {@code .war} is an archive, read in place as a stream: nothing is
 *       extracted to disk.</li>
 *   <li>{@code META-INF/versions/**} is skipped: in a multi-release jar it holds copies of classes compiled for newer
 *       Java versions, and the base version, at the root, is the one analyzed.</li>
 *   <li>Entries come in the order they are stored, each nested archive where it is stored: the same input always
 *       gives the same order, which the "first one wins" rule for duplicates relies on.</li>
 * </ul>
 */
public final class ArchiveClassFileSource implements ClassFileSource {

    /**
     * An EAR holding a WAR holding jars is two levels deep; a fat jar inside an EAR one more. Deeper nesting is not a
     * real application, and reading it is refused rather than followed without end.
     */
    static final int MAX_NESTING = 4;

    private static final String SEPARATOR = "!/";
    private static final String MULTI_RELEASE = "META-INF/versions/";

    private final Path archive;

    public ArchiveClassFileSource(Path archive) {
        this.archive = Objects.requireNonNull(archive, "archive");
    }

    /**
     * @throws IOException if the archive, or one nested in it, cannot be read, is not an archive, or is nested too deep
     */
    @Override
    public void forEach(Consumer consumer) throws IOException {
        Objects.requireNonNull(consumer, "consumer");
        String origin = archive.getFileName().toString();
        try (InputStream file = new BufferedInputStream(Files.newInputStream(archive));
             ZipInputStream zip = new ZipInputStream(file)) {
            if (walk(zip, origin, 0, consumer) == 0) {
                // ZipInputStream finds no entry, without failing, in a file that is not a zip at all
                throw new IOException("Not a jar, war or ear archive, or an empty one: " + archive);
            }
        }
    }

    /** @return how many entries the archive has, class files or not */
    private static int walk(ZipInputStream zip, String archiveOrigin, int nesting, Consumer consumer)
            throws IOException {
        int entries = 0;
        try {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries++;
                String name = entry.getName();
                if (entry.isDirectory() || name.startsWith(MULTI_RELEASE)) {
                    continue;
                }
                String origin = archiveOrigin + SEPARATOR + name;
                if (isClassFile(name)) {
                    // Reads the current entry only: ZipInputStream returns its end as the end of the stream
                    consumer.accept(new ClassFile(origin, zip.readAllBytes()));
                } else if (isArchive(name)) {
                    readNested(zip, origin, nesting + 1, consumer);
                }
            }
        } catch (ZipException e) {
            throw new IOException("Cannot read the archive " + archiveOrigin + ": " + e.getMessage(), e);
        }
        return entries;
    }

    /** The current entry of {@code outer} is itself an archive: reads it as a zip of its own, from the same stream. */
    private static void readNested(ZipInputStream outer, String origin, int nesting, Consumer consumer)
            throws IOException {
        if (nesting > MAX_NESTING) {
            throw new IOException("Archives nested more than " + MAX_NESTING + " levels deep: " + origin);
        }
        // Closing the nested stream releases its inflater, but must not close the outer stream, still being read
        try (ZipInputStream nested = new ZipInputStream(new NonClosingInputStream(outer))) {
            walk(nested, origin, nesting, consumer);
        }
        // The rest of the entry, if the nested reader stopped before its end, is skipped by outer.getNextEntry()
    }

    private static boolean isClassFile(String name) {
        if (!name.endsWith(".class")) {
            return false;
        }
        String fileName = name.substring(name.lastIndexOf('/') + 1);
        return !fileName.equals("module-info.class") && !fileName.equals("package-info.class");
    }

    private static boolean isArchive(String name) {
        String lowerCase = name.toLowerCase(Locale.ROOT);
        return lowerCase.endsWith(".jar") || lowerCase.endsWith(".war");
    }

    /** An input stream whose {@code close} does nothing: the stream it wraps belongs to someone else. */
    private static final class NonClosingInputStream extends FilterInputStream {

        NonClosingInputStream(InputStream in) {
            super(in);
        }

        @Override
        public void close() {
            // Intentionally empty: the outer archive is closed by whoever opened it
        }
    }
}
