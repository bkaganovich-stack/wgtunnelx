// Opens the release key exactly like APK signing does (KeyStore.getKey) and reports which secret
// is wrong. Run with: java scripts/VerifyKey.java <keystore>; reads STORE_PASSWORD, KEY_ALIAS,
// KEY_PASSWORD from the environment and never prints them.
import java.io.File;
import java.security.KeyStore;
import java.security.UnrecoverableKeyException;
import java.util.Collections;

public class VerifyKey {
    public static void main(String[] args) throws Exception {
        String storePass = System.getenv("STORE_PASSWORD");
        String alias = System.getenv("KEY_ALIAS");
        String keyPass = System.getenv("KEY_PASSWORD");
        KeyStore ks;
        try {
            ks = KeyStore.getInstance(new File(args[0]), storePass.toCharArray());
        } catch (Exception e) {
            fail("SIGNING_STORE_PASSWORD does not open the keystore, or KEYSTORE is not the base64 of a .jks/.p12 file (" + e.getClass().getSimpleName() + ").");
            return;
        }
        if (!ks.isKeyEntry(alias)) {
            System.out.println("Private key aliases in the keystore:");
            for (String a : Collections.list(ks.aliases())) {
                if (ks.isKeyEntry(a)) System.out.println("  " + a);
            }
            fail("SIGNING_KEY_ALIAS does not match a private key in the keystore.");
        }
        try {
            if (ks.getKey(alias, keyPass.toCharArray()) == null) fail("The key entry is empty.");
        } catch (UnrecoverableKeyException e) {
            fail("SIGNING_KEY_PASSWORD does not unlock the key. For PKCS12 keystores (keytool default) it is the same as the store password.");
        }
        System.out.println("Signing key OK (" + ks.getType() + ")");
    }

    static void fail(String message) {
        System.out.println("::error::" + message + " See docs/RELEASING.md");
        System.exit(1);
    }
}
