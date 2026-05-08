import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.time.Duration;

public class TtsTest {
    public static void main(String[] args) throws Exception {
        String body = "{\"text\":\"测试\",\"text_lang\":\"zh\","
            + "\"ref_audio_path\":\"/Users/mengzhimeng/Desktop/project/AI-chat/v4/黍/reference_audios/中文/emotions/【默认】让我看看我带来的这些渔具放到哪里好呢？.wav\","
            + "\"prompt_text\":\"让我看看我带来的这些渔具放到哪里好呢？\","
            + "\"prompt_lang\":\"zh\",\"media_type\":\"wav\",\"streaming_mode\":2,\"text_split_method\":\"cut5\"}";
        byte[] payload = body.getBytes(StandardCharsets.UTF_8);

        for (HttpClient.Version v : new HttpClient.Version[]{HttpClient.Version.HTTP_2, HttpClient.Version.HTTP_1_1}) {
            System.out.println("\n============ client version=" + v + " ============");
            HttpClient client = HttpClient.newBuilder().version(v).connectTimeout(Duration.ofSeconds(5)).build();
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:9880/tts"))
                    .timeout(Duration.ofSeconds(60))
                    .header("Content-Type", "application/json; charset=utf-8")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload))
                    .build();
            try {
                HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
                System.out.println("status=" + resp.statusCode() + " size=" + resp.body().length
                        + " version=" + resp.version());
                if (resp.statusCode() != 200) {
                    System.out.println("body=" + new String(resp.body(), StandardCharsets.UTF_8));
                }
            } catch (Exception e) {
                System.out.println("exception: " + e);
            }
        }
    }
}