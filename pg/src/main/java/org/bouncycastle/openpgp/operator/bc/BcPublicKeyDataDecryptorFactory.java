package org.bouncycastle.openpgp.operator.bc;

import java.io.IOException;
import java.math.BigInteger;
import java.util.Arrays;

import org.bouncycastle.asn1.cryptlib.CryptlibObjectIdentifiers;
import org.bouncycastle.bcpg.AEADEncDataPacket;
import org.bouncycastle.bcpg.ECDHPublicBCPGKey;
import org.bouncycastle.bcpg.HashAlgorithmTags;
import org.bouncycastle.bcpg.PublicKeyAlgorithmTags;
import org.bouncycastle.bcpg.SymmetricEncIntegrityPacket;
import org.bouncycastle.bcpg.SymmetricKeyAlgorithmTags;
import org.bouncycastle.bcpg.X25519PublicBCPGKey;
import org.bouncycastle.bcpg.X448PublicBCPGKey;
import org.bouncycastle.crypto.AsymmetricBlockCipher;
import org.bouncycastle.crypto.BlockCipher;
import org.bouncycastle.crypto.BufferedAsymmetricBlockCipher;
import org.bouncycastle.crypto.InvalidCipherTextException;
import org.bouncycastle.crypto.RawAgreement;
import org.bouncycastle.crypto.Wrapper;
import org.bouncycastle.crypto.agreement.ECDHBasicAgreement;
import org.bouncycastle.crypto.agreement.X25519Agreement;
import org.bouncycastle.crypto.agreement.X448Agreement;
import org.bouncycastle.crypto.params.AsymmetricKeyParameter;
import org.bouncycastle.crypto.params.ECDomainParameters;
import org.bouncycastle.crypto.params.ECPrivateKeyParameters;
import org.bouncycastle.crypto.params.ECPublicKeyParameters;
import org.bouncycastle.crypto.params.ElGamalPrivateKeyParameters;
import org.bouncycastle.crypto.params.KeyParameter;
import org.bouncycastle.crypto.params.X25519PublicKeyParameters;
import org.bouncycastle.crypto.params.X448PublicKeyParameters;
import org.bouncycastle.openpgp.PGPException;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.bouncycastle.openpgp.PGPSessionKey;
import org.bouncycastle.openpgp.operator.PGPDataDecryptor;
import org.bouncycastle.openpgp.operator.PGPPad;
import org.bouncycastle.openpgp.operator.PublicKeyDataDecryptorFactory;
import org.bouncycastle.openpgp.operator.RFC6637Utils;
import org.bouncycastle.util.BigIntegers;

/**
 * A decryptor factory for handling public key decryption operations.
 */
