/*
 * Copyright 2016 Adam Stroud
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package me.adamstroud.rxcrypto;

import android.support.test.InstrumentationRegistry;
import android.util.Base64;
import android.util.Log;
import android.util.Pair;

import com.google.common.base.Charsets;
import com.google.common.io.CharStreams;

import org.assertj.core.api.Condition;
import org.junit.BeforeClass;
import org.junit.Test;
import org.spongycastle.openssl.EncryptionException;
import org.spongycastle.openssl.PEMParser;
import org.spongycastle.util.encoders.Hex;
import org.spongycastle.util.io.pem.PemObject;

import java.io.InputStreamReader;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Security;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.TimeUnit;

import javax.crypto.SecretKey;

import rx.Observable;
import rx.functions.Func1;
import rx.functions.Func2;
import rx.observers.TestSubscriber;

import static org.assertj.core.api.Java6Assertions.assertThat;

/**
 * Test cases for {@link RxCrypto}.
 *
 * @author Adam Stroud &#60;<a href="mailto:adam.stroud@gmail.com">adam.stroud@gmail.com</a>&#62;
 */
public class RxCryptoTest {
    private static final String TAG = RxCryptoTest.class.getSimpleName();
    private static final String PROVIDER = "SC";
    private static final String AAD = "some_aad";
    private static final String PUBLIC_PEM = "public.pem";
    private static final String PASSWORD_FILE = "password.txt";
    private static final String ENCRYPTED_PRIVATE_PEM = "encrypted_private.pem";
    private static final String PRIVATE_PEM = "private.pem";

    @BeforeClass
    public static void init() {
        if (Security.getProvider(PROVIDER) == null) {
            PRNGFixes.apply();
            Security.insertProviderAt(new org.spongycastle.jce.provider.BouncyCastleProvider(), 1);
        }
    }

    @Test
    public void testGenerateSecretKey() throws Exception {
        final SecretKeyAlgorithm secretKeyAlgorithm = SecretKeyAlgorithm.AES;
        final int keySizeInBits = 256;

        TestSubscriber<SecretKey> testSubscriber = new TestSubscriber<>();

        RxCrypto.generateSecretKey(secretKeyAlgorithm, keySizeInBits)
                .subscribe(testSubscriber);

        SecretKey secretKey = checkTestSubscriberAndGetValue(testSubscriber);

        assertThat(secretKey)
                .has(new Condition<SecretKey>() {
                    @Override
                    public boolean matches(SecretKey secretKey) {
                        return secretKeyAlgorithm.providerString.equals(secretKey.getAlgorithm());
                    }})
                .has(new Condition<SecretKey>() {
                    @Override
                    public boolean matches(SecretKey secretKey) {
                        return (keySizeInBits / 8) == secretKey.getEncoded().length;
                    }});
    }

    @Test
    public void testGenerateSecretKey_bytes() throws Exception {
        TestSubscriber<Pair<SecretKey, SecretKey>> testSubscriber = new TestSubscriber<>();

        RxCrypto.generateSecretKey(SecretKeyAlgorithm.AES, 256)
                .flatMap(new Func1<SecretKey, Observable<Pair<SecretKey, SecretKey>>>() {
                    @Override
                    public Observable<Pair<SecretKey, SecretKey>> call(SecretKey expectedSecretKey) {
                        return Observable.combineLatest(Observable.just(expectedSecretKey),
                                RxCrypto.generateSecretKey(SecretKeyAlgorithm.AES, expectedSecretKey.getEncoded()),
                                new Func2<SecretKey, SecretKey, Pair<SecretKey, SecretKey>>() {
                                    @Override
                                    public Pair<SecretKey, SecretKey> call(SecretKey expected, SecretKey actual) {
                                        return new Pair<>(expected, actual);
                                    }
                                });
                    }
                })
                .subscribe(testSubscriber);

        Pair<SecretKey, SecretKey> pair = checkTestSubscriberAndGetValue(testSubscriber);
        assertThat(pair.second).isEqualTo(pair.first);
    }

