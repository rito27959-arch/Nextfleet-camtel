package cm.camtel.nextfleet.mobile.tracking;

import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.KeyStore;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * Chiffrement du tampon de positions au repos : AES-256-GCM, clé non exportable générée dans l'Android Keystore
 * (matériel sécurisé quand l'appareil en dispose). Format : IV (12 octets) | texte chiffré + étiquette.
 */
public final class Crypto {

    public interface Box {
        byte[] seal(byte[] plain) throws GeneralSecurityException;

        byte[] open(byte[] sealed) throws GeneralSecurityException;
    }

    private static final String ALIAS = "nexfleet-fixes-v1";

    private Crypto() { }

    public static Box keystore() {
        return new Box() {
            @Override
            public byte[] seal(byte[] plain) throws GeneralSecurityException {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.ENCRYPT_MODE, key());
                byte[] iv = c.getIV();
                byte[] ct = c.doFinal(plain);
                return ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
            }

            @Override
            public byte[] open(byte[] sealed) throws GeneralSecurityException {
                Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
                c.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, sealed, 0, 12));
                return c.doFinal(sealed, 12, sealed.length - 12);
            }
        };
    }

    private static synchronized SecretKey key() throws GeneralSecurityException {
        try {
            KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
            ks.load(null);
            if (ks.containsAlias(ALIAS)) return ((KeyStore.SecretKeyEntry) ks.getEntry(ALIAS, null)).getSecretKey();
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return g.generateKey();
        } catch (java.io.IOException e) {
            throw new GeneralSecurityException("Keystore indisponible", e);
        }
    }
}
