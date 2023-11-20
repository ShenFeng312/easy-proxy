package org.example;


import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.security.*;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;


public class CertificateUtil {

    public static void createRootCertificate(String crtFileName, String keyFileName)  {
        File crtFile = new File(crtFileName);
        File keyFile = new File(keyFileName);
        if (crtFile.exists() && keyFile.exists()) {
            return;
        }
        KeyPair root = generateKeyPair();
        X509Certificate certificate = generateRootCertificate(root);
        saveCertificateToFile(new X509Certificate[]{certificate}, crtFileName);
        savePrivateKeyToFile(root.getPrivate(), keyFileName);
    }


    public static void createHostCertificate(String host, String crtFileName, String keyFileName) {
        String hostCertFile = genCrtFileName(host);
        String hostKeyFile = genKeyFileName(host);
        File file = new File(hostCertFile);
        if (file.exists()) {
            return;
        }
        PrivateKey rootKey = getKey(keyFileName);
        Certificate rootCert = getCertificate(crtFileName);
        KeyPair subjectKeyPair = generateKeyPair();
        X509Certificate certificate = generateChildCertificate(host, rootKey, (X509Certificate) rootCert, subjectKeyPair, false);
        saveCertificateToFile(new X509Certificate[]{certificate}, hostCertFile);
        savePrivateKeyToFile(subjectKeyPair.getPrivate(), hostKeyFile);
    }

    public static String genCrtFileName(String host) {
        host = host.replace(".", "_");
        return "host" + "/" + host + ".crt";
    }

    public static String genKeyFileName(String host) {
        host = host.replace(".", "_");
        return "host" + "/" + host + ".key";
    }


    public static KeyPair generateKeyPair() {
        KeyPairGenerator keyPairGenerator;
        try {
            keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        } catch (Exception e){
            throw new RuntimeException(e);
        }


        keyPairGenerator.initialize(2048); // You can adjust the key size as needed
        return keyPairGenerator.generateKeyPair();
    }

    public static X509Certificate generateRootCertificate(KeyPair keyPair) {
        X509Certificate cert = null;
        try {
            // Generate a self-signed root certificate
            cert = X509CertificateGenerator.generateRootCertificate(keyPair, keyPair, "SHA256withRSA");
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return cert;
    }

    public static X509Certificate generateChildCertificate(String host, PrivateKey parentKey, X509Certificate issuerCertificate, KeyPair subjectKeyPair, boolean isCa) {
        X509Certificate cert = null;
        try {
            // Generate a child certificate signed by the root certificate
            cert = X509CertificateGenerator.generateChildCertificate(host, parentKey, issuerCertificate, subjectKeyPair, "SHA256withRSA", isCa);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
        return cert;
    }

    public static void saveCertificateToFile(X509Certificate[] certificates, String file) {
        try (FileOutputStream fos = new FileOutputStream(file)) {
            for (X509Certificate certificate : certificates) {
                fos.write("-----BEGIN CERTIFICATE-----\n".getBytes());
                fos.write(Base64.getEncoder().encode(certificate.getEncoded()));
                fos.write("\n-----END CERTIFICATE-----\n".getBytes());
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    public static void savePrivateKeyToFile(PrivateKey privateKey, String fileName) {
        try (FileOutputStream fos = new FileOutputStream(fileName)) {
            fos.write("-----BEGIN PRIVATE KEY-----\n".getBytes());
            fos.write(Base64.getEncoder().encode(privateKey.getEncoded()));
            fos.write("\n-----END PRIVATE KEY-----\n".getBytes());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public static PrivateKey getKey(String file) {
        try {
            byte[] keyBytes = Files.readAllBytes(Paths.get(file));
            // 移除PEM头部和尾部，获取Base64编码的私钥数据
            String privateKeyPEM = new String(keyBytes)
                    .replace("-----BEGIN PRIVATE KEY-----", "")
                    .replace("-----END PRIVATE KEY-----", "")
                    .replaceAll("\\s+", ""); // 移除空白字符

            // 将Base64编码的私钥数据解码
            byte[] decodedKey = Base64.getDecoder().decode(privateKeyPEM);

            // 构建私钥规范
            PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(decodedKey);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA"); // 或者使用其他算法，根据实际情况选择

            // 生成私钥

            return keyFactory.generatePrivate(keySpec);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }

    public static Certificate getCertificate(String file) {
        try {
            CertificateFactory x509 = CertificateFactory.getInstance("X509");
            return x509.generateCertificate(Files.newInputStream(new File(file).toPath()));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

    }
}