    @Test
    public void testSymmetricEncryptDecrypt() throws Exception {
        final byte[] plainText = "Something Very Secret".getBytes(Charsets.UTF_8);
        final TestSubscriber<Pair<byte[], SecretKey>> pairTestSubscriber = new TestSubscriber<>();
        final TestSubscriber<byte[]> encryptTestSubscriber = new TestSubscriber<>();

        Observable.zip(RxCrypto.generateIV(),
                RxCrypto.generateSecretKey(SecretKeyAlgorithm.AES, 256),
                new Func2<byte[], SecretKey, Pair<byte[], SecretKey>>() {
                    @Override
                    public Pair<byte[], SecretKey> call(byte[] iv, SecretKey secretKey) {
                        return new Pair<>(iv, secretKey);
                    }
                })
                .subscribe(pairTestSubscriber);
        Pair<byte[], SecretKey> resultPair = checkTestSubscriberAndGetValue(pairTestSubscriber);

        final byte[] iv = resultPair.first;
        final SecretKey secretKey = resultPair.second;

        RxCrypto.encrypt(secretKey, iv, plainText, AAD)
                .flatMap(new Func1<byte[], Observable<byte[]>>() {
                    @Override
                    public Observable<byte[]> call(byte[] cipherTextAndTag) {
                        byte[] tag = Arrays.copyOfRange(cipherTextAndTag, cipherTextAndTag.length - (16 / Byte.SIZE), cipherTextAndTag.length);
                        byte[] cipherText = Arrays.copyOfRange(cipherTextAndTag, 0, (cipherTextAndTag.length - (16 / Byte.SIZE) - 1));

                        Log.d(TAG, "IV = " + Hex.toHexString(iv).toUpperCase(Locale.US));
                        Log.d(TAG, "KEY = " + Hex.toHexString(secretKey.getEncoded()).toUpperCase(Locale.US));
                        Log.d(TAG, "TAG = " + Base64.encodeToString(tag, Base64.DEFAULT));
                        Log.d(TAG, "CIPHER TEXT = " + Base64.encodeToString(cipherText, Base64.DEFAULT));
                        Log.d(TAG, "COMBINED CIPHER TEXT/TAG = " + Base64.encodeToString(cipherTextAndTag, Base64.DEFAULT));

                        return RxCrypto.decrypt(secretKey, iv, cipherTextAndTag, AAD);
                    }
                })
                .subscribe(encryptTestSubscriber);

        byte[] resultBytes = checkTestSubscriberAndGetValue(encryptTestSubscriber);
        assertThat(new String(resultBytes, Charsets.UTF_8)).isEqualTo(new String(plainText, Charsets.UTF_8));
    }

    @Test
    public void testPbeEncryptionDecryption() throws Exception {
        final String password = "password";
        final String plaintext = "This is a secret message.";
        final TestSubscriber<byte[]> saltTestSubscriber = new TestSubscriber<>();

        RxCrypto.generatePbeSalt().subscribe(saltTestSubscriber);
        final byte[] salt = checkTestSubscriberAndGetValue(saltTestSubscriber);

        final TestSubscriber<SecretKey> encryptionKeyTestSubscriber = new TestSubscriber<>();
        RxCrypto.generatePasswordBasedSecretKey(password.toCharArray(), 256, salt)
                .subscribe(encryptionKeyTestSubscriber);
        SecretKey encryptionKey = checkTestSubscriberAndGetValue(encryptionKeyTestSubscriber);

        TestSubscriber<SecretKey> decryptionKeyTestSubscriber = new TestSubscriber<>();
        RxCrypto.generatePasswordBasedSecretKey(password.toCharArray(), 256, salt)
                .subscribe(decryptionKeyTestSubscriber);
        SecretKey decryptionKey = checkTestSubscriberAndGetValue(decryptionKeyTestSubscriber);

        assertThat(encryptionKey.getEncoded()).isEqualTo(decryptionKey.getEncoded());

        TestSubscriber<byte[]> ivTestSubscriber = new TestSubscriber<>();
        RxCrypto.generateIV().subscribe(ivTestSubscriber);
        byte[] iv = checkTestSubscriberAndGetValue(ivTestSubscriber);

        TestSubscriber<byte[]> cipherTextTestSubscriber = new TestSubscriber<>();
        RxCrypto.encrypt(encryptionKey, iv, plaintext.getBytes(Charsets.UTF_8), AAD)
                .subscribe(cipherTextTestSubscriber);
        byte[] cipherText = checkTestSubscriberAndGetValue(cipherTextTestSubscriber);

        TestSubscriber<byte[]> plaintextTestSubscriber = new TestSubscriber<>();
        RxCrypto.decrypt(decryptionKey, iv, cipherText, AAD)
                .subscribe(plaintextTestSubscriber);
        byte[] plaintextBytes = checkTestSubscriberAndGetValue(plaintextTestSubscriber);

        assertThat(new String(plaintextBytes, Charsets.UTF_8)).isEqualTo(plaintext);
    }

