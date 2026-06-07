package com.sql.logic.engine.application.service.storage;

import org.springframework.web.multipart.MultipartFile;

public interface StorageService {
    /**
     * Store the uploaded file and return its accessible URL or path.
     *
     * @param file the uploaded file
     * @return the accessible URL or path
     */
    String store(MultipartFile file);
}
