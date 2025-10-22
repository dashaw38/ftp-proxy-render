package org.example.controller;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;

@RestController
@RequestMapping("/upload")
public class FtpProxyController {

    @PostMapping
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
}
