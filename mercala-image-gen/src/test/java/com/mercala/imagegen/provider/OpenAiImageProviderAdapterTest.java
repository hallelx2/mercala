package com.mercala.imagegen.provider;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.ai.image.ImageGeneration;
import org.springframework.ai.image.ImageModel;
import org.springframework.ai.image.ImagePrompt;
import org.springframework.ai.image.ImageResponse;

import java.util.Base64;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

class OpenAiImageProviderAdapterTest {

    @Test
    void generatesImageFromBase64Json() {
        ImageModel mockImageModel = Mockito.mock(ImageModel.class);
        OpenAiImageProviderAdapter adapter = new OpenAiImageProviderAdapter(
                java.util.Optional.of(mockImageModel),
                java.util.Optional.empty(),
                java.util.Optional.empty());

        String originalData = "test-image-data-payload";
        String encodedData = Base64.getEncoder().encodeToString(originalData.getBytes());

        org.springframework.ai.image.Image outputImage = new org.springframework.ai.image.Image(null, encodedData);
        ImageGeneration generation = new ImageGeneration(outputImage);
        ImageResponse response = new ImageResponse(List.of(generation));

        when(mockImageModel.call(any(ImagePrompt.class))).thenReturn(response);

        byte[] result = adapter.generateImage("Generate a red box");
        assertNotNull(result);
        assertEquals(originalData, new String(result));
    }

    @Test
    void fallsBackToMockImageWhenImageModelThrows() {
        ImageModel mockImageModel = Mockito.mock(ImageModel.class);
        OpenAiImageProviderAdapter adapter = new OpenAiImageProviderAdapter(
                java.util.Optional.of(mockImageModel),
                java.util.Optional.empty(),
                java.util.Optional.empty());

        when(mockImageModel.call(any(ImagePrompt.class))).thenThrow(new RuntimeException("OpenAI API Down"));

        byte[] result = adapter.generateImage("Generate a red box");
        assertNotNull(result);
        assertTrue(result.length > 0);
    }

    @Test
    void fallsBackToMockImageWhenImageModelIsNull() {
        OpenAiImageProviderAdapter adapter = new OpenAiImageProviderAdapter(
                java.util.Optional.empty(),
                java.util.Optional.empty(),
                java.util.Optional.empty());

        byte[] result = adapter.generateImage("Generate a red box");
        assertNotNull(result);
        assertTrue(result.length > 0);
    }
}
