package forge.app.llm;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import forge.ai.llm.PrivateFiles;
import forge.llm.ChatGptAuth;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Encrypted, atomic app-private storage. The encryption key never leaves Android Keystore. */
final class AndroidSecrets implements ChatGptAuth.Storage {
    private static final String ALIAS = "forge.llm.credentials.v1";

    private synchronized SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore");
        store.load(null);
        if (store.containsAlias(ALIAS)) { return (SecretKey) store.getKey(ALIAS, null); }
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256).build());
        return generator.generateKey();
    }

    @Override public JsonObject read(Path path) throws IOException {
        if (!Files.exists(path)) { return new JsonObject(); }
        try {
            JsonObject envelope = JsonParser.parseString(new String(Files.readAllBytes(path), StandardCharsets.UTF_8)).getAsJsonObject();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, Base64.getDecoder().decode(envelope.get("iv").getAsString())));
            cipher.updateAAD(path.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
            byte[] plain = cipher.doFinal(Base64.getDecoder().decode(envelope.get("ciphertext").getAsString()));
            return JsonParser.parseString(new String(plain, StandardCharsets.UTF_8)).getAsJsonObject();
        } catch (Exception e) { throw new IOException("Cannot unlock saved LLM settings on this device."); }
    }

    @Override public void write(Path path, JsonObject value) throws IOException {
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key());
            cipher.updateAAD(path.toAbsolutePath().toString().getBytes(StandardCharsets.UTF_8));
            JsonObject envelope = new JsonObject();
            envelope.addProperty("version", 1);
            envelope.addProperty("iv", Base64.getEncoder().encodeToString(cipher.getIV()));
            envelope.addProperty("ciphertext", Base64.getEncoder().encodeToString(cipher.doFinal(value.toString().getBytes(StandardCharsets.UTF_8))));
            PrivateFiles.write(path, envelope.toString());
        } catch (Exception e) { throw new IOException("Cannot securely save LLM credentials on this device."); }
    }
}
