package com.sql.logic.engine.domain.openai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class VisionContentHandlerTest {

    private VisionContentHandler handler;

    @BeforeEach
    void setUp() {
        handler = new VisionContentHandler();
    }

    @Test
    void shouldExtractTextFromPlainString() {
        String text = handler.extractText("Hello world");
        assertEquals("Hello world", text);
    }

    @Test
    void shouldExtractTextFromContentParts() {
        Object content = List.of(
                Map.of("type", "text", "text", "What is in this image?"),
                Map.of("type", "image_url", "image_url",
                        Map.of("url", "https://example.com/photo.jpg"))
        );

        String text = handler.extractText(content);
        assertEquals("What is in this image?", text);
    }

    @Test
    void shouldExtractImagesFromContentParts() {
        Object content = List.of(
                Map.of("type", "text", "text", "Describe this"),
                Map.of("type", "image_url", "image_url",
                        Map.of("url", "https://example.com/photo.jpg", "detail", "high"))
        );

        List<Map<String, String>> images = handler.extractImages(content);
        assertEquals(1, images.size());
        assertEquals("https://example.com/photo.jpg", images.get(0).get("url"));
        assertEquals("high", images.get(0).get("detail"));
    }

    @Test
    void shouldReturnEmptyImagesForPlainString() {
        List<Map<String, String>> images = handler.extractImages("Hello world");
        assertTrue(images.isEmpty());
    }

    @Test
    void shouldDetectVisionContent() {
        Object withImage = List.of(
                Map.of("type", "image_url", "image_url", Map.of("url", "test.jpg"))
        );
        assertTrue(handler.hasVisionContent(withImage));

        Object plainText = "Just text";
        assertFalse(handler.hasVisionContent(plainText));
    }

    @Test
    void shouldHandleNullContent() {
        assertEquals("", handler.extractText(null));
        assertTrue(handler.extractImages(null).isEmpty());
        assertFalse(handler.hasVisionContent(null));
    }

    @Test
    void shouldBuildVisionEnrichedQuestion() {
        Object content = List.of(
                Map.of("type", "text", "text", "What is this?"),
                Map.of("type", "image_url", "image_url",
                        Map.of("url", "https://example.com/img.png"))
        );

        String question = handler.buildVisionEnrichedQuestion(content);
        assertTrue(question.contains("What is this?"));
        assertTrue(question.contains("[Attached Images]"));
        assertTrue(question.contains("https://example.com/img.png"));
    }

    @Test
    void shouldHandleAudioContent() {
        Object content = List.of(
                Map.of("type", "input_audio", "input_audio",
                        Map.of("data", "base64data", "format", "wav"))
        );
        assertTrue(handler.hasVisionContent(content));
    }

    @Test
    void shouldHandleEmptyContentParts() {
        Object content = List.of();
        assertEquals("", handler.extractText(content));
        assertTrue(handler.extractImages(content).isEmpty());
    }
}
