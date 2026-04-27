import java.io.*;
import java.net.*;
import java.util.zip.*;

public class GetWrapper {
    public static void main(String[] args) throws Exception {
        String jarUrl = "https://services.gradle.org/distributions/gradle-7.5-bin.zip";
        String outDir = args[0];
        
        System.out.println("Connecting to " + jarUrl);
        HttpURLConnection conn = (HttpURLConnection) new URL(jarUrl).openConnection();
        conn.setConnectTimeout(15000);
        conn.setReadTimeout(60000);
        
        // Just need the wrapper jar from the zip
        ZipInputStream zis = new ZipInputStream(conn.getInputStream());
        ZipEntry entry;
        while ((entry = zis.getNextEntry()) != null) {
            if (entry.getName().contains("gradle-launcher")) {
                System.out.println("Found: " + entry.getName());
                File outFile = new File(outDir, "gradle-wrapper.jar");
                FileOutputStream fos = new FileOutputStream(outFile);
                byte[] buf = new byte[8192];
                int len;
                while ((len = zis.read(buf)) > 0) fos.write(buf, 0, len);
                fos.close();
                System.out.println("Saved to " + outFile.getAbsolutePath());
                break;
            }
        }
        zis.close();
    }
}
