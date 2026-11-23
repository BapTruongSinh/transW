package vn.lopdich.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

final class SettingsStore {
    static final String DEFAULT_MODEL = "gemini-3.5-flash-lite";
    private static final String ALIAS = "lopdich-gemini";

    record Options(List<String> keys, String model) {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences("settings", Context.MODE_PRIVATE);
    }

    static Options read(Context context) throws Exception {
        SharedPreferences prefs = prefs(context);
        String stored = prefs.getString("key", "");
        String key = "";
        if (!stored.isEmpty()) {
            String[] fields = stored.split("\\.", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, secret(false),
                    new GCMParameterSpec(128, Base64.decode(fields[0], Base64.NO_WRAP)));
            key = new String(cipher.doFinal(Base64.decode(fields[1], Base64.NO_WRAP)),
                    StandardCharsets.UTF_8);
        }
        List<String> keys = key.isEmpty() ? Collections.emptyList() : Arrays.asList(key.split("\n"));
        String model = prefs.getString("model", DEFAULT_MODEL);
        if (!prefs.getBoolean("imageModeConfigured", false) && "gemini-3.6-flash".equals(model)) {
            model = DEFAULT_MODEL;
        }
        return new Options(keys, model);
    }

    static void save(Context context, Options options) throws Exception {
        Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE, secret(true));
        String iv = Base64.encodeToString(cipher.getIV(), Base64.NO_WRAP);
        String data = Base64.encodeToString(
                cipher.doFinal(String.join("\n", options.keys()).getBytes(StandardCharsets.UTF_8)), Base64.NO_WRAP);
        prefs(context).edit().putString("key", iv + "." + data)
                .putString("model", options.model()).putBoolean("imageModeConfigured", true).apply();
    }

    private static SecretKey secret(boolean create) throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        SecretKey key = (SecretKey) store.getKey(ALIAS, null);
        if (key != null || !create) return key;
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