    @Test
    public void testExternalPbe() throws Exception {
        final String password = "password";
        final String salt = "salt";
        final TestSubscriber<String> testSubscriber = new TestSubscriber<>();

        RxCrypto.generatePasswordBasedSecretKey(password.toCharArray(), 20 * Byte.SIZE, salt.getBytes(Charsets.UTF_8))
                .map(new Func1<SecretKey, String>() {
                    @Override
                    public String call(SecretKey secretKey) {
                        return Hex.toHexString(secretKey.getEncoded()).toUpperCase(Locale.US);
                    }
                })
        .subscribe(testSubscriber);

        assertThat(checkTestSubscriberAndGetValue(testSubscriber)).isEqualTo("6E88BE8BAD7EAE9D9E10AA061224034FED48D03F");
    }

    @Test
    public void testGenerateKeyPair() throws Exception {
        final TestSubscriber<KeyPair> testSubscriber = new TestSubscriber<>();
        RxCrypto.generateKeyPair(4096)
                .subscribe(testSubscriber);

        KeyPair keyPair = checkTestSubscriberAndGetValue(testSubscriber);

        assertThat(keyPair.getPrivate().getAlgorithm()).isEqualTo("RSA");
        assertThat(keyPair.getPublic().getAlgorithm()).isEqualTo("RSA");
    }

    @Test
    public void testKeyPairEncryptDecrypt() throws Exception {
        final TestSubscriber<KeyPair> keyPairTestSubscriber = new TestSubscriber<>();
        final String plainText = "Secret Message";

        RxCrypto.generateKeyPair(4096).subscribe(keyPairTestSubscriber);
        KeyPair keyPair = checkTestSubscriberAndGetValue(keyPairTestSubscriber);

        final TestSubscriber<byte[]> ivTestSubscriber = new TestSubscriber<>();
        RxCrypto.generateIV().subscribe(ivTestSubscriber);

        final TestSubscriber<byte[]> encryptionTestSubscriber = new TestSubscriber<>();
        RxCrypto.encrypt(keyPair.getPublic(),
                plainText.getBytes(Charsets.UTF_8))
                .subscribe(encryptionTestSubscriber);
        byte[] cipherText = checkTestSubscriberAndGetValue(encryptionTestSubscriber);

        final TestSubscriber<byte[]> decryptionTestSubscriber = new TestSubscriber<>();
        RxCrypto.decrypt(keyPair.getPrivate(), cipherText)
                .subscribe(decryptionTestSubscriber);

         byte[] plaintextBytes = checkTestSubscriberAndGetValue(decryptionTestSubscriber);

        assertThat(new String(plaintextBytes, Charsets.UTF_8)).isEqualTo(plainText);
    }

    @Test
    public void testGenerateHash() throws Exception {
        final String message = "This is a message.";
        final TestSubscriber<byte[]> testSubscriber = new TestSubscriber<>();

        RxCrypto.generateHash(message.getBytes(Charsets.UTF_8))
                .subscribe(testSubscriber);
        byte[] messageHash = checkTestSubscriberAndGetValue(testSubscriber);

        assertThat(Hex.toHexString(messageHash)).isEqualToIgnoringCase("B5AEE900D6C80E9EAE27939A9" +
                "548C73288FFE49E0B529F2A64408336B5A4944F6BCCA34EE34ABEB593C688D06B243724DDE133194" +
                "7B74CC83D7A80DDBD569CCD");
    }

