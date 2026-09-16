package com.localmanga.shelf;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.assertThrows;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

public final class ArchiveExtractorTest {
    @Rule public final TemporaryFolder temporary = new TemporaryFolder();

    @Test public void readsNonImagesForLimitsButOnlyWritesImages() throws Exception {
        byte[] archive = archive(new Entry("notes.txt", "notes"), new Entry("chapter/001.jpg", "image"));
        File output = temporary.newFolder("comic");

        int images = ArchiveExtractor.extract(new ByteArrayInputStream(archive), output, null, file -> true);

        assertEquals(1, images);
        assertFalse(new File(output, "notes.txt").exists());
        assertTrue(new File(output, "chapter/001.jpg").isFile());
    }

    @Test public void rejectsAndRemovesFileWhenHeaderValidationFails() throws Exception {
        byte[] archive = archive(new Entry("001.jpg", "not an image"));
        File output = temporary.newFolder("invalid");

        assertThrows(IOException.class, () -> ArchiveExtractor.extract(
                new ByteArrayInputStream(archive), output, null, file -> false));
        assertFalse(new File(output, "001.jpg").exists());
    }

    private static byte[] archive(Entry... entries) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
            for (Entry entry : entries) {
                zip.putNextEntry(new ZipEntry(entry.name));
                zip.write(entry.value.getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return bytes.toByteArray();
    }

    private static final class Entry {
        final String name;
        final String value;

        Entry(String name, String value) { this.name = name; this.value = value; }
    }
}
