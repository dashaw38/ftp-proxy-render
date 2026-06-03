package org.example.controller;

import org.apache.commons.io.FileUtils;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@RestController
public class FtpProxyController {

    @Value("${converter.temp.dir:/tmp/heic-convert}")
    private String tempDir;


    @PostMapping("/upload")
    public ResponseEntity<String> uploadToFtp(
            @RequestParam String host,
            @RequestParam(defaultValue = "21") int port,
            @RequestParam String username,
            @RequestParam String password,
            @RequestParam String remotePath,
            @RequestParam MultipartFile file) {

        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body("Файл не передан");
        }
        FTPClient ftp = new FTPClient();
        try {
            ftp.connect(host, port);
            ftp.login(username, password);
            ftp.setFileType(FTP.BINARY_FILE_TYPE);
            ftp.enterLocalPassiveMode();

            InputStream inputStream = file.getInputStream();
            boolean success = ftp.storeFile(remotePath, inputStream);
            inputStream.close();

            if (success) {
                return ResponseEntity.ok("Файл успешно загружен на FTP");
            } else {
                return ResponseEntity.status(500).body("Ошибка загрузки файла на FTP");
            }
        } catch (IOException e) {
            return ResponseEntity.status(500).body("Ошибка подключения к FTP: " + e.getMessage());
        } finally {
            try {
                if (ftp.isConnected()) {
                    ftp.logout();
                    ftp.disconnect();
                }
            } catch (IOException ignored) {
            }
        }
    }

    @PostMapping(value = "/convert-heic", consumes = {"application/octet-stream", "image/heic", "image/*"})
    public ResponseEntity<byte[]> convertHeicToJpeg(@RequestBody byte[] fileBytes) {
        try {
            if (fileBytes == null || fileBytes.length == 0) {
                return ResponseEntity.badRequest().body("Empty file".getBytes());
            }

            Path tempPath = Paths.get(tempDir);
            Files.createDirectories(tempPath);
            String id = UUID.randomUUID().toString();
            File inputHeic = tempPath.resolve(id + ".heic").toFile();
            File outputJpeg = tempPath.resolve(id + ".jpg").toFile();

            FileUtils.writeByteArrayToFile(inputHeic, fileBytes);

            boolean converted = tryConvertWithLibheif(inputHeic, outputJpeg)
                    || tryConvertWithFfmpeg(inputHeic, outputJpeg)
                    || tryConvertWithVips(inputHeic, outputJpeg)
                    || tryConvertWithImageMagick(inputHeic, outputJpeg);

            if (!converted || !outputJpeg.exists() || outputJpeg.length() == 0) {
                FileUtils.deleteQuietly(inputHeic);
                return ResponseEntity.internalServerError()
                        .body("Conversion failed: no local converter succeeded".getBytes());
            }

            byte[] jpegBytes = FileUtils.readFileToByteArray(outputJpeg);
            FileUtils.deleteQuietly(inputHeic);
            FileUtils.deleteQuietly(outputJpeg);

            return ResponseEntity.ok().contentType(MediaType.IMAGE_JPEG).body(jpegBytes);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(("Conversion error: " + e.getMessage()).getBytes());
        }
    }

    private boolean tryConvertWithLibheif(File in, File out) {
        return runCommand("heif-convert", "-q", "90", in.getAbsolutePath(), out.getAbsolutePath());
    }

    private boolean tryConvertWithFfmpeg(File in, File out) {
        return runCommand("ffmpeg", "-hide_banner", "-loglevel", "error",
                "-i", in.getAbsolutePath(), "-q:v", "2", "-y", out.getAbsolutePath());
    }

    private boolean tryConvertWithVips(File in, File out) {
        // [Q=90] задаёт качество JPEG в синтаксисе vips
        return runCommand("vips", "copy", in.getAbsolutePath(), out.getAbsolutePath() + "[Q=90]");
    }

    private boolean tryConvertWithImageMagick(File in, File out) {
        return runCommand("convert", in.getAbsolutePath() + "[0]", "-quality", "90", "-strip", out.getAbsolutePath());
    }

    private boolean runCommand(String... cmd) {
        try {
            ProcessBuilder check = new ProcessBuilder("which", cmd[0]);
            if (check.start().waitFor() != 0) return false;

            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();

            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) {
                    System.out.println("[" + cmd[0] + "] " + line);
                }
            }
            int exit = p.waitFor();
            boolean success = exit == 0;
            if (success) System.out.println("✅ " + cmd[0] + " succeeded");
            return success;
        } catch (Exception e) {
            System.err.println("[" + cmd[0] + "] failed: " + e.getMessage());
            return false;
        }
    }
}
