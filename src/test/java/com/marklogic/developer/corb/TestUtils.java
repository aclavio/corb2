/*
 * Utility methods used in several of the test classes to facilitate the creation
 * and destruction of files, system properties, etc.
 */
package com.marklogic.developer.corb;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.PrintWriter;

/**
 *
 * @author mhansen
 */
public class TestUtils {

    public static void clearSystemProperties() {
        System.clearProperty("DECRYPTER");
        System.clearProperty("URIS-MODULE");
        System.clearProperty("OPTIONS-FILE");
        System.clearProperty("XCC-CONNECTION-URI");
        System.clearProperty("COLLECTION-NAME");
        System.clearProperty("XQUERY-MODULE");
        System.clearProperty("THREAD-COUNT");
        System.clearProperty("MODULE-ROOT");
        System.clearProperty("MODULES-DATABASE");
        System.clearProperty("INSTALL");
        System.clearProperty("PRIVATE-KEY-FILE");
        System.clearProperty("PROCESS-TASK");
        System.clearProperty("PRE-BATCH-MODULE");
        System.clearProperty("PRE-BATCH-TASK");
        System.clearProperty("POST-BATCH-MODULE");
        System.clearProperty("POST-BATCH-TASK");
        System.clearProperty("EXPORT-FILE-DIR");
        System.clearProperty("EXPORT-FILE-NAME");
        System.clearProperty("URIS-FILE");
        // TODO consider looking for any properties starting with, to ensure they all get cleared
        System.clearProperty("XQUERY-MODULE.foo");
        System.clearProperty("PROCESS-MODULE.foo");
        System.clearProperty("EXPORT_FILE_AS_ZIP");
    }
    
    public static void clearFile(File file) {
        PrintWriter pw = null;
        try {
            pw = new PrintWriter(file);
        } catch (FileNotFoundException e) {
            e.printStackTrace();
        }
        if (pw != null) {
            pw.close();
        }
    }

    public static boolean deleteDir(File dir) {
        if (dir.exists()) {
            for (File file : dir.listFiles()) {
                if (file.isDirectory()) {
                    deleteDir(file);
                } else {
                    file.delete();
                }
            }
        }
        return dir.delete();
    }

    //TODO: remove this, and upgrade code to use Files.createTempDirectory() when we upgrade to a JRE >= 1.7
    public static File createTempDirectory() throws IOException {
        final File temp;
        temp = File.createTempFile("temp", Long.toString(System.nanoTime()));
        if (!(temp.delete())) {
            throw new IOException("Could not delete temp file: " + temp.getAbsolutePath());
        }
        if (!(temp.mkdir())) {
            throw new IOException("Could not create temp directory: " + temp.getAbsolutePath());
        }
        return temp;
    }
    
}