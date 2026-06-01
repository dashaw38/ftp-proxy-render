package org.example.controller;

import org.apache.commons.io.FileUtils;
import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
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

    @PostMapping(value = "/convert-heic", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<byte[]> convertHeicToJpeg(@RequestParam("file") MultipartFile file) {
        try {
            if (file.isEmpty()) {
                return ResponseEntity.badRequest().body("Empty file".getBytes());
            }
            String originalName = file.getOriginalFilename();
            if (originalName == null || !originalName.toLowerCase().endsWith(".heic")) {
                return ResponseEntity.badRequest().body("Only .heic files supported".getBytes());
            }
            Path tempPath = Paths.get(tempDir);
            Files.createDirectories(tempPath);
            String uniqueId = UUID.randomUUID().toString();
            File inputHeic = tempPath.resolve(uniqueId + ".heic").toFile();
            File outputJpeg = tempPath.resolve(uniqueId + ".jpg").toFile();
            file.transferTo(inputHeic);

            boolean converted = tryConvertWithLibheif(inputHeic, outputJpeg);

            if (!converted || !outputJpeg.exists() || outputJpeg.length() == 0) {
                return ResponseEntity.internalServerError()
                        .body("Conversion failed: no suitable converter found".getBytes());
            }
            byte[] jpegBytes = FileUtils.readFileToByteArray(outputJpeg);
            FileUtils.deleteQuietly(inputHeic);
            FileUtils.deleteQuietly(outputJpeg);

            return ResponseEntity.ok()
                    .contentType(MediaType.IMAGE_JPEG)
                    .body(jpegBytes);
        } catch (Exception e) {
            e.printStackTrace();
            return ResponseEntity.internalServerError()
                    .body(("Conversion error: " + e.getMessage()).getBytes());
        }
    }

    private boolean tryConvertWithLibheif(File input, File output) {
        try {
            ProcessBuilder pb = new ProcessBuilder(
                    "heif-convert",
                    "--quality", "90",
                    input.getAbsolutePath(),
                    output.getAbsolutePath()
            );
            pb.redirectErrorStream(true);
            Process process = pb.start();

            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    System.out.println("[heif-convert] " + line);
                }
            }

            int exitCode = process.waitFor();
            return exitCode == 0 && output.exists() && output.length() > 0;
        } catch (Exception e) {
            System.err.println("heif-convert failed: " + e.getMessage());
            return false;
        }
    }
}