public class BcPublicKeyDataDecryptorFactory
    implements PublicKeyDataDecryptorFactory
{
    private static final BcPGPKeyConverter KEY_CONVERTER = new BcPGPKeyConverter();

    private final PGPPrivateKey pgpPrivKey;

    public BcPublicKeyDataDecryptorFactory(PGPPrivateKey pgpPrivKey)
    {
        this.pgpPrivKey = pgpPrivKey;
    }

    @Override
    public byte[] recoverSessionData(int keyAlgorithm, byte[][] secKeyData)
        throws PGPException
    {
        try
        {
            AsymmetricKeyParameter privKey = KEY_CONVERTER.getPrivateKey(pgpPrivKey);

            if (keyAlgorithm != PublicKeyAlgorithmTags.ECDH && keyAlgorithm != PublicKeyAlgorithmTags.X448 && keyAlgorithm != PublicKeyAlgorithmTags.X25519)
            {
                AsymmetricBlockCipher c = BcImplProvider.createPublicKeyCipher(keyAlgorithm);

                BufferedAsymmetricBlockCipher c1 = new BufferedAsymmetricBlockCipher(c);

                c1.init(false, privKey);

                if (keyAlgorithm == PublicKeyAlgorithmTags.RSA_ENCRYPT
                    || keyAlgorithm == PublicKeyAlgorithmTags.RSA_GENERAL)
                {
                    byte[] bi = secKeyData[0];

                    c1.processBytes(bi, 2, bi.length - 2);
                }
                else
                {
                    ElGamalPrivateKeyParameters parms = (ElGamalPrivateKeyParameters)privKey;
                    int size = (parms.getParameters().getP().bitLength() + 7) / 8;
                    byte[] tmp = new byte[size];

                    byte[] bi = secKeyData[0]; // encoded MPI
                    if (bi.length - 2 > size)  // leading Zero? Shouldn't happen but...
                    {
                        c1.processBytes(bi, 3, bi.length - 3);
                    }
                    else
                    {
                        System.arraycopy(bi, 2, tmp, tmp.length - (bi.length - 2), bi.length - 2);
                        c1.processBytes(tmp, 0, tmp.length);
                    }

                    bi = secKeyData[1];  // encoded MPI
                    Arrays.fill(tmp, (byte)0);

                    if (bi.length - 2 > size) // leading Zero? Shouldn't happen but...
                    {
                        c1.processBytes(bi, 3, bi.length - 3);
                    }
                    else
                    {
                        System.arraycopy(bi, 2, tmp, tmp.length - (bi.length - 2), bi.length - 2);
                        c1.processBytes(tmp, 0, tmp.length);
                    }
                }

                return c1.doFinal();
            }
            else
            {
                byte[] enc = secKeyData[0];
                byte[] pEnc;
                byte[] keyEnc;
                if (keyAlgorithm == PublicKeyAlgorithmTags.ECDH)
                {
                    int pLen = ((((enc[0] & 0xff) << 8) + (enc[1] & 0xff)) + 7) / 8;
                    if ((2 + pLen + 1) > enc.length)
                    {
                        throw new PGPException("encoded length out of range");
                    }

                    pEnc = new byte[pLen];
                    System.arraycopy(enc, 2, pEnc, 0, pLen);

                    int keyLen = enc[pLen + 2] & 0xff;
                    if ((2 + pLen + 1 + keyLen) > enc.length)
                    {
                        throw new PGPException("encoded length out of range");
                    }

                    keyEnc = new byte[keyLen];
                    System.arraycopy(enc, 2 + pLen + 1, keyEnc, 0, keyLen);
                }
                else if (keyAlgorithm == PublicKeyAlgorithmTags.X25519)
                {
                    int pLen = X25519PublicBCPGKey.LENGTH;
                    pEnc = new byte[pLen];
                    System.arraycopy(enc, 0, pEnc, 0, pLen);
                    int keyLen = enc[pLen] & 0xff;
                    if ((pLen + 1 + keyLen) > enc.length)
                    {
                        throw new PGPException("encoded length out of range");
                    }
                    keyEnc = new byte[keyLen];
                    System.arraycopy(enc, pLen + 1, keyEnc, 0, keyLen);
                }
                else
                {
                    int pLen = X448PublicBCPGKey.LENGTH;
                    pEnc = new byte[pLen];
                    System.arraycopy(enc, 0, pEnc, 0, pLen);
                    int keyLen = enc[pLen] & 0xff;
                    if ((pLen + 1 + keyLen) > enc.length)
                    {
                        throw new PGPException("encoded length out of range");
                    }
                    keyEnc = new byte[keyLen];
                    System.arraycopy(enc, pLen + 1, keyEnc, 0, keyLen);
                }

                byte[] secret;
                RFC6637KDFCalculator rfc6637KDFCalculator;
                byte[] userKeyingMaterial;
                int symmetricKeyAlgorithm, hashAlgorithm;
                if (keyAlgorithm == PublicKeyAlgorithmTags.ECDH)
                {
                    ECDHPublicBCPGKey ecPubKey = (ECDHPublicBCPGKey)pgpPrivKey.getPublicKeyPacket().getKey();
                    // XDH
                    if (ecPubKey.getCurveOID().equals(CryptlibObjectIdentifiers.curvey25519))
                    {
                        // skip the 0x40 header byte.
                        secret = getSecret(new X25519Agreement(), pEnc.length != 1 + X25519PublicKeyParameters.KEY_SIZE || 0x40 != pEnc[0], privKey, new X25519PublicKeyParameters(pEnc, 1), "25519");
                    }
                    else
                    {
                        ECDomainParameters ecParameters = ((ECPrivateKeyParameters)privKey).getParameters();

                        ECPublicKeyParameters ephPub = new ECPublicKeyParameters(ecParameters.getCurve().decodePoint(pEnc),
                            ecParameters);

                        ECDHBasicAgreement agreement = new ECDHBasicAgreement();
                        agreement.init(privKey);
                        BigInteger S = agreement.calculateAgreement(ephPub);
                        secret = BigIntegers.asUnsignedByteArray(agreement.getFieldSize(), S);
                    }
                    hashAlgorithm = ecPubKey.getHashAlgorithm();
                    symmetricKeyAlgorithm = ecPubKey.getSymmetricKeyAlgorithm();
                }
                else if (keyAlgorithm == PublicKeyAlgorithmTags.X25519)
                {
                    secret = getSecret(new X25519Agreement(), pEnc.length != X25519PublicKeyParameters.KEY_SIZE, privKey, new X25519PublicKeyParameters(pEnc, 0), "25519");
                    symmetricKeyAlgorithm = SymmetricKeyAlgorithmTags.AES_128;
                    hashAlgorithm = HashAlgorithmTags.SHA256;
                }
                else
                {
                    //PublicKeyAlgorithmTags.X448
                    secret = getSecret(new X448Agreement(), pEnc.length != X448PublicKeyParameters.KEY_SIZE, privKey, new X448PublicKeyParameters(pEnc, 0), "448");
                    symmetricKeyAlgorithm = SymmetricKeyAlgorithmTags.AES_256;
                    hashAlgorithm = HashAlgorithmTags.SHA512;
                }
                userKeyingMaterial = RFC6637Utils.createUserKeyingMaterial(pgpPrivKey.getPublicKeyPacket(), new BcKeyFingerprintCalculator());
                rfc6637KDFCalculator = new RFC6637KDFCalculator(new BcPGPDigestCalculatorProvider().get(hashAlgorithm), symmetricKeyAlgorithm);
                KeyParameter key = new KeyParameter(rfc6637KDFCalculator.createKey(secret, userKeyingMaterial));

                Wrapper c = BcImplProvider.createWrapper(symmetricKeyAlgorithm);
                c.init(false, key);
                return PGPPad.unpadSessionData(c.unwrap(keyEnc, 0, keyEnc.length));
            }
        }
        catch (IOException e)
        {
            throw new PGPException("exception creating user keying material: " + e.getMessage(), e);
        }
        catch (InvalidCipherTextException e)
        {
            throw new PGPException("exception decrypting session info: " + e.getMessage(), e);
        }

    }

    private static byte[] getSecret(RawAgreement agreement, boolean condition, AsymmetricKeyParameter privKey, AsymmetricKeyParameter ephPub, String curve)
    {
        if (condition)
        {
            throw new IllegalArgumentException("Invalid Curve" + curve + " public key");
        }
        agreement.init(privKey);
        byte[] secret = new byte[agreement.getAgreementSize()];
        agreement.calculateAgreement(ephPub, secret, 0);
        return secret;
    }

    // OpenPGP v4
    @Override
    public PGPDataDecryptor createDataDecryptor(boolean withIntegrityPacket, int encAlgorithm, byte[] key)
        throws PGPException
    {
        BlockCipher engine = BcImplProvider.createBlockCipher(encAlgorithm);

        return BcUtil.createDataDecryptor(withIntegrityPacket, engine, key);
    }

    // OpenPGP v5
    @Override
    public PGPDataDecryptor createDataDecryptor(AEADEncDataPacket aeadEncDataPacket, PGPSessionKey sessionKey)
        throws PGPException
    {
        return BcAEADUtil.createOpenPgpV5DataDecryptor(aeadEncDataPacket, sessionKey);
    }

    // OpenPGP v6
    @Override
    public PGPDataDecryptor createDataDecryptor(SymmetricEncIntegrityPacket seipd, PGPSessionKey sessionKey)
        throws PGPException
    {
        return BcAEADUtil.createOpenPgpV6DataDecryptor(seipd, sessionKey);
    }
}
