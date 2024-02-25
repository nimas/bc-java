package org.bouncycastle.openpgp.operator;

import java.io.ByteArrayOutputStream;
import java.io.IOException;

import org.bouncycastle.asn1.ASN1ObjectIdentifier;
import org.bouncycastle.asn1.cryptlib.CryptlibObjectIdentifiers;
import org.bouncycastle.asn1.edec.EdECObjectIdentifiers;
import org.bouncycastle.asn1.nist.NISTObjectIdentifiers;
import org.bouncycastle.asn1.ntt.NTTObjectIdentifiers;
import org.bouncycastle.bcpg.BCPGKey;
import org.bouncycastle.bcpg.ECDHPublicBCPGKey;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyPacket;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.bcpg.X25519PublicBCPGKey;
import org.bouncycastle.bcpg.X448PublicBCPGKey;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.util.encoders.Hex;


public class RFC6637Utils
{
    private RFC6637Utils()
    {

    }

    // "Anonymous Sender    ", which is the octet sequence
    private static final byte[] ANONYMOUS_SENDER = Hex.decode("416E6F6E796D6F75732053656E64657220202020");

    public static String getXDHAlgorithm(PublicKeyPacket pubKeyData)
    {
        if (pubKeyData.getKey() instanceof X25519PublicBCPGKey)
        {
            return "X25519withSHA256CKDF";
        }
        else if (pubKeyData.getKey() instanceof X448PublicBCPGKey)
        {
            return "X448withSHA512CKDF";
        }
        ECDHPublicBCPGKey ecKey = (ECDHPublicBCPGKey)pubKeyData.getKey();
        switch (ecKey.getHashAlgorithm())
        {
        case HashAlgorithmTags.SHA256:
            return "X25519withSHA256CKDF";
        case HashAlgorithmTags.SHA384:
            return "X25519withSHA384CKDF";
        case HashAlgorithmTags.SHA512:
            return "X25519withSHA512CKDF";
        default:
            throw new IllegalArgumentException("Unknown hash algorithm specified: " + ecKey.getHashAlgorithm());
        }
    }

    public static String getAgreementAlgorithm(PublicKeyPacket pubKeyData)
    {
        ECDHPublicBCPGKey ecKey = (ECDHPublicBCPGKey)pubKeyData.getKey();

        switch (ecKey.getHashAlgorithm())
        {
        case HashAlgorithmTags.SHA256:
            return "ECCDHwithSHA256CKDF";
        case HashAlgorithmTags.SHA384:
            return "ECCDHwithSHA384CKDF";
        case HashAlgorithmTags.SHA512:
            return "ECCDHwithSHA512CKDF";
        default:
            throw new IllegalArgumentException("Unknown hash algorithm specified: " + ecKey.getHashAlgorithm());
        }
    }


    public static ASN1ObjectIdentifier getKeyEncryptionOID(int algID)
        throws PGPException
    {
        switch (algID)
        {
        case SymmetricKeyAlgorithmTags.AES_128:
            return NISTObjectIdentifiers.id_aes128_wrap;
        case SymmetricKeyAlgorithmTags.AES_192:
            return NISTObjectIdentifiers.id_aes192_wrap;
        case SymmetricKeyAlgorithmTags.AES_256:
            return NISTObjectIdentifiers.id_aes256_wrap;
        //RFC3657
        case SymmetricKeyAlgorithmTags.CAMELLIA_128:
            return NTTObjectIdentifiers.id_camellia128_wrap;
        case SymmetricKeyAlgorithmTags.CAMELLIA_192:
            return NTTObjectIdentifiers.id_camellia192_wrap;
        case SymmetricKeyAlgorithmTags.CAMELLIA_256:
            return NTTObjectIdentifiers.id_camellia256_wrap;
        default:
            throw new PGPException("unknown symmetric algorithm ID: " + algID);
        }
    }

    // RFC 6637 - Section 8
    // curve_OID_len = (byte)len(curve_OID);
    // Param = curve_OID_len || curve_OID || public_key_alg_ID || 03
    // || 01 || KDF_hash_ID || KEK_alg_ID for AESKeyWrap || "Anonymous
    // Sender    " || recipient_fingerprint;
    // Z_len = the key size for the KEK_alg_ID used with AESKeyWrap
    // Compute Z = KDF( S, Z_len, Param );
    public static byte[] createUserKeyingMaterial(PublicKeyPacket pubKeyData, KeyFingerPrintCalculator fingerPrintCalculator)
        throws IOException, PGPException
    {
        ByteArrayOutputStream pOut = new ByteArrayOutputStream();
        BCPGKey key = pubKeyData.getKey();
        ASN1ObjectIdentifier curveID;
        int hashAlgorithm, symmetricKeyAlgorithm;
        if (key instanceof X25519PublicBCPGKey)
        {
            curveID = CryptlibObjectIdentifiers.curvey25519;
            symmetricKeyAlgorithm = SymmetricKeyAlgorithmTags.AES_128;
            hashAlgorithm = HashAlgorithmTags.SHA256;
        }
        else if (key instanceof X448PublicBCPGKey)
        {
            curveID = EdECObjectIdentifiers.id_X448;
            symmetricKeyAlgorithm = SymmetricKeyAlgorithmTags.AES_256;
            hashAlgorithm = HashAlgorithmTags.SHA512;
//            pOut.write(key.getEncoded());
//            pOut.write(EdECObjectIdentifiers.id_X448.getEncoded());
        }
        else
        {
            ECDHPublicBCPGKey ecKey = (ECDHPublicBCPGKey)pubKeyData.getKey();
            curveID = ecKey.getCurveOID();
            hashAlgorithm = ecKey.getHashAlgorithm();
            symmetricKeyAlgorithm = ecKey.getSymmetricKeyAlgorithm();
        }
        byte[] encOid = curveID.getEncoded();
        pOut.write(encOid, 1, encOid.length - 1);
        pOut.write(pubKeyData.getAlgorithm());
        pOut.write(0x03);
        pOut.write(0x01);
        pOut.write(hashAlgorithm);
        pOut.write(symmetricKeyAlgorithm);
        pOut.write(ANONYMOUS_SENDER);
        pOut.write(fingerPrintCalculator.calculateFingerprint(pubKeyData));

        return pOut.toByteArray();
    }
}
