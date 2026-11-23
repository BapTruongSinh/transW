package vn.lopdich.app;

import java.io.IOException;
import java.util.List;

final class GeminiKeyPool {
    interface Request<T> {
        // Return null only when Gemini reports HTTP 429.
        T withKey(String key) throws Exception;
    }

    private String preferredKey;

    <T> T run(List<String> keys, Request<T> request) throws Exception {
        int preferred = preferredKey == null ? 0 : Math.max(0, keys.indexOf(preferredKey));
        for (int attempt = 0; attempt < keys.size(); attempt++) {
            String key = keys.get((preferred + attempt) % keys.size());
            T result = request.withKey(key);
            if (result != null) {
                preferredKey = key;
                return result;
            }
        }
        throw new IOException("Gemini báo hết hạn mức cho toàn bộ API key. Hãy thử lại sau.");
    }
}
