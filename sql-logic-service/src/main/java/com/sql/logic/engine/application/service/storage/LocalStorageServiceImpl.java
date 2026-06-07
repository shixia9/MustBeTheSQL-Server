package com.sql.logic.engine.application.service.storage;

import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.UUID;

@Service
public class LocalStorageServiceImpl implements StorageService {

    // Store in relative directory to the project
    private final String uploadDir = "./uploads/avatars";

    public LocalStorageServiceImpl() {
        File directory = new File(uploadDir);
        if (!directory.exists()) {
            directory.mkdirs();
        }
    }

    @Override
    public String store(MultipartFile file) {
        if (file.isEmpty()) {
            throw new IllegalArgumentException("Failed to store empty file.");
        }
        try {
            String originalFilename = file.getOriginalFilename();
            String extension = "";
            if (originalFilename != null && originalFilename.contains(".")) {
                extension = originalFilename.substring(originalFilename.lastIndexOf("."));
            }
            
            // Generate unique filename
            String newFilename = UUID.randomUUID().toString() + extension;
            Path destinationFile = Paths.get(uploadDir).resolve(Paths.get(newFilename)).normalize().toAbsolutePath();
            
            file.transferTo(destinationFile.toFile());
            
            // Return accessible URL path
            return "/uploads/avatars/" + newFilename;
        } catch (IOException e) {
            throw new RuntimeException("Failed to store file.", e);
        }
    }
}
