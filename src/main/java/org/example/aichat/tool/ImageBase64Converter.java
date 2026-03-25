package org.example.aichat.tool;

import org.springframework.stereotype.Component;
import java.io.InputStream;
import java.net.URL;
import java.util.Base64;

@Component
public class ImageBase64Converter {

    public String convertToBase64(String imageUrl) {
        try (InputStream inputStream = new URL(imageUrl).openStream()) {

            byte[] imageBytes = inputStream.readAllBytes();

            String base64 = Base64.getEncoder().encodeToString(imageBytes);

            // 自动推断格式
            String suffix = imageUrl.substring(imageUrl.lastIndexOf(".") + 1);

            return "data:image/" + suffix + ";base64," + base64;

        } catch (Exception e) {
            throw new RuntimeException("图片转换失败", e);
        }
    }
}