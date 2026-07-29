package com.sql.logic.engine.domain.openai;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Parses vision/multimodal content from OpenAI-compatible content part arrays.
 * Handles {@code image_url} content parts and converts them to a format that
 * can be passed through to multimodal-capable upstream LLMs (GPT-4o, Claude 3.5+).
 */
@Component
public class VisionContentHandler {

    private static final Logger log = LoggerFactory.getLogger(VisionContentHandler.class);

    /**
     * Extract the text portion from a potentially multimodal content field.
     * Content can be a plain String or a List of content part objects
     * (text, image_url, input_audio, etc.).
     */
    public String extractText(Object content) {
        if (content instanceof String s) {
            return s;
        }
        if (content instanceof List<?> parts) {
            StringBuilder sb = new StringBuilder();
            for (Object part : parts) {
                if (part instanceof Map<?, ?> map) {
                    String type = (String) map.get("type");
                    if ("text".equals(type)) {
                        Object text = map.get("text");
                        if (text != null) sb.append(text.toString());
                    }
                }
            }
            return sb.toString();
        }
        return content != null ? content.toString() : "";
    }

    /**
     * Extract image URLs from content parts for vision-capable models.
     * Returns a list of {@code {url, detail}} maps.
     */
    public List<Map<String, String>> extractImages(Object content) {
        List<Map<String, String>> images = new ArrayList<>();
        if (content instanceof List<?> parts) {
            for (Object part : parts) {
                if (part instanceof Map<?, ?> map) {
                    String type = (String) map.get("type");
                    if ("image_url".equals(type)) {
                        Object imageUrlObj = map.get("image_url");
                        if (imageUrlObj instanceof Map<?, ?> imageUrlMap) {
                            String imgUrl = (String) imageUrlMap.get("url");
                            Object detailObj = imageUrlMap.get("detail");
                            String imgDetail = detailObj != null ? detailObj.toString() : "auto";
                            if (imgUrl != null) {
                                Map<String, String> img = new java.util.HashMap<>();
                                img.put("url", imgUrl);
                                img.put("detail", imgDetail);
                                images.add(img);
                            }
                        }
                    }
                }
            }
        }
        if (!images.isEmpty()) {
            log.debug("[VisionContentHandler] Extracted {} image(s) from content", images.size());
        }
        return images;
    }

    /**
     * Check whether the content contains any vision/multimodal parts.
     */
    public boolean hasVisionContent(Object content) {
        if (content instanceof List<?> parts) {
            for (Object part : parts) {
                if (part instanceof Map<?, ?> map) {
                    String type = (String) map.get("type");
                    if ("image_url".equals(type) || "input_audio".equals(type)
                            || "file".equals(type)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    /**
     * Build a vision-enriched user question string that includes image reference
     * markers the downstream LLM can use. For true multimodal support, the
     * upstream LLM must natively handle image inputs.
     */
    public String buildVisionEnrichedQuestion(Object content) {
        String text = extractText(content);
        List<Map<String, String>> images = extractImages(content);
        if (images.isEmpty()) {
            return text;
        }
        StringBuilder sb = new StringBuilder(text);
        sb.append("\n\n[Attached Images]\n");
        for (int i = 0; i < images.size(); i++) {
            sb.append("- Image ").append(i + 1).append(": ").append(images.get(i).get("url")).append("\n");
        }
        return sb.toString();
    }
}