    @Test
    public void testEncryptDecryptPem() throws Exception {
        final String plainText = "A secret Message";

        TestSubscriber<byte[]> testSubscriber = new TestSubscriber<>();
        final PublicKey publicKey = readPublicKey();
        final PrivateKey privateKey = readPrivateKey();

        RxCrypto.encrypt(publicKey, plainText.getBytes(Charsets.UTF_8))
                .flatMap(new Func1<byte[], Observable<byte[]>>() {
                    @Override
                    public Observable<byte[]> call(byte[] cipherText) {
                        Log.d(TAG, "Cipher Text: " + Base64.encodeToString(cipherText, Base64.DEFAULT));
                        return RxCrypto.decrypt(privateKey, cipherText);
                    }
                })
                .subscribe(testSubscriber);

        assertThat(new String(checkTestSubscriberAndGetValue(testSubscriber))).isEqualTo(plainText);
    }

    @Test
    public void testReadPrivateKeyFromPem_validPassword() throws Exception {
        final String password = readFile(PASSWORD_FILE).trim();
        String pemData = readFile(ENCRYPTED_PRIVATE_PEM);

        TestSubscriber<PrivateKey> testSubscriber = new TestSubscriber<>();

        RxCrypto.readPrivateKeyFromPem(pemData, password)
                .subscribe(testSubscriber);

        assertThat(checkTestSubscriberAndGetValue(testSubscriber)).isNotNull();
    }

    @Test
    public void testReadPrivateKeyFromPem_wrongPassword() throws Exception {
        final String password = readFile(PASSWORD_FILE).trim();
        final String badPassword = "badPassword";

        assertThat(badPassword).isNotEqualTo(password);

        String pemData = readFile(ENCRYPTED_PRIVATE_PEM);

        TestSubscriber<PrivateKey> testSubscriber = new TestSubscriber<>();

        RxCrypto.readPrivateKeyFromPem(pemData, badPassword)
                .subscribe(testSubscriber);

        testSubscriber.awaitTerminalEvent(10, TimeUnit.SECONDS);
        testSubscriber.assertNoValues();
        testSubscriber.assertError(EncryptionException.class);
    }

    @Test
    public void testWritePrivateKeyToPemWithPkcs8() throws Exception {
        TestSubscriber<PrivateKey> testSubscriber = new TestSubscriber<>();
        PrivateKey privateKey = readPrivateKey();
        final String password = readFile(PASSWORD_FILE);

        RxCrypto.writeToPemWithPkcs8(privateKey, password)
                .map(new Func1<byte[], String>() {
                    @Override
                    public String call(byte[] bytes) {
                        String pkcs8 = new String(bytes);
                        Log.d(TAG, "PKCS8 output = " + pkcs8);
                        return pkcs8;
                    }
                })
                .flatMap(new Func1<String, Observable<PrivateKey>>() {
                    @Override
                    public Observable<PrivateKey> call(String pemContents) {
                        return RxCrypto.readPrivateKeyFromPem(pemContents, password);
                    }
                })
                .subscribe(testSubscriber);

        assertThat(checkTestSubscriberAndGetValue(testSubscriber).getEncoded()).isEqualTo(privateKey.getEncoded());
    }

    @Test
    public void testWritePublicKeyToPem() throws Exception {
        TestSubscriber<byte[]> testSubscriber = new TestSubscriber<>();
        PublicKey publicKey = readPublicKey();

        RxCrypto.writeToPem(publicKey)
                .subscribe(testSubscriber);

        byte[] pemContents = checkTestSubscriberAndGetValue(testSubscriber);

        assertThat(new String(pemContents)).isEqualTo(readFile(PUBLIC_PEM));
    }

