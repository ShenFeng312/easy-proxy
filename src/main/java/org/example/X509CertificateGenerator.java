package org.example;

import sun.security.x509.*;

import java.io.IOException;
import java.math.BigInteger;
import java.security.*;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Date;

public class X509CertificateGenerator {

    public static X509Certificate generateRootCertificate(KeyPair keyPair, KeyPair issuerKeyPair, String signatureAlgorithm) throws CertificateException, NoSuchAlgorithmException, InvalidKeyException, SignatureException, NoSuchProviderException, IOException {
        X509CertInfo certInfo = new X509CertInfo();
        Date startDate = new Date();
        Date expiryDate = new Date(startDate.getTime() + 365 * 24 * 60 * 60 * 1000); // Validity: 1 year

        CertificateValidity validity = new CertificateValidity(startDate, expiryDate);
        X500Name owner = new X500Name("CN=shenfeng, OU=shenfeng, ST=HZ, O=shenfeng, C=CN");

        certInfo.set(X509CertInfo.VALIDITY, validity);
        certInfo.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(new BigInteger(64, new SecureRandom())));
        certInfo.set(X509CertInfo.SUBJECT,  owner);
        certInfo.set(X509CertInfo.ISSUER, owner);
        certInfo.set(X509CertInfo.KEY, new CertificateX509Key(keyPair.getPublic()));
        certInfo.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3));
        AlgorithmId algorithm = AlgorithmId.get("SHA256withRSA");
        certInfo.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(algorithm));


        CertificateExtensions extensions = new CertificateExtensions();
        BasicConstraintsExtension basicConstraintsExtension = new BasicConstraintsExtension(true, -1);

        extensions.set(BasicConstraintsExtension.NAME, new BasicConstraintsExtension(false, basicConstraintsExtension.getExtensionValue()));
        certInfo.set(X509CertInfo.EXTENSIONS, extensions);
        X509CertImpl certificate = new X509CertImpl(certInfo);

        certificate.sign(issuerKeyPair.getPrivate(), signatureAlgorithm);

        return certificate;
    }

    public static X509Certificate generateChildCertificate(String host, PrivateKey parentKey, X509Certificate issuerCertificate, KeyPair subjectKeyPair, String signatureAlgorithm,boolean isca) throws CertificateException, NoSuchAlgorithmException, InvalidKeyException, SignatureException, NoSuchProviderException, IOException {
        X509CertInfo certInfo = new X509CertInfo();
        Date startDate = new Date();
        Date expiryDate = new Date(startDate.getTime()+ 365 * 24 * 60 * 60 * 1000); // Validity: 1 year

        CertificateValidity validity = new CertificateValidity(startDate, expiryDate);
        X500Name owner = new X500Name("CN="+host);

        certInfo.set(X509CertInfo.VALIDITY, validity);
        certInfo.set(X509CertInfo.SERIAL_NUMBER, new CertificateSerialNumber(new BigInteger(64, new SecureRandom())));
        certInfo.set(X509CertInfo.SUBJECT,owner);
        certInfo.set(X509CertInfo.ISSUER, issuerCertificate.getSubjectDN());
        certInfo.set(X509CertInfo.KEY, new CertificateX509Key(subjectKeyPair.getPublic()));
        certInfo.set(X509CertInfo.VERSION, new CertificateVersion(CertificateVersion.V3));
        AlgorithmId algorithm =  AlgorithmId.get("SHA256withRSA");
        certInfo.set(X509CertInfo.ALGORITHM_ID, new CertificateAlgorithmId(algorithm));
        CertificateExtensions extensions = new CertificateExtensions();
        GeneralNames generalNames = new GeneralNames();
        generalNames.add(new GeneralName(new DNSName(host)));
        SubjectAlternativeNameExtension o = new SubjectAlternativeNameExtension(false, generalNames);
        extensions.set(SubjectAlternativeNameExtension.NAME, o);

        if(isca){

            BasicConstraintsExtension basicConstraintsExtension = new BasicConstraintsExtension(true, -1);

            extensions.set(BasicConstraintsExtension.NAME, new BasicConstraintsExtension(false, basicConstraintsExtension.getExtensionValue()));

        }
        certInfo.set(X509CertInfo.EXTENSIONS, extensions);
        X509CertImpl certificate = new X509CertImpl(certInfo);
        certificate.sign(parentKey, signatureAlgorithm);

        return certificate;
    }
}