    @Test
    public void testWrapUnWrap() throws Exception {
        TestSubscriber<Pair<SecretKey, SecretKey>> testSubscriber = new TestSubscriber<>();

        Observable.combineLatest(RxCrypto.generateKeyPair(4096),
                RxCrypto.generateSecretKey(SecretKeyAlgorithm.AES, 256),
                new Func2<KeyPair, SecretKey, Pair<KeyPair, SecretKey>>() {
                    @Override
                    public Pair<KeyPair, SecretKey> call(KeyPair keyPair, SecretKey secretKey) {
                        return new Pair<>(keyPair, secretKey);
                    }
                })
                .flatMap(new Func1<Pair<KeyPair, SecretKey>, Observable<Triple<KeyPair, SecretKey, byte[]>>>() {
                    @Override
                    public Observable<Triple<KeyPair, SecretKey, byte[]>> call(Pair<KeyPair, SecretKey> pair) {
                        return Observable.combineLatest(Observable.just(pair),
                                RxCrypto.wrap(pair.first.getPublic(), pair.second),
                                new Func2<Pair<KeyPair, SecretKey>, byte[], Triple<KeyPair, SecretKey, byte[]>>() {
                                    @Override
                                    public Triple<KeyPair, SecretKey, byte[]> call(Pair<KeyPair, SecretKey> pair, byte[] bytes) {
                                        return new Triple<>(pair.first, pair.second, bytes);
                                    }
                                });
                    }
                })
                .flatMap(new Func1<Triple<KeyPair, SecretKey, byte[]>, Observable<Pair<SecretKey, SecretKey>>>() {
                    @Override
                    public Observable<Pair<SecretKey, SecretKey>> call(Triple<KeyPair, SecretKey, byte[]> triple) {
                        return Observable.combineLatest(Observable.just(triple.second),
                                RxCrypto.unwrap(triple.first.getPrivate(), triple.third, SecretKeyAlgorithm.AES),
                                new Func2<SecretKey, SecretKey, Pair<SecretKey, SecretKey>>() {
                                    @Override
                                    public Pair<SecretKey, SecretKey> call(SecretKey expectedKey, SecretKey actualKey) {
                                        return new Pair<>(expectedKey, actualKey);
                                    }
                                });
                    }
                })
                .subscribe(testSubscriber);

        Pair<SecretKey, SecretKey> pair = checkTestSubscriberAndGetValue(testSubscriber);

        assertThat(pair.second).isEqualTo(pair.first);
    }

    @Test
    public void testReadPublicKeyFromPem() throws Exception {
        TestSubscriber<PublicKey> testSubscriber = new TestSubscriber<>();
        final PublicKey expectedPublicKey = readPublicKey();

        RxCrypto.readPublicKeyFromPem(readFile(PUBLIC_PEM))
                .subscribe(testSubscriber);

        PublicKey actualPublicKey = checkTestSubscriberAndGetValue(testSubscriber);
        assertThat(actualPublicKey).isEqualTo(expectedPublicKey);
    }

    private <T> T checkTestSubscriberAndGetValue(TestSubscriber<T> testSubscriber) {
        testSubscriber.awaitTerminalEvent(10, TimeUnit.SECONDS);
        testSubscriber.assertNoErrors();
        testSubscriber.assertValueCount(1);
        testSubscriber.assertCompleted();
        return testSubscriber.getOnNextEvents().get(0);
    }

    private String readFile(String filename) throws Exception {
        return CharStreams.toString(new InputStreamReader(InstrumentationRegistry.getContext().getResources().getAssets().open(filename), Charsets.UTF_8));
    }

    private PublicKey readPublicKey() throws Exception {
        PEMParser pemParser =
                new PEMParser(new InputStreamReader(InstrumentationRegistry.getContext().getResources().getAssets().open(PUBLIC_PEM)));

        PemObject pemObject = pemParser.readPemObject();
        pemParser.close();

        byte[] publicBytes = pemObject.getContent();

        X509EncodedKeySpec publicSpec = new X509EncodedKeySpec(publicBytes);

        KeyFactory kf = KeyFactory.getInstance("RSA", PROVIDER);
        PublicKey publicKey = kf.generatePublic(publicSpec);

        assertThat(publicKey).isNotNull();

        return publicKey;
    }

    private PrivateKey readPrivateKey() throws Exception {
        PEMParser pemParser =
                new PEMParser(new InputStreamReader(InstrumentationRegistry.getContext().getResources().getAssets().open(PRIVATE_PEM)));
        PemObject pemObject = pemParser.readPemObject();
        pemParser.close();

        KeyFactory kf = KeyFactory.getInstance("RSA", PROVIDER);
        final PrivateKey privateKey = kf.generatePrivate(new PKCS8EncodedKeySpec(pemObject.getContent()));

        assertThat(privateKey).isNotNull();

        return privateKey;
    }

    private static class Triple<F, S, T> {
        public final F first;
        public final S second;
        public final T third;

        public Triple(F first, S second, T third) {
            this.first = first;
            this.second = second;
            this.third = third;
        }
    }
}